# 🐶 Limier [![Matrix](https://img.shields.io/badge/chat-%23pimalaya-blue?style=flat&logo=matrix&logoColor=white)](https://matrix.to/#/#pimalaya:matrix.org) [![Mastodon](https://img.shields.io/badge/news-%40pimalaya-blue?style=flat&logo=mastodon&logoColor=white)](https://fosstodon.org/@pimalaya)

Android app to hunt down lost mail buried across your IMAP mailboxes, by composing one deliberately broad search and running it against every folder at once.

Enter your account once, type a few keywords, and limier reports which message matched in which mailbox.

## Table of contents

- [How it works](#how-it-works)
- [Architecture](#architecture)
- [Build](#build)
- [License](#license)
- [AI disclosure](#ai-disclosure)
- [Social](#social)

## How it works

The app has three frames under a shared top bar, navigated as a back stack (the bar's back arrow and the system back button pop; popping the root quits):

1. **Auth** (root on first launch): IMAP domain, port, SASL mechanism (PLAIN or LOGIN), login and password. On submit the connection is verified first; only then is the account cached locally, encrypted with an AES-GCM key held in the Android Keystore, and the stack reset to search.
2. **Search** (root once credentials exist): one search bar. Keywords are whitespace-split, each turned into an IMAP `TEXT` key (matching the whole message, header and body), and OR-folded so a hit on any keyword counts; matches are grouped by mailbox, showing UID, subject and date.
3. **Message**: pushed when a result is tapped, showing the fetched message parsed into foldable MIME parts.

Under the hood each mailbox is walked with `SELECT`, then `UID SEARCH`, then `UID FETCH ENVELOPE` for the matches. A folder that cannot be selected is skipped, so one bad mailbox never aborts the sweep.

## Architecture

Three layers, each knowing only the one below:

- `:app` (Kotlin, framework Views): the three panels and Keystore storage. Talks only to `ImapClient`; never sees sockets or JNI.
- `:client` (Android library): `ImapClient.searchAll(account, keywords)`. Opens the TLS `SSLSocket` (platform trust store, zero APK cost), owns the JNI boundary, parses the bridge's JSON reply.
- `liblimier.so` (Rust): [io-imap](https://github.com/pimalaya/io-imap)'s I/O-free coroutines, built with no TLS or client feature. It orchestrates the whole IMAP flow as a pure protocol state machine and performs socket I/O by upcalling the Kotlin transport on each read/write yield.

TLS and TCP live in Kotlin on purpose: the `.so` stays a small `no_std` state machine that cross-compiles trivially, and certificate validation is handled by Android.

## Build

Everything is pinned by Nix (Rust with Android targets, cargo-ndk, the Android SDK/NDK, JDK 17, Gradle):

```sh
nix develop
cd android
gradle assembleRelease
```

`gradle` first cross-compiles the Rust bridge for the four ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`) via cargo-ndk into `client/src/main/jniLibs`, then assembles the APK. Install it with `adb install`.

To iterate on the Rust bridge alone: `cd rust && cargo build`.

## License

Licensed under the [GNU Affero General Public License v3.0 or later](./LICENSE).

## AI disclosure

The initial scaffold of this repository (Rust bridge, Kotlin modules, Gradle and Nix wiring) was generated with AI assistance under human review.

## Social

Follow [@pimalaya](https://fosstodon.org/@pimalaya) and join the [Matrix room](https://matrix.to/#/#pimalaya:matrix.org).
