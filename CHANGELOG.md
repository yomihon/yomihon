# Changelog

All notable changes to this project will be documented in this file.

The format is a modified version of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
- `Added` - for new features.
- `Changed ` - for changes in existing functionality.
- `Improved` - for enhancement or optimization in existing functionality.
- `Removed` - for now removed features.
- `Fixed` - for any bug fixes.
- `Other` - for technical stuff.


## [v0.1.0] - 2026-01-23

### Added
- In-reader OCR using `manga-ocr-tflite` with C++ impl, GPU and CPU fallbacks, automatic re-initialization, and error handling.
- Integrated dictionary system: low-RAM dictionary import and search; it supports term, pitch, kanji, and frequency dicts, priority sorting, and shows import progress in the UI.
- OCR selection mode (long-tap to select regions) and added OCR result UI sheet.

### Changed
- Rebranded to **Yomihon** (app name, logos, links, Firebase and related assets).
- Added C++ (this'll probably be removed with the new model next update) for running OCR.

### Other
- Initial release of **Yomihon** (based on Mihon v0.19.1).
- The full changelog for Mihon releases is available in their [repository](https://github.com/mihonapp/mihon/blob/main/CHANGELOG.md).


[unreleased]: https://github.com/yomihon/yomihon/compare/v0.1.0...main
[v0.1.0]: https://github.com/yomihon/yomihon/compare/c856f12...v0.1.0
