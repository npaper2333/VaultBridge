package io.vaultbridge.app;

import android.content.*;
import android.database.Cursor;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.DocumentsContract;
import android.text.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.security.*;
import java.text.DateFormat;
import java.util.*;
import java.util.concurrent.*;
import org.json.JSONObject;

/** The file page reads only completed download metadata and the original persisted SAF grants. */
final class RecentFilesUi {
    static final String EXTRA_ID="receivedId";
    static final int PAGE_SIZE=30,SCAN_JOBS=8;
    final MainActivity a;
    final RecentFiles catalog;
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    final Handler handler=new Handler(Looper.getMainLooper());
    final SourceThumbnailLoader thumbnails;
    final Map<String,Long> scanned=new HashMap<>();
    volatile boolean closed;
    volatile int request;
    int page;
    String search="",kind="all",indexMessage="";
    boolean viewed,opening;
    TextView summary;
    LinearLayout rows;
    Button receivedButton,viewedButton,filterButton,previous,next,loadMore;
    Runnable searchLater;
    RecentFilesUi(MainActivity activity){a=activity;catalog=new RecentFiles(a.getFilesDir());thumbnails=new SourceThumbnailLoader(a);}
    void post(Runnable task){handler.post(()->{if(!closed&&!a.destroyed)task.run();});}
    Button button(String title,Runnable action){Button button=new Button(a);button.setText(title);button.setAllCaps(false);button.setTextSize(14);button.setStateListAnimator(null);UiChrome.button(button,a.theme,false);button.setOnClickListener(v->action.run());return button;}
    void build(LinearLayout pageView){
        a.content=pageView;LinearLayout card=a.card("接收的文件");LinearLayout tabs=a.row(card);receivedButton=button("最近接收",()->{viewed=false;page=0;refresh(false);});viewedButton=button("最近查看",()->{viewed=true;page=0;refresh(false);});tabs.addView(receivedButton,new LinearLayout.LayoutParams(0,-2,1));tabs.addView(viewedButton,new LinearLayout.LayoutParams(0,-2,1));
        EditText input=new EditText(a);input.setSingleLine(true);input.setHint("搜索文件名或相对目录");a.styleInput(input);input.setText(search);card.addView(input);input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int count,int after){}public void onTextChanged(CharSequence s,int st,int before,int count){}public void afterTextChanged(Editable value){search=value.toString();page=0;if(searchLater!=null)handler.removeCallbacks(searchLater);searchLater=()->refresh(false);handler.postDelayed(searchLater,250);}});
        LinearLayout tools=a.row(card);filterButton=button("类型：全部",this::chooseType);tools.addView(filterButton,new LinearLayout.LayoutParams(0,-2,1));tools.addView(button("刷新",this::refresh),new LinearLayout.LayoutParams(-2,-2));
        summary=a.text("正在读取本应用的接收记录…",12,false);summary.setTextColor(a.theme.muted);card.addView(summary);rows=new LinearLayout(a);rows.setOrientation(LinearLayout.VERTICAL);card.addView(rows);
        LinearLayout paging=a.row(card);previous=button("上一页",()->{if(page>0)page--;refresh(false);});next=button("下一页",()->{page++;refresh(false);});paging.addView(previous,new LinearLayout.LayoutParams(0,-2,1));paging.addView(next,new LinearLayout.LayoutParams(0,-2,1));loadMore=button("继续载入旧任务",this::refresh);card.addView(loadMore);loadMore.setVisibility(View.GONE);
        card.addView(button("接收新文件",()->a.layout.showPage(5)));a.notice(card,"只列本应用普通接收完成的文件，原文件不会移动。旧记录点开时核对；权限或位置变化会明确提示。");if(a.currentPage==6)refresh();
    }
    void chooseType(){String[] names={"全部","图片","PDF","Markdown","文本","Office","压缩包","其他"},types={"all","image","pdf","markdown","text","office","archive","other"};a.dialog().setTitle("筛选文件类型").setItems(names,(d,w)->{kind=types[w];page=0;refresh(false);}).setNegativeButton("取消",null).show();}
    static String typeName(String kind){switch(kind){case "image":return "图片";case "pdf":return "PDF";case "markdown":return "Markdown";case "text":return "文本";case "office":return "Office";case "archive":return "压缩包";case "other":return "其他";default:return "全部";}}
    static String stateName(String state){switch(state){case "permission":return "目录授权已失效，请重新授权原位置";case "missing":return "文件已移动、改名或不存在";case "changed":return "文件内容或身份已变化，不能按旧记录打开";case "error":return "提供器暂不可用，请刷新重试";case "pending":return "旧记录 · 点击核对后打开";default:return "只读打开";}}
    void refresh(){refresh(true);}
    void refresh(boolean scan){
        if(closed||summary==null)return;final int generation=++request,requestedPage=page;final String query=search,filter=kind;final boolean recentViewed=viewed;summary.setText("正在刷新文件目录…");
        worker.submit(()->{
            try{
                if(generation!=request||closed)return;
                int remaining=scan?scanJobs():pendingJobs();RecentFiles.Page result=catalog.page(query,filter,recentViewed,requestedPage,PAGE_SIZE);Map<String,String> states=new HashMap<>();Map<String,Uri> cache=new HashMap<>();
                for(RecentFiles.Entry entry:result.entries){if(closed||Thread.currentThread().isInterrupted()||generation!=request)return;try{String status=inspect(a,entry,false,()->{if(closed||Thread.currentThread().isInterrupted())throw new IOException("已离开文件页");},cache).state;states.put(entry.id,status);}catch(IOException|RuntimeException error){states.put(entry.id,"error");}}
                List<RecentFiles.Entry> visible=new ArrayList<>();for(RecentFiles.Entry entry:result.entries)visible.add(entry.change(entry.uri,states.get(entry.id),entry.viewedAt));final RecentFiles.Page display=new RecentFiles.Page(visible,result.total,result.page,result.hasNext);post(()->{if(generation!=request)return;render(display,remaining);});
            }catch(IOException|RuntimeException error){post(()->{if(generation==request){summary.setText("文件目录未完成刷新："+safe(error));rows.removeAllViews();loadMore.setVisibility(View.VISIBLE);loadMore.setText("重试读取任务记录");}});}
        });
    }
    static String safe(Exception error){return error instanceof IOException&&error.getMessage()!=null?error.getMessage():"文件提供器或目录记录暂不可用";}
    List<File> jobFiles(){File[] folders=new File(a.getFilesDir(),"transfers").listFiles(File::isDirectory);List<File> files=new ArrayList<>();if(folders!=null)for(File folder:folders)if(folder.getName().matches("[0-9a-f]{32}")){File record=new File(folder,"job.json");if(record.isFile())files.add(record);}files.sort((x,y)->Long.compare(y.lastModified(),x.lastModified()));return files;}
    int pendingJobs(){int count=0;for(File file:jobFiles())if(!Long.valueOf(file.lastModified()).equals(scanned.get(file.getParentFile().getName())))count++;return count;}
    int scanJobs()throws IOException {
        List<File> jobs=jobFiles();int processed=0,remaining=0;String warning="";
        for(File file:jobs){if(closed||Thread.currentThread().isInterrupted())return remaining;String id=file.getParentFile().getName();long modified=file.lastModified();if(Long.valueOf(modified).equals(scanned.get(id)))continue;if(processed>=SCAN_JOBS){remaining++;continue;}
            try{JSONObject job=TransferEngine.load(file.getParentFile());if(!id.equals(job.optString("id")))throw new IOException("一份任务编号与目录不符");catalog.indexJob(job);scanned.put(id,modified);}catch(IOException ex){warning=safe(ex);if(warning.contains("容量"))remaining++;else scanned.put(id,modified);}processed++;
        }
        if(!warning.isEmpty())indexMessage="部分旧任务不可读取，原记录保留："+warning;return remaining;
    }
    void render(RecentFiles.Page result,int remaining){
        rows.removeAllViews();receivedButton.setText(viewed?"最近接收":"● 最近接收");viewedButton.setText(viewed?"● 最近查看":"最近查看");filterButton.setText("类型："+typeName(kind));String message=result.total+" 个匹配文件 · 第 "+(result.page+1)+" 页";
        if(remaining>0)message+=" · 还有 "+remaining+" 份任务待载入";if(!indexMessage.isEmpty())message+="\n"+indexMessage;summary.setText(message);previous.setEnabled(page>0);next.setEnabled(result.hasNext);loadMore.setVisibility(remaining>0?View.VISIBLE:View.GONE);loadMore.setText("继续载入旧任务（"+remaining+"）");
        if(result.entries.isEmpty())rows.addView(a.text(viewed?"还没有成功查看的接收文件。":"当前没有匹配的已接收文件。",14,false));
        for(RecentFiles.Entry entry:result.entries){
            LinearLayout row=new LinearLayout(a);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setPadding(a.dp(12),a.dp(9),a.dp(12),a.dp(9));GradientDrawable background=new GradientDrawable();background.setColor(a.theme.wash);background.setCornerRadius(a.dp(8));background.setStroke(a.dp(1),a.theme.line);row.setBackground(background);LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.topMargin=a.dp(7);rows.addView(row,layout);LinearLayout copy=new LinearLayout(a);copy.setOrientation(LinearLayout.VERTICAL);row.addView(copy,new LinearLayout.LayoutParams(0,-2,1));
            TextView name=a.text(DirectoryEngine.name(entry.path),15,true);name.setMaxLines(2);copy.addView(name);String time=entry.receivedAt>0?DateFormat.getDateTimeInstance(DateFormat.SHORT,DateFormat.SHORT).format(new Date(viewed?entry.viewedAt:entry.receivedAt)):"早期记录";String detail=typeName(entry.kind)+" · "+String.format(Locale.ROOT,"%.1f MiB",entry.size/1048576.0)+" · "+(entry.estimatedTime&&!viewed?"任务时间 ":"")+time+"\n"+entry.path+"\n"+stateName(entry.state);TextView meta=a.text(detail,11,false);meta.setTextColor(a.theme.muted);copy.addView(meta);row.setFocusable(true);row.setClickable(true);row.setOnClickListener(v->open(entry));if(entry.kind.equals("image")){ImageButton thumb=new ImageButton(a);thumb.setContentDescription("打开接收图片："+entry.path);thumb.setBackground(UiChrome.rounded(a,a.theme.surface,7,a.theme.line));thumb.setPadding(a.dp(2),a.dp(2),a.dp(2),a.dp(2));LinearLayout.LayoutParams size=new LinearLayout.LayoutParams(a.dp(62),a.dp(62));size.leftMargin=a.dp(9);row.addView(thumb,size);thumbnails.received(thumb,entry);thumb.setOnClickListener(v->open(entry));}
        }
    }
    void open(RecentFiles.Entry entry){
        if(closed||opening)return;opening=true;summary.setText(entry.legacy?"正在原接收位置核对旧文件，完成后打开…":"正在检查原接收位置…");worker.submit(()->{try{RecentFiles.Entry ready=resolve(a,entry.id,()->{if(closed||Thread.currentThread().isInterrupted())throw new IOException("打开已取消");});post(()->{Intent intent=new Intent(a,PreviewActivity.class).putExtra(EXTRA_ID,ready.id).putExtra("appearanceDark",a.theme.dark).putExtra("appearanceAccent",a.theme.accent);a.startActivity(intent);refresh(false);});}
            catch(IOException|RuntimeException error){post(()->{summary.setText(safe(error));a.dialog().setTitle("无法打开这个接收文件").setMessage(safe(error)+"\n仅在原接收目录核对，不会搜索其他位置或改动文件。").setNegativeButton("关闭",null).setPositiveButton("去文件传输页",(d,w)->a.layout.showPage(5)).show();refresh(false);});}finally{post(()->opening=false);}});
    }
    void completed(JSONObject job){
        try{catalog.indexJob(job);post(()->{if(a.currentPage==6)refresh(false);});}catch(IOException|RuntimeException error){post(()->{indexMessage="文件已保存，但文件目录记录未更新："+safe(error);if(a.currentPage==6)refresh(false);});}
    }
    static final class Check {final String state;final Uri uri;Check(String state,Uri uri){this.state=state;this.uri=uri;}}
    static boolean permission(Context context,Uri tree){for(UriPermission grant:context.getContentResolver().getPersistedUriPermissions())if(grant.isReadPermission()&&grant.getUri().equals(tree))return true;return false;}
    static Uri find(Context context,RecentFiles.Entry entry,TransferEngine.Control control,Map<String,Uri> cache)throws IOException {
        Uri tree=Uri.parse(entry.tree),parent=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));String prefix="";String[] parts=entry.path.split("/");
        for(int i=0;i<parts.length;i++){
            if(control!=null)control.check();String before=prefix;prefix+=(prefix.isEmpty()?"":"/")+parts[i];String key=entry.tree+"|"+prefix.toLowerCase(Locale.ROOT);if(cache.containsKey(key)){parent=cache.get(key);if(parent==null)return null;if(i<parts.length-1&&!cache.containsKey(key+"\u0000dir"))return null;continue;}
            Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent));Uri found=null;String mime=null;int count=0;
            try(Cursor cursor=context.getContentResolver().query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
                if(cursor==null)throw new IOException("无法读取原接收目录");Set<String> names=new HashSet<>();while(cursor.moveToNext()){if(control!=null)control.check();if(++count>Engine.MAX_FILES)throw new IOException("原接收目录条目过多，无法安全定位；请使用更小的接收目录");String name=cursor.getString(1);if(!names.add(name.toLowerCase(Locale.ROOT)))throw new IOException("原接收目录存在大小写重名");Uri child=DocumentsContract.buildDocumentUriUsingTree(tree,cursor.getString(0));String childKey=entry.tree+"|"+((before.isEmpty()?"":before+"/")+name).toLowerCase(Locale.ROOT);if(cache.size()<Engine.MAX_FILES*2){cache.put(childKey,child);if(DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(2)))cache.put(childKey+"\u0000dir",child);}if(parts[i].equalsIgnoreCase(name)){found=child;mime=cursor.getString(2);}}
            }
            if(found==null){cache.put(key,null);return null;}boolean directory=DocumentsContract.Document.MIME_TYPE_DIR.equals(mime);if(directory!=(i<parts.length-1))return null;cache.put(key,found);if(directory)cache.put(key+"\u0000dir",found);parent=found;
        }
        return parent;
    }
    static Check inspect(Context context,RecentFiles.Entry entry,boolean verifyLegacy,TransferEngine.Control control,Map<String,Uri> cache)throws IOException {
        try{
            if(control!=null)control.check();Uri tree=Uri.parse(entry.tree);if(UpdateProvider.AUTHORITY.equals(tree.getAuthority()))return new Check("error",null);if(!DocumentsContract.isTreeUri(tree)||!permission(context,tree))return new Check("permission",null);Uri actual=find(context,entry,control,cache);if(actual==null)return new Check("missing",null);
            if(!entry.uri.isEmpty()){
                Uri recorded=Uri.parse(entry.uri);if(!DocumentsContract.isTreeUri(recorded)||!Objects.equals(recorded.getAuthority(),tree.getAuthority())||!DocumentsContract.getTreeDocumentId(recorded).equals(DocumentsContract.getTreeDocumentId(tree))||!DocumentsContract.getDocumentId(recorded).equals(DocumentsContract.getDocumentId(actual)))return new Check("changed",null);
            }
            long size=-1;try(Cursor cursor=context.getContentResolver().query(actual,new String[]{DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){if(cursor==null||!cursor.moveToFirst())return new Check("missing",null);if(DocumentsContract.Document.MIME_TYPE_DIR.equals(cursor.getString(1)))return new Check("missing",null);if(!cursor.isNull(0))size=cursor.getLong(0);}
            if(size>=0&&size!=entry.size)return new Check("changed",null);if(entry.legacy){if(!verifyLegacy)return new Check("pending",actual);if(!hash(context,actual,entry.size,entry.sha256,control))return new Check("changed",null);}return new Check("ready",actual);
        }catch(SecurityException ex){return new Check("permission",null);}catch(IllegalArgumentException ex){return new Check("error",null);}
    }
    static boolean hash(Context context,Uri uri,long size,String expected,TransferEngine.Control control)throws IOException {
        try(InputStream input=context.getContentResolver().openInputStream(uri)){
            if(input==null)throw new IOException("无法读取原接收文件");return RecentFiles.verified(input,size,expected,control);
        }
    }
    static RecentFiles.Entry resolve(Context context,String id,TransferEngine.Control control)throws IOException {
        RecentFiles catalog=new RecentFiles(context.getFilesDir());RecentFiles.Entry entry=catalog.get(id);Check check;
        try{check=inspect(context,entry,true,control,new HashMap<>());}catch(IOException|RuntimeException error){catalog.states(Collections.singletonMap(id,"error"));throw new IOException("原接收文件暂无法读取，请刷新后重试",error);}
        if(!"ready".equals(check.state)||check.uri==null){catalog.states(Collections.singletonMap(id,check.state));throw new IOException(stateName(check.state));}
        if(entry.legacy)catalog.backfill(id,check.uri.toString());else catalog.states(Collections.singletonMap(id,"ready"));return catalog.get(id);
    }
    static void rememberViewed(Context context,String id)throws IOException {new RecentFiles(context.getFilesDir()).viewed(id,System.currentTimeMillis());}
    void close(){closed=true;request++;if(searchLater!=null)handler.removeCallbacks(searchLater);worker.shutdownNow();thumbnails.close();}
}
