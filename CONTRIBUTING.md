Looking to report an issue/bug or make a feature request? Please refer to the [README file](https://github.com/yomihon/yomihon#issues-feature-requests-and-contributing).

---

Thanks for your interest in contributing to Yomihon!


# Code contributions

Pull requests are welcome!

If you're interested in taking on [an open issue](https://github.com/yomihon/yomihon/issues), please comment on it so others are aware.
You do not need to ask for permission nor an assignment.

## Prerequisites

Before you start, please note that the ability to use following technologies is **required** and that existing contributors will not actively teach them to you.

- Basic [Android development](https://developer.android.com/)
- [Kotlin](https://kotlinlang.org/)
- It's recommended to download the float32 encoder, decoder, and embeddings from [Hugging Face](https://huggingface.co/bluolightning/manga-ocr-tflite/tree/main)
  - Move the model files to the `app/src/main/assets/ocr/` directory
  - Rename the files to `encoder.tflite`, `decoder.tflite`, and `embeddings.tflite`

### Tools

- [Android Studio](https://developer.android.com/studio)
- Emulator or phone with developer options enabled to test changes.

# Translations

Translations are done externally via Weblate. See [our website](https://yomihon.github.io/docs/contribute#translation) for more details.


# Forks

Forks are allowed so long as they abide by [the project's LICENSE](https://github.com/yomihon/yomihon/blob/main/LICENSE).

When creating a fork, remember to:

- To avoid confusion with the main app:
    - Change the app name
    - Change the app icon
    - Change or disable the [app update checker](https://github.com/yomihon/yomihon/blob/main/app/src/main/java/eu/kanade/tachiyomi/data/updater/AppUpdateChecker.kt)
- To avoid installation conflicts:
    - Change the `applicationId` in [`build.gradle.kts`](https://github.com/yomihon/yomihon/blob/main/app/build.gradle.kts)
- To avoid having your data polluting the main app's analytics and crash report services:
    - If you want to use Firebase analytics, replace [`google-services.json`](https://github.com/yomihon/yomihon/blob/main/app/src/standard/google-services.json) with your own
