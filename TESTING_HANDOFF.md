# Wiz PDF Master — Testing handoff (Stages 1–3)

For: Claude Opus 5.5 (tester). Written 2026-11-15.
Coded by: Claude Fable 5.2. Your job: **test, find bugs, fix them**, and report what you found and fixed. Don't add features.

## 1. Where things are

- Repo: `/Users/aashish/Desktop/Personal FIles/Wiz Labs/Wiz-PDF-Master`
- Branch: `feature/roadmap-stages` (local only, **not pushed**). `main` is at `e1d0942` = live 1.2.0 (7).
- Commits:
  | Commit | Stage | Version |
  |---|---|---|
  | `4a34c8c` | Stage 1: page tools, compress, scan quality, update prompt | 1.3.0 (8) |
  | `1524861` | Stage 2: sharper scans, ID card, PDF to JPG, passwords, drafts | 1.4.0 (9) |
  | `1e60f90` | Stage 3: smart scanner, multi-language OCR, annotate, forms, watermark, QR | 1.5.0 (10) |
- Inspect each one: `git show --stat <hash>`.
- Package: `com.wizlabs.pdfmaster`. The stack is Kotlin, Compose and Material3, single `app` module, minSdk 26, targetSdk 36.
- Libraries: OpenCV 4.10, PDFBox-Android 2.0.27, CameraX 1.4.2, ML Kit text-recognition 16.0.1 (bundled Latin).
- New libraries:
  - `app-update-ktx` 2.1.0
  - `play-services-mlkit-document-scanner` 16.0.0
  - `play-services-mlkit-text-recognition-{devanagari,chinese,japanese,korean}` 16.0.2
  - `play-services-mlkit-barcode-scanning` 18.3.1
  - `play-services-base` 18.5.0

## 2. House rules (must keep)

- **Documents never leave the phone.** Since 1.5.0 the app has `INTERNET` and `ACCESS_NETWORK_STATE` (Google components, in-app updates, ads later). No ad SDK is linked yet and `AdsSetup.ENABLED` is false. The APK must list only CAMERA, VIBRATE, INTERNET, ACCESS_NETWORK_STATE (plus the auto-generated `DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION`). Check this with `aapt2 dump permissions`.
- **Zero cost.** No ads, billing, analytics or paid APIs.
- **Lightweight.** It has to run well on low-RAM phones. Animations are transform-only, with no per-frame allocation, and bitmaps and Mats are recycled.
- **Theme.** Yellow on black, like the other Wiz apps.
- **Builds run one at a time.** The laptop is low on RAM, so never run two Gradle builds or emulators at once.

## 3. Build and test commands

The Gradle wrapper is blocked; use the local install. Maven Central and Google Maven are reachable.
```bash
cd "/Users/aashish/Desktop/Personal FIles/Wiz Labs/Wiz-PDF-Master"
~/.local/opt/gradle-8.11.1/bin/gradle --no-daemon :app:testDebugUnitTest :app:assembleDebug
~/.local/opt/gradle-8.11.1/bin/gradle --no-daemon :app:assembleRelease
~/.local/opt/gradle-8.11.1/bin/gradle --no-daemon :app:connectedDebugAndroidTest
```
- Keep AGP 8.9.1, Kotlin 2.1.10 and Compose BOM 2025.02.00. Add `--offline` only if Maven is down.
- Emulator: AVD `wiz35` (API 35), SDK at `~/Library/Android/sdk`.
- Gboard may open a "Try out your stylus" sheet that swallows `adb shell input text`; tap Cancel on it.
- **The emulator cannot download Play-services ML Kit modules**, because Play services can't bind to Phonesky. The Google scanner, the extra OCR languages and live QR can't be tested there. Check that they **fall back gracefully** instead.

## 4. Status of earlier checks

- **Stage 1:** 46 unit tests pass and `assembleDebug` builds; this was re-checked independently. `assembleRelease` passed at Stage 1.
- **Stage 2:** 86 unit tests pass and `assembleDebug` builds; also re-checked independently.
- **Stage 3:**
  - Fable reported 113 unit tests passing, 6 device tests passing, and `assembleRelease` building before its last change, the Smart-scanner fallback.
  - After that change only `assembleDebug` was run. **The unit tests, device tests and R8 release build have not been run on the final code. Start here.**
- Nothing has been tested on a real phone. Unit tests: `app/src/test/...`. Device tests: `app/src/androidTest/...` (ScanEngineTest, ScanIntakeTest, PdfDecorDeviceTest, Viewer*).

## 5. Feature map (where to find each thing)

### Stage 1 — 1.3.0 (8)
| Feature | Files | Entry point |
|---|---|---|
| No double JPEG; MAXIMIZE_QUALITY + JPEG 95; ~8 MP cap | CameraScreen.kt | Camera |
| AF/AE lock before shot (1.5 s timeout) | CameraScreen.kt | Camera capture |
| Blur gate (live 90, recheck 60 at 1000 px, 2.5 s grace) + "Looks blurry — Retake?" | FrameAnalyzer.kt, ScanLogic.kt (ScanTuning), ReviewScreen.kt | Camera, Review |
| Glare hint (Y>250 + low chroma) | FrameAnalyzer.kt | Camera |
| INTER_CUBIC + unsharp mask | ScanEngine.kt | Every scan |
| Y-plane analysis ~9 fps, 800 ms steady | FrameAnalyzer.kt, CameraScreen.kt | Auto-capture |
| Page grid: drag reorder, multi-select, rotate, delete, extract | PagesScreen.kt, PageLogic.kt, PdfOps.kt | Viewer menu, library item menu, Home "PDF tools" |
| Merge, Split (ranges / every N), Images to PDF | ToolsScreen.kt, PdfOps.kt | Home "PDF tools" |
| Compress to target size / preset | PdfCompress.kt | Tools |
| 1-bit B&W (CCITT G4, Flate fallback) | PdfBuilder.kt, PdfCompress.kt | B&W filter save, Compress |
| In-app update card (FLEXIBLE) + "What's new" sheet | UpdatePrompt.kt, HomeScreen.kt | Home only |

### Stage 2 — 1.4.0 (9)
| Feature | Files | Entry point |
|---|---|---|
| Corner refinement (2000 px copy), inset 0.3% when refined | ScanEngine.kt (`refine`) | Capture, gallery import, Crop "Auto detect" |
| Quad scoring: Canny, Otsu, HoughLinesP, saturation | ScanEngine.kt | Live detection |
| Aspect snap A4 / Letter / ID-1 within 4% | ScanEngine.kt, ScanLogic.kt | Warp |
| New shadow removal; Magic = 3rd/95th percentile levels | ScanEngine.kt (`backgroundOf`, also used by SignatureEngine) | Filters, Sign |
| Sauvola B&W (`integral2`, 256-row strips) | ScanEngine.kt | B&W filter |
| Auto filter (default on new installs; old installs stay Magic) | ScanEngine.kt, Library.kt Prefs | Review filter chip "Auto · X" |
| Orientation + deskew via ML Kit line angle; setting "Auto-rotate pages" | Ocr.kt, ScanEngine.kt, SettingsScreen.kt | Capture, Settings |
| ID card mode: front + back on A4 at 85.6×54 mm | CameraScreen.kt, ToolsScreen.kt | Camera chip, Tools "Scan ID card" |
| PDF to JPG (150/200/300 DPI, MediaStore; share-only below Android 10); share page as image | ToolsScreen.kt, ViewerScreen.kt | Tools, Viewer menu |
| Password add (AES-256, AES-128 retry) / remove | PdfOps.kt, ToolsScreen.kt | Tools |
| Draft persistence `filesDir/drafts/<id>/`, "Continue scan (N pages)" card | DraftCodec.kt, ScanSession.kt, HomeScreen.kt | Home |

### Stage 3 — 1.5.0 (10)
| Feature | Files | Entry point |
|---|---|---|
| Smart scanner (Google ML Kit), BASE_WITH_FILTER / "Extra clean-up" = FULL, JPEG results into own Review; branded launch/return; falls back to Classic camera | SmartScanner.kt, PlayFeatures.kt, ProLogic.kt, SettingsScreen.kt, ReviewScreen.kt | Home hero and FAB, Review "Add page"; Settings → "Scanner" |
| Multi-language OCR (Devanagari/Chinese/Japanese/Korean via Play services), language sheet before first save, Unicode font embedding from /system/fonts | Ocr.kt, PlayFeatures.kt, UnicodeFonts.kt, PdfBuilder.kt | Settings → "OCR languages", save flow |
| Annotate (highlighter, pen, text note, eraser, undo/redo), drawn into page content, one page at a time | AnnotateScreen.kt, PdfDecor.kt | Tools, Viewer ⋮ |
| Watermark (text/image) + page numbers | StampScreen.kt, PdfDecor.kt, PdfOps.kt (`edit`) | Tools, Viewer ⋮ |
| Form fill (text, checkbox, radio, combo/list, flatten; XFA = not supported) | FormScreen.kt | Tools, Viewer ⋮ |
| Curve flatten (Beta), only if not low-RAM and ≥3.5 GB reported RAM, 8 s timeout | CurveFlatten.kt, ScanSession.kt, DraftCodec.kt | Review filter row chip |
| Batch rename `{name}` `{date}` `{n}` with preview | BatchRename.kt, HomeScreen.kt, Library.kt | Home long-press or ⋮ → "Select several" → "Rename" |
| QR / barcode (camera + from image), result card copy/share/open | QrScreen.kt, ProLogic.kt | Tools "Scan QR or barcode", Classic camera chip "QR scan" |
| Draft prompt "Continue previous scan or start new?" | SmartScanner.kt (`ScanStarter`) | Any new scan or photo import while a draft exists |

## 6. Test plan (do in this order)

**A. Automated (first)**
1. Run `:app:testDebugUnitTest :app:assembleDebug`. All tests should pass; fix any failure.
2. Run `:app:assembleRelease`. Check that R8 didn't break PDFBox, OpenCV or ML Kit; add keep rules if needed. Install the release APK on the emulator and smoke-test scan, save, open, compress and sign.
3. Run `aapt2 dump permissions` on the release APK. It must show no INTERNET and no ACCESS_NETWORK_STATE.
4. Run `:app:connectedDebugAndroidTest` on wiz35.

**B. Emulator smoke tests (use test PDFs and images; push with `adb push`)**
- **Regression on the old features:**
  - Scan → filters → save
  - Open → zoom/search → password PDF → Sign PDF
  - Library rename, share and delete; pin to home screen
- **Stage 1:**
  - Page grid: reorder, rotate, delete, extract. Save as copy, and replace.
  - Merge 3 PDFs, including one with a password.
  - Split "1-3,5" and every 2 pages.
  - Images to PDF.
  - Compress to 50% and to an unreachable target; check the honest message and the closest result.
  - Check that B&W pages really come out 1-bit and small.
- **Stage 2:**
  - Filters, including Auto and Sauvola, on varied images.
  - ID card A4 layout and size.
  - PDF to JPG at each DPI.
  - Add a password, then remove it, then reopen.
  - Draft: start a scan, run `adb shell am kill com.wizlabs.pdfmaster`, reopen, and continue.
- **Stage 3:**
  - Smart scanner fallback: there's no module on the emulator, so the Classic camera must open with no crash or error loop.
  - OCR language status shows "Not available"/"Downloading" gracefully.
  - Annotate, watermark, page numbers and form fill. Check that the **in-app viewer (PdfRenderer) renders the result**, including on rotated pages.
  - Curve-flatten chip hidden or shown based on RAM.
  - Batch rename conflicts.
  - QR from image: fallback message if the module is missing.
  - Draft prompt.
- **Performance:**
  - Check `adb shell top` CPU during camera preview; aim for under ~35% on the emulator.
  - Check `dumpsys gfxinfo` jank on Home and the Viewer.
  - Make sure the update card never shows outside Home.

**C. Known gaps / risks to look at in code**
- The ML Kit scanner result is lost if the activity is destroyed while Google's scanner is open. Fix it if it's cheap, for example by persisting pending state.
- Possible double-tap on Home scan rows (`pressScale` + `combinedClickable`).
- CJK invisible text is usually skipped: the system NotoSansCJK is CFF and PDFBox can't embed it. "Extract text" should still work. Make sure there's no crash.
- Non-Latin text typed into form fields is dropped. At minimum, warn the user.
- Compress estimates can miss on text-heavy PDFs. Images with masks, JPX or JBIG2 are skipped.
- AES-256 PDFs may not open on older Android viewers; check that ours opens them.
- Signature cut-out quality after the Stage 2 `backgroundOf(closeK = 21)` change.
- Auto filter may pick B&W too often on text pages.
- Capture is slower: the AF/AE lock and the ML Kit orientation pass both run before a page appears.
- Live detection cost with Hough plus scoring at ~9 fps on low-end devices; text rows may attract the line strategy.
- Not runnable on the emulator: the update card (needs a Play-installed build), MediaStore save on real devices, and the extra OCR languages / Google scanner / live QR. List these as "needs real phone" in the report.

## 7. When done

- Fix bugs in small commits on `feature/roadmap-stages`. Each commit message ends with `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.
- **Don't push and don't merge to main.** The owner decides that.
- If fixes go into a release, bump versionCode/versionName and add a changelog (`fastlane/metadata/android/en-US/changelogs/<code>.txt`, under 500 chars, no promo words like free/best/No ads).
- Report (≤ 500 words):
  - Commands run with pass/fail and test counts
  - Bugs found → fixed / not fixed
  - Permission check result
  - Release-build result
  - What still needs a real phone
