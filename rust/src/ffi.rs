//! JNI entry points called from the limier `:client` module. Each owns
//! one connection, so the client fans several out in parallel.
//!
//! Every method captures the FFI [`EnvUnowned`], upgrades it to a usable
//! [`Env`] inside [`EnvUnowned::with_env`] (which also guards against
//! unwinding across the JNI boundary), and resolves the outcome with
//! [`LogErrorAndDefault`] so an unexpected JNI failure logs and returns a
//! null string rather than throwing.

use jni::{
    Env, EnvUnowned,
    errors::{Error, LogErrorAndDefault},
    objects::{JClass, JObject, JString},
    sys::jlong,
};

use crate::{client::Client, types::Credentials};

/// `Native.listMailboxes`: greeting, auth, LIST. Returns a JSON array
/// of selectable mailbox names, or `{"error": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_limier_client_Native_listMailboxes<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    login: JString<'local>,
    password: JString<'local>,
    sasl: JString<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let sasl = read_string(env, &sasl);
        let credentials = Credentials {
            login: &login,
            password: &password,
            sasl: &sasl,
        };

        let json = {
            let mut client = Client::new(env, &transport);
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

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.searchMailboxes`: greeting, auth, then per assigned mailbox
/// EXAMINE + UID SEARCH + UID FETCH ENVELOPE, calling `listener`'s
/// `onMailbox(String)` for each mailbox that has hits. Returns an empty
/// string on success, or an error message.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_limier_client_Native_searchMailboxes<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    login: JString<'local>,
    password: JString<'local>,
    sasl: JString<'local>,
    mailboxes: JString<'local>,
    keywords: JString<'local>,
    listener: JObject<'local>,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let sasl = read_string(env, &sasl);
        let keywords = read_string(env, &keywords);
        let mailboxes: Vec<String> =
            serde_json::from_str(&read_string(env, &mailboxes)).unwrap_or_default();
        let credentials = Credentials {
            login: &login,
            password: &password,
            sasl: &sasl,
        };

        let message = {
            let mut client = Client::new(env, &transport);
            let searched = client
                .connect(&credentials)
                .and_then(|()| client.search_mailboxes(&listener, &mailboxes, &keywords));
            match searched {
                Ok(()) => String::new(),
                Err(err) => err,
            }
        };

        Ok(env.new_string(message)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// `Native.fetchMessage`: greeting, auth, EXAMINE, UID FETCH BODY.PEEK[],
/// then mail-parser. Returns `{"parts": [..]}` or `{"error": ".."}`.
#[unsafe(no_mangle)]
pub extern "system" fn Java_org_pimalaya_limier_client_Native_fetchMessage<'local>(
    mut env: EnvUnowned<'local>,
    _class: JClass<'local>,
    transport: JObject<'local>,
    login: JString<'local>,
    password: JString<'local>,
    sasl: JString<'local>,
    mailbox: JString<'local>,
    uid: jlong,
) -> JObject<'local> {
    env.with_env(|env| -> Result<JObject<'local>, Error> {
        let login = read_string(env, &login);
        let password = read_string(env, &password);
        let sasl = read_string(env, &sasl);
        let mailbox = read_string(env, &mailbox);
        let credentials = Credentials {
            login: &login,
            password: &password,
            sasl: &sasl,
        };

        let json = {
            let mut client = Client::new(env, &transport);
            let fetched = client
                .connect(&credentials)
                .and_then(|()| client.fetch_message(&mailbox, uid as u32));
            match fetched {
                Ok(parts) => serde_json::json!({ "parts": parts }).to_string(),
                Err(err) => error_json(&err),
            }
        };

        Ok(env.new_string(json)?.into())
    })
    .resolve::<LogErrorAndDefault>()
}

/// Reads a Java string, defaulting to empty on any conversion error.
fn read_string(env: &Env, value: &JString) -> String {
    value.try_to_string(env).unwrap_or_default()
}

fn error_json(message: &str) -> String {
    serde_json::json!({ "error": message }).to_string()
}
