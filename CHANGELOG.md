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

### Changed

- Added a shared top app bar across all frames: app title on the left, a back arrow that appears on pushed frames, account settings behind a gear icon on the search frame, and the search progress bar anchored directly beneath it.
- Replaced panel flipping with a back-stack navigation model: auth or search is the root depending on whether credentials exist, opening a message or the settings pushes a frame, the back arrow and system back button pop, and popping the root quits.
- Verified the IMAP connection on auth submit before persisting credentials and resetting the stack to the search frame.
- Pinned the auth submit button to the bottom of the frame so it stays visible while the form scrolls.
- Replaced the long search-field hint with a short placeholder, adding a heading and a one-paragraph description of the search above the field.
- Gave the search and auth frame headings a shared h2 style, distinct from the top bar h1.
- Anchored the search progress bar flush against the top bar in the bar colour at a fixed height, so it reads as the bar's edge and never shifts the content.
- Made stopping a search instant: cancelling closes the live sockets so blocked reads unwind at once, and the UI flips back without waiting for the workers, dropping any late callbacks.
- Drew the per-mailbox result tables with single grid lines instead of accumulating per-cell borders.
- Switched mailbox opening from `SELECT` to read-only `EXAMINE` for searching and fetching, since limier only consults messages.
