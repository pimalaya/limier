//! Serializable payloads returned across the JNI boundary.

use chrono::DateTime;
use io_imap::types::fetch::MessageDataItem;
use serde::Serialize;

use crate::utils::{bytes_to_string, decode_subject};

/// Borrowed account credentials threaded through one connection.
pub struct Credentials<'a> {
    pub login: &'a str,
    pub password: &'a str,
    pub sasl: &'a str,
}

/// One MIME part of a fetched message, for the detail panel. `kind` is
/// "text" (then `text` is set), "image" or "binary" (then `data` is
/// base64). `filename` is the attachment name when present.
#[derive(Serialize)]
pub struct Part {
    pub mime: String,
    pub kind: &'static str,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub text: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub data: Option<String>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub filename: Option<String>,
}

/// Search hits for a single mailbox; the streaming callback payload.
#[derive(Serialize)]
pub struct MailboxHits {
    pub mailbox: String,
    pub hits: Vec<Hit>,
}

/// One matching message, as shown by the results screen. `timestamp`
/// is the Date header as Unix seconds (0 when unparseable); the UI
/// formats it locally and falls back to the raw `date`.
#[derive(Serialize)]
pub struct Hit {
    pub uid: u32,
    pub subject: String,
    pub date: String,
    pub timestamp: i64,
}

impl Hit {
    /// Folds one FETCH row (UID + ENVELOPE) into a [`Hit`].
    pub fn from_items(items: Vec<MessageDataItem<'static>>) -> Hit {
        let mut uid = 0;
        let mut subject = String::new();
        let mut date = String::new();

        for item in items {
            match item {
                MessageDataItem::Uid(value) => uid = value.get(),
                MessageDataItem::Envelope(envelope) => {
                    if let Some(value) = envelope.subject.into_option() {
                        subject = decode_subject(value.as_ref());
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
}
