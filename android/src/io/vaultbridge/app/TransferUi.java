package io.vaultbridge.app;

import android.content.*;
import android.database.Cursor;
import android.net.Uri;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.util.*;
import java.security.*;

/** Native generic transfer/SAF entry points; actual tasks survive process restart. */
final class TransferUi {
    static final int RECEIVE_TREE=30,SEND_FILES=31,SEND_TREE=32;
    final MainActivity a;
    final File root;
    volatile TransferClient active;
    TextView destination,tasks;
    String pendingMode="files";
    TransferUi(MainActivity activity){a=activity;root=new File(a.getFilesDir(),"transfers");}
    void build(LinearLayout page){
        a.content=page;LinearLayout receive=a.card("普通文件 · 电脑 → 平板");destination=a.text("",13,false);receive.addView(destination);a.button(receive,"选择 / 切换接收位置",this::chooseDestination);a.explain(receive,"使用独立的系统目录授权，记住常用接收位置。不会更改当前 Obsidian 库。");a.button(receive,"获取电脑清单并选择接收",this::fetch,true);a.explain(receive,"电脑在普通文件页先选择发送文件或文件夹，再开始分享。保留相对目录，ZIP 原样保存，Markdown 不改写。");
        LinearLayout send=a.card("普通文件 · 平板 → 电脑");a.button(send,"选择发送文件",this::chooseFiles,true);a.button(send,"选择发送文件夹",this::chooseFolder);a.explain(send,"电脑逐批确认保存位置；默认普通收件箱。同名内容变化另存，相同内容跳过。发送不删除来源。");
        LinearLayout queue=a.card("任务队列与续传");tasks=a.text("",12,false);tasks.setTextIsSelectable(true);queue.addView(tasks);a.button(queue,"选择任务继续 / 查看结果",this::selectTask);a.button(queue,"继续所有未完成任务",()->runQueue(null));
        Button pause=new Button(a);pause.setText("暂停当前任务");pause.setAllCaps(false);UiChrome.button(pause,a.theme,false);pause.setOnClickListener(v->{TransferClient current=active;if(current!=null)current.pause();});queue.addView(pause);a.explain(queue,"暂停按钮在传输时可用。任务和已校验块保存在应用私有空间；重启后点继续。取消只改变任务状态，不删除文件。");
        a.notice(queue,"单文件 8 GiB，一批 64 GiB。断网后按 2/4/8/15/30 秒间隔尝试重连，只补缺失块；仍不通就保留等待状态。发送前先暂存选中内容；这一步中断时当前文件需重新读取。完成与失败结果逐文件保存。");refresh();
    }
    void ensure()throws IOException {if(!root.isDirectory()&&!root.mkdirs())throw new IOException("无法创建任务空间");}
    void chooseDestination(){
        Set<String> known=a.prefs.getStringSet("fileTrees",Collections.emptySet());List<String> list=new ArrayList<>(known);List<String> labels=new ArrayList<>();for(String tree:list)labels.add(LibraryUi.label(tree));labels.add("授权新的接收位置");
        a.dialog().setTitle("普通文件接收位置").setItems(labels.toArray(new String[0]),(d,w)->{if(w<list.size()){a.prefs.edit().putString("fileTree",list.get(w)).apply();refresh();}else{Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);a.startActivityForResult(i,RECEIVE_TREE);}}).setNegativeButton("取消",null).show();
    }
    void chooseFiles(){pendingMode="files";Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.addCategory(Intent.CATEGORY_OPENABLE);i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);a.startActivityForResult(i,SEND_FILES);}
    void chooseFolder(){pendingMode="files";Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);a.startActivityForResult(i,SEND_TREE);}
    boolean result(int code,Intent data){
        if(code!=RECEIVE_TREE&&code!=SEND_FILES&&code!=SEND_TREE)return false;
        try{
            if(code==RECEIVE_TREE){Uri uri=data.getData();if(uri==null)return true;a.getContentResolver().takePersistableUriPermission(uri,data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION));Set<String> trees=new HashSet<>(a.prefs.getStringSet("fileTrees",Collections.emptySet()));trees.add(uri.toString());a.prefs.edit().putString("fileTree",uri.toString()).putStringSet("fileTrees",trees).commit();refresh();return true;}
            if(code==SEND_TREE){Uri uri=data.getData();if(uri==null)return true;persist(uri,data.getFlags());a.runWork("读取所选文件夹清单…",()->{List<Source> sources=new ArrayList<>();walk(new DocumentStore(a.getContentResolver(),uri),"",sources);a.runOnUiThread(()->selectSources(sources,"files"));});}
            else{List<Source> sources=new ArrayList<>();if(data.getClipData()!=null){for(int i=0;i<data.getClipData().getItemCount();i++)addUri(sources,data.getClipData().getItemAt(i).getUri(),data.getFlags());}else if(data.getData()!=null)addUri(sources,data.getData(),data.getFlags());selectSources(sources,pendingMode);}
        }catch(Exception ex){a.alert(ex.getMessage());}return true;
    }
    void persist(Uri uri,int flags){try{a.getContentResolver().takePersistableUriPermission(uri,flags&Intent.FLAG_GRANT_READ_URI_PERMISSION);}catch(SecurityException ignored){/* Content is staged while the transient grant is still held. */}}
    static class Source {final String path;final Uri uri;Source(String p,Uri u){path=p;uri=u;}}
    void addUri(List<Source> list,Uri uri,int flags)throws IOException {persist(uri,flags);String name=null;try(Cursor cursor=a.getContentResolver().query(uri,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(cursor!=null&&cursor.moveToFirst())name=cursor.getString(0);}if(name==null)throw new IOException("无法读取文件名");list.add(new Source(TransferEngine.path(name),uri));}
    void walk(DocumentStore store,String prefix,List<Source> files)throws IOException {for(DirectoryEngine.Entry entry:store.list(prefix)){TransferEngine.path(entry.path);if(entry.directory)walk(store,entry.path,files);else{files.add(new Source(entry.path,store.find(entry.path,false,false)));if(files.size()>Engine.MAX_FILES)throw new IOException("请将目录缩小到 20,000 个文件以内");}}}
    void sendNotes(){if(!a.requireTarget())return;a.runWork("读取当前库可回传的笔记与附件…",()->{a.ledger().requireReady();List<Source> files=new ArrayList<>();walk(a.store(),"",files);a.runOnUiThread(()->selectSources(files,"notes"));});}
    void selectSources(List<Source> sources,String mode){
        if(a.destroyed)return;if(sources.isEmpty()){a.alert("没有可发送的普通文件。");return;}
        String[] labels=new String[sources.size()];boolean[] checked=new boolean[sources.size()];for(int i=0;i<sources.size();i++){labels[i]=sources.get(i).path;checked[i]=mode.equals("files");}
        a.dialog().setTitle(mode.equals("notes")?"选择平板笔记与附件回传":"选择发送内容").setMultiChoiceItems(labels,checked,(d,w,v)->checked[w]=v).setNegativeButton("取消",null).setPositiveButton("下一步",(d,w)->{List<Source> selected=new ArrayList<>();for(int i=0;i<sources.size();i++)if(checked[i])selected.add(sources.get(i));if(selected.isEmpty()){a.alert("至少选择一个文件");return;}EditText prefix=new EditText(a);a.styleInput(prefix);prefix.setSingleLine(true);prefix.setHint("库内子目录，留空为根目录");
            a.dialog().setTitle(mode.equals("notes")?"电脑 Obsidian 接收路径":"电脑收件箱内的子目录").setMessage((mode.equals("notes")?"目标是电脑当前 Obsidian 主库，留空放到库根目录。":"目标由电脑确认，留空放到普通收件箱。")+"保留所选文件的相对目录；同名内容变化另存。发送前会暂存内容，电脑仍需确认本批接收。").setView(prefix).setNegativeButton("取消",null).setPositiveButton("建立发送任务",(x,y)->createUpload(selected,mode,prefix.getText().toString().trim())).show();}).show();
    }
    JSONObject base(String direction,String mode)throws Exception {LanClient lan=a.client();return new JSONObject().put("id",UUID.randomUUID().toString().replace("-","")).put("direction",direction).put("mode",mode).put("pin",lan.pin).put("owner",TransferEngine.hash(lan.token.getBytes("UTF-8"))).put("state","waiting").put("created",System.currentTimeMillis());}
    void createUpload(List<Source> sources,String mode,String prefix){
        try{if(!prefix.isEmpty())prefix=TransferEngine.path(prefix);ensure();JSONObject job=base("up",mode).put("prefix",prefix);JSONArray files=new JSONArray();Set<String> paths=new HashSet<>();for(Source source:sources){if(!paths.add(source.path.toLowerCase(Locale.ROOT)))throw new IOException("所选文件重名，请分批");files.put(new JSONObject().put("path",source.path).put("uri",source.uri.toString()).put("prepared",false));}job.put("files",files);TransferEngine.save(TransferEngine.folder(root,job.getString("id")),job);runQueue(job.optString("id"));}catch(Exception e){a.alert(e.getMessage());}
    }
    void fetch(){String tree=a.prefs.getString("fileTree","");if(tree.isEmpty()){a.alert("先选择普通文件接收位置。");return;}a.runWork("获取电脑普通文件清单…",()->{TransferClient client=new TransferClient(a.client(),a::log);JSONObject listing=client.listing();JSONArray files=listing.getJSONArray("files");TransferEngine.validate(files);a.runOnUiThread(()->{if(a.destroyed)return;try{String[] names=new String[files.length()];boolean[] chosen=new boolean[files.length()];Arrays.fill(chosen,true);for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);names[i]=f.getString("path")+" · "+String.format(Locale.ROOT,"%.1f MiB",f.getLong("size")/1048576.0);}a.dialog().setTitle("选择接收内容").setMultiChoiceItems(names,chosen,(d,w,v)->chosen[w]=v).setNegativeButton("取消",null).setPositiveButton("确认位置并加入队列",(d,w)->{try{JSONArray selected=new JSONArray();for(int i=0;i<files.length();i++)if(chosen[i])selected.put(files.getJSONObject(i));TransferEngine.validate(selected);a.dialog().setTitle("确认普通文件接收").setMessage("保存到："+LibraryUi.label(tree)+"\n"+selected.length()+" 个文件。相同内容跳过；同名不同内容另存。ZIP 原样保存。").setNegativeButton("取消",null).setPositiveButton("开始接收",(q,r)->{try{ensure();JSONObject job=base("down","files").put("offer",listing.getString("id")).put("tree",tree).put("files",selected);TransferEngine.save(TransferEngine.folder(root,job.getString("id")),job);runQueue(job.getString("id"));}catch(Exception e){a.alert(e.getMessage());}}).show();}catch(Exception e){a.alert(e.getMessage());}}).show();}catch(Exception e){a.alert(e.getMessage());}});});}
    void prepare(JSONObject job,File folder,TransferClient client)throws Exception {
        JSONArray files=job.getJSONArray("files");long total=0;
        for(int i=0;i<files.length();i++){client.check();JSONObject item=files.getJSONObject(i);if(item.optBoolean("prepared")){total+=item.getLong("size");continue;}TransferEngine.space(folder,TransferEngine.CHUNK);
            File source=new File(folder,UUID.randomUUID()+".source");MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;Uri uri=Uri.parse(item.getString("uri"));
            try(InputStream input=a.getContentResolver().openInputStream(uri);FileOutputStream output=new FileOutputStream(source)){if(input==null)throw new IOException("来源授权失效，请重新选择文件");byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1){client.check();size+=n;if(size>TransferEngine.MAX_FILE||total+size>TransferEngine.MAX_BATCH)throw new IOException("超过单文件 8 GiB / 每批 64 GiB 上限");TransferEngine.space(folder,n);output.write(buffer,0,n);digest.update(buffer,0,n);}output.getFD().sync();}
            total+=size;item.put("size",size).put("sha256",Engine.hex(digest.digest())).put("local",source.getName()).put("prepared",true);TransferEngine.save(folder,job);a.log("暂存完成 "+(i+1)+"/"+files.length()+" · "+item.getString("path"));
        }
    }
    void download(JSONObject job,File folder,TransferClient client)throws Exception {
        JSONArray files=job.getJSONArray("files");TransferEngine.validate(files);DocumentStore store=new DocumentStore(a.getContentResolver(),Uri.parse(job.getString("tree")));
        TransferLanding.Tree target=new TransferLanding.Tree(){
            TransferLanding.Entry entry(Uri uri,String parent)throws IOException {if(uri==null)return null;String name=store.displayName(uri);return new TransferLanding.Entry(uri.toString(),(parent.isEmpty()?"":parent+"/")+name,store.size(uri),android.provider.DocumentsContract.Document.MIME_TYPE_DIR.equals(store.type(uri)));}
            public void ensureParent(String parent)throws IOException {store.refresh();if(!parent.isEmpty())store.find(parent,true,true);}
            public TransferLanding.Entry find(String path)throws IOException {store.refresh();return entry(store.any(path),DirectoryEngine.parent(path));}
            public TransferLanding.Entry create(String path)throws IOException {return entry(store.createTemporary(path),DirectoryEngine.parent(path));}
            public InputStream read(TransferLanding.Entry entry)throws IOException {try{InputStream input=a.getContentResolver().openInputStream(Uri.parse(entry.id));if(input==null)throw new IOException("接收文档无法读取");return input;}catch(RuntimeException ex){throw new IOException("接收目录授权失效或无法读取",ex);}}
            public OutputStream rewrite(TransferLanding.Entry entry)throws IOException {try{OutputStream output=a.getContentResolver().openOutputStream(Uri.parse(entry.id),"wt");if(output==null)throw new IOException("接收暂存无法写入");return output;}catch(RuntimeException ex){throw new IOException("接收暂存写入被系统拒绝",ex);}}
            public boolean canRename(TransferLanding.Entry entry)throws IOException {return (store.flags(Uri.parse(entry.id))&android.provider.DocumentsContract.Document.FLAG_SUPPORTS_RENAME)!=0;}
            public TransferLanding.Entry rename(TransferLanding.Entry entry,String path)throws IOException {return entry(store.renameNew(Uri.parse(entry.id),path),DirectoryEngine.parent(path));}
        };
        for(int i=0;i<files.length();i++){client.check();JSONObject item=files.getJSONObject(i);if(item.optBoolean("done"))continue;File complete=client.download(job,folder,i);TransferLanding.publish(target,complete,item,client,()->TransferEngine.save(folder,job));}
    }
    List<JSONObject> queue()throws IOException {
        List<JSONObject> result=new ArrayList<>();if(!root.exists())return result;File[] folders=root.listFiles(File::isDirectory);if(folders==null)throw new IOException("无法读取任务目录");for(File folder:folders)if(folder.getName().matches("[0-9a-f]{32}")&&new File(folder,"job.json").isFile())try{JSONObject job=TransferEngine.load(folder);if(job.optString("id").equals(folder.getName()))result.add(job);}catch(IOException e){a.log("一份任务记录无法读取，保留供检查");}result.sort(Comparator.comparingLong(j->j.optLong("created")));return result;
    }
    void runQueue(String only){
        a.runWork("开始处理传输队列…",()->{
            for(JSONObject job:queue()){
                if(only!=null&&!only.equals(job.optString("id")))continue;if(job.optString("state").equals("done")||job.optString("state").equals("cancelled"))continue;
                File folder=TransferEngine.folder(root,job.getString("id"));TransferClient client=new TransferClient(a.client(),a::log);active=client;
                try{if(!client.lan.pin.equals(job.getString("pin"))||!TransferEngine.hash(client.lan.token.getBytes("UTF-8")).equals(job.getString("owner")))throw new IOException("电脑身份或配对变化，请为这台电脑重新建立任务");job.put("state","running");TransferEngine.save(folder,job);
                    if(job.getString("direction").equals("up")){prepare(job,folder,client);client.upload(job,folder);}else download(job,folder,client);
                    job.put("state","done").put("result","全部校验通过并保存");TransferEngine.save(folder,job);a.result("本批完成："+job.getJSONArray("files").length()+" 个文件；可在任务详情查看保存路径。");
                }catch(Exception ex){job.put("state",ex instanceof TransferEngine.Paused?"paused":"waiting").put("result",ex.getMessage()==null?ex.getClass().getSimpleName():ex.getMessage());TransferEngine.save(folder,job);a.result("任务保留："+job.optString("result"));if(ex instanceof TransferEngine.Paused)break;}
                finally{active=null;}
            }
        },this::refresh);
    }
    void refresh(){if(destination!=null){String tree=a.prefs.getString("fileTree","");destination.setText(tree.isEmpty()?"尚未选择普通文件接收位置":"接收位置："+LibraryUi.label(tree));}if(tasks!=null)try{List<JSONObject> queue=queue();StringBuilder text=new StringBuilder();int waiting=0;for(JSONObject job:queue){String state=job.optString("state");if(!state.equals("done")&&!state.equals("cancelled"))waiting++;}text.append(queue.size()).append(" 个任务 · ").append(waiting).append(" 个未完成");for(JSONObject job:queue.subList(Math.max(0,queue.size()-5),queue.size()))text.append("\n").append(job.optString("direction").equals("up")?"发送":"接收").append(" · ").append(label(job.optString("state"))).append(" · ").append(job.optJSONArray("files").length()).append(" 个文件");tasks.setText(text.toString());}catch(Exception e){tasks.setText(e.getMessage());}}
    static String label(String state){switch(state){case "done":return "完成";case "cancelled":return "已取消";case "paused":return "已暂停";case "running":return "可恢复";default:return "等待 / 可继续";}}
    void selectTask(){try{List<JSONObject> queue=queue();if(queue.isEmpty()){a.alert("还没有传输任务");return;}String[] names=new String[queue.size()];for(int i=0;i<queue.size();i++){JSONObject job=queue.get(i);names[i]=label(job.optString("state"))+" · "+job.getJSONArray("files").length()+" 个文件 · "+job.getString("id").substring(0,8);}a.dialog().setTitle("任务与结果").setItems(names,(d,w)->{JSONObject job=queue.get(w);StringBuilder detail=new StringBuilder(job.optString("result","已保存任务，等待处理"));try{JSONArray files=job.getJSONArray("files");for(int i=0;i<Math.min(files.length(),40);i++){JSONObject f=files.getJSONObject(i);detail.append("\n").append(f.optString("path")).append(" → ").append(f.optString("saved","待完成"));}a.dialog().setTitle(names[w]).setMessage(detail.toString()).setNegativeButton("关闭",null).setNeutralButton("取消任务（保留文件）",(q,r)->{try{job.put("state","cancelled");TransferEngine.save(TransferEngine.folder(root,job.getString("id")),job);refresh();}catch(Exception e){a.alert(e.getMessage());}}).setPositiveButton("继续",(q,r)->runQueue(job.optString("id"))).show();}catch(Exception e){a.alert(e.getMessage());}}).setNegativeButton("关闭",null).show();}catch(Exception e){a.alert(e.getMessage());}}
    void shared(List<Uri> uris){try{List<Source> sources=new ArrayList<>();for(Uri uri:uris)addUri(sources,uri,Intent.FLAG_GRANT_READ_URI_PERMISSION);pendingMode="files";a.currentPage=5;if(!a.busy)a.layout.showPage(5);selectSources(sources,"files");}catch(Exception e){a.alert(e.getMessage());}}
}
