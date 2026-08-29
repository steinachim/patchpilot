# Defense in depth: strip verbose/debug logging from release builds even if a future call site
# forgets to gate itself on BuildConfig.DEBUG. Protocol bytes and preset names have gone through
# Log.d before (see Pro800Editor.kt, SysExExchange.kt) and none of it belongs in a release logcat.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
