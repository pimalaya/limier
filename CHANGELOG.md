# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

### Added

- Added the Rust JNI bridge driving io-imap's sans-io coroutines (greeting, SASL auth, LIST, then per mailbox SELECT, UID SEARCH and UID FETCH ENVELOPE), with all socket I/O delegated to a Kotlin transport.
- Added the Android `:client` module exposing `ImapClient.search`, owning the TLS `SSLSocket` and the native bridge.
- Added the Android `:app` module: a single activity flipping between the config, search and results panels, with Keystore-encrypted credential storage.
- Added the Nix flake providing the Rust toolchain (Android targets), cargo-ndk, the Android SDK/NDK, JDK 17 and Gradle.
- Added an Open action on message attachments that hands the part to an installed viewer through a content URI when one can handle the type, keeping Save as the fallback.

### Changed

- Added a shared top app bar across all frames: app title on the left, a back arrow that appears on pushed frames, account settings behind a gear icon on the search frame, and the search progress bar anchored directly beneath it.
- Replaced panel flipping with a back-stack navigation model: auth or search is the root depending on whether credentials exist, opening a message or the settings pushes a frame, the back arrow and system back button pop, and popping the root quits.
- Verified the IMAP connection on auth submit before persisting credentials and resetting the stack to the search frame.
- Pinned the auth submit button to the bottom of the frame so it stays visible while the form scrolls.
- Replaced the long search-field hint with a short placeholder, adding a heading and a one-paragraph description of the search above the field.
- Gave every frame the same content padding and a shared title style: an h2 frame heading with uniform spacing, distinct from the top bar h1.
- Added a labelled field to every input on the auth frame (domain, port, mechanism, login, password).
- Added an About frame, reachable from the auth frame's top bar, describing Pimalaya and limier with links and donation options.
- Showed the search progress bar only while a search runs, flush against the top bar: the done portion in the app bar blue, the remaining in white.
- Made stopping a search instant: cancelling closes the live sockets so blocked reads unwind at once, and the UI flips back without waiting for the workers, dropping any late callbacks.
- Replaced the per-mailbox result tables with a divided list of clickable "date: subject" rows.
- Switched mailbox opening from `SELECT` to read-only `EXAMINE` for searching and fetching, since limier only consults messages.
