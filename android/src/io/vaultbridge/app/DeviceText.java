package io.vaultbridge.app;

/** Device wording only; window layout and all user content stay independent. */
final class DeviceText {
    static String name(int shortestDisplayDp) { return shortestDisplayDp >= 600 ? "平板" : "手机"; }
}
