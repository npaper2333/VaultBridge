package io.vaultbridge.app;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import org.json.*;

/** Rebuildable private metadata for completed ordinary downloads; never contains file bodies. */
final class RecentFiles {
    static final Object GATE=new Object();
    static final int MAX_ITEMS=20000,MAX_BYTES=16*1024*1024;
    final File root;
    static final class Entry {
        final String id,job,tree,path,uri,sha256,kind,state;
        final int index;
        final long size,receivedAt,viewedAt;
        final boolean legacy,estimatedTime;
        Entry(String id,String job,int index,String tree,String path,String uri,String sha,long size,long receivedAt,long viewedAt,boolean estimated,String state){this.id=id;this.job=job;this.index=index;this.tree=tree;this.path=path;this.uri=uri;sha256=sha;this.size=size;this.receivedAt=receivedAt;this.viewedAt=viewedAt;estimatedTime=estimated;legacy=uri.isEmpty();kind=PreviewRules.kind(path);this.state=state;}
        Entry change(String finalUri,String nextState,long viewed){return new Entry(id,job,index,tree,path,finalUri,sha256,size,receivedAt,viewed,estimatedTime,nextState);}
    }
    static final class Page {
        final List<Entry> entries;final int total,page;final boolean hasNext;
        Page(List<Entry> entries,int total,int page,boolean next){this.entries=Collections.unmodifiableList(entries);this.total=total;this.page=page;hasNext=next;}
    }
    RecentFiles(File files){root=new File(files,"received");}
    static String id(String job,int index)throws IOException {return TransferEngine.hash((job+":"+index).getBytes("UTF-8"));}
    static String entity(Entry e)throws IOException {return TransferEngine.hash((e.tree+"|"+(e.uri.isEmpty()?e.path+"|"+e.sha256:e.uri)).getBytes("UTF-8"));}
    static boolean temporary(String path){return path.toLowerCase(Locale.ROOT).endsWith(".partial");}
    static URI content(String value)throws IOException {try{URI uri=new URI(value);if(!"content".equals(uri.getScheme())||uri.getRawAuthority()==null||uri.getRawAuthority().isEmpty()||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null)throw new IOException("接收文件目录记录无效");return uri;}catch(URISyntaxException e){throw new IOException("接收文件目录记录无效",e);}}
    static void bound(String tree,String uri)throws IOException {URI parent=content(tree);if(!uri.isEmpty()&&!parent.getRawAuthority().equals(content(uri).getRawAuthority()))throw new IOException("接收文件不属于记录的文件提供器");}
    void ensure()throws IOException {if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法建立私有文件目录索引");}
    JSONObject read(String name)throws IOException {
        File file=new File(root,name);if(!file.exists())return new JSONObject();if(file.length()>MAX_BYTES)throw new IOException("文件目录索引超过容量，原文件和任务保留");
        try{byte[] data=Files.readAllBytes(file.toPath());if(data.length>MAX_BYTES)throw new IOException("文件目录索引超过容量，原文件和任务保留");return new JSONObject(new String(data,"UTF-8"));}catch(JSONException e){throw new IOException("文件目录索引无法读取，原文件和任务保留",e);}
    }
    void write(String name,JSONObject value)throws IOException {
        byte[] data=value.toString().getBytes("UTF-8");if(data.length>MAX_BYTES)throw new IOException("文件目录索引容量已满，原文件和任务保留");ensure();File temporary=new File(root,UUID.randomUUID()+".writing"),target=new File(root,name);
        try(FileOutputStream output=new FileOutputStream(temporary)){output.write(data);output.getFD().sync();}try{Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temporary.toPath(),target.toPath(),StandardCopyOption.REPLACE_EXISTING);}
    }
    static JSONObject json(Entry e)throws IOException {try{return new JSONObject().put("id",e.id).put("job",e.job).put("index",e.index).put("tree",e.tree).put("path",e.path).put("uri",e.uri).put("sha256",e.sha256).put("size",e.size).put("receivedAt",e.receivedAt).put("estimatedTime",e.estimatedTime).put("state",e.state);}catch(JSONException ex){throw new IOException("文件目录记录无法编码",ex);}}
    static Entry entry(JSONObject item,long viewed)throws IOException {
        try{String job=item.getString("job"),key=item.getString("id"),path=TransferEngine.path(item.getString("path")),tree=item.getString("tree"),uri=item.optString("uri",""),sha=item.getString("sha256");int index=item.getInt("index");long size=item.getLong("size"),received=item.optLong("receivedAt",0);
            if(!job.matches("[0-9a-f]{32}")||index<0||index>=Engine.MAX_FILES||!key.equals(id(job,index))||!sha.matches("[0-9a-f]{64}")||temporary(path)||received<0)throw new IOException("接收文件索引记录无效");TransferEngine.count(size);bound(tree,uri);
            String state=item.optString("state",uri.isEmpty()?"pending":"ready");if(!Arrays.asList("pending","ready","permission","missing","changed","error").contains(state))throw new IOException("接收文件状态无效");return new Entry(key,job,index,tree,path,uri,sha,size,received,Math.max(0,viewed),item.optBoolean("estimatedTime",false),state);
        }catch(JSONException e){throw new IOException("接收文件索引记录无法读取",e);}
    }
    LinkedHashMap<String,Entry> entries()throws IOException {
        JSONObject saved=read("catalog.json");JSONArray items=saved.optJSONArray("items");LinkedHashMap<String,Entry> result=new LinkedHashMap<>();if(saved.length()>0&&(saved.optInt("schema",0)!=1||items==null))throw new IOException("文件目录索引结构无效，原记录保留");if(items==null)return result;if(items.length()>MAX_ITEMS)throw new IOException("文件目录索引条目过多");
        for(int i=0;i<items.length();i++)try{Entry e=entry(items.getJSONObject(i),0);if(result.put(e.id,e)!=null)throw new IOException("文件目录索引编号重复");}catch(JSONException ex){throw new IOException("文件目录索引无效",ex);}return result;
    }
    void save(Collection<Entry> entries)throws IOException {if(entries.size()>MAX_ITEMS)throw new IOException("文件目录索引容量已满，原文件和任务保留");try{JSONArray items=new JSONArray();for(Entry e:entries)items.put(json(e));write("catalog.json",new JSONObject().put("schema",1).put("items",items));}catch(JSONException ex){throw new IOException("文件目录索引无法保存",ex);}}
    void indexJob(JSONObject job)throws IOException {
        synchronized(GATE){
            if(!"down".equals(job.optString("direction"))||!"files".equals(job.optString("mode")))return;
            String source=job.optString("id"),tree=job.optString("tree");if(!source.matches("[0-9a-f]{32}"))throw new IOException("接收任务编号无效");content(tree);JSONArray files=job.optJSONArray("files");if(files==null||files.length()>Engine.MAX_FILES)throw new IOException("接收任务清单无效");Map<String,Entry> all=entries();boolean changed=false;
            for(int i=0;i<files.length();i++){
                JSONObject f=files.optJSONObject(i);if(f==null||!Boolean.TRUE.equals(f.opt("done")))continue;String path=f.optString("saved","");if(path.isEmpty()||temporary(path))continue;
                try{path=TransferEngine.path(path);String sha=f.getString("sha256"),uri=f.optString("finalUri","");long size=f.getLong("size");TransferEngine.count(size);if(!sha.matches("[0-9a-f]{64}"))throw new IOException("接收任务哈希无效");bound(tree,uri);
                    String key=id(source,i);Entry old=all.get(key);if(old!=null){if(!old.tree.equals(tree)||!old.path.equals(path)||!old.sha256.equals(sha)||old.size!=size)throw new IOException("接收任务与已有目录记录冲突");if(uri.isEmpty()||uri.equals(old.uri))continue;}
                    long received=f.optLong("receivedAt",0);boolean estimated=received<=0;if(estimated)received=Math.max(0,job.optLong("created",0));all.put(key,new Entry(key,source,i,tree,path,uri,sha,size,received,0,estimated,uri.isEmpty()?"pending":"ready"));changed=true;
                }catch(JSONException ex){throw new IOException("已完成接收项记录无效",ex);}
            }
            if(changed)save(all.values());
        }
    }
    Entry get(String id)throws IOException {synchronized(GATE){if(id==null||!id.matches("[0-9a-f]{64}"))throw new IOException("接收文件编号无效");Entry entry=entries().get(id);if(entry==null)throw new IOException("该文件不在本应用的已接收记录中");return entry.change(entry.uri,entry.state,read("view-stats.json").optLong(entity(entry),0));}}
    void backfill(String id,String uri)throws IOException {synchronized(GATE){Map<String,Entry> all=entries();Entry e=all.get(id);if(e==null)throw new IOException("接收记录不存在");bound(e.tree,uri);if(uri.isEmpty())throw new IOException("最终文件 URI 为空");all.put(id,e.change(uri,"ready",0));save(all.values());}}
    static boolean verified(InputStream input,long size,String expected,TransferEngine.Control control)throws IOException {
        TransferEngine.count(size);if(expected==null||!expected.matches("[0-9a-f]{64}"))throw new IOException("旧接收记录哈希无效");
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");long total=0;byte[] bytes=new byte[65536];int count;while((count=input.read(bytes))!=-1){if(control!=null)control.check();total+=count;if(total>size)return false;digest.update(bytes,0,count);}return total==size&&Engine.hex(digest.digest()).equals(expected);}catch(NoSuchAlgorithmException ex){throw new IOException("无法核对旧接收文件",ex);}
    }
    void states(Map<String,String> states)throws IOException {synchronized(GATE){Map<String,Entry> all=entries();boolean changed=false;for(Map.Entry<String,String> update:states.entrySet()){Entry e=all.get(update.getKey());if(e!=null&&!e.state.equals(update.getValue())){if(!Arrays.asList("pending","ready","permission","missing","changed","error").contains(update.getValue()))throw new IOException("接收文件状态无效");all.put(e.id,e.change(e.uri,update.getValue(),0));changed=true;}}if(changed)save(all.values());}}
    void viewed(String id,long time)throws IOException {synchronized(GATE){Entry e=get(id);if(e.uri.isEmpty()||!"ready".equals(e.state)||time<=0)throw new IOException("未成功打开的文件不能加入最近查看");JSONObject stats=read("view-stats.json");try{stats.put(entity(e),time);write("view-stats.json",stats);}catch(JSONException ex){throw new IOException("无法保存最近查看记录",ex);}}}
    Page page(String search,String kind,boolean viewed,int page,int count)throws IOException {
        synchronized(GATE){if(page<0||count<1||count>50)throw new IOException("文件目录分页参数无效");String query=(search==null?"":search.trim()).toLowerCase(Locale.ROOT);if(query.length()>200)query=query.substring(0,200);JSONObject stats=read("view-stats.json");List<Entry> matches=new ArrayList<>();
            Map<String,Entry> unique=new LinkedHashMap<>();for(Entry original:entries().values()){String key=entity(original);Entry previous=unique.get(key);if(previous==null||previous.receivedAt<original.receivedAt)unique.put(key,original);}
            for(Map.Entry<String,Entry> item:unique.entrySet()){Entry original=item.getValue();long at=stats.optLong(item.getKey(),0);Entry e=original.change(original.uri,original.state,at);if(viewed&&at<=0)continue;if(!query.isEmpty()&&!e.path.toLowerCase(Locale.ROOT).contains(query))continue;if(kind!=null&&!kind.equals("all")&&!kind.equals(e.kind))continue;matches.add(e);}
            matches.sort((a,b)->{int order=Long.compare(viewed?b.viewedAt:b.receivedAt,viewed?a.viewedAt:a.receivedAt);return order!=0?order:a.id.compareTo(b.id);});int start=(int)Math.min((long)page*count,matches.size()),end=Math.min(start+count,matches.size());return new Page(new ArrayList<>(matches.subList(start,end)),matches.size(),page,end<matches.size());
        }
    }
}
