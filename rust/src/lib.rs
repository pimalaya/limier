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
//! protocol state machine driving io-imap's sans-io coroutines and
//! doing all socket I/O by upcalling a Kotlin `Transport` on each
//! yield. Two entry points let the client fan out across connections:
//!
//! - `listMailboxes`: greeting, SASL auth, LIST; returns selectable
//!   mailbox names as a JSON array (or `{"error": ".."}`).
//! - `searchMailboxes`: greeting, SASL auth, then SELECT + UID SEARCH +
//!   UID FETCH ENVELOPE over an assigned subset of mailboxes, streaming
//!   each non-empty mailbox to a Kotlin listener as it completes.
//!
//! Each call owns one connection, so the client runs several in
//! parallel; results (UID + subject + date) surface mailbox by mailbox.

use chrono::DateTime;
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

/// One matching message, as shown by the results screen. `timestamp`
/// is the Date header as Unix seconds (0 when unparseable); the UI
/// formats it locally and falls back to the raw `date`.
#[derive(Serialize)]
struct Hit {
    uid: u32,
    subject: String,
    date: String,
    timestamp: i64,
}

/// Search hits for a single mailbox; the streaming callback payload.
#[derive(Serialize)]
struct MailboxHits {
    mailbox: String,
    hits: Vec<Hit>,
}

/// Borrowed account credentials threaded through one connection.
struct Credentials<'a> {
    login: &'a str,
    password: &'a str,
    sasl: &'a str,
}

/// `Native.listMailboxes`: greeting, auth, LIST. Returns a JSON array
/// of selectable mailbox names, or `{"error": ".."}`.
#[no_mangle]
pub extern "system" fn Java_org_pimalaya_limier_client_Native_listMailboxes<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    login: JString<'local>,
    password: JString<'local>,
    sasl: JString<'local>,
) -> jstring {
    let login = read_string(&mut env, &login);
    let password = read_string(&mut env, &password);
    let sasl = read_string(&mut env, &sasl);
    let credentials = Credentials {
        login: &login,
        password: &password,
        sasl: &sasl,
    };

    let json = match list_mailboxes(&mut env, &transport, &credentials) {
        Ok(names) => {
            serde_json::to_string(&names).unwrap_or_else(|err| error_json(&err.to_string()))
        }
        Err(err) => error_json(&err),
    };

    new_string(&mut env, json)
}

/// `Native.searchMailboxes`: greeting, auth, then per assigned mailbox
/// SELECT + UID SEARCH + UID FETCH ENVELOPE, calling `listener`'s
/// `onMailbox(String)` for each mailbox that has hits. Returns an empty
/// string on success, or an error message.
#[no_mangle]
pub extern "system" fn Java_org_pimalaya_limier_client_Native_searchMailboxes<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    login: JString<'local>,
    password: JString<'local>,
    sasl: JString<'local>,
    mailboxes: JString<'local>,
    keywords: JString<'local>,
    listener: JObject<'local>,
) -> jstring {
    let login = read_string(&mut env, &login);
    let password = read_string(&mut env, &password);
    let sasl = read_string(&mut env, &sasl);
    let keywords = read_string(&mut env, &keywords);
    let mailboxes: Vec<String> =
        serde_json::from_str(&read_string(&mut env, &mailboxes)).unwrap_or_default();
    let credentials = Credentials {
        login: &login,
        password: &password,
        sasl: &sasl,
    };

    let message = match search_mailboxes(
        &mut env,
        &transport,
        &listener,
        &credentials,
        &mailboxes,
        &keywords,
    ) {
        Ok(()) => String::new(),
        Err(err) => err,
    };

    new_string(&mut env, message)
}

/// Greeting, auth, then LIST filtered to selectable mailbox names.
fn list_mailboxes(
    env: &mut JNIEnv,
    transport: &JObject,
    credentials: &Credentials,
) -> Result<Vec<String>, String> {
    let mut fragmentizer = Fragmentizer::new(MAX_MESSAGE_SIZE);

    open_session(env, transport, &mut fragmentizer, credentials)?;

    let reference: Mailbox = "".try_into().expect("empty LIST reference is valid");
    let pattern: ListMailbox = "*".try_into().expect("`*` LIST pattern is valid");
    let listing = drive(
        env,
        transport,
        &mut fragmentizer,
        ImapMailboxList::new(reference, pattern),
    )?;

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

/// Greeting, auth, then a streamed search over the assigned mailboxes.
/// A mailbox that fails to SELECT/SEARCH is skipped, never fatal.
fn search_mailboxes(
    env: &mut JNIEnv,
    transport: &JObject,
    listener: &JObject,
    credentials: &Credentials,
    mailboxes: &[String],
    keywords: &str,
) -> Result<(), String> {
    let mut fragmentizer = Fragmentizer::new(MAX_MESSAGE_SIZE);

    open_session(env, transport, &mut fragmentizer, credentials)?;

    for name in mailboxes {
        if should_stop(env, listener)? {
            break;
        }

        // A bad mailbox is treated as empty: still counted for progress,
        // never fatal to the rest of the sweep.
        let hits =
            search_one(env, transport, &mut fragmentizer, name, keywords).unwrap_or_default();
        if !hits.is_empty() {
            let payload = MailboxHits {
                mailbox: name.clone(),
                hits,
            };
            let json = serde_json::to_string(&payload).map_err(|err| err.to_string())?;
            emit_mailbox(env, listener, &json)?;
        }

        emit_progress(env, listener)?;
    }

    Ok(())
}

/// SELECT, UID SEARCH, UID FETCH ENVELOPE for one mailbox.
fn search_one(
    env: &mut JNIEnv,
    transport: &JObject,
    fragmentizer: &mut Fragmentizer,
    name: &str,
    keywords: &str,
) -> Result<Vec<Hit>, String> {
    let mailbox: Mailbox = name
        .to_string()
        .try_into()
        .map_err(|_| format!("Invalid mailbox `{name}`"))?;

    let select = drive(
        env,
        transport,
        fragmentizer,
        ImapMailboxSelect::new(mailbox, ImapMailboxSelectOptions::default()),
    )?;

    if select.exists.unwrap_or(0) == 0 {
        return Ok(Vec::new());
    }

    let uids = drive(
        env,
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
        env,
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

/// Greeting then SASL auth, shared by both entry points.
fn open_session(
    env: &mut JNIEnv,
    transport: &JObject,
    fragmentizer: &mut Fragmentizer,
    credentials: &Credentials,
) -> Result<(), String> {
    drive(
        env,
        transport,
        fragmentizer,
        ImapGreetingGet::new(ImapGreetingGetOptions {
            ensure_capabilities: true,
        }),
    )?;

    let Credentials {
        login,
        password,
        sasl,
    } = credentials;

    if sasl.eq_ignore_ascii_case("login") {
        drive(
            env,
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
            env,
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

    let timestamp = DateTime::parse_from_rfc2822(date.trim())
        .map(|parsed| parsed.timestamp())
        .unwrap_or(0);

    Hit {
        uid,
        subject,
        date,
        timestamp,
    }
}

/// Drives a standard-shape coroutine to completion, servicing every
/// `WantsRead`/`WantsWrite` yield through the Kotlin transport.
fn drive<C, T, E>(
    env: &mut JNIEnv,
    transport: &JObject,
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
                arg = Some(transport_read(env, transport)?);
            }
            ImapCoroutineState::Yielded(ImapYield::WantsWrite(bytes)) => {
                transport_write(env, transport, &bytes)?;
                arg = None;
            }
        }
    }
}

/// Reads the next chunk from the Kotlin transport; an empty slice
/// signals EOF to the coroutine.
fn transport_read(env: &mut JNIEnv, transport: &JObject) -> Result<Vec<u8>, String> {
    let value = env
        .call_method(transport, "read", "()[B", &[])
        .map_err(|err| clear_and_fail(env, "transport read", err))?;
    let array = value.l().map_err(|err| err.to_string())?;
    let array = unsafe { JByteArray::from_raw(array.into_raw()) };
    env.convert_byte_array(&array)
        .map_err(|err| err.to_string())
}

/// Writes all bytes to the Kotlin transport.
fn transport_write(env: &mut JNIEnv, transport: &JObject, bytes: &[u8]) -> Result<(), String> {
    let array = env
        .byte_array_from_slice(bytes)
        .map_err(|err| err.to_string())?;
    env.call_method(transport, "write", "([B)V", &[(&array).into()])
        .map_err(|err| clear_and_fail(env, "transport write", err))?;
    Ok(())
}

/// Hands one mailbox's JSON to the Kotlin listener's `onMailbox`.
fn emit_mailbox(env: &mut JNIEnv, listener: &JObject, json: &str) -> Result<(), String> {
    let payload = env.new_string(json).map_err(|err| err.to_string())?;
    env.call_method(
        listener,
        "onMailbox",
        "(Ljava/lang/String;)V",
        &[(&payload).into()],
    )
    .map_err(|err| clear_and_fail(env, "listener onMailbox", err))?;
    Ok(())
}

/// Tells the Kotlin listener one more mailbox has been processed.
fn emit_progress(env: &mut JNIEnv, listener: &JObject) -> Result<(), String> {
    env.call_method(listener, "onProgress", "()V", &[])
        .map_err(|err| clear_and_fail(env, "listener onProgress", err))?;
    Ok(())
}

/// Asks the Kotlin listener whether the search has been cancelled.
fn should_stop(env: &mut JNIEnv, listener: &JObject) -> Result<bool, String> {
    env.call_method(listener, "shouldStop", "()Z", &[])
        .map_err(|err| clear_and_fail(env, "listener shouldStop", err))?
        .z()
        .map_err(|err| err.to_string())
}

/// IMAP `Mailbox` to its display/group name.
fn mailbox_name(mailbox: &Mailbox<'static>) -> String {
    match mailbox {
        Mailbox::Inbox => "INBOX".to_string(),
        Mailbox::Other(other) => String::from_utf8_lossy(other.inner().as_ref()).into_owned(),
    }
}

/// Reads a Java string, defaulting to empty on any conversion error.
fn read_string(env: &mut JNIEnv, value: &JString) -> String {
    env.get_string(value)
        .map(Into::into)
        .unwrap_or_else(|_| String::new())
}

/// Builds the Java string returned across the JNI boundary.
fn new_string(env: &mut JNIEnv, value: String) -> jstring {
    env.new_string(value)
        .map(JString::into_raw)
        .unwrap_or(core::ptr::null_mut())
}

/// Clears any pending Java exception and renders a message.
fn clear_and_fail(env: &mut JNIEnv, op: &str, err: jni::errors::Error) -> String {
    env.exception_clear().ok();
    format!("{op} failed: {err}")
}

fn bytes_to_string(bytes: &[u8]) -> String {
    String::from_utf8_lossy(bytes).into_owned()
}

fn error_json(message: &str) -> String {
    serde_json::json!({ "error": message }).to_string()
}
