//! Serializable payloads returned across the JNI boundary, and the
//! parsing that turns IMAP FETCH results into them.

use base64::{engine::general_purpose::STANDARD, Engine};
use chrono::DateTime;
use io_imap::types::fetch::MessageDataItem;
use mail_parser::{MessageParser, MessagePart, MimeHeaders, PartType};
use rfc2047_decoder::{Decoder, RecoverStrategy};
use serde::Serialize;

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

/// Search hits for a single mailbox; the streaming callback payload.
#[derive(Serialize)]
pub struct MailboxHits {
    pub mailbox: String,
    pub hits: Vec<Hit>,
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

/// Borrowed account credentials threaded through one connection.
pub struct Credentials<'a> {
    pub login: &'a str,
    pub password: &'a str,
    pub sasl: &'a str,
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

/// Flattens a parsed message into leaf MIME parts (containers skipped).
pub fn parse_parts(raw: &[u8]) -> Vec<Part> {
    let Some(message) = MessageParser::default().parse(raw) else {
        return Vec::new();
    };

    let mut parts = Vec::new();

    for part in &message.parts {
        match &part.body {
            PartType::Text(text) => parts.push(Part {
                mime: content_type_string(part).unwrap_or_else(|| "text/plain".to_string()),
                kind: "text",
                text: Some(text.to_string()),
                data: None,
                filename: None,
            }),
            PartType::Html(html) => parts.push(Part {
                mime: content_type_string(part).unwrap_or_else(|| "text/html".to_string()),
                kind: "text",
                text: Some(html.to_string()),
                data: None,
                filename: None,
            }),
            PartType::Binary(bytes) | PartType::InlineBinary(bytes) => {
                let mime = content_type_string(part)
                    .unwrap_or_else(|| "application/octet-stream".to_string());
                let kind = if mime.starts_with("image/") {
                    "image"
                } else {
                    "binary"
                };
                parts.push(Part {
                    mime,
                    kind,
                    text: None,
                    data: Some(STANDARD.encode(bytes.as_ref())),
                    filename: part.attachment_name().map(str::to_string),
                });
            }
            PartType::Message(_) | PartType::Multipart(_) => {}
        }
    }

    parts
}

/// `type/subtype` of a MIME part, when the Content-Type header is present.
fn content_type_string(part: &MessagePart) -> Option<String> {
    part.content_type()
        .map(|content_type| match content_type.subtype() {
            Some(subtype) => format!("{}/{}", content_type.ctype(), subtype),
            None => content_type.ctype().to_string(),
        })
}

/// Decodes RFC 2047 encoded-words in a Subject header, falling back to
/// a lossy UTF-8 read when the input is malformed.
fn decode_subject(bytes: &[u8]) -> String {
    Decoder::new()
        .too_long_encoded_word_strategy(RecoverStrategy::Decode)
        .decode(bytes)
        .unwrap_or_else(|_| bytes_to_string(bytes))
}

fn bytes_to_string(bytes: &[u8]) -> String {
    String::from_utf8_lossy(bytes).into_owned()
}
