package io.vaultbridge.app;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import javax.net.ssl.HttpsURLConnection;

/** Standard HTTPS only; redirects and bytes are bounded before anything is offered for installation. */
final class UpdateClient {
    interface Connections {HttpURLConnection open(URL url)throws IOException;}
    interface Progress {void update(long current,long total);}
    static final class Failure extends IOException {Failure(String message){super(message);}Failure(String message,Throwable cause){super(message,cause);}}
    final Connections connections;
    volatile boolean cancelled;
    volatile long deadline;
    volatile HttpURLConnection active;
    UpdateClient(){this(url->{URLConnection connection=url.openConnection();if(!(connection instanceof HttpsURLConnection))throw new Failure("更新只允许标准 HTTPS 连接");return (HttpURLConnection)connection;});}
    UpdateClient(Connections connections){this.connections=connections;}
    void cancel(){cancelled=true;HttpURLConnection connection=active;if(connection!=null)connection.disconnect();}
    void check()throws IOException {if(cancelled||Thread.currentThread().isInterrupted())throw new Failure("更新操作已停止，已有下载文件保留");if(deadline!=0&&System.nanoTime()-deadline>=0)throw new Failure("更新网络操作超时，已有下载文件保留，请稍后重试");}
    static boolean redirect(String value,UpdateInfo info){
        try{URI uri=UpdateInfo.https(value);if(info==null)return UpdateInfo.API.equals(value);
            if(UpdateInfo.assetUrl(value,info.version,info.name))return true;
            String host=uri.getHost();return ("release-assets.githubusercontent.com".equals(host)||"objects.githubusercontent.com".equals(host)||"github-releases.githubusercontent.com".equals(host))&&uri.getRawPath()!=null&&!uri.getRawPath().isEmpty();
        }catch(IOException e){return false;}
    }
    HttpURLConnection open(String value,UpdateInfo info)throws IOException {
        String current=value;
        for(int redirects=0;redirects<=5;redirects++){
            check();if(!redirect(current,info))throw new Failure("更新服务器跳转到不允许的地址，已停止");HttpURLConnection connection=connections.open(new URL(current));active=connection;
            connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(15000);connection.setUseCaches(false);connection.setRequestProperty("User-Agent","VaultBridge-Updater");connection.setRequestProperty("Accept",info==null?"application/vnd.github+json":"application/octet-stream");connection.setRequestProperty("Accept-Encoding","identity");
            int status;try{status=connection.getResponseCode();}catch(IOException e){connection.disconnect();throw e;}
            if(status==200)return connection;
            if(status==301||status==302||status==303||status==307||status==308){String next=connection.getHeaderField("Location");connection.disconnect();if(next==null||redirects==5)throw new Failure("更新下载跳转次数过多或缺少地址");try{current=new URL(new URL(current),next).toString();}catch(MalformedURLException e){throw new Failure("更新服务器返回无效跳转地址");}continue;}
            connection.disconnect();throw new Failure(status==403||status==429?"GitHub 暂时限制检查频率，请稍后重试":"GitHub 更新请求未成功（HTTP "+status+"）");
        }
        throw new Failure("更新跳转次数超过上限");
    }
    UpdateInfo latest(String installed)throws IOException {
        deadline=System.nanoTime()+45L*1000*1000*1000;
        HttpURLConnection connection=null;
        try{connection=open(UpdateInfo.API,null);long declared=connection.getContentLengthLong();if(declared>UpdateInfo.MAX_FEED)throw new Failure("更新清单超过大小上限");byte[] bytes;
            try(InputStream input=connection.getInputStream();ByteArrayOutputStream output=new ByteArrayOutputStream()){byte[] buffer=new byte[16384];int n;while((n=input.read(buffer))!=-1){check();if(output.size()+n>UpdateInfo.MAX_FEED)throw new Failure("更新清单超过大小上限");output.write(buffer,0,n);}bytes=output.toByteArray();}
            return UpdateInfo.parse(new String(bytes,"UTF-8"),installed);
        }catch(Failure e){throw e;}catch(IOException e){throw new Failure("无法检查 GitHub 更新，请检查网络后重试",e);}finally{if(connection!=null)connection.disconnect();active=null;}
    }
    File download(UpdateInfo info,File updates,Progress progress)throws IOException {
        deadline=System.nanoTime()+15L*60*1000*1000*1000;
        HttpURLConnection connection=null;
        try{
            if(info==null||info.sha256.isEmpty())throw new Failure("此版本缺少可信 SHA-256，不能在应用内下载，请查看版本页");
            if(!UpdateInfo.assetUrl(info.url,info.version,info.name)||info.size<=0||info.size>UpdateInfo.MAX_APK)throw new Failure("更新下载信息无效");
            check();if(!updates.isDirectory()&&!updates.mkdirs())throw new Failure("无法建立私有更新目录");if(updates.getUsableSpace()<info.size+32L*1024*1024)throw new Failure("私有更新空间不足");
            File directory=new File(updates,info.version+"-"+UUID.randomUUID().toString());if(!directory.mkdir())throw new Failure("无法建立本次更新目录");File partial=new File(directory,"download.partial"),target=new File(directory,info.name);
            connection=open(info.url,info);long declared=connection.getContentLengthLong();if(declared>=0&&declared!=info.size)throw new Failure("更新下载大小与 GitHub 清单不一致");
            try(InputStream input=connection.getInputStream()){copy(input,partial,info.size,info.sha256,progress);}
            check();Files.move(partial.toPath(),target.toPath());return target;
        }catch(Failure e){throw e;}catch(IOException e){throw new Failure("更新下载未完成，文件保留，可稍后重新下载",e);}finally{if(connection!=null)connection.disconnect();active=null;}
    }
    void copy(InputStream input,File partial,long expected,String sha,Progress progress)throws IOException {
        if(expected<=0||expected>UpdateInfo.MAX_APK||sha==null||!sha.matches("[0-9a-f]{64}"))throw new Failure("更新校验信息无效");
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;byte[] buffer=new byte[65536];int n;
            try(FileOutputStream output=new FileOutputStream(partial)){while((n=input.read(buffer))!=-1){check();total+=n;if(total>expected)throw new Failure("更新下载超出清单大小，未开放安装");output.write(buffer,0,n);digest.update(buffer,0,n);if(progress!=null)progress.update(total,expected);}output.getFD().sync();}
            if(total!=expected||!hex(digest.digest()).equals(sha))throw new Failure("更新文件大小或 SHA-256 校验失败，未开放安装");
        }catch(NoSuchAlgorithmException e){throw new Failure("系统不支持 SHA-256 校验",e);}
    }
    static String hash(File file)throws IOException {try{MessageDigest digest=MessageDigest.getInstance("SHA-256");try(InputStream input=new FileInputStream(file)){byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1)digest.update(buffer,0,n);}return hex(digest.digest());}catch(NoSuchAlgorithmException e){throw new IOException("无法校验更新文件",e);}}
    static String hex(byte[] bytes){StringBuilder result=new StringBuilder();for(byte b:bytes)result.append(String.format(Locale.ROOT,"%02x",b&255));return result.toString();}
}
