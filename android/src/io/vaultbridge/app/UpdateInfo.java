package io.vaultbridge.app;

import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;

/** Strict public-release metadata. It never contains vault, pairing or device information. */
final class UpdateInfo {
    static final String REPOSITORY="npaper2333/VaultBridge";
    static final String API="https://api.github.com/repos/"+REPOSITORY+"/releases?per_page=20";
    static final long MAX_APK=128L*1024*1024;
    static final int MAX_FEED=1024*1024;
    final String version,name,url,page,sha256,notes;
    final long size;
    final boolean prerelease;
    UpdateInfo(String version,String url,String page,long size,String sha,String notes,boolean prerelease)throws IOException {
        parts(version);this.version=version;name="VaultBridge-Android-"+version+".apk";
        if(!assetUrl(url,version,name)||!pageUrl(page,version)||size<=0||size>MAX_APK)throw new IOException("更新资产信息不符合本项目规则");
        if(sha==null)sha="";if(!sha.isEmpty()&&!sha.matches("[0-9a-f]{64}"))throw new IOException("更新 SHA-256 信息无效");
        this.url=url;this.page=page;this.size=size;sha256=sha;this.notes=plain(notes==null?"":notes,1600);this.prerelease=prerelease;
    }
    static long[] parts(String version)throws IOException {
        if(version==null||!version.matches("(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})\\.(?:0|[1-9][0-9]{0,8})"))throw new IOException("版本号格式无效");
        String[] values=version.split("\\.");return new long[]{Long.parseLong(values[0]),Long.parseLong(values[1]),Long.parseLong(values[2])};
    }
    static int compare(String left,String right)throws IOException {long[] a=parts(left),b=parts(right);for(int i=0;i<3;i++){int c=Long.compare(a[i],b[i]);if(c!=0)return c;}return 0;}
    static void identity(String installedPackage,String archivePackage,String expectedVersion,String archiveVersion,long installedCode,long archiveCode,Set<String> installedSigners,Set<String> archiveSigners)throws IOException {
        if(!installedPackage.equals(archivePackage)||!expectedVersion.equals(archiveVersion)||archiveCode<=installedCode||archiveCode<=0)throw new IOException("安装包包名、版本或版本码与本次更新不符");
        if(installedSigners==null||installedSigners.isEmpty()||archiveSigners==null||!installedSigners.equals(archiveSigners))throw new IOException("安装包签名与当前应用不同，无法安全覆盖安装");
    }
    static URI https(String text)throws IOException {
        try{if(text==null||text.length()>8192)throw new IOException("更新地址无效");URI uri=new URI(text);if(!"https".equals(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||(uri.getPort()!=-1&&uri.getPort()!=443)||uri.getRawFragment()!=null)throw new IOException("更新只允许指定 GitHub HTTPS 地址");return uri;}
        catch(URISyntaxException e){throw new IOException("更新地址格式无效",e);}
    }
    static boolean assetUrl(String value,String version,String name){try{URI uri=https(value);return "github.com".equals(uri.getHost())&&uri.getRawQuery()==null&&uri.getRawPath().equals("/"+REPOSITORY+"/releases/download/v"+version+"/"+name);}catch(IOException e){return false;}}
    static boolean pageUrl(String value,String version){try{URI uri=https(value);return "github.com".equals(uri.getHost())&&uri.getRawQuery()==null&&uri.getRawPath().equals("/"+REPOSITORY+"/releases/tag/v"+version);}catch(IOException e){return false;}}
    static String plain(String value,int maximum){StringBuilder result=new StringBuilder();for(int i=0;i<value.length()&&result.length()<maximum;i++){char c=value.charAt(i);if(c>=32||c=='\n'||c=='\t')result.append(c);}return result.toString();}
    static UpdateInfo parse(String text,String installed)throws IOException {
        parts(installed);if(text==null||text.getBytes("UTF-8").length>MAX_FEED)throw new IOException("更新清单超过大小上限");
        try{
            JSONArray releases=new JSONArray(text);if(releases.length()>100)throw new IOException("更新清单条目过多");UpdateInfo best=null;
            for(int i=0;i<releases.length();i++){
                JSONObject release=releases.optJSONObject(i);if(release==null||!(release.opt("draft") instanceof Boolean)||release.optBoolean("draft"))continue;
                String tag=release.optString("tag_name","");if(!tag.startsWith("v"))continue;String version=tag.substring(1);try{if(compare(version,installed)<=0)continue;}catch(IOException e){continue;}
                String page=release.optString("html_url","");if(!pageUrl(page,version))continue;JSONArray assets=release.optJSONArray("assets");if(assets==null||assets.length()>100)continue;
                String expected="VaultBridge-Android-"+version+".apk";JSONObject selected=null;boolean duplicate=false;
                for(int j=0;j<assets.length();j++){JSONObject asset=assets.optJSONObject(j);if(asset!=null&&expected.equals(asset.optString("name"))){if(selected!=null)duplicate=true;selected=asset;}}
                if(duplicate||selected==null||!"uploaded".equals(selected.optString("state")))continue;
                Object rawSize=selected.opt("size");if(!(rawSize instanceof Number)||!rawSize.toString().matches("[1-9][0-9]{0,8}"))continue;long size=selected.getLong("size");if(size>MAX_APK)continue;
                String sha="",digest=selected.optString("digest","");if(digest.matches("sha256:[0-9a-fA-F]{64}"))sha=digest.substring(7).toLowerCase(Locale.ROOT);
                UpdateInfo info;try{info=new UpdateInfo(version,selected.optString("browser_download_url",""),page,size,sha,release.optString("body",""),release.optBoolean("prerelease"));}catch(IOException e){continue;}
                if(best==null||compare(info.version,best.version)>0)best=info;
            }
            return best;
        }catch(JSONException e){throw new IOException("GitHub 更新清单格式无效",e);}
    }
    String encode()throws IOException {try{return new JSONObject().put("version",version).put("url",url).put("page",page).put("size",size).put("sha256",sha256).put("notes",notes).put("prerelease",prerelease).toString();}catch(JSONException e){throw new IOException("无法记录更新信息",e);}}
    static UpdateInfo decode(String value)throws IOException {try{if(value==null||value.getBytes("UTF-8").length>16384)throw new IOException("更新记录无效");JSONObject info=new JSONObject(value);return new UpdateInfo(info.getString("version"),info.getString("url"),info.getString("page"),info.getLong("size"),info.getString("sha256"),info.optString("notes",""),info.optBoolean("prerelease"));}catch(JSONException e){throw new IOException("更新记录无法读取",e);}}
}
