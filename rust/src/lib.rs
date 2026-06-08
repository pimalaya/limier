//! JNI bridge for the limier Android :client module.
//!
//! TLS and TCP live in Kotlin (SSLSocket); this crate is a pure
//! protocol state machine running io-imap's sans-io coroutines and
//! doing all socket I/O by upcalling a Kotlin `Transport` on each
//! yield. Three entry points let the client fan out across connections:
//!
//! - `listMailboxes`: greeting, SASL auth, LIST; returns selectable
//!   mailbox names as a JSON array (or `{"error": ".."}`).
//! - `searchMailboxes`: greeting, SASL auth, then EXAMINE + UID SEARCH +
//!   UID FETCH ENVELOPE over an assigned subset of mailboxes, streaming
//!   each non-empty mailbox to a Kotlin listener as it completes.
//! - `fetchMessage`: greeting, SASL auth, EXAMINE, UID FETCH BODY.PEEK[],
//!   then mail-parser into MIME parts for the detail panel.
//!
//! Each call owns one connection, so the client runs several in
//! parallel; results (UID + subject + date) surface mailbox by mailbox.

mod client;
mod ffi;
mod types;
mod utils;
