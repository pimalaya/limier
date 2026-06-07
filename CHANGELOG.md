# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Added the Rust JNI bridge driving io-imap's sans-io coroutines (greeting, SASL auth, LIST, then per mailbox SELECT, UID SEARCH and UID FETCH ENVELOPE), with all socket I/O delegated to a Kotlin transport.
- Added the Android `:client` module exposing `ImapClient.searchAll`, owning the TLS `SSLSocket` and the native bridge.
- Added the Android `:app` module: a single activity flipping between the config, search and results panels, with Keystore-encrypted credential storage.
- Added the Nix flake providing the Rust toolchain (Android targets), cargo-ndk, the Android SDK/NDK, JDK 17 and Gradle.
