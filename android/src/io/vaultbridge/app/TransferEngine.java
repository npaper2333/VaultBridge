package io.vaultbridge.app;

import org.json.*;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;

/** Persisted, content-bound jobs; immutable verified blocks, no automatic cleanup. */
final class TransferEngine {
    static final int CHUNK=4*1024*1024,MAX_RECORD=64*1024*1024;
    static final long MAX_FILE=8L*1024*1024*1024,MAX_BATCH=64L*1024*1024*1024;
    static class Paused extends IOException { Paused(){super("任务已暂停，已校验分块保留，可继续");} }
    interface Control { void check() throws IOException; }
    static int count(long size)throws IOException {if(size<0||size>MAX_FILE)throw new IOException("单文件上限 8 GiB");return (int)((size+CHUNK-1)/CHUNK);}
    static int length(long size,int index)throws IOException {if(index<0||index>=count(size))throw new IOException("分块编号错误");return (int)Math.min(CHUNK,size-(long)index*CHUNK);}
    static String path(String path)throws IOException {String p=Engine.normalize(path);if(Engine.protectedPath(p))throw new IOException("隐藏配置或备份不参与传输");return p;}
    static void validate(JSONArray files)throws IOException {
        try{if(files.length()==0||files.length()>Engine.MAX_FILES)throw new IOException("选择 1–20,000 个文件");Set<String> names=new HashSet<>();long total=0;
            for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);String p=path(f.getString("path"));long size=f.getLong("size");count(size);if(!f.getString("sha256").matches("[0-9a-f]{64}")||!names.add(p.toLowerCase(Locale.ROOT)))throw new IOException("哈希无效或清单重名");total+=size;if(total>MAX_BATCH)throw new IOException("一批最多 64 GiB，请分批");}
        }catch(JSONException e){throw new IOException("任务清单无效",e);}
    }
    static String hash(InputStream input,Control control)throws IOException {
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];long total=0;int n;while((n=input.read(buffer))!=-1){if(control!=null)control.check();total+=n;if(total>MAX_FILE)throw new IOException("单文件上限 8 GiB");digest.update(buffer,0,n);}return Engine.hex(digest.digest());}catch(NoSuchAlgorithmException e){throw new IOException(e);}
    }
    static String hash(File file)throws IOException {try(InputStream in=new FileInputStream(file)){return hash(in,null);}}
    static String hash(byte[] bytes)throws IOException {return hash(new ByteArrayInputStream(bytes),null);}
    static void space(File folder,long bytes)throws IOException {if(folder.getUsableSpace()<bytes+64L*1024*1024)throw new IOException("应用暂存空间不足，需要保留分块与完整文件");}
    static void save(File directory,JSONObject job)throws IOException {
        byte[] record=job.toString().getBytes("UTF-8");if(record.length>MAX_RECORD)throw new IOException("任务记录过大，请分批");
        File target=new File(directory,"job.json"),temp=new File(directory,"job-"+UUID.randomUUID()+".writing");
        try(FileOutputStream out=new FileOutputStream(temp)){out.write(record);out.getFD().sync();}
        try{Files.move(temp.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temp.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
    static JSONObject load(File directory)throws IOException {try{File file=new File(directory,"job.json");if(file.length()>MAX_RECORD)throw new IOException("任务记录过大，请分批");byte[] record=Files.readAllBytes(file.toPath());if(record.length>MAX_RECORD)throw new IOException("任务记录过大，请分批");return new JSONObject(new String(record,"UTF-8"));}catch(JSONException e){throw new IOException("任务记录无法读取",e);}}
    static File folder(File parent,String id)throws IOException {if(!id.matches("[0-9a-f]{32}"))throw new IOException("任务编号错误");File folder=Engine.resolve(parent,id);if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("无法创建任务目录");return folder;}
    static File block(File folder,int file,int index,String sha){return new File(folder,file+"-"+index+"-"+sha+".block");}
    static File verified(File folder,int file,int index,int length,Control control)throws IOException {
        File[] candidates=folder.listFiles((d,name)->name.matches(file+"-"+index+"-[0-9a-f]{64}\\.block"));if(candidates==null)throw new IOException("无法读取分块记录");
        for(File f:candidates){if(control!=null)control.check();String sha=f.getName().substring((file+"-"+index+"-").length(),f.getName().length()-6);if(f.length()==length&&hash(f).equals(sha))return f;}return null;
    }
    static void put(File folder,int file,int index,byte[] data,String expected)throws IOException {
        if(data.length>CHUNK||!hash(data).equals(expected))throw new IOException("分块校验失败");space(folder,data.length);File target=block(folder,file,index,expected);
        if(target.exists()){if(hash(target).equals(expected))return;Files.move(target.toPath(),new File(folder,target.getName()+".corrupt-"+UUID.randomUUID()).toPath());}
        File pending=new File(folder,"block-"+UUID.randomUUID()+".partial");try(FileOutputStream out=new FileOutputStream(pending)){out.write(data);out.getFD().sync();}Files.move(pending.toPath(),target.toPath());
    }
    static File assemble(File folder,int file,JSONObject item,Control control)throws IOException {
        try{long size=item.getLong("size");File complete=new File(folder,"assembled-"+file+".bin");if(complete.isFile()&&complete.length()==size&&hash(complete).equals(item.getString("sha256")))return complete;space(folder,size-Math.min(complete.length(),size));
            try(FileOutputStream output=new FileOutputStream(complete)){for(int i=0;i<count(size);i++){if(control!=null)control.check();File block=verified(folder,file,i,length(size,i),control);if(block==null)throw new IOException("有缺失或损坏分块");try(InputStream input=new FileInputStream(block)){Engine.copy(input,output,CHUNK);}}output.getFD().sync();}
            if(complete.length()!=size||!hash(complete).equals(item.getString("sha256")))throw new IOException("全文件校验失败，未保存目标");return complete;
        }catch(JSONException e){throw new IOException(e);}
    }
    static byte[] readBlock(File file,long size,int index)throws IOException {int length=length(size,index);byte[] data=new byte[length];try(RandomAccessFile input=new RandomAccessFile(file,"r")){input.seek((long)index*CHUNK);input.readFully(data);}return data;}
    static String alternate(String relative,int number)throws IOException {int slash=relative.lastIndexOf('/'),dot=relative.lastIndexOf('.');if(dot<=slash)dot=relative.length();String prefix=slash<0?"":relative.substring(0,slash+1),stem=relative.substring(slash+1,dot),ext=relative.substring(dot);if(stem.length()>150)stem=stem.substring(0,150);if(ext.length()>60)ext=ext.substring(0,60);return path(prefix+stem+" (VaultBridge "+number+")"+ext);}
    static boolean retryable(IOException error){if(error instanceof Paused||error instanceof LanClient.RemoteError)return false;boolean network=false;for(Throwable cause=error;cause!=null;cause=cause.getCause()){if(cause instanceof java.security.cert.CertificateException)return false;if(cause instanceof java.net.SocketException||cause instanceof InterruptedIOException||cause instanceof EOFException||cause instanceof javax.net.ssl.SSLException)network=true;}return network;}
    static final int[] DELAYS={2,4,8,15,30};
}
