# Wiz PDF Master

**Scan · Read · Search — 100% offline.** A Wiz Labs app that combines a document scanner, a fast PDF reader and on-device text recognition (OCR) in one app. No internet permission, no accounts, no ads.

- Package: `com.wizlabs.pdfmaster` · Launcher name: **PDF Master** · min Android 8.0 (API 26), target API 36
- Kotlin + Jetpack Compose, Wiz black-and-yellow look

## Features

**Scanner**
- Live page-edge detection with a smoothed outline (OpenCV)
- Auto-capture when the page is held steady (progress runs around the outline)
- Single or batch mode, torch, tap-to-focus, import from the gallery
- Corner adjustment with a magnifier, auto-detect and full-page reset
- Perspective correction and five looks: Original, Magic (shadow removal + colour boost), Grayscale, B&W, No shadow
- Review: swipe between pages, rotate, reorder, delete, add more, "apply look to all"
- Save as A4 / Letter / fit-to-image PDF, optionally **searchable** (invisible OCR text layer)

**Reader**
- Opens any PDF: from the app, the phone's storage, or "Open with" / share from WhatsApp, Gmail, file managers
- Smooth scrolling with pinch-zoom, double-tap zoom and fling; pages re-render sharply after zooming
- Search with highlights and next/previous, page thumbnails, page slider, go-to-page
- Night reading, resume where you left off, document info
- Password-protected PDFs (unlocked on the phone; the password is never stored)
- **Extract text** from any page: uses the PDF's own text, or OCR for scanned pages; copy or share

**Library**
- My scans (private storage) with thumbnails, rename, share, save to device, delete
- Recent PDFs opened from elsewhere, with first-page thumbnails
- Search by name

## Engines (all on-device)
| Job | Library | Licence |
|---|---|---|
| Edge detection, warp, filters | OpenCV 4.10 | Apache 2.0 |
| Writing PDFs, text extraction, decryption | PDFBox-Android 2.0.27 | Apache 2.0 |
| Text recognition | Google ML Kit Text Recognition (bundled Latin model) | ML Kit terms |
| Page rendering | Android `PdfRenderer` | — |
| Camera | AndroidX CameraX 1.4 | Apache 2.0 |

The manifest strips `INTERNET` and `ACCESS_NETWORK_STATE`, so nothing can leave the phone.

## Build

```bash
~/.local/opt/gradle-8.11.1/bin/gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
```

Release APK + AAB (signed with the Wiz keystore when `KEYSTORE_FILE`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` are set; otherwise debug-signed for local testing only):

```bash
~/.local/opt/gradle-8.11.1/bin/gradle --no-daemon :app:assembleRelease :app:bundleRelease
```

Release builds are shrunk with R8 and ship only ARM libraries (about 29 MB APK / 32 MB AAB; Play delivers less per device). Debug builds also include x86_64 for emulators.

GitHub Actions: `.github/workflows/android.yml` builds the signed production APK and AAB on every push to `main` using the `KEYSTORE_BASE64`, `KEYSTORE_PASSWORD`, `KEY_ALIAS`, `KEY_PASSWORD` secrets.

## Privacy
See [privacy-policy.html](privacy-policy.html) (also bundled in the app under Settings → Privacy policy).

## Store assets
- `store-assets/`: Play icon (512), feature graphic (1024×500), 6 framed phone screenshots, logo, and **[PLAY_CONSOLE_GUIDE.md](store-assets/PLAY_CONSOLE_GUIDE.md)** (listing, content rating, Data safety answers, release steps)
- `fastlane/metadata/android/en-US/`: title, short and full description, changelog and images in Play's standard layout
- `tools/store-art.swift`: regenerates the icon, logo and feature graphic (`swift tools/store-art.swift icons store-assets`) and frames screenshots
