# Readout production readiness audit — 6 September 2026

**Decision: do not approve the current local build for a production rollout yet.** The app builds, but targeted probes and code tracing found parser crashes, text fidelity defects, translation state problems, and backup integrity gaps. A public Play listing exists; the exact production binary and its runtime health remain unverified.

## Local code, GitHub, and live release

- Native Kotlin/Jetpack Compose Android application, package `com.iefan.readout`. Room database v7, Android platform TextToSpeech, iTextG PDF extraction, Jsoup HTML/XML parsing. Minimum Android API 24, target 36. No app backend or bundled ONNX speech runtime was found in the inspected implementation. The README's AI Studio/Gemini setup instructions are stale relative to the implementation.
- Local `main` HEAD: `05a8cb4f4b032c9546cb250a9ebad7c7b24a9379`. A fresh `git ls-remote origin refs/heads/main` returned that same SHA. All commits on local main through this revision are on GitHub main.
- At audit start, **10 tracked files were modified and one new source file was untracked**. These working-tree changes are not included in GitHub main. The tracked diff was 270 additions / 111 deletions, excluding the new file.
- GitHub main declares **1.6.0 / versionCode 36**; the local working tree declares **1.6.2 / versionCode 38**.
- GitHub API returned no releases and zero Actions runs. No checked-in CI workflow was found. This does not rule out manual Play uploads or builds outside GitHub Actions.
- The [public Play listing](https://play.google.com/store/apps/details?id=com.iefan.readout) was reachable. It showed an August 14, 2026 update date and release-note text labeled v1.5.5. Release-note text does **not** prove the delivered versionCode or rollout track. The recent September 6 commits cannot be established as deployed from this page.
- No connected Android device was available (`adb devices -l` was empty), and this SDK did not contain the emulator executable. No on-device audio listening, frame-time measurement, Play-installed binary comparison, crash/ANR telemetry, or Play Console track inspection was performed.

### Changes still local at audit start

| File | Main effect |
|---|---|
| `app/build.gradle.kts` | Version 1.6.2 / 38 |
| `MainActivity.kt` | System-bar/theme behavior |
| `tts/ReadoutTtsEngine.kt` | Translation providers, caching, prefetch, voice fallback, connectivity check |
| `ui/components/KaraokeView.kt` | Follow scrolling, translations, sentence presentation |
| `ui/components/BookOptionsDialog.kt` | Dialog adjustment |
| `ui/components/EditBookDetailsDialog.kt` | Image preview loading |
| `ui/screens/MainLibraryView.kt` | Cover/UI behavior |
| `utils/CoverPreviewHelper.kt` | Sampled cover decoding |
| `viewmodel/ReadoutViewModel.kt` | Optimized cover saving |
| `app/src/main/res/values/themes.xml` | Theme/system bars |
| `utils/BitmapOptimizer.kt` — untracked | Shared bitmap downsampling/saving helper |

Paths above are relative to `app/src/main/java/com/iefan/readout` unless otherwise stated. Existing application source was not edited by the audit. Audit artifacts are additional files.

## Verification performed

| Check | Result |
|---|---|
| `:app:assembleDebug` | Passed |
| `:app:bundleRelease` | Passed, including R8 and signing; not uploaded |
| `:app:testDebugUnitTest` | Passed: 13 tests, zero failures/errors |
| `:app:lintRelease` | Passed with 66 warnings and zero errors |
| Whitespace check `git diff --check` | Passed |
| Additional JVM parser/normalizer/XML probes | Reproduced defects below |
| SQLite chunk-offset probe, actual 800,000-character chunks | Reproduced skipped text |
| Three translation provider HTTP probes | All responded to synthetic English-to-Spanish input |

Build command: `JAVA_HOME=/opt/homebrew/opt/openjdk@21/libexec/openjdk.jdk/Contents/Home GRADLE_USER_HOME="$PWD/.gradle-local" ./gradlew :app:testDebugUnitTest :app:lintRelease :app:assembleDebug --console=plain --no-configuration-cache`.

The initial offline attempt could not resolve uncached test dependencies; the online retry passed. This was an environment/dependency availability issue, not a Kotlin compilation failure. Existing tests cover text cleaning/parsing/normalization and chapter detection, plus trivial arithmetic/resource and empty-content screenshot checks. They do not validate real playback, translation races, file-import fidelity, migrations, backup round trips, batch recovery, or scroll frame timing. Lint warnings are mostly dependency freshness, unused resources, and style. Its static-context warning is not treated as a confirmed Activity leak: the engine is constructed with the Application context.

Run `python3 audit/2026-09-06/run-probes.py` after building to repeat the host probes.

See [probe source](/Users/iefan/code/github/readit-new/audit/2026-09-06/AuditProbe.java), [probe output](/Users/iefan/code/github/readit-new/audit/2026-09-06/probe-results.txt), [unit test report](/Users/iefan/code/github/readit-new/app/build/reports/tests/testDebugUnitTest/index.html), and [lint report](/Users/iefan/code/github/readit-new/app/build/reports/lint-results-release.html). Probes call the freshly built parser and normalizer classes; the DOCX probe reproduces the exact XML selection/concatenation logic using the app's Jsoup version. These are host JVM checks, not device instrumentation.

## Findings requiring action

### 1. High — long paragraphs can crash speech parsing

Source: [DocumentParser.kt:92](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/tts/DocumentParser.kt:92), [ReadoutViewModel.kt:710](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/viewmodel/ReadoutViewModel.kt:710).

Calling `DocumentParser.parse("word ".repeat(1200))` on the compiled local implementation produced `StackOverflowError`. The recursive/backtracking sentence regex processes an unbounded paragraph. Import prewarming invokes this parser after saving the document; the import exception handlers catch `Exception`, which does not catch `StackOverflowError`. Opening an already imported document or prewarming it can encounter the same input again. Exact stack limits vary by runtime, but the algorithmic failure is reproduced.

Replace this with bounded/iterative segmentation. Add regressions for long unpunctuated paragraphs, minified text, and long multilingual content. Also cap utterance size after normalization/translation: the queue passes whole sentences to `speak` and never applies the [platform maximum speech-input length](https://developer.android.com/reference/android/speech/tts/TextToSpeech#getMaxSpeechInputLength()).

### 2. High — document/language switches can admit stale translation results

Source: [ReadoutTtsEngine.kt:231](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/tts/ReadoutTtsEngine.kt:231), [459](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/tts/ReadoutTtsEngine.kt:459), [811](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/tts/ReadoutTtsEngine.kt:811), [953](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/tts/ReadoutTtsEngine.kt:953).

`prepareSpokenText` captures an old sentence/language, waits on translation, then writes into shared maps without validating document identity or a generation token. Spoken-cache keys contain sentence index and language but no document ID. `loadDocument` clears maps, but playback invalidation does not cancel the translation prefetch job; paused-language and seek preparation also launch separate jobs. An old request can finish after the clear and place the old book's sentence into the new book's cache. The queue checks its playback token only **after** preparation has already mutated those maps. This is a code-confirmed race opportunity, not an on-device reproduced incident.

Capture immutable document/language/generation context, reject stale results before every state write, and cancel in-flight work on switches. Test rapid A→B document and Spanish→Hindi language switching with delayed responses.

### 3. High — translation failures are cached as success-like fallback

Source: [ReadoutTtsEngine.kt:811](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/tts/ReadoutTtsEngine.kt:811), [KaraokeView.kt:250](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/ui/components/KaraokeView.kt:250).

When offline or when all providers fail, preparation normalizes the original sentence and caches it under the target-language key. Subsequent attempts return that cache entry without retrying translation. The translated map stays empty, while the UI can keep saying “Translating...”. The user hears original-language content without an explicit failure. Voice fallback uses the current voice locale rather than a detected source language, so it can also pronounce original content with the target-language voice.

Represent loading/success/failure/offline distinctly; do not cache a failed translation as translated speech. Provide retry and intentional original-language fallback.

Live probe: Google mobile HTML returned HTTP 200 with the expected result container (~0.51s); the Google `dict-chrome-ex` endpoint returned Spanish JSON (~0.45s); MyMemory also returned Spanish JSON (~1.38s). These single requests establish reachability from this machine only. Google HTML scraping remains coupled to page markup. MyMemory documents a [500-byte query limit](https://mymemory.translated.net/doc/spec.php); the app does not split to this limit. Rate limits, long content, device networking, and sustained translation playback were not load-tested.

### 4. High — privacy statements disagree with implemented data flow

Source: [PRIVACY_POLICY.md](/Users/iefan/code/github/readit-new/PRIVACY_POLICY.md), [ReadoutTtsEngine.kt:566](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/tts/ReadoutTtsEngine.kt:566), [994](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/tts/ReadoutTtsEngine.kt:994).

The policy says documents are never transmitted to external servers and all audio rendering is local. Translation sends sentence text to Google and potentially MyMemory; automatic voice scoring favors network-dependent voices when online. The public listing also describes wholly local/offline processing. Update product copy and disclosures to the actual behavior, clearly identify online processing, and provide a meaningful offline-only voice choice. This is an observed product/disclosure mismatch, not a determination of legal or Play policy compliance.

### 5. High — large Unicode documents lose characters during chunked loading

Source: [DocumentRepository.kt:24](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/data/DocumentRepository.kt:24).

SQLite `length` and `substr` operate on Unicode characters; Kotlin `String.length` counts UTF-16 code units. Advancing SQL offsets by `chunk.length` skips characters after supplementary Unicode characters such as emoji. Using the actual 800,000-character chunk size with a 1,600,008-character fixture reproduced a 1,600,007-character result: `BOUNDARY` became `OUNDARY`. This applies to the large-document/chunk fallback path.

Advance using the SQL character count or the requested chunk span. Add a Room round-trip regression with emoji before chunk boundaries. Do not treat chunked retrieval as bounded-memory parsing: the repository still rebuilds the full string.

### 6. High — backup restoration is not a reliable round trip

Source: [ReadoutViewModel.kt:1571](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/viewmodel/ReadoutViewModel.kt:1571), [1651](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/viewmodel/ReadoutViewModel.kt:1651).

- Collections reference document titles. `titleToNewIdMap[title]` overwrites earlier books with the same title, restoring membership onto the last duplicate.
- Backup saves absolute cover paths, not cover bytes. Covers are absent on a fresh installation/device.
- Restore inserts progressively without a surrounding transaction. A malformed later record returns false after earlier records have already been inserted; retrying duplicates those records.
- Global preferences such as voice/language/theme/collection ordering are not exported. There is no backup schema version.

Use stable exported identifiers, validate before writing, restore transactionally, package covers, and define settings/version compatibility. Test duplicate titles, corrupt midway input, new-device restore, and repeated imports.

### 7. Medium — DOCX text loses meaningful whitespace

Source: [ReadoutViewModel.kt:1266](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/viewmodel/ReadoutViewModel.kt:1266).

Appending `t.text()` for each Word text run trims each run independently. A fixture with `<w:t xml:space="preserve">Hello </w:t>` followed by `<w:t>world</w:t>` returned `Helloworld`. Formatting changes commonly create separate runs. Tabs and explicit breaks are also not handled by this text-node-only extraction. Preserve XML text whitespace and process Word tab/break elements.

### 8. Medium — multilingual segmentation and normalization change reading fidelity

Source: [DocumentParser.kt:92](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/tts/DocumentParser.kt:92), [SpokenTextNormalizer.kt:58](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/tts/SpokenTextNormalizer.kt:58), [TextCleaner.kt:10](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/utils/TextCleaner.kt:10).

Probes returned a single sentence for Chinese `你好。世界！再见？` and for two Hindi sentences separated by danda. Normalization converted Spanish `El descuento es 15%.` into `El descuento es 15 percent.` and `Meet at 5:30 p.m.` into `Meet at 5, 30 p. m.`. Cleanup deleted standalone `2026` and `42`, which can be genuine content rather than page numbers. Use locale-aware segmentation and normalization and make destructive cleanup contextual or optional.

### 9. Medium — word highlighting is not implemented in the rendered reader

Source: [KaraokeView.kt:45](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/ui/components/KaraokeView.kt:45), [235](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/ui/components/KaraokeView.kt:235).

`currentWordRange` is accepted but never read. Text renders as plain `sentence.text`; only the sentence card is emphasized. The public listing promises active-word highlighting. Restoring it also requires a reliable original-to-spoken offset mapping: the engine currently estimates normalized/translated progress proportionally, which cannot guarantee exact word alignment.

### 10. Medium — TTS failure and initialization handling can leave playback unusable

Source: [ReadoutTtsEngine.kt:300](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/tts/ReadoutTtsEngine.kt:300), [745](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/tts/ReadoutTtsEngine.kt:745), [803](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/tts/ReadoutTtsEngine.kt:803), [AndroidManifest.xml](/Users/iefan/code/github/readit-new/app/src/main/AndroidManifest.xml).

The queue logs but does not act on synchronous `speak()` failure, and marks the index queued before checking success. Its stall watchdog begins only after `onDone`, so an initial rejected request has no equivalent recovery. Asynchronous errors silently skip a sentence. Audio-focus acquisition returns a Boolean that the caller ignores. Initialization requires US English and logs failure without user recovery state. The merged manifest lacks the [documented TTS service visibility query](https://developer.android.com/reference/android/speech/tts/TextToSpeech). Add explicit errors, bounded retry, input splitting, focus handling, and engine/language setup recovery. These pathways need device/provider testing.

### 11. Medium — shared text files and multiple shared documents are missed

Source: [MainActivity.kt:382](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/MainActivity.kt:382), [AndroidManifest.xml](/Users/iefan/code/github/readit-new/app/src/main/AndroidManifest.xml).

For `ACTION_SEND` with `text/*`, the handler checks only `EXTRA_TEXT`. A TXT/HTML file delivered via `EXTRA_STREAM` is ignored. `ACTION_SEND_MULTIPLE` is not registered or handled. The in-app multiple-file picker works through a separate path; that does not cover Android multi-file sharing. Handle stream payloads before text snippets and implement multiple streams if advertised.

### 12. Medium — batch processing lacks durable recovery and reliable summaries

Source: [ReadoutViewModel.kt:1123](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/viewmodel/ReadoutViewModel.kt:1123), [974](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/viewmodel/ReadoutViewModel.kt:974).

Positive: documents are processed sequentially on IO, temporary files are deleted in `finally`, and ordinary per-item exceptions allow the next file to continue. However, work belongs to the ViewModel rather than a durable job; there is no restart/resume checkpoint, per-item result ledger, or cancellation control. Multiple entry points independently update one global progress state. Whole file/ZIP entry reads have no application-level size/decompressed-byte limit. Stack overflow/OOM errors are outside the batch `Exception` handler. Add a serialized import coordinator, limits, cancellation, and explicit success/failure results. Use durable work if imports are expected to survive process death.

### 13. Medium — reading progress can remain stale through many short sentences

Source: [ReadoutViewModel.kt:225](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/viewmodel/ReadoutViewModel.kt:225), [370](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/viewmodel/ReadoutViewModel.kt:370), [556](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/viewmodel/ReadoutViewModel.kt:556).

Progress saving uses `collectLatest` with a three-second delay. Every faster sentence transition cancels the pending save. Switching directly to another book stops the engine without first saving the old position. Notification pause uses the engine directly. A long run of short sentences can therefore leave an old resume position. Also, `togglePlayback` assumes being on the last sentence means completion, so pausing midway through the last sentence and resuming restarts the book. Persist periodically and on all transitions; track completion separately from sentence index.

### 14. Medium — scanning can hide distinct files

Source: [DeviceDocumentScanner.kt:172](/Users/iefan/code/github/readit-new/app/src/main/java/com/iefan/readout/utils/DeviceDocumentScanner.kt:172).

Folder scanning deduplicates by lowercase filename, not URI/document identity. Two different directories containing `chapter.pdf` produce one result. Traversal stops beyond depth four. Deduplicate by identity and expose scan limits/errors rather than silently implying all documents were discovered.

## Feature coverage and remaining limits

| Area | Assessment |
|---|---|
| TTS/audio controls | Platform engine, queue buffering, MediaSession, headphone-unplug pause, focus listener, speed, audition, and sleep timer are present. Sound quality/gaplessness, Bluetooth, calls, screen-off behavior and battery use were not measured. Engine lifetime is tied to a ViewModel; swiping the task away explicitly pauses playback. |
| Reader scrolling | LazyColumn, stable sentence keys, manual-follow suspension and resync are implemented. Active styling changes item padding, width and font weight, while translation arrival changes height. The follow effect is keyed to sentence index/follow state, not later text-layout changes; off-screen targets jump using scrollToItem. These are layout/jump risks, not measured jank. Long sentences do not scroll word-by-word because word range is unused. |
| Translation | Three providers responded to a tiny probe; reliability/state defects remain. No offline translation model exists in inspected code. English normalizer is applied to translated languages. |
| PDF | Text-layer extraction and PDF cover rendering exist. No OCR implementation was found, so image-only scans return no readable text. Multi-column, encrypted and malformed PDF behavior needs fixtures. PDF page offsets are recorded before inserting page separators; verify chapter alignment after cleanup. |
| EPUB | OPF spine order is attempted, with natural filename fallback. It chooses the first OPF rather than resolving container.xml and concatenates relative hrefs without normalizing `..`. Partially resolved spines can silently omit sections. TOC alignment searches titles rather than resolving anchors. Test real EPUB 2/3 packages, nested paths and duplicate titles. |
| HTML/web | Static Jsoup extraction; no JavaScript rendering/authenticated session. Nested selected blocks such as blockquote+p or li+p can duplicate descendant text. URL pages lacking selected paragraph/heading blocks may be rejected. |
| TXT/DOCX | UTF-8-oriented imports exist. DOCX whitespace defect reproduced. Numeric cleanup can remove content. Legacy encodings and structural Word features need tests. |
| Large/multiple documents | Metadata-only library queries, chunked retrieval, six-document sentence cache, and sequential imports are good foundations. Full strings/DOMs/sentence lists still accumulate in memory; there is no size-based sentence-cache budget or large-file corpus evidence. |
| Library/bookmarks/collections | Room relationships and deletion transactions exist. Backup identity flaw and chunked Unicode loss need fixes. Cover caches are byte-limited; edited covers still use a raw-copy path and old cover-file cleanup is incomplete. |
| Settings | Preferences persist for core options. The hardware benchmark simulates elapsed progress and classifies CPU/RAM rather than measuring speech performance. `configureVoiceForTier` does not use its `tier` argument to select distinct synthesis models; avoid presenting this as measured model performance. |
| Accessibility/UI | No TalkBack/font-scaling/device contrast pass performed. Reader uses low-alpha white for inactive text and bookmark long-press interactions; validate readability and discoverability. The existing screenshot test renders empty theme content, not the real screen. |
| Database upgrades | Migrations 3→7 exist; schema export is disabled and migration tests are absent. Earlier versions have no defined upgrade path; downgrade is destructive. Whether this affects existing users depends on historical shipped database versions. |
| Release/security basics | Release shrinking/lint are enabled. Signing contains default passwords and a debug-key fallback: release builds should fail closed when intended signing material is unavailable. The upload keystore was not tracked in the current index; a debug-keystore base64 file was tracked. No claim of upload-key exposure is made. Broad storage access, backup configuration and dependency/license obligations need release-owner review; no vulnerability/CVE or legal compliance certification was performed. |

## Release gates

1. Fix the reproduced parser crash, Unicode chunk loss, DOCX whitespace loss, translation state/failure handling, and backup identity/atomicity defects; add regression fixtures.
2. Resolve TTS input/error/language recovery, progress persistence, text-file sharing and any mismatches between advertised and rendered features.
3. Run device tests on a current Pixel and Samsung plus a lower-memory device: multiple installed/missing voices, airplane mode and reconnect, rapid document/language switches, 30–60 minute playback, screen off, Bluetooth/calls, sentence seeking, and final-sentence resume.
4. Test a representative import corpus: scanned/text/multi-column/encrypted PDFs; EPUB 2/3 with relative paths; formatted DOCX; Unicode/legacy text; 50+ mixed imports; huge/invalid files; duplicate-name files; backup/restore onto a fresh install.
5. Measure release-build reader/library frame times and memory under that corpus; verify TalkBack and large fonts. No FPS or production stability claim can be made from source review alone.
6. Commit the intended working-tree changes including BitmapOptimizer, add CI tests/lint/release artifact checks, and associate a release artifact with a commit. Verify the Play Console production versionCode, signing identity, rollout and Android Vitals before declaring these changes live.
