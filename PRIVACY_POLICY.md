# Privacy Policy for Readout

**Last Updated: September 6, 2026**

Readout ("we", "our", or "us") is dedicated to protecting your privacy. This Privacy Policy details our data practices for the Readout mobile application (the "App").

---

## 1. Local-First Architecture & No Personal Data Collection

Readout is designed as a privacy-respecting, local-first document reader and text-to-speech companion.

* **No Accounts Required**: You do not need to create an account, log in, or provide any personal details (such as your name, email address, phone number, or billing information) to use the App.
* **No Analytics or Trackers**: We do not integrate third-party advertising SDKs, tracking frameworks, or usage analytics in the App.
* **On-Device Storage**: Your imported documents (PDF, EPUB, DOCX, TXT, HTML), extracted text, reading positions, chapters, bookmarks, collections, and app settings are stored locally on your device in the App's private SQLite/Room database and internal storage directory.

---

## 2. Text-to-Speech Audio Rendering & Voice Preferences

Readout interfaces directly with the Android platform Text-to-Speech (TTS) subsystem on your device.

* **Local Synthesis**: By default, Readout synthesizes audio using local on-device TTS voice packs installed on your system.
* **Network-Dependent Voices**: Depending on your Android device manufacturer and default TTS engine (such as Google Speech Services), some high-fidelity voices may stream synthesis audio from the speech provider's servers.
* **Offline-Only Voice Setting**: Readout includes a user-configurable **"Use offline voices only"** preference in Settings. Enabling this ensures that only local, downloaded, offline voices are utilized for speech synthesis.

---

## 3. Optional Online Translation Feature

Readout offers an optional real-time sentence translation feature.

* **User-Initiated**: Translation is disabled by default ("None"). It only activates when you explicitly select a target translation language.
* **Data Transmission**: When real-time translation is enabled, text sentences from the document you are reading are sent over encrypted HTTPS connections to translation service endpoints (such as Google Translate and MyMemory API) to retrieve the translated text.
* **No User Identifiers**: These network requests contain only the sentence text and language pair. No user identity, advertising ID, account information, or document metadata is transmitted.
* **Offline Fallback & Disabling**: If you do not wish for text to be sent for translation, keep the translation language set to "None". The App will function completely offline.

---

## 4. Network Access & URL Article Imports

* **Direct Fetching**: When you use the "Import Web Link" feature, the App connects directly to the specified HTTP/HTTPS web address to download and parse the article text.
* **No Middleman Tracking**: Requests go directly from your device to the target website. We do not proxy, intercept, or log the URLs or content you import.

---

## 5. Local Hardware Profiling

* **Device Benchmarking**: The App includes a hardware profiling check that queries local system parameters (such as CPU core count and available RAM) to recommend appropriate performance tiers for smooth playback.
* **Local Only**: Benchmarking metrics are calculated and evaluated strictly on-device and are never transmitted externally.

---

## 6. Backup and Data Portability

* **Export & Import**: Readout allows you to export your entire library, bookmarks, and collections to a backup file on your device.
* **User Controlled**: You have complete control over where backup files are saved and shared. Readout never automatically uploads backups to cloud services.

---

## 7. Changes to This Privacy Policy

We may update our Privacy Policy occasionally to reflect changes in functionality or regulatory requirements. Any revisions will be published in this document and included in App updates.

---

## 8. Contact Us

If you have any questions or feedback regarding this Privacy Policy, please contact:
* **Email**: realiefan@gmail.com
