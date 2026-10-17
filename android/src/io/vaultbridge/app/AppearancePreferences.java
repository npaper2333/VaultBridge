package io.vaultbridge.app;

/** Independent user choices over the read-only vault appearance. No Android dependencies. */
final class AppearancePreferences {
    static final String MODE="appearanceMode", SOURCE="appearanceAccentSource", COLOR="appearanceCustomAccent";
    static AppearanceConfig resolve(AppearanceConfig vault,boolean systemDark,String mode,String source,String custom){
        boolean dark="light".equals(mode)?false:"dark".equals(mode)?true:"system".equals(mode)?systemDark:vault.dark;
        Integer parsed=AppearanceConfig.color(custom);
        int accent="custom".equals(source)&&parsed!=null?parsed:vault.accent;
        String depth="light".equals(mode)?"固定浅色":"dark".equals(mode)?"固定深色":"system".equals(mode)?"深浅跟随系统":"深浅跟随 Obsidian";
        String color="custom".equals(source)&&parsed!=null?"自定义主题色":"主题色跟随 Obsidian";
        return new AppearanceConfig(dark,accent,depth+"；"+color+"\n"+vault.detail);
    }
}
