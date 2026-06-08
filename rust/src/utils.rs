//! Parsing helpers turning IMAP FETCH results into the result payloads.

use base64::{Engine, engine::general_purpose::STANDARD};
use mail_parser::{MessageParser, MessagePart, MimeHeaders, PartType};
use rfc2047_decoder::{Decoder, RecoverStrategy};

use crate::types::Part;

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
pub(crate) fn decode_subject(bytes: &[u8]) -> String {
    Decoder::new()
        .too_long_encoded_word_strategy(RecoverStrategy::Decode)
        .decode(bytes)
        .unwrap_or_else(|_| bytes_to_string(bytes))
}

pub(crate) fn bytes_to_string(bytes: &[u8]) -> String {
    String::from_utf8_lossy(bytes).into_owned()
}
