package io.vaultbridge.app;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.provider.DocumentsContract;
import android.view.*;
import android.widget.*;
import android.text.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Consumer;
import org.json.*;

/** Real SAF directory operations, with preview, persistent journals and no deletion fallback. */
final class LibraryUi {
    final MainActivity a;
    final LocationState locations;
    String folder="";
    List<DirectoryEngine.Entry> visible=new ArrayList<>();
    LibraryUi(MainActivity owner) throws IOException {
        a=owner;locations=new LocationState(a.prefs.getString("locations-v2","{}"),value->{if(!a.prefs.edit().putString("locations-v2",value).commit())throw new IOException("无法保存库位置");});
        String old=a.prefs.getString("tree","");if(!old.isEmpty()&&locations.locations().stream().noneMatch(x->x[0].equals(old)))locations.add(old,label(old));
    }
    static String label(String uri){try{String id=DocumentsContract.getTreeDocumentId(Uri.parse(uri));int colon=id.indexOf(':');String path=colon>=0?id.substring(colon+1):id;return path.isEmpty()?"Obsidian 库":DirectoryEngine.name(path);}catch(Exception ex){return "Obsidian 库";}}
    String current(){return a.prefs.getString("tree","");}
    void saveOptions()throws IOException{
        if(current().isEmpty())return;
        try{JSONObject options=new JSONObject().put("mergeMode",a.prefs.getBoolean("mergeMode",false)).put("mergeFolder",a.mergeFolder==null?a.prefs.getString("mergeFolder",""):a.mergeFolder.getText().toString()).put("selection",a.prefs.getString("lastSelection","")).put("kind",a.prefs.getString("lastSelectionKind","files"));locations.options(current(),a.prefs.getString("vaultId",""),options);}catch(JSONException ex){throw new IOException(ex);}
    }
    void activate(String uri)throws IOException{
        if(a.busy)throw new IOException("当前任务尚未结束，暂不能切换库");saveOptions();JSONObject options=locations.options(uri,a.prefs.getString("vaultId",""));
        if(!a.prefs.edit().putString("tree",uri).putBoolean("mergeMode",options.optBoolean("mergeMode",false)).putString("mergeFolder",options.optString("mergeFolder","")).putString("lastSelection",options.optString("selection","")).putString("lastSelectionKind",options.optString("kind","files")).commit())throw new IOException("无法切换库记录");folder="";a.manifest=null;a.resetAppearanceForVault();
    }
    void chooseLocation(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION);if(!current().isEmpty())i.putExtra(DocumentsContract.EXTRA_INITIAL_URI,Uri.parse(current()));a.startActivityForResult(i,MainActivity.TREE);}
    void accepted(Uri uri,int flags)throws IOException{
        if((flags&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION))!=(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION))throw new IOException("请为目录同时授权读取和写入");a.getContentResolver().takePersistableUriPermission(uri,flags);locations.add(uri.toString(),label(uri.toString()));if(current().isEmpty()&&!a.busy)activate(uri.toString());else showLocations();
    }
    void showLocations(){
        try{List<String[]> all=locations.locations();LinearLayout panel=new LinearLayout(a);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(a.dp(18),a.dp(8),a.dp(18),0);panel.addView(a.text("切换只影响后续操作。原库仍保留；新库首次需在 Obsidian 中打开同一文件夹。",13,false));
            for(String[] item:all){String uri=item[0];Button b=a.makeButton((uri.equals(current())?"当前：":"切换到：")+item[1],()->{});panel.addView(b);a.explain(panel,Uri.decode(uri));b.setOnClickListener(v->{if(a.busy){a.alert("任务处理中，稍后再切换。");return;}try{activate(uri);a.result("已切换到 "+item[1]+"。旧库保留；在 Obsidian 中选择同一文件夹。");locationDialog.dismiss();}catch(IOException ex){a.alert(ex.getMessage());}});}
            ScrollView scroll=UiChrome.scroll(a,a.theme);scroll.addView(panel);locationDialog=a.dialog().setTitle("管理"+a.deviceName()+"库位置").setView(scroll).setNegativeButton("关闭",null).setPositiveButton("添加 / 重新授权",(d,w)->chooseLocation()).show();
        }catch(IOException ex){a.alert(ex.getMessage());}
    }
    AlertDialog locationDialog;
    void refresh(){
        if(a.directoryArea==null||a.busy)return;
        if(current().isEmpty()){a.directoryArea.removeAllViews();a.directoryArea.addView(a.text("先添加并选择 Obsidian 库位置。",14,false));return;}
        final String requested=folder;
        a.runWork("读取"+a.deviceName()+"目录…",()->{List<DirectoryEngine.Entry> items=a.store().list(requested);a.runOnUiThread(()->{visible=items;renderEntries("");a.log(a.prefs.getString("lastResult","目录已读取。"));});});
    }
    void renderEntries(String query){
        LinearLayout area=a.directoryArea;area.removeAllViews();LinearLayout toolbar=a.row(area);if(!folder.isEmpty())a.button(toolbar,"上一级",()->{folder=DirectoryEngine.parent(folder);refresh();});a.button(toolbar,"返回库根目录",()->{folder="";refresh();});area.addView(a.text(label(current())+" / "+(folder.isEmpty()?"根目录":folder),14,true));
        EditText search=new EditText(a);a.styleInput(search);search.setSingleLine(true);search.setHint("搜索当前目录");area.addView(search);LinearLayout rows=new LinearLayout(a);rows.setOrientation(LinearLayout.VERTICAL);area.addView(rows);Runnable update=()->{rows.removeAllViews();String q=search.getText().toString().toLowerCase(Locale.ROOT);int count=0;for(DirectoryEngine.Entry item:visible){if(!item.path.toLowerCase(Locale.ROOT).contains(q))continue;if(++count>500){rows.addView(a.text("只显示前 500 条，输入名称可缩小范围。",12,false));break;}LinearLayout entry=new LinearLayout(a);entry.setOrientation(LinearLayout.VERTICAL);entry.setPadding(0,a.dp(8),0,a.dp(8));rows.addView(entry);String display=(item.directory?"文件夹  ":"文件  ")+DirectoryEngine.name(item.path);a.button(entry,display,()->{if(item.directory){folder=item.path;refresh();}else previewFile(item.path);});LinearLayout actions=a.row(entry);Button rename=a.makeButton("重命名",()->rename(item));Button move=a.makeButton("移动",()->move(item));actions.addView(rename,new LinearLayout.LayoutParams(0,-2,1));actions.addView(move,new LinearLayout.LayoutParams(0,-2,1));if(!item.rename)rename.setTag("unavailable");if(!item.move)move.setTag("unavailable");rename.setEnabled(!a.busy&&item.rename);move.setEnabled(!a.busy&&item.move);a.explain(entry,"重命名 / 移动先预览影响"+(!item.move?"；系统未提供此项移动能力":"")+(!item.rename?"；系统未提供重命名能力":""));}if(count==0)rows.addView(a.text("这里没有匹配的笔记或文件夹。",13,false));};
        search.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int af){}public void onTextChanged(CharSequence s,int st,int before,int count){update.run();}public void afterTextChanged(Editable e){}});update.run();
    }
    void previewFile(String path){if(!path.toLowerCase(Locale.ROOT).endsWith(".md")){a.alert("此处只预览 Markdown。图片 / PDF 可在 Obsidian 或文件管理器中查看。");return;}a.runWork("读取笔记预览…",()->{String text=DirectoryEngine.readNote(a.store(),path);a.runOnUiThread(()->a.dialog().setTitle(DirectoryEngine.name(path)).setMessage(text.length()>65536?text.substring(0,65536)+"\n（仅显示前 64 KB）":text).setPositiveButton("关闭",null).show());});}
    void newFolder(){if(!a.requireTarget())return;EditText input=new EditText(a);a.styleInput(input);input.setHint("文件夹名称");a.dialog().setTitle("新建文件夹").setMessage("创建位置："+(folder.isEmpty()?"库根目录":folder)).setView(input).setNegativeButton("取消",null).setPositiveButton("新建",(d,w)->{String value=input.getText().toString().trim();a.runWork("新建文件夹…",()->{a.ledger().requireReady();String path=PathState.normal((folder.isEmpty()?"":folder+"/")+value);a.store().createFolder(path);a.result("已新建："+path);},this::refresh);}).show();}
    void setImport(){if(!a.requireTarget())return;a.prefs.edit().putBoolean("mergeMode",true).putString("mergeFolder",folder).apply();a.importMode.check(102);a.mergeFolder.setText(folder);a.result("ZIP 合并目标已设为："+(folder.isEmpty()?"当前库根目录":folder)+"。已有文件未移动。");}
    void rename(DirectoryEngine.Entry item){EditText input=new EditText(a);a.styleInput(input);input.setText(DirectoryEngine.name(item.path));a.dialog().setTitle("重命名").setMessage("当前："+item.path+"\n先预览受影响的文件和链接。").setView(input).setNegativeButton("取消",null).setPositiveButton("预览影响",(d,w)->{String name=input.getText().toString().trim();if(name.contains("/")||name.contains("\\")){a.alert("这里只填写名称，不包含目录分隔符。");return;}plan(item,(DirectoryEngine.parent(item.path).isEmpty()?"":DirectoryEngine.parent(item.path)+"/")+name);}).show();}
    void pickDirectory(String title,String path,Consumer<String> chosen){a.runWork("读取目标文件夹…",()->{List<DirectoryEngine.Entry>dirs=new ArrayList<>();for(DirectoryEngine.Entry e:a.store().list(path))if(e.directory)dirs.add(e);a.runOnUiThread(()->{List<String> names=new ArrayList<>();if(!path.isEmpty())names.add("返回上一级");for(DirectoryEngine.Entry e:dirs)names.add(DirectoryEngine.name(e.path));a.dialog().setTitle(title+"："+(path.isEmpty()?"库根目录":path)).setItems(names.toArray(new String[0]),(d,w)->{if(!path.isEmpty()&&w==0)pickDirectory(title,DirectoryEngine.parent(path),chosen);else pickDirectory(title,dirs.get(w-(path.isEmpty()?0:1)).path,chosen);}).setNegativeButton("取消",null).setPositiveButton("选择此目录",(d,w)->chosen.accept(path)).show();});});}
    void move(DirectoryEngine.Entry item){pickDirectory("移动到",folder,target->plan(item,(target.isEmpty()?"":target+"/")+DirectoryEngine.name(item.path)));}
    void plan(DirectoryEngine.Entry item,String target){a.runWork("检查目录、同步对应和笔记引用…",()->{
        DocumentStore tree=a.store();if(item.directory)tree.checkOrdinarySubtree(item.path);AndroidLedger records=a.ledger();DirectoryEngine.Plan plan=DirectoryEngine.plan(tree,records,item.path,target,item.directory);
        if(a.manifest!=null){Map<String,String>targets=records.targets(a.manifestPaths());Set<String> seen=new HashSet<>();for(String p:targets.values())if(!seen.add(PathState.replace(p,item.path,target).toLowerCase(Locale.ROOT)))throw new IOException("调整后两个电脑来源指向同一位置，请换一个目标");}
        a.runOnUiThread(()->confirmPlan(plan));
    });}
    void confirmPlan(DirectoryEngine.Plan plan){LinearLayout panel=new LinearLayout(a);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(a.dp(18),a.dp(6),a.dp(18),0);panel.addView(a.text("原位置："+plan.from+"\n新位置："+plan.to+"\n受影响文件："+plan.files+"\n明确引用更新："+plan.edits.size()+" 篇笔记\n同步对应会跟随，电脑路径保持不变。",14,false));CheckBox repair=new CheckBox(a);repair.setText("先备份，再更新明确的笔记引用");repair.setTextColor(a.theme.ink);repair.setChecked(true);panel.addView(repair);panel.addView(a.text("只处理常见 Markdown 路径与 Wikilink，代码片段和外部链接保持原样。插件特有语法须手动检查。",12,false));CheckBox warnings=new CheckBox(a);warnings.setText("我会检查未确定的引用");warnings.setTextColor(a.theme.ink);if(!plan.warnings.isEmpty()){panel.addView(a.text("待人工检查：\n"+String.join("\n",plan.warnings.subList(0,Math.min(8,plan.warnings.size()))),12,false));panel.addView(warnings);}ScrollView scroll=UiChrome.scroll(a,a.theme);scroll.addView(panel);AlertDialog dialog=a.dialog().setTitle("预览目录调整").setView(scroll).setNegativeButton("取消",null).setPositiveButton("按预览整理",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(!plan.warnings.isEmpty()&&!warnings.isChecked()){a.alert("先确认会检查未确定引用，或取消这次操作。");return;}dialog.dismiss();a.runWork("整理目录并保留版本…",()->{List<String> failures=DirectoryEngine.apply(a.store(),a.ledger(),plan,repair.isChecked(),a.work("directory"));if(folder.equals(plan.from)||folder.startsWith(plan.from+"/"))folder=PathState.replace(folder,plan.from,plan.to);String merge=a.prefs.getString("mergeFolder","");if(!merge.isEmpty())a.prefs.edit().putString("mergeFolder",PathState.replace(merge,plan.from,plan.to)).commit();a.result("目录已调整："+plan.to+"\n同步对应已跟随。"+(repair.isChecked()?"明确引用已按备份规则处理。":"引用保持原样，请在 Obsidian 中检查。")+(plan.warnings.isEmpty()?"":"\n有 "+plan.warnings.size()+" 条不确定引用待检查。")+(failures.isEmpty()?"":"\n部分引用未完成：\n"+String.join("\n",failures)));},()->{a.mergeFolder.setText(a.prefs.getString("mergeFolder",""));refresh();});}));dialog.show();}
    void mappings(){if(!a.requireTarget())return;a.runWork("读取目录对应…",()->{List<String[]> rules=a.ledger().routes();StringBuilder text=new StringBuilder("对应按顺序应用，后续同步和合并导入沿用。\n\n");for(String[] r:rules)text.append(r[0]).append(" → ").append(r[1]).append('\n');if(rules.isEmpty())text.append("尚未调整，").append(a.deviceName()).append("沿用来源目录。\n");text.append("\n在目录列表中重命名或移动，先预览后同步维护对应。不会改动电脑。");a.runOnUiThread(()->a.dialog().setTitle("同步目录对应").setMessage(text.toString()).setPositiveButton("关闭",null).show());});}
    void recover(){if(!a.requireTarget())return;a.runWork("检查中断操作的新旧位置…",()->{AndroidLedger records=a.ledger();a.result(records.pending()?DirectoryEngine.recover(a.store(),records):"没有待恢复的目录操作。");},this::refresh);}
    void copyLibrary(){if(!a.requireTarget())return;try{List<String[]> targets=new ArrayList<>();for(String[] l:locations.locations())if(!l[0].equals(current()))targets.add(l);if(targets.isEmpty()){a.alert("先在库位置管理中添加另一个位置，再复制笔记。原库会保留。");return;}String[] labels=targets.stream().map(x->x[1]).toArray(String[]::new);a.dialog().setTitle("复制到哪个库位置？").setItems(labels,(d,w)->previewCopy(targets.get(w))).setNegativeButton("取消",null).show();}catch(IOException ex){a.alert(ex.getMessage());}}
    void previewCopy(String[] target){try{saveOptions();}catch(IOException ex){a.alert(ex.getMessage());return;}final String original=current();a.runWork("检查复制范围与新位置…",()->{AndroidLedger records=a.ledger();records.requireReady();DocumentStore source=a.store(),dest=new DocumentStore(a.getContentResolver(),Uri.parse(target[0]));rejectOverlap(source,dest);requireEmptyTarget(dest,target[0]);List<DirectoryEngine.Entry> entries=DirectoryEngine.scan(source);long total=0;int files=0;for(DirectoryEngine.Entry e:entries)if(!e.directory){if(e.size<0||e.size>Engine.MAX_FILE)throw new IOException("单文件超过复制上限："+e.path);total+=e.size;files++;if(total>Engine.MAX_TOTAL)throw new IOException("复制总量超过 1 GB，请分库整理");}final long bytes=total;final int count=files;a.runOnUiThread(()->a.dialog().setTitle("预览复制笔记库").setMessage("目标："+target[1]+"\n普通文件："+count+"\n预计大小："+(bytes/1024/1024)+" MB\n\n复制笔记、附件和目录对应，校验后可切换。旧库和旧版本保留；.obsidian 配置不复制。失败文件留在目标并明确报告，不删除任何文件。").setNegativeButton("取消",null).setPositiveButton("开始复制",(d,w)->copyTo(original,target,entries)).show());});}
    void requireEmptyTarget(DocumentStore dest,String uri)throws IOException{if(!dest.list("").isEmpty()||dest.find(".vaultbridge",false,true)!=null||!new AndroidLedger(a.prefs,uri).unused())throw new IOException("目标已有文件、历史备份或同步记录。请选择新的空目录，避免覆盖管理记录。");}
    static final class BoundedInput extends FilterInputStream {
        final long limit;long count;
        BoundedInput(InputStream input,long maximum){super(input);limit=maximum;}
        void add(int n)throws IOException{if(n>0){count+=n;if(count>limit)throw new IOException("实际复制大小超过上限。已写入的目标文件保留，原库未变动。");}}
        @Override public int read()throws IOException{int n=in.read();add(n<0?0:1);return n;}
        @Override public int read(byte[] b,int o,int l)throws IOException{int n=in.read(b,o,l);add(n);return n;}
    }
    static void rejectOverlap(DocumentStore source,DocumentStore dest)throws IOException{
        String from=DocumentsContract.getTreeDocumentId(source.tree),to=DocumentsContract.getTreeDocumentId(dest.tree);
        if(source.tree.getAuthority().equals(dest.tree.getAuthority())){
            if(from.equals(to)||to.startsWith(from+"/")||from.startsWith(to+"/"))throw new IOException("复制目标不能是原库或其上下级目录");
            try{if(DocumentsContract.isChildDocument(source.resolver,source.directory(""),dest.directory(""))||DocumentsContract.isChildDocument(source.resolver,dest.directory(""),source.directory("")))throw new IOException("复制目标不能是原库的上下级目录");}catch(RuntimeException ex){throw new IOException("系统不能验证这两个库是否重叠，暂不能复制。请改选设备存储中两个独立的库位置。",ex);}
        }
    }
    void copyTo(String original,String[] target,List<DirectoryEngine.Entry> entries){
        a.runWork("复制笔记并逐文件校验…",()->{
            if(!current().equals(original))throw new IOException("当前库已变化，请重新预览");
            AndroidLedger records=a.ledger();records.requireReady();
            DocumentStore source=a.store(),dest=new DocumentStore(a.getContentResolver(),Uri.parse(target[0]));
            rejectOverlap(source,dest);requireEmptyTarget(dest,target[0]);
            Set<String> expected=new HashSet<>(),actual=new HashSet<>();
            for(DirectoryEngine.Entry e:entries)expected.add(e.path+"|"+e.directory);
            for(DirectoryEngine.Entry e:DirectoryEngine.scan(source))actual.add(e.path+"|"+e.directory);
            if(!expected.equals(actual))throw new IOException("预览后来源目录变化，请重新预览");
            long copied=0;int done=0;
            for(DirectoryEngine.Entry item:entries){
                if(item.directory){dest.createFolder(item.path);continue;}
                a.log("正在复制："+(++done)+" 个文件\n"+item.path);
                // Use one fresh destination view for the collision check, write and verification.
                // The preflight view intentionally caches the initially empty directory.
                DocumentStore receiving=new DocumentStore(a.getContentResolver(),Uri.parse(target[0]));
                if(receiving.find(item.path,false,false)!=null)throw new IOException("复制途中目标出现同名文件，已停止："+item.path);
                String before=Engine.hash(source,item.path);
                try(BoundedInput input=new BoundedInput(source.read(item.path),Math.min(Engine.MAX_FILE,Engine.MAX_TOTAL-copied))){receiving.write(item.path,input);copied+=input.count;}
                if(!before.equals(Engine.hash(receiving,item.path))||!before.equals(Engine.hash(source,item.path)))throw new IOException("复制校验未完成："+item.path+"。原文件未变动，目标已写入的文件保留。");
            }
            Set<String> finalPaths=new HashSet<>();for(DirectoryEngine.Entry e:DirectoryEngine.scan(source))finalPaths.add(e.path+"|"+e.directory);
            if(!finalPaths.equals(expected))throw new IOException("复制期间来源目录发生变化。目标已有文件保留，未切换库，请检查后选择新的空目录重试。");
            String state=records.copyWithoutHistory();
            if(!a.prefs.edit().putString(AndroidLedger.keyFor(target[0]),state).commit())throw new IOException("文件已复制，但无法保存新库对应记录，未切换库");
            locations.options(target[0],a.prefs.getString("vaultId",""),locations.options(original,a.prefs.getString("vaultId","")));
            a.result("笔记复制完成："+target[1]+"。旧库和旧版本保留。");
        },()->a.dialog().setTitle("复制完成").setMessage("现在切换到新库吗？首次在 Obsidian 中选择同一个文件夹。").setNegativeButton("保留当前库",null).setPositiveButton("切换到新库",(d,w)->{try{activate(target[0]);}catch(IOException ex){a.alert(ex.getMessage());}}).show());
    }
}
