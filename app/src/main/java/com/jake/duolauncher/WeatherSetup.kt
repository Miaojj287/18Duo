package com.jake.duolauncher

import android.Manifest
import android.content.Intent
import android.provider.Settings
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.launch

// ---------------------------------------------------------------------------------------
// "Weather Location" prompt: shown only when the user taps a Weather widget (or picks Edit
// Widget on one). It explains what is sent to Open-Meteo, then either asks for
// ACCESS_COARSE_LOCATION or lets the user type a city. One prompt for all placed weather
// widgets: the tapped widget hosts it; a context-menu request goes to the first one on Home.
// ---------------------------------------------------------------------------------------

internal object WeatherSetupController {
    private var isOpen by mutableStateOf(false)
    private var owner by mutableStateOf<Any?>(null)
    private val hosts = mutableStateListOf<Any>()

    /** [token] = the tapped widget; null (Edit Widget) = whichever weather widget is on Home. */
    fun open(token: Any? = null) { owner = token; isOpen = true }
    fun close() { isOpen = false; owner = null }
    fun register(token: Any) { if (token !in hosts) hosts += token }
    fun unregister(token: Any) { hosts.remove(token) }
    fun hosts(token: Any): Boolean {
        if (!isOpen) return false
        val target = owner?.takeIf { it in hosts } ?: hosts.firstOrNull()
        return target === token
    }
}

private enum class WeatherSetupStep { MAIN, DENIED, CITY, RESULTS, NOT_FOUND, FAILED }

@Composable
internal fun WeatherSetupHost(token: Any) {
    DisposableEffect(token) {
        WeatherSetupController.register(token)
        onDispose { WeatherSetupController.unregister(token) }
    }
    if (!WeatherSetupController.hosts(token)) return
    WeatherSetupFlow(onDone = WeatherSetupController::close)
}

@Composable
private fun WeatherSetupFlow(onDone: () -> Unit) {
    val context = LocalContext.current
    val activity = androidx.activity.compose.LocalActivity.current
    val scope = rememberCoroutineScope()
    val state = rememberWeatherState(active = false)
    var step by remember { mutableStateOf(WeatherSetupStep.MAIN) }
    var query by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    var results by remember { mutableStateOf(emptyList<WeatherPlace>()) }
    val permission = Manifest.permission.ACCESS_COARSE_LOCATION
    fun useLocation() {
        WeatherRepository.useCurrentLocation(context)
        onDone()
    }
    val launcher = if (androidx.activity.compose.LocalActivityResultRegistryOwner.current != null)
        androidx.activity.compose.rememberLauncherForActivityResult(
            androidx.activity.result.contract.ActivityResultContracts.RequestPermission()) { granted ->
            when {
                granted || WeatherRepository.hasLocationPermission(context) -> useLocation()
                activity != null && !activity.shouldShowRequestPermissionRationale(permission) -> step = WeatherSetupStep.DENIED
                else -> onDone()
            }
        } else null
    val chinese = java.util.Locale.getDefault().language == "zh"
    val enterCity = IosAlertAction(uxText("输入城市…", "Enter City…"), "weather-setup-city") { step = WeatherSetupStep.CITY }
    val cancel = IosAlertAction(uxText("取消", "Cancel"), "weather-setup-cancel", IosAlertRole.Cancel, onDone)
    val request: IosAlertRequest? = when (step) {
        WeatherSetupStep.MAIN -> IosAlertRequest("weather-setup", uxText("天气位置", "Weather Location"),
            uxText("天气数据来自 Open-Meteo。使用当前位置时，只在桌面显示时取得一次大致位置，并只发送约 1 公里精度的坐标；也可以输入城市。",
                "Weather comes from Open-Meteo. With Current Location, 18Duo gets an approximate location only while Home is on screen and sends just the coordinates, rounded to about 1 km. You can also enter a city."),
            buildList {
                add(IosAlertAction(uxText("使用当前位置", "Use Current Location"), "weather-setup-location") {
                    when {
                        WeatherRepository.hasLocationPermission(context) -> useLocation()
                        launcher != null -> launcher.launch(permission)
                        else -> onDone()
                    }
                })
                add(enterCity)
                if (state.configured) add(IosAlertAction(uxText("停止显示天气", "Turn Off Weather"), "weather-setup-off",
                    IosAlertRole.Destructive) { WeatherRepository.turnOff(context); onDone() })
                add(cancel)
            })
        WeatherSetupStep.DENIED -> IosAlertRequest("weather-denied", uxText("位置访问已关闭", "Location Access Is Off"),
            uxText("可以在“设置”中允许 18Duo 使用大致位置，或者输入一个城市。",
                "Allow 18Duo to use your approximate location in Settings, or type a city instead."),
            listOf(IosAlertAction(uxText("打开设置", "Open Settings"), "weather-setup-settings") {
                runCatching {
                    context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        android.net.Uri.fromParts("package", context.packageName, null)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
                }
                WeatherRepository.resetBackoff()
                onDone()
            }, enterCity, cancel))
        WeatherSetupStep.RESULTS -> IosAlertRequest("weather-results-$query", uxText("选择城市", "Choose a City"), null,
            results.take(5).mapIndexed { i, place ->
                val detail = if (chinese) place.detail.replace(", ", "，") else place.detail
                IosAlertAction(listOf(place.name, detail).filter { it.isNotBlank() }.joinToString(if (chinese) "，" else ", "),
                    "weather-setup-result-$i") { WeatherRepository.useCity(context, place); onDone() }
            } + cancel)
        WeatherSetupStep.NOT_FOUND, WeatherSetupStep.FAILED -> IosAlertRequest("weather-none-$step",
            if (step == WeatherSetupStep.NOT_FOUND) uxText("找不到“$query”", "No Results for “$query”") else uxText("无法搜索城市", "Can’t Search Cities"),
            if (step == WeatherSetupStep.NOT_FOUND) uxText("请检查拼写，或输入附近较大的城市。", "Check the spelling or try a nearby larger city.")
            else uxText("请检查网络连接后重试。", "Check your internet connection and try again."),
            listOf(IosAlertAction(uxText("重试", "Try Again"), "weather-setup-retry") { step = WeatherSetupStep.CITY }, cancel))
        WeatherSetupStep.CITY -> null
    }
    if (request != null) IosAlertDialog(request, onDone)
    if (step == WeatherSetupStep.CITY) WeatherCityAlert(query, { query = it }, searching, onCancel = onDone, onSearch = {
        if (query.isBlank() || searching) return@WeatherCityAlert
        searching = true
        scope.launch {
            val found = WeatherRepository.searchCity(query)
            searching = false
            results = found.orEmpty()
            step = when {
                found == null -> WeatherSetupStep.FAILED
                found.isEmpty() -> WeatherSetupStep.NOT_FOUND
                else -> WeatherSetupStep.RESULTS
            }
        }
    })
}

/** UIAlertController with a text field (iOS 26 glass alert look, opaque body like [IosAlertDialog]). */
@Composable
private fun WeatherCityAlert(query: String, onQuery: (String) -> Unit, searching: Boolean, onCancel: () -> Unit, onSearch: () -> Unit) {
    val dark = LocalDuoPalette.current.dark
    val ink = if (dark) Color.White else Color.Black
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
    val bars = rememberHostSystemBars()
    Dialog(onDismissRequest = onCancel, properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false)) {
        // Keep Home's hidden status / navigation bars while the alert has focus (round-3 review).
        val dialogView = androidx.compose.ui.platform.LocalView.current
        androidx.compose.runtime.SideEffect {
            ((dialogView.parent as? androidx.compose.ui.window.DialogWindowProvider)?.window
                ?: (dialogView as? androidx.compose.ui.window.DialogWindowProvider)?.window)?.let(bars::applyTo)
        }
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = .22f)).imePadding(), contentAlignment = Alignment.Center) {
            val shape = RoundedCornerShape(34.dp)
            Column(Modifier.fillMaxWidth(.86f).wrapContentWidth().widthIn(max = 300.dp).fillMaxWidth()
                .background(if (dark) Color(0xFF2C2C2E) else Color(0xFFF2F2F7), shape)
                .padding(start = 22.dp, end = 22.dp, top = 22.dp, bottom = 16.dp)
                .testTag("weather-city-alert")) {
                Text(uxText("输入城市", "Enter City"), fontSize = 17.sp, lineHeight = 22.sp, fontWeight = FontWeight.SemiBold, color = ink)
                Spacer(Modifier.height(6.dp))
                Text(uxText("城市名称只会发送给 Open-Meteo 用于查找位置。", "The city name is sent only to Open-Meteo to find the place."),
                    fontSize = 14.sp, lineHeight = 19.sp, color = ink.copy(alpha = .78f))
                Spacer(Modifier.height(14.dp))
                BasicTextField(query, onQuery, Modifier.fillMaxWidth().height(40.dp)
                    .background(if (dark) Color(0xFF3A3A3C) else Color.White, RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp).focusRequester(focus).testTag("weather-city-field"),
                    singleLine = true, textStyle = TextStyle(color = ink, fontSize = 17.sp),
                    cursorBrush = SolidColor(IosUi.blue),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                    keyboardActions = KeyboardActions(onSearch = { onSearch() }),
                    decorationBox = { inner ->
                        Box(contentAlignment = Alignment.CenterStart) {
                            if (query.isEmpty()) Text(uxText("例如：上海", "e.g. San Francisco"), color = ink.copy(alpha = .3f), fontSize = 17.sp)
                            inner()
                        }
                    })
                Spacer(Modifier.height(18.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    CityAlertButton(uxText("取消", "Cancel"), ink, false, true, Modifier.weight(1f), "weather-city-cancel", onCancel)
                    CityAlertButton(if (searching) uxText("正在查找…", "Searching…") else uxText("搜索", "Search"), IosUi.blue, true,
                        !searching && query.isNotBlank(), Modifier.weight(1f), "weather-city-search", onSearch)
                }
            }
        }
    }
}

@Composable
private fun CityAlertButton(label: String, color: Color, bold: Boolean, enabled: Boolean, modifier: Modifier, tag: String, onClick: () -> Unit) {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val ink = if (LocalDuoPalette.current.dark) Color.White else Color.Black
    Box(modifier.height(46.dp)
        .graphicsLayer { val s = if (pressed) .97f else 1f; scaleX = s; scaleY = s }
        .background(ink.copy(alpha = if (pressed) .16f else .09f), CircleShape)
        .clickable(interactionSource = interaction, indication = null, enabled = enabled, role = Role.Button, onClick = onClick)
        .testTag(tag), contentAlignment = Alignment.Center) {
        Text(label, color = if (enabled) color else color.copy(alpha = .35f), fontSize = 17.sp,
            fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal, textAlign = TextAlign.Center, maxLines = 1)
    }
}
