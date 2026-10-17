package io.vaultbridge.app;

import java.io.*;
import java.util.*;
import javax.net.ssl.*;
import org.json.*;

/** Chunk protocol over the existing pinned TLS transport. */
final class TransferClient implements TransferEngine.Control {
    final LanClient lan;
    volatile boolean paused;
    volatile SSLSocket active;
    interface Progress {void show(String text);}
    interface Attempt<T> {T run(int attempt)throws IOException;}
    final Progress progress;
    TransferClient(LanClient client,Progress listener){lan=client;progress=listener;}
    public void check()throws IOException {if(paused||Thread.currentThread().isInterrupted())throw new TransferEngine.Paused();}
    void pause(){paused=true;SSLSocket socket=active;if(socket!=null)try{socket.close();}catch(IOException ignored){}}
    <T>T retry(Attempt<T> action)throws IOException {
        for(int attempt=0;;attempt++){check();try{return action.run(attempt);}catch(IOException e){check();if(!TransferEngine.retryable(e)||attempt>=TransferEngine.DELAYS.length)throw e;int seconds=TransferEngine.DELAYS[attempt];progress.show("连接中断，"+seconds+" 秒后续传；已验证块保留");for(int t=0;t<seconds*4;t++){check();try{Thread.sleep(250);}catch(InterruptedException x){Thread.currentThread().interrupt();check();}}}}
    }
    JSONObject object(String action)throws IOException {try{return new JSONObject().put("action",action).put("token",lan.token);}catch(JSONException e){throw new IOException(e);}}
    JSONObject call(JSONObject request)throws IOException {
        check();try(SSLSocket socket=lan.connect()){active=socket;check();LanClient.write(socket.getOutputStream(),request);return LanClient.checked(LanClient.read(socket.getInputStream()));}finally{active=null;}
    }
    JSONObject listing()throws IOException {return retry(a->call(object("x-list")));}
    JSONObject compareNotes(String prefix)throws IOException {try{return call(object("x-compare").put("mode","notes").put("prefix",SendPickerModel.prefix(prefix)));}catch(JSONException e){throw new IOException(e);}}
    JSONObject verify(String job,int file,String targetId)throws IOException {try{return call(object("x-verify").put("id",job).put("file",file).put("targetId",targetId));}catch(JSONException e){throw new IOException(e);}}
    static void receipt(JSONObject item,JSONObject reply)throws JSONException,IOException {if(reply.optString("targetId").isEmpty())return;if(!reply.optString("sha256").equals(item.getString("sha256"))||reply.optLong("size",-1)!=item.getLong("size")||reply.optLong("completedAt",0)<=0)throw new IOException("电脑成功回执未绑定本次内容，保留任务待核验");item.put("targetId",reply.getString("targetId")).put("vaultId",reply.optString("vaultId")).put("completedAt",reply.getLong("completedAt"));}
    JSONObject status(JSONObject job,int file)throws IOException {try{return call(object("x-status").put("id",job.getString("id")).put("file",file));}catch(JSONException e){throw new IOException(e);}}
    void upload(JSONObject job,File folder)throws IOException {
        try{
            JSONArray files=job.getJSONArray("files");TransferEngine.validate(files);
            JSONArray manifest=new JSONArray();for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);manifest.put(new JSONObject().put("path",f.getString("path")).put("size",f.getLong("size")).put("sha256",f.getString("sha256")).put("modified",0));}
            JSONObject request=object("x-request").put("id",job.getString("id")).put("mode",job.getString("mode")).put("prefix",job.optString("prefix","")).put("files",manifest);
            retry(a->call(request));JSONObject approved;
            for(;;){check();approved=retry(a->status(job,0));String state=approved.getString("state");if(state.equals("approved"))break;if(state.equals("rejected"))throw new LanClient.RemoteError("电脑拒绝本批接收；重新选择文件创建任务");progress.show("等待电脑确认接收，本批目标与文件列表已发送…");for(int t=0;t<12;t++){check();try{Thread.sleep(250);}catch(InterruptedException e){Thread.currentThread().interrupt();check();}}}
            for(int i=0;i<files.length();i++){
                final int index=i;JSONObject item=files.getJSONObject(i);JSONObject remote=retry(a->status(job,index));if(remote.optBoolean("done")){receipt(item,remote);item.put("done",true).put("saved",remote.optString("saved"));TransferEngine.save(folder,job);continue;}
                File source=new File(folder,item.getString("local"));if(source.length()!=item.getLong("size")||!TransferEngine.hash(source).equals(item.getString("sha256")))throw new IOException("来源暂存变化，请重新选文件建立任务");
                Map<Integer,String> complete=new HashMap<>();JSONArray blocks=remote.getJSONArray("chunks");for(int b=0;b<blocks.length();b++){JSONObject block=blocks.getJSONObject(b);complete.put(block.getInt("index"),block.getString("sha256"));}
                long size=item.getLong("size");for(int b=0;b<TransferEngine.count(size);b++){
                    check();final int block=b;byte[] data=TransferEngine.readBlock(source,size,b);String sha=TransferEngine.hash(data);if(sha.equals(complete.get(b)))continue;
                    JSONObject put=object("x-put").put("id",job.getString("id")).put("file",i).put("block",b).put("sha256",sha);
                    JSONObject query=object("x-has").put("id",job.getString("id")).put("file",index).put("block",block).put("sha256",sha);
                    retry(attempt->{if(attempt>0){JSONObject has=call(query);if(has.optBoolean("present"))return has;}
                        try(SSLSocket socket=lan.connect()){active=socket;check();OutputStream out=socket.getOutputStream();LanClient.write(out,put);LanClient.checked(LanClient.read(socket.getInputStream()));out.write(data);out.flush();return LanClient.checked(LanClient.read(socket.getInputStream()));}finally{active=null;}});
                    progress.show("发送 "+(i+1)+"/"+files.length()+" · "+item.getString("path")+" · 分块 "+(b+1)+"/"+TransferEngine.count(size));
                }
                JSONObject commit=object("x-commit").put("id",job.getString("id")).put("file",i);JSONObject reply=retry(a->call(commit));receipt(item,reply);item.put("done",true).put("saved",reply.getString("saved"));TransferEngine.save(folder,job);
            }
        }catch(JSONException e){throw new IOException("传输任务格式错误",e);}
    }
    File download(JSONObject job,File folder,int file)throws IOException {
        try{
            JSONObject item=job.getJSONArray("files").getJSONObject(file);long size=item.getLong("size");TransferEngine.count(size);long retained=0;for(int b=0;b<TransferEngine.count(size);b++){File saved=TransferEngine.verified(folder,file,b,TransferEngine.length(size,b),this);if(saved!=null)retained+=saved.length();}File assembled=new File(folder,"assembled-"+file+".bin");TransferEngine.space(folder,Math.max(0,size*2-retained-assembled.length()));
            for(int b=0;b<TransferEngine.count(size);b++){
                check();int length=TransferEngine.length(size,b);if(TransferEngine.verified(folder,file,b,length,this)!=null)continue;
                JSONObject request=object(job.optString("mode").equals("notes-download")?"x-note-get":"x-get").put("id",job.optString("offer")).put("path",item.getString("path")).put("sha256",item.getString("sha256")).put("block",b);
                final int block=b;
                byte[] data=retry(a->{try(SSLSocket socket=lan.connect()){active=socket;check();LanClient.write(socket.getOutputStream(),request);InputStream input=socket.getInputStream();JSONObject response=LanClient.checked(LanClient.read(input));if(response.optInt("size",-1)!=length)throw new LanClient.RemoteError("分块长度变化");byte[] bytes=new byte[length];new DataInputStream(input).readFully(bytes);String sha=TransferEngine.hash(bytes);if(!sha.equals(response.optString("sha256")))throw new LanClient.RemoteError("分块校验失败");return bytes;}finally{active=null;}});
                TransferEngine.put(folder,file,block,data,TransferEngine.hash(data));progress.show("接收 · "+item.getString("path")+" · 分块 "+(b+1)+"/"+TransferEngine.count(size));
            }
            return TransferEngine.assemble(folder,file,item,this);
        }catch(JSONException e){throw new IOException(e);}
    }
}
