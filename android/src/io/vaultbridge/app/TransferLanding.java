package io.vaultbridge.app;

import java.io.*;
import java.security.*;
import java.util.*;
import org.json.*;

/** Publish verified content through an owned SAF temporary document; never truncate a final name. */
final class TransferLanding {
    static final class Entry {
        final String id,path;final long size;final boolean directory;
        Entry(String id,String path,long size,boolean directory){this.id=id;this.path=path;this.size=size;this.directory=directory;}
    }
    interface Tree {
        void ensureParent(String parent)throws IOException;
        // Each lookup must refresh the provider listing, including after a rename.
        Entry find(String path)throws IOException;
        Entry create(String path)throws IOException;
        InputStream read(Entry entry)throws IOException;
        OutputStream rewrite(Entry entry)throws IOException;
        boolean canRename(Entry entry)throws IOException;
        Entry rename(Entry entry,String target)throws IOException;
    }
    interface Save {void run()throws IOException;}
    static void check(TransferEngine.Control control)throws IOException{if(control!=null)control.check();}
    static boolean content(Tree tree,Entry entry,long expected,String sha,TransferEngine.Control control)throws IOException {
        if(entry==null||entry.directory)return false;
        // An existing final file with unknown size is conservatively retained and numbered around.
        if(entry.size!=expected)return false;
        return verified(tree,entry,expected,sha,control);
    }
    static boolean verified(Tree tree,Entry entry,long expected,String sha,TransferEngine.Control control)throws IOException {
        if(entry==null||entry.directory)return false;
        try(InputStream input=tree.read(entry)){
            if(input==null)throw new IOException("接收文档无法读取");
            MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;int n;byte[] buffer=new byte[65536];
            while((n=input.read(buffer))!=-1){check(control);size+=n;if(size>expected)return false;digest.update(buffer,0,n);}
            return size==expected&&Engine.hex(digest.digest()).equals(sha);
        }catch(NoSuchAlgorithmException e){throw new IOException(e);}
    }
    static String finish(JSONObject item,JSONObject landing,Entry finalEntry,Save save)throws IOException {
        try{item.put("done",true).put("saved",finalEntry.path).put("finalUri",finalEntry.id);if(item.optLong("receivedAt",0)<=0)item.put("receivedAt",System.currentTimeMillis());landing.put("state","published");save.run();return finalEntry.path;}catch(JSONException e){throw new IOException("接收发布记录无法更新",e);}
    }
    static String publish(Tree tree,File complete,JSONObject item,TransferEngine.Control control,Save save)throws IOException {
        try {
            check(control);String desired=TransferEngine.path(item.getString("path"));long size=item.getLong("size");TransferEngine.count(size);String sha=item.getString("sha256");
            if(!sha.matches("[0-9a-f]{64}")||!complete.isFile()||complete.length()!=size)throw new IOException("完整暂存文件与任务不符，未发布目标");
            try(InputStream input=new FileInputStream(complete)){if(!TransferEngine.hash(input,control).equals(sha))throw new IOException("完整暂存文件校验失败，未发布目标");}
            String parent=DirectoryEngine.parent(desired);tree.ensureParent(parent);
            JSONObject landing=item.optJSONObject("landing");if(landing==null){landing=new JSONObject();item.put("landing",landing);}
            String pending=landing.optString("final","");
            // The provider may have renamed successfully immediately before process/record failure.
            if(!pending.isEmpty()){
                pending=TransferEngine.path(pending);if(!DirectoryEngine.parent(pending).equals(parent))throw new IOException("接收发布记录的目录不符");
                Entry published=tree.find(pending);
                if(published!=null&&!published.directory&&(published.size==size||published.size<0)&&verified(tree,published,size,sha,control))return finish(item,landing,published,save);
            }
            String target=desired;int number=0;Entry existing;
            while((existing=tree.find(target))!=null){
                if(content(tree,existing,size,sha,control))return finish(item,landing,existing,save);
                if(++number>10000)throw new IOException("重名文件过多");target=TransferEngine.alternate(desired,number);
            }
            Entry staging;String tempPath=landing.optString("temporary",""),tempId=landing.optString("uri","");
            if(!tempId.isEmpty()){
                tempPath=TransferEngine.path(tempPath);
                if(!DirectoryEngine.parent(tempPath).equals(parent)||!DirectoryEngine.name(tempPath).matches("VaultBridge-[0-9a-f]{32}\\.partial"))throw new IOException("接收暂存记录无效，保留供检查");
                staging=tree.find(tempPath);
                if(staging==null||staging.directory||!staging.id.equals(tempId)||!staging.path.equals(tempPath))throw new IOException("本任务暂存文档已被移动或更改，未重写任何文件");
            }else{
                do{tempPath=(parent.isEmpty()?"":parent+"/")+"VaultBridge-"+UUID.randomUUID().toString().replace("-","")+".partial";}while(tree.find(tempPath)!=null);
                staging=tree.create(tempPath);
                if(staging==null)throw new IOException("文件提供器未创建接收暂存");
                landing.put("temporary",tempPath).put("uri",staging.id).put("state","staging");save.run();
                if(staging.directory||!staging.path.equals(tempPath))throw new IOException("文件提供器改变了暂存名称，文档保留，未发布目标");
            }
            if(!tree.canRename(staging))throw new IOException("接收位置不支持安全重命名发布；.partial 暂存保留，请选择支持重命名的目录");
            check(control);
            if(!verified(tree,staging,size,sha,control)){
                try(InputStream input=new FileInputStream(complete);OutputStream output=tree.rewrite(staging)){
                    if(output==null)throw new IOException("接收暂存无法写入");byte[] buffer=new byte[65536];int n;long written=0;
                    while((n=input.read(buffer))!=-1){check(control);written+=n;if(written>size)throw new IOException("暂存来源长度改变");output.write(buffer,0,n);}output.flush();
                }
                if(!verified(tree,staging,size,sha,control))throw new IOException("接收暂存校验失败，未发布目标");
            }
            // Recheck collisions after the potentially long SAF copy. Never request replacement.
            target=desired;number=0;
            while((existing=tree.find(target))!=null){
                if(content(tree,existing,size,sha,control))return finish(item,landing,existing,save);
                if(++number>10000)throw new IOException("重名文件过多");target=TransferEngine.alternate(desired,number);
            }
            landing.put("final",target).put("state","publishing");save.run();check(control);
            Entry renamed=tree.rename(staging,target);
            if(renamed==null||renamed.directory||!renamed.path.equals(target))throw new IOException("提供器未按目标名称发布，暂存与记录保留供检查");
            Entry finalEntry=tree.find(target);
            if(finalEntry==null||!finalEntry.id.equals(renamed.id)||!finalEntry.path.equals(target)||!verified(tree,finalEntry,size,sha,control))throw new IOException("发布后的目标身份、名称或内容校验失败，未标记完成");
            return finish(item,landing,finalEntry,save);
        }catch(JSONException e){throw new IOException("接收发布记录无效",e);}
    }
}
