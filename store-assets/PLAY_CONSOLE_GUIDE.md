# Wiz PDF Master — Google Play Console guide

Everything needed to publish, in the order Play Console asks for it.

## 1. App details
| Field | Value |
|---|---|
| App name | Wiz PDF Master: Scan & Read |
| Package | `com.wizlabs.pdfmaster` |
| Default language | English (United States) — en-US |
| App or game | App |
| Free or paid | Free |
| Category | Productivity |
| Tags (pick up to 5) | Document scanner, PDF reader, Productivity, Office, Tools |
| Contact email | *your Wiz Labs support email* |
| Privacy policy URL | https://therealwiz007.github.io/Wiz-PDF-Master/privacy-policy.html |

## 2. Store listing (text is in `fastlane/metadata/android/en-US/`)
- **Short description** → `short_description.txt`
- **Full description** → `full_description.txt`
- **Release notes (v1.0.0)** → `changelogs/1.txt`

## 3. Graphics (all in `store-assets/`)
| Asset | File | Play requirement |
|---|---|---|
| App icon | `icon-512.png` | 512×512 PNG, no transparency ✓ |
| Feature graphic | `feature-graphic-1024x500.png` | 1024×500 PNG/JPG ✓ |
| Phone screenshots (6) | `screenshots/01-home.png` … `06-privacy.png` | 2–8 images, 9:16, 1080×1920 ✓ |
| Logo (website/docs) | `logo-1600x480.png` | not uploaded to Play |

Upload screenshots in this order: Home → Edges → Scan → Search → OCR → Privacy.
Tablet screenshots are optional; skip them.

## 4. App content (Policy → App content)
| Section | Answer |
|---|---|
| Privacy policy | URL above |
| Ads | **No**, my app does not contain ads |
| App access | **All functionality is available without special access** (no login) |
| Content rating | Fill the IARC questionnaire with category **Utility, Productivity, Communication or other**; answer **No** to every question (no violence, sexual content, language, controlled substances, gambling, user-to-user interaction, location sharing, or purchases). Expected rating: **Everyone / 3+** |
| Target audience | **18 and over** (simplest; avoids the Families policy. 13+ is also fine) |
| News app | No |
| COVID-19 tracing | No |
| Data safety | see below |
| Government app | No |
| Financial features | My app doesn't provide any financial features |
| Health | No health features |
| Advertising ID | **No** (the app doesn't use the advertising ID) |

## 5. Data safety form
The app has **no INTERNET permission**, so nothing can be transmitted.

- Does your app collect or share any of the required user data types? → **No**
- Is all of the user data collected by your app encrypted in transit? → not applicable (no data collected)
- Do you provide a way for users to request that their data is deleted? → not applicable; documents stay on the device and are deleted in-app or on uninstall

Result shown on Play: **"No data collected · No data shared with third parties"**.

Camera, photos and files are used only on the device, which Google does not count as "collection".

## 6. Release
1. Production → Create new release.
2. Use **Play App Signing** (default). Upload `app-release.aab` from the GitHub Actions artifact **wiz-pdf-master-production**.
   The upload key is the shared Wiz keystore (alias `wizfueldensity`).
3. Release name: `<versionName> (<versionCode>)`, e.g. `1.0.3 (4)`; notes from `changelogs/<versionCode>.txt`.
4. Countries: India (and any others you want).
5. Recommended: first roll out to **Internal testing** and install from Play on your own phone to check the camera scanner on real paper, then promote to Production.

## 7. Before the next update
- Bump `versionCode` and `versionName` in `app/build.gradle.kts`.
- Add `fastlane/metadata/android/en-US/changelogs/<versionCode>.txt`.
- Regenerate art if the design changes: `swift tools/store-art.swift icons store-assets`.
