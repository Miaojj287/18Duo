# Third-party notices

Copyright notices for this project are in `LICENSE`. Dependencies retain their own licenses; the APK includes their notices under `assets/licenses/`.

| Component family | Source | License |
| --- | --- | --- |
| AndroidX, Jetpack Compose, Material components and icons, Window | https://android.googlesource.com/platform/frameworks/support/ | Apache 2.0 |
| Haze background blur for Compose, copyright Chris Banes and contributors | https://github.com/chrisbanes/haze | Apache 2.0 |
| GlassProjection optical model, Gaussian pyramid and shader adaptation, revision 18489f3 | https://github.com/spideytznn/GlassProjection | MIT; full copyright and license in `app/src/main/assets/licenses/GlassProjection-MIT.txt` |
| AndroidLiquidGlass (Backdrop) lens-refraction AGSL and rounded-rect SDF, copyright 2025 Kyant; modified | https://github.com/Kyant0/AndroidLiquidGlass | Apache 2.0; notice in `app/src/main/assets/licenses/AndroidLiquidGlass-NOTICE.txt` |
| Inter 4.1 typeface (Regular, Medium, SemiBold, Bold; Display Medium, SemiBold, Bold), copyright 2016 The Inter Project Authors | https://github.com/rsms/inter | SIL Open Font License 1.1; full text in `app/src/main/assets/licenses/Inter-OFL.txt` |
| Roboto Flex typeface, static instance subset to clock figures (res/font/duo_clock_digits.ttf, renamed "Duo Clock Digits (Roboto Flex)"), copyright 2017 The Roboto Flex Project Authors | https://github.com/TypeNetwork/Roboto-Flex | SIL Open Font License 1.1; full text in `app/src/main/assets/licenses/RobotoFlex-OFL.txt` |
| Kotlin standard library | https://github.com/JetBrains/kotlin | Apache 2.0 |
| Shizuku local ADB transport and native pairing, RikkaApps contributors, commit 2650830c | https://github.com/RikkaApps/Shizuku | Apache 2.0; modified Kotlin transport, native libadb.so from v13.6.0; no Shizuku manager/daemon redistributed |
| Conscrypt 2.5.3 TLS provider | https://github.com/google/conscrypt | Apache 2.0 and bundled notices; app/src/main/assets/licenses/Conscrypt-NOTICE.txt |
| BoringSSL, used by pairing and Conscrypt | https://github.com/google/boringssl | app/src/main/assets/licenses/BoringSSL-LICENSE.txt and Conscrypt-NOTICE.txt |
| Bouncy Castle | https://www.bouncycastle.org | MIT-style license; app/src/main/assets/licenses/BouncyCastle-LICENSE.txt |
| Kotlin coroutines | https://github.com/Kotlin/kotlinx.coroutines | Apache 2.0 |
| Kotlin serialization | https://github.com/Kotlin/kotlinx.serialization | Apache 2.0 |
| JetBrains annotations | https://github.com/JetBrains/java-annotations | Apache 2.0 |
| Guava ListenableFuture | https://github.com/google/guava | Apache 2.0 |
| JSpecify annotations | https://github.com/jspecify/jspecify | Apache 2.0 |
| Gradle wrapper and build tooling | https://github.com/gradle/gradle | Apache 2.0; build-tool distributions include their additional notices |
| Optional Apple-style app icon artwork from the Xiaomi MTZ theme “遇见hyper26” | `app/src/main/assets/apple_icons/`; source attribution and checksum in `SOURCE.txt` | Theme metadata credits 酷安@horla. Artwork is third-party material and is not covered by this project's MIT code license. |
| Apple-style HD app icons (50 apps) and status-bar / Control Center artwork from the Xiaomi MTZ theme “AP16超级米果” | `app/src/main/assets/apple_icons/` and `app/src/main/assets/ap16/`; attribution and checksum in each `SOURCE.txt` | Theme metadata credits 超级苏哥. Artwork is third-party material and is not covered by this project's MIT code license. |

The Gradle dependency graph records the resolved artifact versions. Test and build tools provide their own notices.

Optional app icon artwork is converted from the supplied theme and used only when the user enables it in 18Duo settings. Installed application icons and widget content belong to their respective providers. Search can delegate to installed applications; their application code and content are not redistributed here.

Apple, Google, Android, Samsung, and other referenced names are trademarks of their respective owners; this project is unaffiliated with those companies.
