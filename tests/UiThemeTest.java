package io.vaultbridge.app;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.*;

/** Runs the actual app palette code, without replacing Android UI drawing with mocks. */
public final class UiThemeTest {
    static int count;
    static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);count++;System.out.println("PASS: "+name);}
    static boolean neutral(int color){return (color>>>24)==255&&UiTheme.red(color)==UiTheme.green(color)&&UiTheme.green(color)==UiTheme.blue(color);}
    static boolean readable(UiTheme t){for(int bg:new int[]{t.canvas,t.surface,t.wash,t.nav})if(UiTheme.contrast(t.ink,bg)<4.5||UiTheme.contrast(t.muted,bg)<4.5)return false;return UiTheme.contrast(t.primaryInk,t.accent)>=4.5&&UiTheme.contrast(t.thumb,t.canvas)>=3&&UiTheme.contrast(t.thumb,t.surface)>=3;}
    static boolean quiet(UiTheme t){for(int bg:new int[]{t.canvas,t.surface,t.wash,t.nav,t.line,t.thumb})if(!neutral(bg))return false;return true;}
    public static void main(String[] args)throws Exception{
        JSONArray cases=new JSONArray(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8));
        for(int i=0;i<cases.length();i++){JSONObject c=cases.getJSONObject(i);UiTheme t=UiTheme.from(AppearanceConfig.parse(c.getString("json"),c.getBoolean("systemDark")));check(quiet(t)&&readable(t),"neutral readable UI: "+c.getString("name"));}
        for(boolean dark:new boolean[]{false,true}){UiTheme reference=UiTheme.from(AppearanceConfig.fallback(dark,"test"));for(String hex:new String[]{"#ffff00","#000000","#ffffff","#0000ff","#ff00ff"}){UiTheme t=UiTheme.from(new AppearanceConfig(dark,AppearanceConfig.color(hex),"test"));check(quiet(t)&&readable(t)&&t.canvas==reference.canvas&&t.surface==reference.surface&&t.wash==reference.wash&&t.nav==reference.nav,"accent changes only important controls: "+dark+" "+hex);}}
        System.out.println("Android UI palette checks passed: "+count);
    }
}
