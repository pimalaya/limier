# Contributing guide

Thank you for investing your time in contributing to Limier.

## Development environment

The development environment is managed by [Nix flakes](https://nixos.wiki/wiki/Flakes). Running `nix develop` spawns a shell with everything pinned: a Rust toolchain carrying the four Android ABI targets (read from `rust-toolchain.toml`), `cargo-ndk`, the Android SDK and NDK, JDK 17, Gradle and `kotlin-language-server`.

If you do not want to use Nix, provide these yourself: the Android SDK (platform 34, build-tools 34.0.0) and NDK r26, JDK 17, Gradle, plus a Rust toolchain (>= `v1.87`) with the `aarch64-linux-android`, `armv7-linux-androideabi`, `x86_64-linux-android` and `i686-linux-android` targets and `cargo-ndk`.

## Architecture

Three layers, each knowing only the one below:

- `:app` (Kotlin, framework Views): the frames and Keystore storage. Talks only to `ImapClient`; never sees sockets or JNI.
- `:client` (Android library): `ImapClient.search(account, keywords, listener)`, streaming hits mailbox by mailbox. Opens the TLS `SSLSocket` (platform trust store, zero APK cost), owns the JNI boundary, parses the bridge's JSON reply.
- `liblimier.so` (Rust): [io-imap](https://github.com/pimalaya/io-imap)'s I/O-free coroutines, built with no TLS or client feature. It orchestrates the whole IMAP flow as a pure protocol state machine and performs socket I/O by upcalling the Kotlin transport on each read/write yield.

TLS and TCP live in Kotlin on purpose: the `.so` stays a small `no_std` state machine that cross-compiles trivially, and certificate validation is handled by Android.

## Build

```sh
nix develop
cd android
gradle assembleRelease
```

`gradle` first cross-compiles the Rust bridge for the four ABIs (`arm64-v8a`, `armeabi-v7a`, `x86_64`, `x86`) via cargo-ndk into `client/src/main/jniLibs`, then assembles the APKs. The release build emits one APK per ABI plus a universal one under `app/build/outputs/apk/release/`. For a quick install on your own device, `gradle assembleDebug` produces a ready-to-sideload `app/build/outputs/apk/debug/app-debug.apk`.

To iterate on the Rust bridge alone: `cd rust && cargo build`.

## Lint, test

The Rust bridge is checked with:

```sh
cd rust
cargo fmt
cargo clippy
cargo test
```

## Commit style

Limier follows the [conventional commits specification](https://www.conventionalcommits.org/en/v1.0.0/#summary).
