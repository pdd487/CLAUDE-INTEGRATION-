# QwiKShot uses no reflection, serialization, or JNI. The activity and the
# accessibility service are referenced from AndroidManifest.xml, and AAPT
# generates keep rules for manifest components automatically, so R8's
# defaults are all this app needs.

# Drop verbose/debug logging from release builds.
-assumenosideeffects class android.util.Log {
    public static int v(...);
    public static int d(...);
}
