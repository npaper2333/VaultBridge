package io.vaultbridge.app;

import android.app.*;
import android.content.*;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Full native selection page; list rows are virtualised and preview never changes a checkbox. */
final class SendPickerUi {
    final TransferUi owner;final MainActivity a;final List<TransferUi.Source> sources;final String mode,session,identity;
    final SendPickerModel model;final Dialog dialog;final ExecutorService worker=Executors.newSingleThreadExecutor();final SourceThumbnailLoader thumbnails;
    final Handler main=new Handler(Looper.getMainLooper());final Rows adapter=new Rows();
    volatile boolean closed;volatile int generation;volatile InputStream activeInput;volatile TransferClient comparing;Future<?> task;
    EditText target,search;TextView status,count,scope;Button next,compare,filter,selectNew;SentDisplayControl displaySwitch;List<SendPickerModel.Item> shown=new ArrayList<>();String query="",kind="all";boolean binding,networkMonitored;
    android.net.ConnectivityManager networks;android.net.ConnectivityManager.NetworkCallback networkCallback;
    final android.database.ContentObserver sourceChanges=new android.database.ContentObserver(main){@Override public void onChange(boolean selfChange){if(!closed){cancel();model.invalidate();thumbnails.invalidateSources();if(status!=null)status.setText("来源提供器报告内容或位置变化，旧核验已失效。请重新对比；勾选保留。");render();}}};
    final android.content.SharedPreferences.OnSharedPreferenceChangeListener preferenceChanged=this::preferenceChanged;
    void preferenceChanged(android.content.SharedPreferences prefs,String key){if(!closed&&"sentDisplay".equals(key)&&model.hidden!=prefs.getString(key,"marked").equals("hidden"))setHidden(prefs.getString(key,"marked").equals("hidden"));}
    void observeSources(){try{if(mode.equals("notes")){String tree=a.prefs.getString("tree","");if(!tree.isEmpty())a.getContentResolver().registerContentObserver(Uri.parse(tree),true,sourceChanges);}else{Set<String> seen=new HashSet<>();for(TransferUi.Source source:sources)if(seen.size()<128&&seen.add(source.uri.toString()))try{a.getContentResolver().registerContentObserver(source.uri,false,sourceChanges);}catch(RuntimeException ignored){}}}catch(RuntimeException ignored){/* Final source rehash and staged-byte checks remain mandatory without provider notifications. */}}
    SendPickerUi(TransferUi owner,List<TransferUi.Source> sources,String mode)throws IOException {
        this.owner=owner;this.a=owner.a;this.sources=new ArrayList<>(sources);this.mode=mode;identity=binding();List<SendPickerModel.Item> items=new ArrayList<>();for(int i=0;i<sources.size();i++)items.add(new SendPickerModel.Item(Integer.toString(i),sources.get(i).path,mode.equals("files")));model=new SendPickerModel(items,identity);model.hidden=a.prefs.getString("sentDisplay","marked").equals("hidden");session=SourcePreviewSession.create(sources);thumbnails=new SourceThumbnailLoader(a);dialog=new Dialog(a,UiChrome.themeResource(a,a.theme.dark,false));
    }
    String binding(){return a.prefs.getString("pin","")+"|"+a.prefs.getString("token","")+"|"+mode+"|"+a.prefs.getString("vaultId","")+"|"+(mode.equals("notes")?a.prefs.getString("tree",""):"");}
    int dp(int n){return a.dp(n);}TextView text(String value,int size,boolean bold){TextView v=new TextView(a);v.setText(value);v.setTextSize(size);v.setTextColor(a.theme.ink);if(bold)v.setTypeface(null,Typeface.BOLD);return v;}
    Button button(String value,Runnable action,boolean primary){Button b=new Button(a);b.setText(value);b.setTextSize(12);b.setMinWidth(0);b.setMinHeight(dp(40));UiChrome.button(b,a.theme,primary);b.setOnClickListener(v->action.run());return b;}
    LinearLayout column(){LinearLayout v=new LinearLayout(a);v.setOrientation(LinearLayout.VERTICAL);return v;}
    LinearLayout row(){LinearLayout v=new LinearLayout(a);v.setGravity(Gravity.CENTER_VERTICAL);return v;}
    void explain(LinearLayout parent,String value,boolean required){if(required||a.prefs.getBoolean("showHelp",true)){TextView v=text(value,12,false);v.setTextColor(a.theme.muted);v.setPadding(0,dp(8),0,dp(8));parent.addView(v);}}
    void show(){
        LinearLayout root=column();root.setBackgroundColor(a.theme.canvas);root.setPadding(dp(16),dp(12),dp(16),dp(10));
        if(Build.VERSION.SDK_INT>=35)root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left+dp(16),bars.top+dp(12),bars.right+dp(16),bars.bottom+dp(10));return insets;});
        LinearLayout title=row();title.addView(text(mode.equals("notes")?a.deviceName()+"笔记与附件回传":"选择发送文件",19,true),new LinearLayout.LayoutParams(0,-2,1));title.addView(button("关闭",dialog::dismiss,false));root.addView(title);
        ListView list=new ListView(a);list.setDivider(null);UiChrome.scrolling(list,a.theme);list.setBackgroundColor(a.theme.canvas);LinearLayout header=column();
        explain(header,"电脑："+a.prefs.getString("computerName","已配对电脑")+"\n"+(mode.equals("notes")?"主库："+a.prefs.getString("vaultName","当前主库"):"目标：当前电脑收件位置，仍需逐批确认"),true);
        target=new EditText(a);a.styleInput(target);target.setSingleLine(true);target.setHint(mode.equals("notes")?"库内子目录，留空为根目录":"收件位置内子目录，留空为根目录");header.addView(target);explain(header,"保留原有相对目录；修改目标后必须重新对比。",true);
        LinearLayout comparison=row();compare=button(mode.equals("notes")?"对比电脑分享范围":"核验历史接收目标",this::compare,false);comparison.addView(compare,new LinearLayout.LayoutParams(0,-2,1));comparison.addView(button("取消对比",()->{cancel();model.invalidate();status.setText("已取消对比。选择保留，历史待核验；不隐藏未知项目。");render();},false));header.addView(comparison);
        search=new EditText(a);a.styleInput(search);search.setSingleLine(true);search.setHint("搜索文件名或相对路径");header.addView(search);
        LinearLayout tools=row();displaySwitch=new SentDisplayControl(a,a.theme,model.hidden,this::setHidden);tools.addView(displaySwitch,new LinearLayout.LayoutParams(0,dp(44),1));filter=button("全部类型",this::type,false);tools.addView(filter);header.addView(tools);
        LinearLayout bulk=row();selectNew=button("选确认缺少",()->{model.selectMissing();render();},false);bulk.addView(selectNew,new LinearLayout.LayoutParams(0,-2,1));bulk.addView(button("全不选",()->{for(SendPickerModel.Item item:model.items)item.selected=false;render();},false),new LinearLayout.LayoutParams(0,-2,1));header.addView(bulk);
        status=text("尚未对比。历史发送不表示电脑当前仍有相同内容。",12,false);status.setTextColor(a.theme.muted);status.setPadding(0,dp(10),0,dp(8));header.addView(status);scope=text("只检查电脑授权范围，其他位置未检查。",11,false);scope.setTextColor(a.theme.muted);header.addView(scope);list.addHeaderView(header,null,false);list.setAdapter(adapter);root.addView(list,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout footer=row();count=text("",12,false);footer.addView(count,new LinearLayout.LayoutParams(0,-2,1));next=button("下一步",this::summary,true);footer.addView(next);root.addView(footer);dialog.setContentView(root);dialog.setOnDismissListener(d->close());dialog.setOnShowListener(d->{Window w=dialog.getWindow();if(w!=null){w.setLayout(-1,-1);w.setBackgroundDrawableResource(android.R.color.transparent);w.setStatusBarColor(a.theme.canvas);w.setNavigationBarColor(a.theme.canvas);w.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE);}});
        target.addTextChangedListener(watch(()->{cancel();try{model.target(target.getText().toString(),binding());target.setError(null);status.setText("目标已改，请重新对比；旧确认全部失效。");}catch(IOException e){model.invalidate();target.setError(e.getMessage());}render();}));search.addTextChangedListener(watch(()->{query=search.getText().toString();render();}));a.prefs.registerOnSharedPreferenceChangeListener(preferenceChanged);networks=(android.net.ConnectivityManager)a.getSystemService(Context.CONNECTIVITY_SERVICE);networkCallback=new android.net.ConnectivityManager.NetworkCallback(){@Override public void onLost(android.net.Network n){main.post(()->{if(!closed){cancel();model.invalidate();status.setText("网络已断开，当前确认降为待核验；选择保留，未知项目不隐藏。");render();}});}@Override public void onAvailable(android.net.Network n){main.post(()->{if(!closed&&model.checkedAt>0){cancel();model.invalidate();status.setText("网络环境变化，请重新对比当前电脑目标。");render();}});}};try{networks.registerDefaultNetworkCallback(networkCallback);networkMonitored=true;}catch(RuntimeException ignored){networkMonitored=false;status.setText("网络状态监测不可用；只能查看历史，当前确认不会用于隐藏。");}dialog.show();observeSources();render();
    }
    TextWatcher watch(Runnable action){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int aft){}public void onTextChanged(CharSequence s,int st,int before,int count){}public void afterTextChanged(Editable e){if(!binding)action.run();}};}
    void setHidden(boolean value){int removed=model.hide(value);a.prefs.edit().putString("sentDisplay",value?"hidden":"marked").apply();status.setText(value?"隐藏本次确认相同的 "+model.sameCount()+" 项；已取消其中 "+removed+" 项勾选。未知、变化和异名同内容仍保留。":"已标记显示全部；已取消的勾选不会自动恢复。");render();}
    void type(){String[] labels={"全部类型","图片","PDF","Markdown","文本","其他"},values={"all","image","pdf","markdown","text","other"};a.dialog().setTitle("筛选类型").setItems(labels,(d,w)->{kind=values[w];filter.setText(labels[w]);render();}).setNegativeButton("取消",null).show();}
    void render(){if(closed)return;boolean setting=a.prefs.getString("sentDisplay","marked").equals("hidden");if(setting!=model.hidden)model.hide(setting);shown=model.visible(query,kind);adapter.notifyDataSetChanged();displaySwitch.setHidden(model.hidden,true);int selected=model.selected().size(),visible=0;for(SendPickerModel.Item item:shown)if(item.selected)visible++;count.setText("已选 "+selected+" 项 · 显示 "+shown.size()+" / "+model.items.size()+(selected>visible?"\n列表外另有 "+(selected-visible)+" 项已选":""));next.setEnabled(selected>0&&target.getError()==null);selectNew.setEnabled(model.checkedAt>0&&model.items.stream().anyMatch(item->item.state.equals("missing")));}
    void cancel(){generation++;if(task!=null)task.cancel(true);TransferClient c=comparing;if(c!=null)c.pause();InputStream input=activeInput;if(input!=null)try{input.close();}catch(IOException ignored){}comparing=null;}
    void check(int version)throws IOException {if(closed||version!=generation||Thread.currentThread().isInterrupted())throw new IOException("对比已取消");}
    void post(int version,Runnable action){main.post(()->{if(!closed&&version==generation){action.run();render();}});}
    static final class Fingerprint {long size;String sha;Fingerprint(long size,String sha){this.size=size;this.sha=sha;}}
    Fingerprint fingerprint(TransferUi.Source source,int version)throws Exception {MessageDigest sha=MessageDigest.getInstance("SHA-256");long size=0;try(InputStream input=a.getContentResolver().openInputStream(source.uri)){if(input==null)throw new IOException("来源授权失效");activeInput=input;byte[] bytes=new byte[65536];int n;while((n=input.read(bytes))!=-1){check(version);size+=n;if(size>TransferEngine.MAX_FILE)throw new IOException("来源超过单文件8GiB上限");sha.update(bytes,0,n);}}finally{activeInput=null;}check(version);return new Fingerprint(size,Engine.hex(sha.digest()));}
    static String historyKey(String uri,String path,long size,String hash){return uri+"\n"+path+"\n"+size+":"+hash;}
    void compare(){
        cancel();final int version=generation;final String prefix;try{prefix=SendPickerModel.prefix(target.getText().toString());model.target(prefix,binding());}catch(IOException e){target.setError(e.getMessage());return;}
        model.invalidate();status.setText("正在只读计算来源内容；可以继续搜索、勾选或取消。首次读取大文件需要时间。");render();
        task=worker.submit(()->{try{
            Map<String,Fingerprint> fingerprints=new LinkedHashMap<>();Map<String,JSONObject> receipts=new LinkedHashMap<>(),prior=new HashMap<>();Map<String,String> failures=new HashMap<>();long total=0;LanClient lan=a.client();String ownerHash=TransferEngine.hash(lan.token.getBytes("UTF-8"));
            for(JSONObject job:owner.queue()){
                check(version);if(!"up".equals(job.optString("direction"))||!mode.equals(job.optString("mode"))||!lan.pin.equals(job.optString("pin"))||!ownerHash.equals(job.optString("owner"))||!prefix.equals(job.optString("prefix")))continue;
                if(mode.equals("notes")&&!job.optString("sourceTree",a.prefs.getString("tree","")).equals(a.prefs.getString("tree","")))continue;
                JSONArray files=job.optJSONArray("files");if(files==null)continue;
                for(int n=0;n<files.length();n++){JSONObject f=files.optJSONObject(n);if(f!=null&&f.optBoolean("done")&&f.optString("sha256").matches("[0-9a-f]{64}")){JSONObject r=new JSONObject(f.toString()).put("jobId",job.optString("id")).put("fileIndex",n).put("prefix",prefix);prior.put(historyKey(f.optString("uri"),f.optString("path"),f.optLong("size",-1),f.optString("sha256")),r);}}
            }
            for(int i=0;i<sources.size();i++){
                check(version);TransferUi.Source source=sources.get(i);String key=Integer.toString(i);
                try{Fingerprint fp=fingerprint(source,version);total+=fp.size;if(total>TransferEngine.MAX_BATCH)throw new IOException("当前来源清单超过每批64GiB，请缩小范围");fingerprints.put(key,fp);JSONObject receipt=prior.get(historyKey(source.uri.toString(),source.path,fp.size,fp.sha));if(receipt!=null)receipts.put(key,receipt);}catch(IOException e){check(version);failures.put(key,e.getMessage()==null?"来源无法核验":e.getMessage());}
                if(i%20==0){final int progress=i+1;post(version,()->status.setText("正在核对来源 "+progress+" / "+sources.size()+"；勾选保留，可取消。"));}
            }
            check(version);TransferClient client=new TransferClient(lan,t->post(version,()->status.setText(t)));comparing=client;JSONObject manifest=null;Map<String,JSONObject> verified=new LinkedHashMap<>();String unavailable="";
            try{if(mode.equals("notes"))manifest=client.compareNotes(prefix);else for(Map.Entry<String,JSONObject> receipt:receipts.entrySet()){check(version);JSONObject r=receipt.getValue();if(!r.optString("targetId").isEmpty())try{verified.put(receipt.getKey(),client.verify(r.optString("jobId"),r.optInt("fileIndex"),r.optString("targetId")));}catch(IOException e){unavailable="部分原批准目标无法核验；保持历史待核验。";}}}catch(IOException e){unavailable="电脑核验未完成（未连接、权限变化或旧版接口）；保留历史待核验。";}
            final JSONObject response=manifest;final String warning=unavailable;
            post(version,()->{try{
                if(!identity.equals(binding())){model.invalidate();status.setText("电脑、配对或来源库变化，请关闭后重新选择。");return;}
                int selectedBefore=model.selected().size();thumbnails.invalidateSources();
                for(SendPickerModel.Item item:model.items){Fingerprint fp=fingerprints.get(item.key);item.history=receipts.containsKey(item.key);if(fp!=null)model.fingerprint(item,fp.size,fp.sha);else{item.sha="";item.size=-1;}item.state=item.history?"history":"unknown";item.other=failures.getOrDefault(item.key,"");}
                if(networkMonitored){if(response!=null)model.compare(response,a.prefs.getString("vaultId",""));else if(mode.equals("files"))for(SendPickerModel.Item item:model.items)if(verified.containsKey(item.key))model.verify(item,verified.get(item.key),receipts.get(item.key));}
                for(SendPickerModel.Item item:model.items)if(failures.containsKey(item.key))item.other=failures.get(item.key);int removed=selectedBefore-model.selected().size();
                status.setText((warning.isEmpty()?"本次只读核验完成。":warning)+" 相同 "+model.sameCount()+" 项；隐藏时取消了 "+removed+" 项勾选。"+(failures.isEmpty()?"":"有 "+failures.size()+" 项来源无法核验，保持可见。")+(!networkMonitored?" 网络监测不可用，当前结果不用于隐藏。":"")+"历史、未知、变化和异名同内容保留。");
                if(response!=null)scope.setText(response.optBoolean("complete")?"本次已覆盖电脑可分享库范围；隐藏配置、备份与链接仍排除。":"电脑授权范围内，其他位置未检查。部分范围不可据此认定缺少。");else scope.setText("只核验当前电脑收件位置中本设备原批准的文件；新批仍需电脑确认。");
            }catch(IOException e){model.invalidate();status.setText(e.getMessage());}});
        }catch(Exception e){post(version,()->{model.invalidate();status.setText("未完成核验："+(e.getMessage()==null?"来源或网络不可用":e.getMessage())+"。保留选择，不隐藏未知项目。");});}finally{comparing=null;}});
    }
    void summary(){
        cancel();final int version=generation;List<SendPickerModel.Item> checks=new ArrayList<>();for(SendPickerModel.Item item:model.items)if((item.selected||item.state.equals("same"))&&!item.sha.isEmpty())checks.add(item);
        if(checks.isEmpty()){confirmSummary();return;}status.setText("正在确认已对比的来源仍未变化；原件不改动，可取消。");
        task=worker.submit(()->{try{for(SendPickerModel.Item item:checks){Fingerprint current=fingerprint(sources.get(Integer.parseInt(item.key)),version);if(current.size!=item.size||!current.sha.equals(item.sha)){post(version,()->{model.invalidate();thumbnails.invalidateSources();status.setText("来源内容已变化，旧确认全部失效。请重新对比并确认选择："+item.path);});return;}}post(version,this::confirmSummary);}catch(Exception e){post(version,()->{model.invalidate();status.setText("来源无法重新核对，尚未建立任务。请重新授权或选择："+(e.getMessage()==null?"读取未完成":e.getMessage()));});}});
    }
    void confirmSummary(){try{String prefix=SendPickerModel.prefix(target.getText().toString());if(!identity.equals(binding()))throw new IOException("电脑或来源库变化，请重新选择");List<SendPickerModel.Item> items=model.selected();List<TransferUi.Source> chosen=new ArrayList<>();for(SendPickerModel.Item item:items){TransferUi.Source source=sources.get(Integer.parseInt(item.key));chosen.add(new TransferUi.Source(source.path,source.uri,item.sha,item.size));}if(chosen.isEmpty())return;StringBuilder body=new StringBuilder((mode.equals("notes")?"电脑主库":"电脑收件位置")+" / "+(prefix.isEmpty()?"根目录":prefix)+"\n"+items.size()+" 个文件。电脑仍需逐批确认；同名变化保护另存，来源不删除。\n\n");for(int i=0;i<Math.min(12,items.size());i++)body.append(items.get(i).path).append('\n');if(items.size()>12)body.append("……其余见电脑接收清单\n");a.dialog().setTitle("确认发送清单").setMessage(body.toString()).setNegativeButton("返回选择",null).setPositiveButton("建立发送任务",(d,w)->{dialog.dismiss();owner.createUpload(chosen,mode,prefix);}).show();}catch(IOException e){target.setError(e.getMessage());}}
    void preview(SendPickerModel.Item item){if(closed)return;a.startActivity(new Intent(a,PreviewActivity.class).putExtra(SourcePreviewSession.EXTRA_TOKEN,session).putExtra(SourcePreviewSession.EXTRA_INDEX,Integer.parseInt(item.key)).putExtra("appearanceDark",a.theme.dark).putExtra("appearanceAccent",a.theme.accent));}
    void close(){if(closed)return;closed=true;cancel();worker.shutdownNow();thumbnails.close();a.prefs.unregisterOnSharedPreferenceChangeListener(preferenceChanged);try{a.getContentResolver().unregisterContentObserver(sourceChanges);}catch(RuntimeException ignored){}if(networks!=null&&networkCallback!=null)try{networks.unregisterNetworkCallback(networkCallback);}catch(RuntimeException ignored){}SourcePreviewSession.close(session);if(owner.picker==this)owner.picker=null;}
    final class RowHolder {
        final LinearLayout root,copy;final CheckBox check;final TextView name,meta,path;final ImageButton thumb;final Button preview;SendPickerModel.Item bound;boolean expanded;
        RowHolder(){root=row();root.setPadding(dp(8),dp(12),dp(8),dp(12));check=new CheckBox(a);check.setButtonTintList(UiChrome.controlTint(a.theme));root.addView(check,new LinearLayout.LayoutParams(dp(42),dp(48)));copy=column();root.addView(copy,new LinearLayout.LayoutParams(0,-2,1));name=text("",14,true);name.setMaxLines(2);name.setEllipsize(TextUtils.TruncateAt.END);name.setFocusable(true);copy.addView(name);meta=text("",11,false);meta.setTextColor(a.theme.muted);copy.addView(meta);path=text("",11,false);path.setTextColor(a.theme.muted);path.setFocusable(true);copy.addView(path);thumb=new ImageButton(a);thumb.setBackground(UiChrome.rounded(a,a.theme.wash,7,a.theme.line));thumb.setPadding(dp(2),dp(2),dp(2),dp(2));LinearLayout.LayoutParams imageSize=new LinearLayout.LayoutParams(dp(62),dp(62));imageSize.leftMargin=dp(9);root.addView(thumb,imageSize);preview=button("预览",()->{if(bound!=null)preview(bound);},false);LinearLayout.LayoutParams buttonSize=new LinearLayout.LayoutParams(dp(60),-2);buttonSize.leftMargin=dp(6);root.addView(preview,buttonSize);root.setTag(this);
            name.setOnClickListener(v->{if(bound!=null){bound.selected=!bound.selected;render();}});path.setOnClickListener(v->{if(bound!=null){expanded=!expanded;path.setText(expanded?bound.path:"展开路径 ›");}});thumb.setOnClickListener(v->{if(bound!=null)preview(bound);});}
        void bind(SendPickerModel.Item item){if(bound==null||!bound.key.equals(item.key))expanded=false;bound=item;check.setOnCheckedChangeListener(null);check.setChecked(item.selected);check.setContentDescription("选择 "+item.path);check.setOnCheckedChangeListener((v,checked)->{if(bound!=null){bound.selected=checked;render();}});root.setBackground(UiChrome.rounded(a,item.selected?a.theme.wash:a.theme.surface,10,a.theme.line));name.setText(DirectoryEngine.name(item.path));name.setContentDescription(item.path);meta.setText(SendPickerModel.label(item.state)+(item.other.isEmpty()?"":"\n"+item.other));path.setText(expanded?item.path:"展开路径 ›");boolean image=item.kind.equals("image");thumb.setVisibility(image?View.VISIBLE:View.GONE);preview.setVisibility(image?View.GONE:View.VISIBLE);if(image){thumb.setContentDescription("预览图片，不改变选择："+item.path);TransferUi.Source source=sources.get(Integer.parseInt(item.key));thumbnails.source(thumb,new TransferUi.Source(source.path,source.uri,item.sha,item.size));}else thumbnails.unbind(thumb);}
    }
    final class Rows extends BaseAdapter {
        public int getCount(){return shown.size();}public Object getItem(int n){return shown.get(n);}public long getItemId(int n){return Integer.parseInt(shown.get(n).key);}public boolean hasStableIds(){return true;}
        public View getView(int position,View recycled,android.view.ViewGroup parent){RowHolder holder=recycled!=null&&recycled.getTag() instanceof RowHolder?(RowHolder)recycled.getTag():new RowHolder();holder.bind(shown.get(position));return holder.root;}
    }
}
