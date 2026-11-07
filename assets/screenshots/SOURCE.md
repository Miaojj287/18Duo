# Screenshot sources

These images originate from native Android screenshots. The Home and split-screen images include the website display edits described below; the other PNG captures are resized proportionally and encoded as WebP.

| File | Source | Original size |
| --- | --- | --- |
| `home-inner.webp` | October 29, 2026 showcase capture build, real MainActivity and native widgets; October 30 website edit removes Paint and replaces the reminder empty-state copy | 2364 × 1672 |
| `home-portrait.webp` | Same running MainActivity, first page in portrait | 1672 × 2364 |
| `library.webp` | Same running MainActivity, native App Library alphabetical list | 1672 × 2364 |
| `split-screen.webp` | Native Android split screen, Fossify Notes above Fossify Calculator; October 30 website edit clears Notes chrome and moves its text upward | 1672 × 2364 |
| `home-cover.webp` | V1.0.0 physical 18 Fold cover-display capture retained from October 26, 2026 | 1168 × 1712 |
| `book-mode.webp` | Existing `StandbyGalleryTest` → `r24-book.png`, native production Compose components | 2364 × 1672 |
| `standby-calendar.webp` | Existing `StandbyGalleryTest` → `r24-calendar.png`, native production Compose components | 1712 × 1168 |
| `standby.webp` | Existing `StandbyGalleryTest` → `r24-analog.png`, native production Compose components | 1712 × 1168 |

## Showcase capture setup

The October 29, 2026 Home/App Library captures use an isolated Android 16 AOSP emulator and a separate debug copy of the project. All 16 apps are actually installed: the system Calendar, Camera, Contacts, Clock, Phone, Gallery, Messaging, Files, Search, Settings and WebView Browser Tester, plus Fossify Notes 1.7.0, Calculator 1.4.0, Music Player 1.8.5, Voice Recorder 1.7.4 and Paint 1.4.0 from their official GitHub releases.

The original unfolded Home capture has six shortcuts on each page and four in the Dock: 16 visible icons, 16 distinct application packages, and 16 distinct icon artwork hashes. The current website image removes the second-page Paint shortcut, leaving **15 visible icons**. No Dock application also appears in either Home grid. The portrait capture shows six first-page shortcuts plus the same four Dock shortcuts, with no duplication. The library capture shows the production App Library's alphabetical list, where each application has one row; its normal category view has smart folders which may also contain apps found in their categories.

The capture build uses the project's existing AppleIconPack loader and artwork. It adds asset filename aliases for genuine AOSP/Fossify packages that the provided MTZ did not list: camera2 → camera, dialer → TwelveKeyDialer, messaging → mms, documentsui → Google DocumentsUI, quicksearchbox → Google Search, WebView shell → browser, Fossify Notes → notes, and Fossify Calculator → calculator; Music Player → the existing music artwork; Voice Recorder → sound recorder; Paint → the existing painting artwork. These aliases only exist in the external capture build. They are not modifications to the published release APK, and the screenshots must not be described as captures of an unmodified release build.

The capture build also provides the verified Xiaomi 18 Fold camera keep-out rectangles as a test input to the existing production cutout-avoidance code: landscape `[2224,0][2364,164]`, portrait `[0,0][164,140]`. This moves the application's own status rail using the production layout algorithm. Android's system status bar is hidden. The simulator does not paint a camera hole into the captured screen; the website model provides the hardware cutout.

Original PNGs, exact source differences, package identities, capture commands, APK SHA-256 checks and theme-alias provenance are retained outside the repository at `/Users/miaoyijun/.codex/work-products/18duo-open-source-20261029/restore-duo/screenshots/`, with a complete record in `capture-provenance.json`. That record also links the preceding capture configuration in `lightweight-redesign/screenshots/`. The installed capture APK is verified against the separate compiled debug APK. The main repository's Android source and `release/18Duo-V1.0.0.apk` match its existing Git HEAD. No downloaded third-party APK, fixture code, emulator data or build cache is shipped with this website.

The book/StandBy fixtures display the production native UI at their fixed fixture time. The book's album area displays the default wallpaper when an album is not selected. Brand images in `../brand/` come from this project's existing launcher icon. Hardware rendering belongs to the website model, not to these screenshot files.

## Native split-screen capture

The original split-screen capture contains two actual running app activities: Fossify Notes above Fossify Calculator, with the Android system's horizontal split divider. The Notes text was entered into its real editor; the Calculator shows the result of real button presses (`18 × 100 = 1,800`). The keyboard was dismissed and Android gesture navigation was enabled before capture. This demonstrates the native Android system's app split screen; it does not imply that 18Duo draws the two app interfaces itself. The screenshot is captured in portrait (1672 × 2364) so the viewer can align its horizontal divider to the half-folded device's hinge.

## October 30 website display edits

- `split-screen.webp`: clear the Notes area's system status bar and search/add/undo/share/menu icons using its existing solid background. Move the three existing English text lines 160 px upward in the original PNG, placing them just below the model camera. The Calculator area and Android split divider remain from the capture.
- `home-inner.webp`: remove the Paint shortcut and its shadow by registering the project's original `duo_wallpaper_c.webp` to the capture and restoring the matching wallpaper pixels. Replace only the reminder widget's empty-state body with `给18Duo在GitHub一颗Star吧～`, wrapped into two lines; retain its title, count and dark platter.

These are presentation edits to the website's screen textures, not new app features or captures of those exact states. The original PNGs remain outside the repository; edit script, registration matrix, before-images and output SHA-256 values are recorded in `/Users/miaoyijun/.codex/work-products/18duo-open-source-20261030/screen-logo-refinement/`.

## Website texture versioning

The website references content-addressed copies of the two edited screenshots:

| Website file | SHA-256 |
| --- | --- |
| `home-inner.493564f41d5e.webp` | `493564f41d5e2769e85d5260db7ef4d25564a6f7fb83da730ba6235b2256d709` |
| `split-screen.9b5f74d08b1d.webp` | `9b5f74d08b1d988629a5c066c44afd69cff15382dfc405d62e780e541fb98b49` |

Each file matches the correspondingly named canonical WebP byte for byte. The scene adapter checks its image URL before reuse so cached scene manifests cannot restore an older screenshot. Posters, fallback views and 3D screens use the same version. When regenerating these screenshots, create new content-addressed filenames and update `screenFiles`, the HTML poster/fallback paths and the scene asset list together.
