-keep class moe.shizuku.manager.adb.PairingContext { *; }
-keep class org.conscrypt.** { *; }
# Conscrypt includes optional pre-Lollipop socket adapters. Their platform-only
# parameter classes do not exist on this app's supported Android 12+ devices.
-dontwarn com.android.org.conscrypt.SSLParametersImpl
-dontwarn org.apache.harmony.xnet.provider.jsse.SSLParametersImpl

# Android manifest components and directly constructed widget-host classes are traced by AGP/R8.
# Layout and backup persistence use org.json with explicit keys, so there are no model classes
# that require broad reflection or serialization keep rules.

# ComposeAccessibilityGate: hands Compose's accessibility delegate only the services that can read
# windows. It reaches these two fields by name; without them it does nothing.
-keepnames class androidx.compose.ui.platform.AndroidComposeView
-keepnames class androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat
-keepclassmembernames class androidx.compose.ui.platform.AndroidComposeView {
    androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat composeAccessibilityDelegate;
}
-keepclassmembernames class androidx.compose.ui.platform.AndroidComposeViewAccessibilityDelegateCompat {
    java.util.List enabledServices;
    java.util.List scrollObservationScopes;
}
