// limier - search your mailboxes for lost mail
// Copyright (C) 2026  Clement DOUIN
//
// This program is free software: you can redistribute it and/or modify
// it under the terms of the GNU Affero General Public License as
// published by the Free Software Foundation, either version 3 of the
// License, or (at your option) any later version.
//
// This program is distributed in the hope that it will be useful, but
// WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
// Affero General Public License for more details.
//
// You should have received a copy of the GNU Affero General Public
// License along with this program. If not, see
// <https://www.gnu.org/licenses/>.

//! JNI bridge for the limier Android :client module.
//!
//! TLS and TCP live in Kotlin (SSLSocket); this crate is a pure
//! protocol state machine. The single exported `search` call drives
//! io-imap's sans-io coroutines (greeting, SASL auth, LIST, then per
//! mailbox SELECT + UID SEARCH + UID FETCH ENVELOPE), performing all
//! socket I/O by upcalling a Kotlin `Transport` object on each yield.
//! Results (UID + subject + date, grouped by mailbox) come back as a
//! JSON string; failures come back as `{"error": "..."}`.

use io_imap::{
    codec::fragmentizer::Fragmentizer,
    coroutine::{ImapCoroutine, ImapCoroutineState, ImapYield},
    rfc3501::{
        fetch::{ImapMessageFetch, ImapMessageFetchOptions},
        greeting::{ImapGreetingGet, ImapGreetingGetOptions},
        list::ImapMailboxList,
        search::{ImapMessageSearch, ImapMessageSearchOptions},
        select::{ImapMailboxSelect, ImapMailboxSelectOptions},
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
    objects::{JByteArray, JClass, JObject, JString},
    sys::jstring,
    JNIEnv,
};
use serde::Serialize;

/// Matches io-imap's own fragmentizer ceiling (100 MiB per message).
const MAX_MESSAGE_SIZE: u32 = 100 * 1024 * 1024;

/// One matching message, as shown by the results panel.
#[derive(Serialize)]
struct Hit {
    uid: u32,
    subject: String,
    date: String,
}

/// Search hits for a single mailbox.
#[derive(Serialize)]
struct MailboxHits {
    mailbox: String,
    hits: Vec<Hit>,
}

/// `Imap.search` (Kotlin `org.pimalaya.limier.client.Native`).
///
/// `transport` is a connected (already TLS-wrapped) Kotlin object
/// exposing `read(): ByteArray` and `write(ByteArray)`. Returns a JSON
/// string: an array of [`MailboxHits`] on success, or `{"error": ".."}`.
#[no_mangle]
pub extern "system" fn Java_org_pimalaya_limier_client_Native_search<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    login: JString<'local>,
    password: JString<'local>,
    sasl: JString<'local>,
    keywords: JString<'local>,
) -> jstring {
    let login = jstring_to_string(&mut env, &login);
    let password = jstring_to_string(&mut env, &password);
    let sasl = jstring_to_string(&mut env, &sasl);
    let keywords = jstring_to_string(&mut env, &keywords);

    // Scope the transport borrow so `env` is free again for the reply.
    let result = {
        let mut transport = Transport {
            env: &mut env,
            obj: &transport,
        };
        run_search(&mut transport, &login, &password, &sasl, &keywords)
    };

    let json = match result {
        Ok(mailboxes) => {
            serde_json::to_string(&mailboxes).unwrap_or_else(|err| error_json(&err.to_string()))
        }
        Err(err) => error_json(&err),
    };

    env.new_string(json)
        .map(JString::into_raw)
        .unwrap_or(std::ptr::null_mut())
}

/// Full session flow over the Kotlin transport. Greeting, auth and LIST
/// failures are fatal; a single mailbox that fails to SELECT/SEARCH is
/// skipped so one bad folder never aborts the whole sweep.
fn run_search(
    transport: &mut Transport,
    login: &str,
    password: &str,
    sasl: &str,
    keywords: &str,
) -> Result<Vec<MailboxHits>, String> {
    let mut fragmentizer = Fragmentizer::new(MAX_MESSAGE_SIZE);

    drive(
        transport,
        &mut fragmentizer,
        ImapGreetingGet::new(ImapGreetingGetOptions {
            ensure_capabilities: true,
        }),
    )?;

    authenticate(transport, &mut fragmentizer, login, password, sasl)?;

    let reference: Mailbox = "".try_into().expect("empty LIST reference is valid");
    let pattern: ListMailbox = "*".try_into().expect("`*` LIST pattern is valid");
    let listing = drive(
        transport,
        &mut fragmentizer,
        ImapMailboxList::new(reference, pattern),
    )?;

    let mut out = Vec::new();

    for (mailbox, _delimiter, attributes) in listing {
        if attributes
            .iter()
            .any(|attr| matches!(attr, FlagNameAttribute::Noselect))
        {
            continue;
        }

        let name = mailbox_name(&mailbox);

        match search_mailbox(transport, &mut fragmentizer, mailbox, keywords) {
            Ok(hits) if !hits.is_empty() => out.push(MailboxHits {
                mailbox: name,
                hits,
            }),
            // Empty result or a per-mailbox error: just move on.
            _ => {}
        }
    }

    Ok(out)
}

/// SELECT the mailbox, UID SEARCH the keywords, UID FETCH ENVELOPE for
/// every match, and fold each row down to UID + subject + date.
fn search_mailbox(
    transport: &mut Transport,
    fragmentizer: &mut Fragmentizer,
    mailbox: Mailbox<'static>,
    keywords: &str,
) -> Result<Vec<Hit>, String> {
    let select = drive(
        transport,
        fragmentizer,
        ImapMailboxSelect::new(mailbox, ImapMailboxSelectOptions::default()),
    )?;

    if select.exists.unwrap_or(0) == 0 {
        return Ok(Vec::new());
    }

    let uids = drive(
        transport,
        fragmentizer,
        ImapMessageSearch::new(
            search_criteria(keywords)?,
            ImapMessageSearchOptions { uid: true },
        ),
    )?;

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

    let fetched = drive(
        transport,
        fragmentizer,
        ImapMessageFetch::new(
            sequence_set,
            item_names,
            ImapMessageFetchOptions {
                uid: true,
                ..Default::default()
            },
        ),
    )?;

    let hits = fetched
        .into_values()
        .map(|items| hit_from(items.into_inner()))
        .collect();

    Ok(hits)
}

/// SASL PLAIN (default) or SASL LOGIN, selected by the `sasl` argument.
/// `initial_request: false` keeps it working on servers without
/// SASL-IR.
fn authenticate(
    transport: &mut Transport,
    fragmentizer: &mut Fragmentizer,
    login: &str,
    password: &str,
    sasl: &str,
) -> Result<(), String> {
    if sasl.eq_ignore_ascii_case("login") {
        drive(
            transport,
            fragmentizer,
            ImapAuthLogin::new(
                login,
                password,
                ImapAuthLoginOptions {
                    initial_request: false,
                    ensure_capabilities: true,
                    auto_id: None,
                },
            ),
        )?;
    } else {
        drive(
            transport,
            fragmentizer,
            ImapAuthPlain::new(
                None::<&str>,
                login,
                password,
                ImapAuthPlainOptions {
                    initial_request: false,
                    ensure_capabilities: true,
                    auto_id: None,
                },
            ),
        )?;
    }

    Ok(())
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

/// Folds one FETCH row (UID + ENVELOPE) into a [`Hit`].
fn hit_from(items: Vec<MessageDataItem<'static>>) -> Hit {
    let mut uid = 0;
    let mut subject = String::new();
    let mut date = String::new();

    for item in items {
        match item {
            MessageDataItem::Uid(value) => uid = value.get(),
            MessageDataItem::Envelope(envelope) => {
                if let Some(value) = envelope.subject.into_option() {
                    subject = bytes_to_string(value.as_ref());
                }
                if let Some(value) = envelope.date.into_option() {
                    date = bytes_to_string(value.as_ref());
                }
            }
            _ => {}
        }
    }

    Hit { uid, subject, date }
}

/// Drives a standard-shape coroutine to completion, servicing every
/// `WantsRead`/`WantsWrite` yield through the Kotlin transport.
fn drive<C, T, E>(
    transport: &mut Transport,
    fragmentizer: &mut Fragmentizer,
    mut coroutine: C,
) -> Result<T, String>
where
    C: ImapCoroutine<Yield = ImapYield, Return = Result<T, E>>,
    E: core::fmt::Display,
{
    let mut arg: Option<Vec<u8>> = None;

    loop {
        match coroutine.resume(fragmentizer, arg.as_deref()) {
            ImapCoroutineState::Complete(Ok(value)) => return Ok(value),
            ImapCoroutineState::Complete(Err(err)) => return Err(err.to_string()),
            ImapCoroutineState::Yielded(ImapYield::WantsRead) => {
                arg = Some(transport.read()?);
            }
            ImapCoroutineState::Yielded(ImapYield::WantsWrite(bytes)) => {
                transport.write(&bytes)?;
                arg = None;
            }
        }
    }
}

/// Borrowed handle to the Kotlin `Transport` object, used for socket
/// I/O via JNI upcalls.
struct Transport<'a, 'local> {
    env: &'a mut JNIEnv<'local>,
    obj: &'a JObject<'local>,
}

impl Transport<'_, '_> {
    /// Reads the next chunk; an empty slice signals EOF to the
    /// coroutine.
    fn read(&mut self) -> Result<Vec<u8>, String> {
        let value = self
            .env
            .call_method(self.obj, "read", "()[B", &[])
            .map_err(|err| self.fail("read", err))?;
        let array = value.l().map_err(|err| err.to_string())?;
        let array = unsafe { JByteArray::from_raw(array.into_raw()) };
        self.env
            .convert_byte_array(&array)
            .map_err(|err| err.to_string())
    }

    /// Writes all bytes to the socket.
    fn write(&mut self, bytes: &[u8]) -> Result<(), String> {
        let array = self
            .env
            .byte_array_from_slice(bytes)
            .map_err(|err| err.to_string())?;
        self.env
            .call_method(self.obj, "write", "([B)V", &[(&array).into()])
            .map_err(|err| self.fail("write", err))?;
        Ok(())
    }

    /// Clears any pending Java exception and renders a message.
    fn fail(&mut self, op: &str, err: jni::errors::Error) -> String {
        self.env.exception_clear().ok();
        format!("Transport {op} failed: {err}")
    }
}

/// IMAP `Mailbox` to its display/group name.
fn mailbox_name(mailbox: &Mailbox<'static>) -> String {
    match mailbox {
        Mailbox::Inbox => "INBOX".to_string(),
        Mailbox::Other(other) => String::from_utf8_lossy(other.inner().as_ref()).into_owned(),
    }
}

/// Reads a Java string, defaulting to empty on any conversion error.
fn jstring_to_string(env: &mut JNIEnv, value: &JString) -> String {
    env.get_string(value)
        .map(Into::into)
        .unwrap_or_else(|_| String::new())
}

fn bytes_to_string(bytes: &[u8]) -> String {
    String::from_utf8_lossy(bytes).into_owned()
}

fn error_json(message: &str) -> String {
    serde_json::json!({ "error": message }).to_string()
}
