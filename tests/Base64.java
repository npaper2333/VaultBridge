package android.util;
/** JVM-only adapter for the Android Base64 API; never packaged in the APK. */
public final class Base64 {
    public static final int DEFAULT = 0;
    public static byte[] decode(String text, int flags) { return java.util.Base64.getMimeDecoder().decode(text); }
}
