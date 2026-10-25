package com.jake.duolauncher

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** iOS Settings ▸ General ▸ About: name / version as value rows, licences behind a disclosure row. */
@Composable
internal fun EighteenDuoAbout() {
    val context = LocalContext.current
    val version = remember(context) { context.packageManager.getPackageInfo(context.packageName, 0).versionName.orEmpty() }
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    IosInsetGroup(uxText("关于", "About")) {
        IosRow(uxText("名称", "Name"), value = stringResource(R.string.core_about), chevron = false,
            modifier = Modifier.testTag("18Duo-about"))
        IosSeparator(inset = 16.dp)
        IosRow(uxText("版本", "Version"), value = version, chevron = false)
        IosSeparator(inset = 16.dp)
        IosRow(stringResource(R.string.duo18_licenses), tag = "18Duo-open-licenses", chevron = false,
            onClick = { showLicenses = !showLicenses }, trailing = { IosDisclosureChevron(showLicenses, iosTertiaryText()) })
    }
    IosFooter(stringResource(R.string.duo18_copyright))
    if (showLicenses) {
        val licenses by produceState("") {
            value = withContext(Dispatchers.IO) {
                listOf("THIRD-PARTY-NOTICES.txt", "MIT-DuoLauncher.txt", "GlassProjection-MIT.txt",
                    "AndroidLiquidGlass-NOTICE.txt", "Inter-OFL.txt", "RobotoFlex-OFL.txt", "Apache-2.0.txt",
                    "BouncyCastle-LICENSE.txt", "BoringSSL-LICENSE.txt", "Conscrypt-NOTICE.txt")
                    .joinToString("\n\n") { file -> context.assets.open("licenses/$file").bufferedReader().use { it.readText() } }
            }
        }
        // Legal texts stay in their original language; the navigation is bilingual.
        IosInsetGroup {
            SelectionContainer {
                Text(licenses, Modifier.padding(16.dp).testTag("18Duo-license-text"), fontSize = 12.sp, lineHeight = 16.sp,
                    color = iosSecondaryText())
            }
        }
    }
}
