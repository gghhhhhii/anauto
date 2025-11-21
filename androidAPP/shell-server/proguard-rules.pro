# Add project specific ProGuard rules here.
# You can control the set of applied configuration files using the
# proguardFiles setting in build.gradle.
#
# For more details, see
#   http://developer.android.com/guide/developing/tools/proguard.html

# Keep Shell Server main class
-keep class com.autobot.shell.ShellServerKt { *; }
-keep class com.autobot.shell.ShellServer { *; }

# Keep NanoHTTPD
-keep class fi.iki.elonen.** { *; }

# Keep UiAutomation related classes
-keep class com.autobot.shell.core.** { *; }

