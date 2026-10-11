package io.vaultbridge.app;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import org.json.*;

public final class AppearanceTest {
    static int count;
    static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);count++;System.out.println("PASS: "+name);}
    public static void main(String[] args)throws Exception{
        JSONArray cases=new JSONArray(new String(Files.readAllBytes(Paths.get(args[0])),StandardCharsets.UTF_8));
        for(int i=0;i<cases.length();i++){JSONObject c=cases.getJSONObject(i);AppearanceConfig value=AppearanceConfig.parse(c.getString("json"),c.getBoolean("systemDark"));check(value.dark==c.getBoolean("dark")&&value.hex().equals(c.getString("accent")),c.getString("name"));}
        check(AppearanceConfig.read(new ByteArrayInputStream(new byte[65537]),true).detail.contains("64 KiB"),"bounded configuration read");
        check(AppearanceConfig.read(new ByteArrayInputStream(new byte[]{(byte)0xc3,0x28}),false).detail.contains("格式无效"),"invalid UTF8 fallback");
        check(AppearanceConfig.parse(new String(new char[65537]).replace('\0','x'),false).detail.contains("64 KiB"),"oversized parsed configuration");
        System.out.println("Android appearance configuration checks passed: "+count);
    }
}
