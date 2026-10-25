package com.jake.duolauncher

import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager

internal enum class SignalElementEmphasis {
    DIM,
    NEUTRAL,
    LIT,
}

internal sealed interface WifiSignalVisual {
    data object Disconnected : WifiSignalVisual
    data class Connected(val elements: List<SignalElementEmphasis>) : WifiSignalVisual
}

/** Elements of the SF "wifi" glyph, inner to outer: the filled wedge, then two arcs. */
internal const val WIFI_GLYPH_ELEMENTS = 3
/** Cellular bars become four dots on the iPhone Duo ring (Apple status-bar video). */
internal const val CELLULAR_DOTS = 4

/** Android reports 0–4; iOS lights 1–3 bars while connected (a weak link still shows the wedge). */
internal fun wifiSignalVisual(connected: Boolean, level: Int?): WifiSignalVisual {
    if (!connected) return WifiSignalVisual.Disconnected
    if (level == null) return WifiSignalVisual.Connected(List(WIFI_GLYPH_ELEMENTS) { SignalElementEmphasis.NEUTRAL })
    val activeElements = ((level.coerceIn(0, 4) * WIFI_GLYPH_ELEMENTS + 3) / 4).coerceIn(1, WIFI_GLYPH_ELEMENTS)
    return WifiSignalVisual.Connected(List(WIFI_GLYPH_ELEMENTS) { index ->
        if (index < activeElements) SignalElementEmphasis.LIT else SignalElementEmphasis.DIM
    })
}

internal sealed interface CellularSignalVisual {
    data object Airplane : CellularSignalVisual
    data object Unavailable : CellularSignalVisual
    data class Available(val activeDots: Int) : CellularSignalVisual
}

internal fun cellularSignalVisual(level: Int?, airplane: Boolean): CellularSignalVisual = when {
    airplane -> CellularSignalVisual.Airplane
    level == null -> CellularSignalVisual.Unavailable
    else -> CellularSignalVisual.Available(level.coerceIn(0, CELLULAR_DOTS))
}

enum class CellularTechnology(val label: String?) {
    TWO_G("2G"),
    THREE_G("3G"),
    FOUR_G("4G"),
    FIVE_G("5G"),
    UNKNOWN(null),
}

internal fun cellularTechnology(networkType: Int, overrideNetworkType: Int): CellularTechnology {
    if (overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
        overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED ||
        overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA_MMWAVE
    ) return CellularTechnology.FIVE_G
    if (overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_CA ||
        overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_LTE_ADVANCED_PRO
    ) return CellularTechnology.FOUR_G
    return when (networkType) {
        TelephonyManager.NETWORK_TYPE_NR -> CellularTechnology.FIVE_G
        TelephonyManager.NETWORK_TYPE_LTE -> CellularTechnology.FOUR_G
        TelephonyManager.NETWORK_TYPE_UMTS,
        TelephonyManager.NETWORK_TYPE_EVDO_0,
        TelephonyManager.NETWORK_TYPE_EVDO_A,
        TelephonyManager.NETWORK_TYPE_HSDPA,
        TelephonyManager.NETWORK_TYPE_HSUPA,
        TelephonyManager.NETWORK_TYPE_HSPA,
        TelephonyManager.NETWORK_TYPE_EVDO_B,
        TelephonyManager.NETWORK_TYPE_EHRPD,
        TelephonyManager.NETWORK_TYPE_HSPAP,
        TelephonyManager.NETWORK_TYPE_TD_SCDMA -> CellularTechnology.THREE_G
        TelephonyManager.NETWORK_TYPE_GPRS,
        TelephonyManager.NETWORK_TYPE_EDGE,
        TelephonyManager.NETWORK_TYPE_CDMA,
        TelephonyManager.NETWORK_TYPE_1xRTT,
        TelephonyManager.NETWORK_TYPE_GSM -> CellularTechnology.TWO_G
        else -> CellularTechnology.UNKNOWN
    }
}

/**
 * Spoken summary of the status rail in ONE language (Core.15 mixed a Chinese date with English
 * battery/network phrases). [chinese] follows the app language; [time] is the formatted clock.
 */
internal fun statusAccessibilityText(status: DeviceStatus, now: java.time.LocalDateTime, time: String,
    locationInUse: Boolean, chinese: Boolean): String {
    val locale = if (chinese) java.util.Locale.SIMPLIFIED_CHINESE else java.util.Locale.ENGLISH
    val date = now.format(java.time.format.DateTimeFormatter.ofPattern(
        if (chinese) "M月d日 EEEE" else "EEEE, MMMM d", locale))
    fun t(zh: String, en: String) = if (chinese) zh else en
    val battery = status.battery?.let { level ->
        t("电池电量 $level%", "Battery $level percent") + if (status.charging) t("，正在充电", ", charging") else ""
    } ?: t("电池电量不可用", "Battery unavailable")
    val wifi = if (status.wifiConnected) t("无线局域网已连接", "Wi-Fi connected") +
        (status.wifiLevel?.let { t("，信号 $it/4", ", signal $it of 4") } ?: "")
        else t("无线局域网未连接", "Wi-Fi disconnected")
    val cellular = if (status.airplane) t("飞行模式", "Airplane mode") else listOfNotNull(
        status.cellularTechnology.label?.let { t("$it 蜂窝网络", "$it cellular network") },
        status.cellularLevel?.let { t("信号 $it/4", "signal $it of 4") },
    ).takeIf { it.isNotEmpty() }?.joinToString(t("，", ", ")) ?: t("无蜂窝信号", "Cellular signal unavailable")
    return listOfNotNull(
        if (locationInUse) t("正在使用定位", "Location in use") else null,
        t("$date $time", "$date, $time"), battery, wifi, cellular,
    ).joinToString(t("。", ". "))
}
