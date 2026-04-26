# Changelog

All notable changes to this project will be documented in this file.

The format is a modified version of [Keep a Changelog](https://keepachangelog.com/en/1.1.0/), and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).
- `Added` - for new features.
- `Changed` - for changes in existing functionality.
- `Improved` - for enhancement or optimization in existing functionality.
- `Removed` - for now removed features.
- `Fixed` - for any bug fixes.
- `Other` - for technical stuff.

## [v0.3.1] - 2026-04-14

### Improved
- Better performance for selecting a region

### Fixed
- Fix new region selection
  - Prefer loaded pages over refetching
  - Fix selection region for vertical reader
  - Allow selection across multiple pages for vertical reader

## [v0.3.0] - 2026-04-12

### Added
- Panel-by-panel reading mode (@leoxs22)
- Cropped image export for Anki cards
  - This can be enabled in the settings

### Improved
- Normalize punctuation for OCR speech bubble text
- Allow Japanese/Chinese dictionary searches across spaces

### Fixed
- Fix saved searches not deleting when they are held
- Fix shifted OCR results in crop mode
- Fix app crash on devices without LiteRT library

### Other
- Derive region crop from page source, rather than PixelCopy

## [v0.2.5] - 2026-04-05

### Added
- Dictionary results as a popup mode for pre-scanned chapters
  - Requires clearing the scanned chapter cache (sorry)
  - Allows for one-tap dictionary lookups of any word
- Setting toggle for darkening the reader when the dictionary is open
- Audio button for dictionary terms and Anki export

### Improved
- Improve ruby format for exported Anki cards

### Changed
- Default Anki card format no longer center aligns glossary
- Group dictionary results with the same expression

### Fixed
- Fix some incorrect dictionary styling
- Fix extra pitch and term info for dictionary searches

## [v0.2.4] - 2026-03-31

### Added
- Search filter presets
- Full-page OCR chapter scanning
- Dedicated OCR setting screen

### Improved
- Better Japanese deinflection for searches
- Near instant dictionary imports

### Changed
- Reposition the copy button next to OCR text (@leoxs22)
- Switch dictionary controls to use Hoshidicts
- Add top whitespace to images in exported Anki cards

### Other
- Added database automatic migration logic
- Added Android DevContainer development support (@leoxs22)

## [v0.2.2] - 2026-03-23

### Added
- Add more field values for frequency dictionaries

### Improved
- Alphabetically sort Anki field value list

### Fixed
- Fix WordSelector alignment when no dictionary results

## [v0.2.1] - 2026-03-15

### Added
- Add English dictionary search support
- "Online" OCR model option (GLens) - many languages

### Improved
- Automatic fallback to another model if OCR fails

### Changed
- Add and use word (furigana) for default Anki template
- Add checkmark icon for duplicate cards

### Fixed
- Fix dictionary highlight issue with romaji
- Fix pitch accent color on Anki light mode
- Fix reader crash on arm32 devices when running OCR

## [v0.2.0] - 2026-02-20

### Added
- Add one-click AnkiDroid card creation
- Add AnkiDroid settings screen and field mappings
- Download and import recommended dictionaries from in-app
- "Fast" OCR model option (experimental)

### Improved
- OCR selection can be done on immediate hold + drag (no finger lift required)

### Changed
- Dictionaries now import in the background
- Dictionary import status is shown in notifications

### Fixed
- Fix broken pitch accent graph display

## [v0.1.1] - 2026-01-25

### Changed
- Improve display of OCR results on large screens

### Fixed
- Fix crash on tablets when opening dictionary settings
- Fix missing app language options

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


[unreleased]: https://github.com/yomihon/yomihon/compare/v0.3.1...main
[v0.3.1]: https://github.com/yomihon/yomihon/compare/v0.3.0...v0.3.1
[v0.3.0]: https://github.com/yomihon/yomihon/compare/v0.2.5...v0.3.0
[v0.2.5]: https://github.com/yomihon/yomihon/compare/v0.2.4...v0.2.5
[v0.2.4]: https://github.com/yomihon/yomihon/compare/v0.2.2...v0.2.4
[v0.2.2]: https://github.com/yomihon/yomihon/compare/v0.2.1...v0.2.2
[v0.2.1]: https://github.com/yomihon/yomihon/compare/v0.2.0...v0.2.1
[v0.2.0]: https://github.com/yomihon/yomihon/compare/v0.1.1...v0.2.0
[v0.1.1]: https://github.com/yomihon/yomihon/compare/v0.1.0...v0.1.1
[v0.1.0]: https://github.com/yomihon/yomihon/compare/c856f12...v0.1.0
