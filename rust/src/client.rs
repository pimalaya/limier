//! Blocking IMAP client over the JNI transport: wraps a `JNIEnv` and
//! the Kotlin `Transport` with a per-connection [`Fragmentizer`] and one
//! method per coroutine, mirroring io-imap's own `ImapClientStd`.
//!
//! Session state is not cached: each native call builds a client,
//! `connect`s, runs one operation, and drops it.

use core::{cmp::Reverse, fmt::Display};

use io_imap::{
    codec::fragmentizer::Fragmentizer,
    coroutine::{ImapCoroutine, ImapCoroutineState, ImapYield},
    rfc3501::{
        examine::{ImapMailboxExamine, ImapMailboxExamineOptions},
        fetch::{ImapMessageFetch, ImapMessageFetchOptions},
        greeting::{ImapGreetingGet, ImapGreetingGetOptions},
        list::ImapMailboxList,
        search::{ImapMessageSearch, ImapMessageSearchOptions},
    },
    sasl::{
        auth_login::{ImapAuthLogin, ImapAuthLoginOptions},
        auth_plain::{ImapAuthPlain, ImapAuthPlainOptions},
    },
    types::{
        core::{AString, Vec1},
        fetch::{MacroOrMessageDataItemNames, MessageDataItem, MessageDataItemName},
        flag::FlagNameAttribute,
        mailbox::{ListMailbox, Mailbox},
        search::SearchKey,
        sequence::SequenceSet,
    },
};
use jni::{
    errors::Error,
    objects::{JByteArray, JObject},
    JNIEnv,
};

use crate::types::{parse_parts, Credentials, Hit, MailboxHits, Part};

/// Matches io-imap's own fragmentizer ceiling (100 MiB per message).
const MAX_MESSAGE_SIZE: u32 = 100 * 1024 * 1024;

/// One native call's IMAP client: a mutable `JNIEnv`, the Kotlin
/// transport it upcalls for socket I/O, and a per-connection
/// fragmentizer.
pub struct Client<'a, 'local> {
    env: &'a mut JNIEnv<'local>,
    transport: &'a JObject<'local>,
    fragmentizer: Fragmentizer,
}

impl<'a, 'local> Client<'a, 'local> {
    /// Wraps the JNI context for one connection.
    pub fn new(env: &'a mut JNIEnv<'local>, transport: &'a JObject<'local>) -> Self {
        Self {
            env,
            transport,
            fragmentizer: Fragmentizer::new(MAX_MESSAGE_SIZE),
        }
    }

    /// Runs a standard-shape coroutine to completion, servicing every
    /// `WantsRead`/`WantsWrite` yield through the Kotlin transport.
    pub fn run<C, T, E>(&mut self, mut coroutine: C) -> Result<T, String>
    where
        C: ImapCoroutine<Yield = ImapYield, Return = Result<T, E>>,
        E: Display,
    {
        let mut arg: Option<Vec<u8>> = None;

        loop {
            match coroutine.resume(&mut self.fragmentizer, arg.as_deref()) {
                ImapCoroutineState::Complete(Ok(value)) => return Ok(value),
                ImapCoroutineState::Complete(Err(err)) => return Err(err.to_string()),
                ImapCoroutineState::Yielded(ImapYield::WantsRead) => {
                    // NOTE: an empty slice signals EOF to the coroutine.
                    let value = self
                        .env
                        .call_method(self.transport, "read", "()[B", &[])
                        .map_err(|err| clear_and_fail(self.env, "transport read", err))?;
                    let array = value.l().map_err(|err| err.to_string())?;
                    let array = unsafe { JByteArray::from_raw(array.into_raw()) };
                    arg = Some(
                        self.env
                            .convert_byte_array(&array)
                            .map_err(|err| err.to_string())?,
                    );
                }
                ImapCoroutineState::Yielded(ImapYield::WantsWrite(bytes)) => {
                    let array = self
                        .env
                        .byte_array_from_slice(&bytes)
                        .map_err(|err| err.to_string())?;
                    self.env
                        .call_method(self.transport, "write", "([B)V", &[(&array).into()])
                        .map_err(|err| clear_and_fail(self.env, "transport write", err))?;
                    arg = None;
                }
            }
        }
    }

    // ---- Session lifecycle ------------------------------------------------

    /// Consumes the greeting then authenticates, the handshake shared by
    /// every entry point.
    pub fn connect(&mut self, credentials: &Credentials) -> Result<(), String> {
        self.greeting()?;

        let Credentials {
            login,
            password,
            sasl,
        } = credentials;

        if sasl.eq_ignore_ascii_case("login") {
            self.auth_login(login, password)
        } else {
            self.auth_plain(login, password)
        }
    }

    /// Consumes the greeting, forcing a CAPABILITY round-trip when it
    /// carried none.
    pub fn greeting(&mut self) -> Result<(), String> {
        self.run(ImapGreetingGet::new(ImapGreetingGetOptions {
            ensure_capabilities: true,
        }))
        .map(|_| ())
    }

    /// SASL `AUTHENTICATE LOGIN`.
    pub fn auth_login(&mut self, login: &str, password: &str) -> Result<(), String> {
        self.run(ImapAuthLogin::new(
            login,
            password,
            ImapAuthLoginOptions {
                initial_request: false,
                ensure_capabilities: true,
                auto_id: None,
            },
        ))
        .map(|_| ())
    }

    /// SASL `AUTHENTICATE PLAIN`.
    pub fn auth_plain(&mut self, login: &str, password: &str) -> Result<(), String> {
        self.run(ImapAuthPlain::new(
            None::<&str>,
            login,
            password,
            ImapAuthPlainOptions {
                initial_request: false,
                ensure_capabilities: true,
                auto_id: None,
            },
        ))
        .map(|_| ())
    }

    // ---- Operations -------------------------------------------------------

    /// LIST filtered to selectable mailbox names.
    pub fn list_mailboxes(&mut self) -> Result<Vec<String>, String> {
        let reference: Mailbox = "".try_into().expect("empty LIST reference is valid");
        let pattern: ListMailbox = "*".try_into().expect("`*` LIST pattern is valid");
        let listing = self.run(ImapMailboxList::new(reference, pattern))?;

        let names = listing
            .into_iter()
            .filter(|(_, _, attributes)| {
                !attributes
                    .iter()
                    .any(|attr| matches!(attr, FlagNameAttribute::Noselect))
            })
            .map(|(mailbox, _, _)| mailbox_name(&mailbox))
            .collect();

        Ok(names)
    }

    /// Streams a search over the assigned mailboxes, emitting each
    /// non-empty mailbox to the listener. A mailbox that fails to
    /// EXAMINE/SEARCH is skipped, never fatal.
    pub fn search_mailboxes(
        &mut self,
        listener: &JObject,
        mailboxes: &[String],
        keywords: &str,
    ) -> Result<(), String> {
        for name in mailboxes {
            if self.should_stop(listener)? {
                break;
            }

            // NOTE: a bad mailbox is treated as empty, still counted for
            // progress, never fatal to the rest of the sweep.
            let hits = self.search_one(name, keywords).unwrap_or_default();
            if !hits.is_empty() {
                let payload = MailboxHits {
                    mailbox: name.clone(),
                    hits,
                };
                let json = serde_json::to_string(&payload).map_err(|err| err.to_string())?;
                self.emit_mailbox(listener, &json)?;
            }

            self.emit_progress(listener)?;
        }

        Ok(())
    }

    /// EXAMINE the mailbox, UID FETCH the full raw message (BODY.PEEK[],
    /// so `\Seen` is untouched), and parse it into MIME parts.
    pub fn fetch_message(&mut self, mailbox: &str, uid: u32) -> Result<Vec<Part>, String> {
        let selected: Mailbox = mailbox
            .to_string()
            .try_into()
            .map_err(|_| format!("Invalid mailbox `{mailbox}`"))?;
        self.run(ImapMailboxExamine::new(
            selected,
            ImapMailboxExamineOptions::default(),
        ))?;

        let sequence_set: SequenceSet = uid
            .to_string()
            .as_str()
            .try_into()
            .map_err(|_| format!("Invalid UID `{uid}`"))?;
        let item_names =
            MacroOrMessageDataItemNames::MessageDataItemNames(vec![MessageDataItemName::BodyExt {
                section: None,
                partial: None,
                peek: true,
            }]);
        let fetched = self.run(ImapMessageFetch::new(
            sequence_set,
            item_names,
            ImapMessageFetchOptions {
                uid: true,
                ..Default::default()
            },
        ))?;

        let raw = fetched
            .into_values()
            .flat_map(|items| items.into_inner())
            .find_map(|item| match item {
                MessageDataItem::BodyExt { data, .. } => {
                    data.into_option().map(|value| value.as_ref().to_vec())
                }
                _ => None,
            })
            .ok_or_else(|| "Message body not found".to_string())?;

        Ok(parse_parts(&raw))
    }

    /// EXAMINE, UID SEARCH, UID FETCH ENVELOPE for one mailbox.
    fn search_one(&mut self, name: &str, keywords: &str) -> Result<Vec<Hit>, String> {
        let mailbox: Mailbox = name
            .to_string()
            .try_into()
            .map_err(|_| format!("Invalid mailbox `{name}`"))?;

        let examine = self.run(ImapMailboxExamine::new(
            mailbox,
            ImapMailboxExamineOptions::default(),
        ))?;

        if examine.exists.unwrap_or(0) == 0 {
            return Ok(Vec::new());
        }

        let uids = self.run(ImapMessageSearch::new(
            search_criteria(keywords)?,
            ImapMessageSearchOptions { uid: true },
        ))?;

        if uids.is_empty() {
            return Ok(Vec::new());
        }

        let uid_set = uids
            .iter()
            .map(|uid| uid.get().to_string())
            .collect::<Vec<_>>()
            .join(",");
        let sequence_set: SequenceSet = uid_set
            .as_str()
            .try_into()
            .map_err(|_| format!("Invalid UID set `{uid_set}`"))?;

        let item_names = MacroOrMessageDataItemNames::MessageDataItemNames(vec![
            MessageDataItemName::Uid,
            MessageDataItemName::Envelope,
        ]);

        let fetched = self.run(ImapMessageFetch::new(
            sequence_set,
            item_names,
            ImapMessageFetchOptions {
                uid: true,
                ..Default::default()
            },
        ))?;

        let mut hits: Vec<Hit> = fetched
            .into_values()
            .map(|items| Hit::from_items(items.into_inner()))
            .collect();

        // Most recent first; unparseable dates (timestamp 0) sink to the
        // end.
        hits.sort_by_key(|hit| Reverse(hit.timestamp));

        Ok(hits)
    }

    // ---- Listener upcalls -------------------------------------------------

    /// Hands one mailbox's JSON to the Kotlin listener's `onMailbox`.
    fn emit_mailbox(&mut self, listener: &JObject, json: &str) -> Result<(), String> {
        let payload = self.env.new_string(json).map_err(|err| err.to_string())?;
        self.env
            .call_method(
                listener,
                "onMailbox",
                "(Ljava/lang/String;)V",
                &[(&payload).into()],
            )
            .map_err(|err| clear_and_fail(self.env, "listener onMailbox", err))?;
        Ok(())
    }

    /// Tells the Kotlin listener one more mailbox has been processed.
    fn emit_progress(&mut self, listener: &JObject) -> Result<(), String> {
        self.env
            .call_method(listener, "onProgress", "()V", &[])
            .map_err(|err| clear_and_fail(self.env, "listener onProgress", err))?;
        Ok(())
    }

    /// Asks the Kotlin listener whether the search has been cancelled.
    fn should_stop(&mut self, listener: &JObject) -> Result<bool, String> {
        self.env
            .call_method(listener, "shouldStop", "()Z", &[])
            .map_err(|err| clear_and_fail(self.env, "listener shouldStop", err))?
            .z()
            .map_err(|err| err.to_string())
    }
}

/// Broad "any field" search: each whitespace-split keyword becomes an
/// IMAP TEXT key (header + body), OR-folded so a hit on any keyword
/// matches.
fn search_criteria(keywords: &str) -> Result<Vec1<SearchKey<'static>>, String> {
    let mut keys = keywords
        .split_whitespace()
        .map(|word| {
            AString::try_from(word.to_string())
                .map(SearchKey::Text)
                .map_err(|_| format!("Invalid search keyword `{word}`"))
        })
        .collect::<Result<Vec<_>, _>>()?
        .into_iter();

    let mut criteria = keys.next().ok_or("Search keywords are empty")?;
    for key in keys {
        criteria = SearchKey::Or(Box::new(criteria), Box::new(key));
    }

    Ok(Vec1::from(criteria))
}

/// IMAP `Mailbox` to its display/group name.
fn mailbox_name(mailbox: &Mailbox<'static>) -> String {
    match mailbox {
        Mailbox::Inbox => "INBOX".to_string(),
        Mailbox::Other(other) => String::from_utf8_lossy(other.inner().as_ref()).into_owned(),
    }
}

/// Clears any pending Java exception and renders a message.
fn clear_and_fail(env: &mut JNIEnv, op: &str, err: Error) -> String {
    env.exception_clear().ok();
    format!("{op} failed: {err}")
}
