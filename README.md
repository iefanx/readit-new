# Readout

Readout is a modern, privacy-focused, offline-first document reader and Text-to-Speech (TTS) audio engine for Android, built with Jetpack Compose, Material 3, and Room.

---

## Features

- **Document Ingestion**:
  - **PDF**: Fast text-layer extraction and native cover thumbnail rendering.
  - **EPUB**: Full OPF package resolution (`container.xml`), spine order synchronization, and table-of-contents alignment.
  - **DOCX**: Complete OOXML run parsing preserving whitespace (`xml:space="preserve"`), tabs, and paragraph line breaks.
  - **Plain Text / TXT**: Automatic encoding detection for UTF-8, UTF-16 with BOM, and legacy Windows-1252 encodings.
  - **Web Articles & HTML**: Clean DOM extraction filtering scripts, navigation menus, and nested containers.
- **Text-to-Speech Engine**:
  - Seamless sentence-by-sentence audio playback powered by the Android platform Text-to-Speech subsystem.
  - Utterance stall watchdog and automatic synthesis error recovery.
  - Platform-compliant speech input splitting (`SpeechChunks`) enforcing Android's maximum speech-input length.
  - Dynamic speech speed control, audition personas, and sleep timer.
  - Audio focus handling (pauses during phone calls and navigations; auto-pauses on headphone disconnect).
- **Multilingual Segmentation & Normalization**:
  - Bounded, iterative sentence parser preventing regex recursion and stack overflow errors on large unpunctuated texts.
  - Full boundary support for CJK (`。！？`), Indic scripts (`।`, `॥`), and standard Latin punctuation with abbreviation detection.
  - Language-aware speech normalizer that preserves timestamps, mathematical expressions, and foreign vocabulary.
- **Real-Time Translation**:
  - Optional sentence translation with isolated generational request tokens preventing race conditions across document/language switches.
  - Granular error states with one-tap retry for offline or transient network failures.
  - Offline-first fallback keeping original text readable without corrupting pronunciation caches.
- **Reliable Storage & Backups**:
  - Room v8 database with migration path and transactional operations.
  - Chunked Unicode queries using code-point offsets to prevent character loss on documents containing emojis or supplementary symbols.
  - Complete backup export/import packaging cover images in Base64 with transactional rollback protection.
  - Resilient batch file import queue with durable journal checkpoints surviving process death.

---

## Tech Stack

- **UI**: 100% Jetpack Compose with Material Design 3 and OLED dark theme
- **Language**: Kotlin 2.x
- **Persistence**: Room Database (SQLite) with transactional operations
- **Concurrency**: Kotlin Coroutines & StateFlow / SharedFlow
- **Parsing**: Jsoup (HTML/XML/DOCX), iTextG (PDF)
- **Audio**: Android TextToSpeech API, MediaSessionCompat, AudioFocusRequest
- **Target OS**: Android 7.0 (API 24) minimum, Android 16 (API 36) target

---

## Getting Started

### Prerequisites

- **Java Development Kit**: OpenJDK 21
- **Android SDK**: Build Tools 36, Platform API 36
- **Gradle**: Wrapper included (Gradle 8.13)

### Building Locally

To build the debug APK:
```bash
./gradlew :app:assembleDebug
```

To run unit and regression tests:
```bash
./gradlew :app:testDebugUnitTest
```

To run Android lint:
```bash
./gradlew :app:lintRelease
```

To build the production release bundle (AAB):
```bash
./gradlew :app:bundleRelease
```

---

## Automated Probes & Auditing

Host-side probes are included in the `audit/` directory to verify core algorithmic invariants (stack overflow boundaries, Unicode code point offsets, XML run preservation, normalizer behavior) without requiring a physical Android device:

```bash
python3 audit/2026-09-06/run-probes.py
```

---

## Privacy

Readout is designed to be local-first. Documents, metadata, and reading progress are stored locally on your device. For details regarding optional online translation and speech engine network options, refer to [PRIVACY_POLICY.md](PRIVACY_POLICY.md).

---

## License

Readout is available under the MIT License.
