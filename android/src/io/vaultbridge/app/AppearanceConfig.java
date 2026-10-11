package io.vaultbridge.app;

import org.json.*;
import java.io.*;
import java.nio.ByteBuffer;
import java.nio.charset.*;
import java.util.regex.*;

/** Bounded, read-only interpretation of Obsidian's base appearance settings. */
final class AppearanceConfig {
    static final int MAX_BYTES=65536, DEFAULT_ACCENT=0xff7f6df2;
    final boolean dark;
    final int accent;
    final String detail;
    AppearanceConfig(boolean d,int color,String description){dark=d;accent=color;detail=description;}
    String hex(){return String.format(java.util.Locale.ROOT,"#%06x",accent&0xffffff);}
    String summary(){return (dark?"深色背景":"浅色背景")+" · "+hex()+"\n"+detail;}
    String key(){return dark+"|"+accent+"|"+detail;}
    static AppearanceConfig fallback(boolean dark,String why){return new AppearanceConfig(dark,DEFAULT_ACCENT,why+"；使用系统深浅色和默认紫色");}
    static Integer color(String input){
        if(input==null)return null;String value=input.trim();
        if(value.matches("#[0-9a-fA-F]{3}"))value="#"+value.charAt(1)+value.charAt(1)+value.charAt(2)+value.charAt(2)+value.charAt(3)+value.charAt(3);
        if(value.matches("#[0-9a-fA-F]{6}"))return 0xff000000|Integer.parseInt(value.substring(1),16);
        Matcher m=Pattern.compile("^rgb\\(\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*,\\s*(\\d{1,3})\\s*\\)$",Pattern.CASE_INSENSITIVE).matcher(value);
        if(m.matches()){int r=Integer.parseInt(m.group(1)),g=Integer.parseInt(m.group(2)),b=Integer.parseInt(m.group(3));if(r<256&&g<256&&b<256)return 0xff000000|(r<<16)|(g<<8)|b;}return null;
    }
    static AppearanceConfig parse(String json,boolean systemDark){
        try{
            if(json==null||json.getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)return fallback(systemDark,"外观配置超过 64 KiB 或为空");
            if(json.startsWith("\ufeff"))json=json.substring(1);
            JSONObject data=new JSONObject(json);Object raw=data.opt("theme");
            if(raw!=null&&!(raw instanceof String))return fallback(systemDark,"外观模式格式无效");
            String mode=raw==null?"":(String)raw;boolean dark=systemDark;String detail="已读取 .obsidian/appearance.json";
            if(mode.equals("obsidian")||mode.equals("dark"))dark=true;else if(mode.equals("moonstone")||mode.equals("light"))dark=false;else if(mode.isEmpty()||mode.equals("system"))detail+="，深浅色跟随系统";else return fallback(systemDark,"外观模式无法识别");
            Object accentRaw=data.opt("accentColor");String value=accentRaw instanceof String?(String)accentRaw:null;Integer accent=color(value);
            if(accent==null)detail+=(value==null||value.trim().isEmpty())?"，使用默认紫色":"，强调色无效，使用默认紫色";
            return new AppearanceConfig(dark,accent==null?DEFAULT_ACCENT:accent,detail);
        }catch(Exception ex){return fallback(systemDark,"外观配置格式无效");}
    }
    static AppearanceConfig read(InputStream input,boolean systemDark)throws IOException{
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();byte[] buffer=new byte[4096];int n;
        while((n=input.read(buffer))!=-1){if(bytes.size()+n>MAX_BYTES)return fallback(systemDark,"外观配置超过 64 KiB");bytes.write(buffer,0,n);}
        try{return parse(StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString(),systemDark);}catch(CharacterCodingException ex){return fallback(systemDark,"外观配置格式无效");}
    }
}
