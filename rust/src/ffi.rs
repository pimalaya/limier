//! JNI entry points called from the limier `:client` module. Each owns
//! one connection, so the client fans several out in parallel.

use jni::{
    objects::{JClass, JObject, JString},
    sys::{jlong, jstring},
    JNIEnv,
};

use crate::{client::Client, types::Credentials};

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

    let json = {
        let mut client = Client::new(&mut env, &transport);
        let listed = client
            .connect(&credentials)
            .and_then(|()| client.list_mailboxes());
        match listed {
            Ok(names) => {
                serde_json::to_string(&names).unwrap_or_else(|err| error_json(&err.to_string()))
            }
            Err(err) => error_json(&err),
        }
    };

    new_string(&mut env, json)
}

/// `Native.searchMailboxes`: greeting, auth, then per assigned mailbox
/// EXAMINE + UID SEARCH + UID FETCH ENVELOPE, calling `listener`'s
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

    let message = {
        let mut client = Client::new(&mut env, &transport);
        let searched = client
            .connect(&credentials)
            .and_then(|()| client.search_mailboxes(&listener, &mailboxes, &keywords));
        match searched {
            Ok(()) => String::new(),
            Err(err) => err,
        }
    };

    new_string(&mut env, message)
}

/// `Native.fetchMessage`: greeting, auth, EXAMINE, UID FETCH BODY.PEEK[],
/// then mail-parser. Returns `{"parts": [..]}` or `{"error": ".."}`.
#[no_mangle]
pub extern "system" fn Java_org_pimalaya_limier_client_Native_fetchMessage<'local>(
    mut env: JNIEnv<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    login: JString<'local>,
    password: JString<'local>,
    sasl: JString<'local>,
    mailbox: JString<'local>,
    uid: jlong,
) -> jstring {
    let login = read_string(&mut env, &login);
    let password = read_string(&mut env, &password);
    let sasl = read_string(&mut env, &sasl);
    let mailbox = read_string(&mut env, &mailbox);
    let credentials = Credentials {
        login: &login,
        password: &password,
        sasl: &sasl,
    };

    let json = {
        let mut client = Client::new(&mut env, &transport);
        let fetched = client
            .connect(&credentials)
            .and_then(|()| client.fetch_message(&mailbox, uid as u32));
        match fetched {
            Ok(parts) => serde_json::json!({ "parts": parts }).to_string(),
            Err(err) => error_json(&err),
        }
    };

    new_string(&mut env, json)
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

fn error_json(message: &str) -> String {
    serde_json::json!({ "error": message }).to_string()
}
