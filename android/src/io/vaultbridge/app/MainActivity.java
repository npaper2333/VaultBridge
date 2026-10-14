package io.vaultbridge.app;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.ClipboardManager;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.text.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends Activity {
    static final int TREE = 10, ZIP = 11, SCAN = 12, CAMERA_PERMISSION = 13;
    int accent, ink;
    UiTheme theme;
    AppearanceConfig appearanceConfig;
    int appearanceRequest;
    boolean appearanceAfterWork;
    AppLayout layout;
    LibraryUi libraryUi;
    TransferUi transferUi;
    UpdateUi updateUi;
    RecentFilesUi recentFilesUi;
    final ExecutorService worker = Executors.newSingleThreadExecutor();
    final List<View> actions = new ArrayList<>();
    final List<TextView> helpViews = new ArrayList<>();
    SharedPreferences prefs;
    LinearLayout content, directoryArea;
    TextView targetLabel, computerLabel, message, zipLabel;
    ProgressBar progress;
    RadioGroup importMode;
    EditText mergeFolder;
    CheckBox automatic;
    int currentPage;
    volatile boolean busy;
    boolean destroyed, rebuildAfterWork;
    Uri pendingZip;
    String pendingZipName;
    JSONObject manifest;
    interface Work { void run() throws Exception; }
    @Override public void onCreate(Bundle state) {
        prefs=getSharedPreferences("vaultbridge",MODE_PRIVATE);
        appearanceConfig=AppearanceConfig.fallback(systemDark(),"等待检测当前库外观");
        setTheme(UiChrome.themeResource(this,appearanceConfig.dark,false));
        super.onCreate(state);
        if(state!=null)currentPage=state.getInt("page",0);
        try {libraryUi=new LibraryUi(this);} catch(IOException ex){dialog().setTitle("库记录无法读取").setMessage(ex.getMessage()+"。已有笔记未变动；本次停止操作。").setPositiveButton("关闭",(d,w)->finish()).show();return;}
        transferUi=new TransferUi(this);updateUi=new UpdateUi(this);recentFilesUi=new RecentFilesUi(this);rebuildUi();receiveIntent(getIntent());updateUi.start();
    }
    void rebuildUi() {
        if(busy){rebuildAfterWork=true;return;}
        theme=UiTheme.from(appearanceConfig);accent=theme.accent;ink=theme.ink;
        setTheme(UiChrome.themeResource(this,theme.dark,false));
        layout=new AppLayout(this);layout.build();
    }
    boolean systemDark(){return (getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;}
    void preserveAppearanceInput(){if(mergeFolder!=null)prefs.edit().putString("mergeFolder",mergeFolder.getText().toString()).apply();}
    void refreshAppearance(){
        if(destroyed||libraryUi==null)return;if(busy){appearanceAfterWork=true;return;}
        final String location=prefs.getString("tree","");final boolean dark=systemDark();final int request=++appearanceRequest;
        worker.submit(()->{final AppearanceConfig next=AppearanceReader.read(getContentResolver(),location,dark);runOnUiThread(()->{if(destroyed||request!=appearanceRequest||!location.equals(prefs.getString("tree","")))return;if(busy){appearanceAfterWork=true;return;}if(!next.key().equals(appearanceConfig.key())){preserveAppearanceInput();appearanceConfig=next;rebuildUi();}});});
    }
    void resetAppearanceForVault(){appearanceRequest++;appearanceConfig=AppearanceConfig.fallback(systemDark(),"正在检测当前库外观");rebuildUi();refreshAppearance();}
    @Override protected void onResume(){super.onResume();refreshAppearance();if(updateUi!=null)updateUi.resume();if(recentFilesUi!=null&&currentPage==6)recentFilesUi.refresh();}
    @Override public void onConfigurationChanged(android.content.res.Configuration config){super.onConfigurationChanged(config);if(libraryUi!=null){preserveAppearanceInput();rebuildUi();refreshAppearance();}}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("page",currentPage);super.onSaveInstanceState(out);}
    int dp(int value) {return Math.round(value*getResources().getDisplayMetrics().density);}
    String deviceName() {
        if(Build.VERSION.SDK_INT>=30){android.graphics.Rect bounds=getWindowManager().getMaximumWindowMetrics().getBounds();return DeviceText.name(Math.round(Math.min(bounds.width(),bounds.height())/getResources().getDisplayMetrics().density));}
        android.util.DisplayMetrics metrics=new android.util.DisplayMetrics();getWindowManager().getDefaultDisplay().getRealMetrics(metrics);return DeviceText.name(Math.round(Math.min(metrics.widthPixels,metrics.heightPixels)/metrics.density));
    }
    TextView text(String value,int size,boolean bold){TextView v=new TextView(this);v.setText(value);v.setTextSize(size);v.setTextColor(ink);if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);v.setPadding(0,dp(4),0,dp(7));return v;}
    void explain(LinearLayout parent,String value){TextView v=text(value,12,false);v.setTextColor(theme.muted);v.setVisibility(prefs.getBoolean("showHelp",true)?View.VISIBLE:View.GONE);parent.addView(v);helpViews.add(v);}
    void notice(LinearLayout parent,String value){TextView v=text(value,12,false);v.setTextColor(theme.muted);v.setPadding(0,dp(9),0,dp(7));parent.addView(v);}
    LinearLayout card(String title){LinearLayout c=new LinearLayout(this);c.setOrientation(LinearLayout.VERTICAL);c.setPadding(dp(theme.padding),dp(13),dp(theme.padding),dp(14));GradientDrawable bg=new GradientDrawable();bg.setColor(theme.surface);bg.setCornerRadius(dp(theme.radius));bg.setStroke(dp(1),theme.line);c.setBackground(bg);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(12);content.addView(c,p);c.addView(text(title,17,true));return c;}
    LinearLayout row(LinearLayout parent){LinearLayout v=new LinearLayout(this);v.setOrientation(LinearLayout.HORIZONTAL);parent.addView(v,new LinearLayout.LayoutParams(-1,-2));return v;}
    Button makeButton(String label,Runnable handler){return makeButton(label,handler,false);}
    Button makeButton(String label,Runnable handler,boolean primary){
        Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setAllCaps(false);b.setMinHeight(dp(48));b.setMinWidth(dp(96));b.setMaxWidth(dp(320));b.setMaxLines(2);b.setEllipsize(android.text.TextUtils.TruncateAt.END);b.setStateListAnimator(null);b.setElevation(0);
        UiChrome.button(b,theme,primary);b.setEnabled(!busy);b.setOnClickListener(v->{if(!busy)handler.run();});actions.add(b);return b;
    }
    void button(LinearLayout parent,String label,Runnable handler){button(parent,label,handler,false);}
    void button(LinearLayout parent,String label,Runnable handler,boolean primary){Button b=makeButton(label,handler,primary);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(parent.getOrientation()==LinearLayout.HORIZONTAL?0:-2,-2,parent.getOrientation()==LinearLayout.HORIZONTAL?1:0);p.topMargin=dp(7);p.bottomMargin=dp(5);p.rightMargin=dp(7);parent.addView(b,p);}
    void styleInput(EditText input){UiChrome.input(input,theme);}
    AlertDialog.Builder dialog(){return UiChrome.dialog(this,theme!=null?theme:UiTheme.from(appearanceConfig));}
    void chooseZip(){Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT);i.setType("*/*");i.putExtra(Intent.EXTRA_MIME_TYPES,new String[]{"application/zip","application/x-zip-compressed","application/octet-stream"});i.addCategory(Intent.CATEGORY_OPENABLE);i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);startActivityForResult(i,ZIP);}
    void showLibraryManager(){libraryUi.showLocations();}
    void refreshDirectory(){libraryUi.refresh();}
    void newDirectory(){libraryUi.newFolder();}
    void setImportDirectory(){libraryUi.setImport();}
    void showMappings(){libraryUi.mappings();}
    void recoverDirectory(){libraryUi.recover();}
    void copyLibrary(){libraryUi.copyLibrary();}
    void updateLabels() { String tree = prefs.getString("tree", ""); targetLabel.setText(tree.isEmpty() ? "尚未选择目标目录" : "目标：" + LibraryUi.label(tree) + "\n" + Uri.decode(tree.substring(tree.lastIndexOf('/') + 1))); String host = prefs.getString("host", ""); computerLabel.setText(host.isEmpty() ? "尚未配对电脑" : "电脑：" + prefs.getString("computerName", host) + "  ·  " + host + "\n主库：" + prefs.getString("vaultName", "")); }
    void log(String value) { runOnUiThread(() -> { if (!destroyed) message.setText(value); }); }
    void alert(String value) { if (!destroyed) dialog().setTitle("VaultBridge").setMessage(value).setPositiveButton("知道了", null).show(); }
    boolean requireTarget() { if (prefs.getString("tree", "").isEmpty()) { alert("先选择"+deviceName()+"上的 Obsidian 库目录。"); return false; } return true; }
    DocumentStore store() { return new DocumentStore(getContentResolver(), Uri.parse(prefs.getString("tree", ""))); }
    AndroidLedger ledger() throws IOException { return new AndroidLedger(prefs, prefs.getString("tree", "")); }
    LanClient client() throws IOException { if (prefs.getString("token", "").isEmpty()) throw new IOException("先扫码或粘贴文字配对电脑"); return new LanClient(prefs.getString("host", ""), prefs.getInt("port", 27183), prefs.getString("pin", ""), prefs.getString("token", "")); }
    File work(String tag) throws IOException { File directory = new File(getCacheDir(), tag + "-" + Engine.batchId()); if (!directory.mkdirs()) throw new IOException("无法创建临时目录，请检查剩余空间"); return directory; }
    void runWork(String title, Work action) { runWork(title, action, null); }
    void runWork(String title, Work action, Runnable afterSuccess) {
        if (busy) return; actions.removeIf(view->view.getParent()==null); busy = true; actions.forEach(view -> view.setEnabled(false)); importMode.setEnabled(false); for (int i = 0; i < importMode.getChildCount(); i++) importMode.getChildAt(i).setEnabled(false); mergeFolder.setEnabled(false); automatic.setEnabled(false); progress.setVisibility(View.VISIBLE); getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); log(title);
        worker.submit(() -> {
            boolean success = false;
            try { action.run(); success = true; }
            catch (Exception ex) { String detail = "未完成：" + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()); log(detail); prefs.edit().putString("lastResult", detail).commit(); }
            finally { final boolean completed = success; runOnUiThread(() -> { busy = false; if (!destroyed) { actions.forEach(view -> view.setEnabled(!"unavailable".equals(view.getTag()))); importMode.setEnabled(true); for (int i = 0; i < importMode.getChildCount(); i++) importMode.getChildAt(i).setEnabled(true); mergeFolder.setEnabled(true); automatic.setEnabled(true); progress.setVisibility(View.GONE); getWindow().clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON); updateLabels(); if (rebuildAfterWork) { rebuildAfterWork=false; rebuildUi(); } if (completed && afterSuccess != null) afterSuccess.run(); if(appearanceAfterWork){appearanceAfterWork=false;refreshAppearance();} } }); }
        });
    }
    void scan() { if (checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) requestPermissions(new String[] { Manifest.permission.CAMERA }, CAMERA_PERMISSION); else startActivityForResult(new Intent(this, ScanActivity.class).putExtra("appearanceDark",theme.dark).putExtra("appearanceAccent",theme.accent), SCAN); }
    @Override public void onRequestPermissionsResult(int code, String[] permissions, int[] grants) { super.onRequestPermissionsResult(code, permissions, grants); if (code == CAMERA_PERMISSION && grants.length > 0 && grants[0] == PackageManager.PERMISSION_GRANTED) scan(); }
    void pastePairing() { EditText input = new EditText(this); input.setHint("vaultbridge:…"); input.setMinLines(3); ClipboardManager clipboard = (ClipboardManager)getSystemService(CLIPBOARD_SERVICE); if (clipboard != null && clipboard.hasPrimaryClip()) { CharSequence value = clipboard.getPrimaryClip().getItemAt(0).coerceToText(this); if (value != null && value.toString().trim().startsWith("vaultbridge:")) input.setText(value); } dialog().setTitle("粘贴电脑配对文字").setView(input).setNegativeButton("取消", null).setPositiveButton("配对", (dialog, which) -> pair(input.getText().toString())).show(); }
    void pair(String value) {
        if(busy)return;try{libraryUi.saveOptions();}catch(IOException ex){alert(ex.getMessage());return;}
        runWork("正在验证电脑身份并配对…",()->{JSONObject information=LanClient.parsePairing(value);LanClient connection=new LanClient(information.getString("host"),information.getInt("port"),information.getString("pin"),null);JSONObject reply=connection.pair(information.getString("code"),Build.MANUFACTURER+" "+Build.MODEL);JSONObject options=libraryUi.locations.options(prefs.getString("tree",""),reply.getString("vaultId"));
            if(!prefs.edit().putString("host",connection.host).putInt("port",connection.port).putString("pin",connection.pin).putString("token",reply.getString("token")).putString("vaultId",reply.getString("vaultId")).putString("computerName",information.optString("name",connection.host)).putString("vaultName",reply.getString("name")).putString("lastSelection",options.optString("selection","")).putString("lastSelectionKind",options.optString("kind","files")).putBoolean("mergeMode",options.optBoolean("mergeMode",false)).putString("mergeFolder",options.optString("mergeFolder","")).commit())throw new IOException("无法保存配对");manifest=null;result("电脑配对完成。点击“选择笔记并同步”。");},this::rebuildUi);
    }
    void changeAddress() { if (prefs.getString("host", "").isEmpty()) { alert("先完成配对。"); return; } EditText input = new EditText(this); input.setSingleLine(true); input.setText(prefs.getString("host", "")); dialog().setTitle("电脑地址变化").setMessage("填写同一台已配对电脑的新 IPv4 地址。仍会校验证书身份。").setView(input).setNegativeButton("取消", null).setPositiveButton("保存", (dialog, which) -> { try { new LanClient(input.getText().toString().trim(), prefs.getInt("port", 27183), prefs.getString("pin", ""), prefs.getString("token", "")); prefs.edit().putString("host", input.getText().toString().trim()).apply(); updateLabels(); } catch (IOException ex) { alert(ex.getMessage()); } }).show(); }
    void fetchManifest(boolean previous) {
        if (!requireTarget()) return;
        runWork("正在获取并校验电脑笔记清单…", () -> {
            JSONObject fresh = client().manifest(); if (!prefs.getString("vaultId", "").equals(fresh.getString("vaultId"))) throw new IOException("电脑切换了主库，请重新配对");
            JSONArray files = fresh.getJSONArray("files"); if (files.length() > Engine.MAX_FILES) throw new IOException("文件数量超过上限"); Set<String> unique = new HashSet<>();
            for (int i = 0; i < files.length(); i++) { JSONObject file = files.getJSONObject(i); String path = Engine.normalize(file.getString("path")); if (Engine.protectedPath(path) || !unique.add(path.toLowerCase(Locale.ROOT)) || file.getLong("size") < 0 || file.getLong("size") > Engine.MAX_FILE || !file.getString("sha256").matches("[0-9a-f]{64}")) throw new IOException("电脑清单有不允许或重复的文件"); }
            manifest = fresh;
        }, () -> {
            if (previous && !prefs.getString("lastSelection", "").isEmpty()) {
                try { JSONArray saved = new JSONArray(prefs.getString("lastSelection", "[]")); Set<String> selected = new HashSet<>(); for (int i = 0; i < saved.length(); i++) selected.add(saved.getString(i)); String kind = prefs.getString("lastSelectionKind", "files"); if (kind.equals("scopes")) syncScopes(new ArrayList<>(selected)); else syncSelected(selected); }
                catch (Exception ex) { alert("上次范围无法读取，请重新选择。"); showSelection(); }
            } else showSelection();
        });
    }
    void showSelection() {
        dialog().setTitle("选择同步范围").setItems(new String[] { "同步全部已授权内容\n包含电脑以后新增的内容", "按文件夹选择\n包含所选文件夹以后新增的笔记", "按具体文件选择\n精确勾选；新增文件需再次选择" }, (dialog, which) -> { if (which == 0) syncScopes(Collections.singletonList("")); else if (which == 1) showFolderSelection(); else showFileSelection(); }).setNegativeButton("取消", null).show();
    }
    List<String> manifestPaths() throws JSONException { List<String> paths = new ArrayList<>(); JSONArray files = manifest.getJSONArray("files"); for (int i = 0; i < files.length(); i++) paths.add(files.getJSONObject(i).getString("path")); return paths; }
    void showFolderSelection() {
        try { Map<String, Integer> groups = new TreeMap<>(); for (String path : manifestPaths()) { String scope = path.contains("/") ? path.substring(0, path.indexOf('/')) : path; groups.put(scope, groups.containsKey(scope) ? groups.get(scope) + 1 : 1); } List<String> scopes = new ArrayList<>(groups.keySet()); if (scopes.isEmpty()) { alert("没有可同步内容。"); return; } String[] labels = new String[scopes.size()]; boolean[] checked = new boolean[scopes.size()]; Arrays.fill(checked, true); for (int i = 0; i < scopes.size(); i++) labels[i] = scopes.get(i) + "（" + groups.get(scopes.get(i)) + " 个文件）"; dialog().setTitle("选择文件夹与根目录文件").setMultiChoiceItems(labels, checked, (dialog, which, value) -> checked[which] = value).setNegativeButton("取消", null).setPositiveButton("同步所选", (dialog, which) -> { List<String> selected = new ArrayList<>(); for (int i = 0; i < scopes.size(); i++) if (checked[i]) selected.add(scopes.get(i)); syncScopes(selected); }).show(); }
        catch (JSONException ex) { alert("清单无效，请重新获取。"); }
    }
    void syncScopes(List<String> scopes) { try { syncPaths(Engine.selectScopes(manifestPaths(), scopes), "scopes", new JSONArray(scopes)); } catch (Exception ex) { alert(ex.getMessage()); } }
    void showFileSelection() {
        try {
            JSONArray files = manifest.getJSONArray("files"); if (files.length() == 0) { alert("电脑没有提供可同步内容，请在电脑勾选分享范围。"); return; }
            List<String> all = new ArrayList<>(); for (int i = 0; i < files.length(); i++) all.add(files.getJSONObject(i).getString("path")); Set<String> selected = new HashSet<>(all);
            LinearLayout panel = new LinearLayout(this); panel.setOrientation(LinearLayout.VERTICAL); panel.setPadding(dp(18), dp(8), dp(18), 0);
            TextView note = text("按文件名或目录筛选。相关图片、附件请一起勾选。", 12, false); panel.addView(note);
            EditText search = new EditText(this); search.setSingleLine(true); search.setHint("搜索笔记或文件夹"); panel.addView(search);
            LinearLayout choices = row(panel); Button allButton = new Button(this), noneButton = new Button(this); allButton.setText("选中筛选结果"); noneButton.setText("取消筛选结果"); choices.addView(allButton, new LinearLayout.LayoutParams(0, -2, 1)); choices.addView(noneButton, new LinearLayout.LayoutParams(0, -2, 1));
            ListView list = new ListView(this); list.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE); panel.addView(list, new LinearLayout.LayoutParams(-1, dp(330))); List<String> visible = new ArrayList<>(all);
            Runnable refresh = () -> { String query = search.getText().toString().toLowerCase(Locale.ROOT); visible.clear(); for (String path : all) if (path.toLowerCase(Locale.ROOT).contains(query)) visible.add(path); list.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_list_item_multiple_choice, visible)); for (int i = 0; i < visible.size(); i++) list.setItemChecked(i, selected.contains(visible.get(i))); };
            list.setOnItemClickListener((parent, view, position, id) -> { if (list.isItemChecked(position)) selected.add(visible.get(position)); else selected.remove(visible.get(position)); });
            allButton.setOnClickListener(view -> { selected.addAll(visible); refresh.run(); }); noneButton.setOnClickListener(view -> { selected.removeAll(visible); refresh.run(); });
            search.addTextChangedListener(new TextWatcher() { public void beforeTextChanged(CharSequence s, int start, int count, int after) {} public void onTextChanged(CharSequence s, int start, int before, int count) { refresh.run(); } public void afterTextChanged(Editable value) {} }); refresh.run();
            dialog().setTitle("选择同步内容 · " + all.size() + " 个文件").setView(panel).setNegativeButton("取消", null).setPositiveButton("同步所选", (dialog, which) -> syncSelected(selected)).show();
        } catch (JSONException ex) { alert("清单无效，请重新获取。"); }
    }
    void syncSelected(Set<String> selection) {
        syncPaths(selection, "files", new JSONArray(selection));
    }
    void syncPaths(Set<String> selection, String kind, JSONArray savedSelection) {
        if (selection.isEmpty()) { alert("至少选择一篇笔记或一个附件。"); return; }
        JSONObject snapshot = manifest;
        runWork("正在比较"+deviceName()+"和电脑的内容…", () -> {
            DocumentStore destination = store(); AndroidLedger records = ledger(); records.requireReady(); LanClient connection = client(); String source = "pc:" + snapshot.getString("vaultId"), batch = Engine.batchId(); File temp = work("sync"); int[] counts = new int[4]; int failed = 0, done = 0; List<String> errors = new ArrayList<>();
            TransferClient resumable=new TransferClient(connection,this::log);transferUi.active=resumable;
            JSONArray files = snapshot.getJSONArray("files"); Set<String> available = new HashSet<>(); for (int i = 0; i < files.length(); i++) available.add(files.getJSONObject(i).getString("path")); List<String> selectedScopes = new ArrayList<>(); for (int i = 0; i < savedSelection.length(); i++) selectedScopes.add(savedSelection.getString(i)); Set<String> missing = kind.equals("scopes") ? Engine.selectScopes(records.managedPaths(source), selectedScopes) : new HashSet<>(selection); missing.removeAll(available);
            Map<String,String> targets=records.targets(available);List<String> linkWarnings=new ArrayList<>();
            for (int i = 0; i < files.length(); i++) {
                JSONObject remote = files.getJSONObject(i); String path = remote.getString("path"); if (!selection.contains(path)) continue;
                log("正在同步 " + (++done) + "/" + selection.size() + "\n" + path);
                try {
                    String expected=remote.getString("sha256"),target=targets.get(path);
                    if(destination.exists(target)){String local=Engine.hash(destination,target);if((records.routes().isEmpty()&&expected.equals(local))||(expected.equals(records.sourceHash(source,path))&&local.equals(records.baseline(source,target)))){records.baseline(source,target,local);records.sourceHash(source,path,expected);counts[2]++;continue;}}
                    String taskKey=TransferEngine.hash((source+"|"+prefs.getString("tree","")+"|"+path+"|"+expected).getBytes("UTF-8")).substring(0,32);transferUi.ensure();File transferFolder=TransferEngine.folder(transferUi.root,taskKey);JSONObject transferJob=new JSONObject().put("id",taskKey).put("mode","notes-download").put("files",new JSONArray().put(remote));File staged=resumable.download(transferJob,transferFolder,0);Engine.Item download=new Engine.Item(path,expected,remote.getLong("size"),staged);
                    Engine.Item incoming=localize(download,path,available,records,new File(temp,"mapped-"+i),linkWarnings);
                    Engine.Outcome outcome=Engine.apply(destination,records,incoming,target,source,batch,false);counts[outcome.ordinal()]++;
                    if(outcome!=Engine.Outcome.CONFLICT)records.sourceHash(source,path,expected);
                } catch (Exception ex) { failed++; errors.add(path + "：" + ex.getMessage());if(ex instanceof TransferEngine.Paused)break; }
            }
            transferUi.active=null;
            prefs.edit().putString("lastSelection", savedSelection.toString()).putString("lastSelectionKind", kind).commit();
            String summary = summary("同步", counts, failed) + (errors.isEmpty() ? "" : "\n" + String.join("\n", errors.subList(0, Math.min(8, errors.size()))));
            if (!missing.isEmpty()) summary += "\n上次范围有 " + missing.size() + " 个文件目前未提供（可能已删除或分享范围变化），"+deviceName()+"原文件保留。";
            JSONArray warnings = snapshot.optJSONArray("warnings"); if (warnings != null && warnings.length() > 0) summary += "\n电脑有 " + warnings.length() + " 个未提供文件或目录，请查看电脑分享范围。";
            if(!linkWarnings.isEmpty())summary+="\n有 "+linkWarnings.size()+" 条引用需要检查：\n"+String.join("\n",linkWarnings.subList(0,Math.min(5,linkWarnings.size())));
            result(summary);
        });
    }
    Engine.Item localize(Engine.Item item,String rawPath,Collection<String> all,PathState records,File output,List<String> warnings)throws IOException {
        if(records.routes().isEmpty()||!rawPath.toLowerCase(Locale.ROOT).endsWith(".md"))return item;
        String text=DirectoryEngine.readNote(new Engine.Store(){public boolean exists(String p){return true;}public InputStream read(String p)throws IOException{return new FileInputStream(item.file);}public void write(String p,InputStream d)throws IOException{throw new IOException("只读暂存");}},rawPath);
        DirectoryEngine.Rewrite result=DirectoryEngine.rewrite(text,rawPath,all,records::target);warnings.addAll(result.warnings);if(result.count==0)return item;
        try(OutputStream stream=new FileOutputStream(output)){stream.write(result.text.getBytes(StandardCharsets.UTF_8));}return new Engine.Item(item.path,Engine.hash(output),output.length(),output);
    }
    String summary(String action, int[] counts, int failed) { return action + (failed == 0 ? "处理完成" : "部分完成") + "：新增 " + counts[0] + "，更新 " + counts[1] + "，相同跳过 " + counts[2] + "，冲突保留 " + counts[3] + "，失败 " + failed + "。" + (counts[3] > 0 ? "\n原笔记保留。到“备份与冲突版本”查看并选择采用来源版本。" : ""); }
    void result(String value) { prefs.edit().putString("lastResult", value).commit(); log(value); }
    @Override protected void onNewIntent(Intent intent) { super.onNewIntent(intent); setIntent(intent); receiveIntent(intent); }
    void receiveIntent(Intent intent) {
        if(intent==null||layout==null||libraryUi==null)return;
        if(Intent.ACTION_SEND_MULTIPLE.equals(intent.getAction())){ArrayList<Uri> uris=intent.getParcelableArrayListExtra(Intent.EXTRA_STREAM);if(uris!=null){for(Uri uri:uris)if(UpdateProvider.own(uri))return;transferUi.shared(uris);}return;}
        Uri uri=null;if(Intent.ACTION_SEND.equals(intent.getAction()))uri=intent.getParcelableExtra(Intent.EXTRA_STREAM);else if(Intent.ACTION_VIEW.equals(intent.getAction()))uri=intent.getData();
        if(uri!=null){if(UpdateProvider.own(uri))return;if(!"content".equals(uri.getScheme())){alert("通过系统文件选择或分享可访问的文件。");return;}String name=displayName(uri);if(name.toLowerCase(Locale.ROOT).endsWith(".zip")){final Uri selected=uri;dialog().setTitle("收到 ZIP").setItems(new String[]{"导入 Obsidian 库（按原导入规则）","作为普通文件发送给电脑（不解压）"},(d,w)->{if(w==0)acceptZip(selected,true);else transferUi.shared(Collections.singletonList(selected));}).show();}else transferUi.shared(Collections.singletonList(uri));}
    }
    void acceptZip(Uri uri, boolean external) {
        if (!"content".equals(uri.getScheme())) { alert("需要通过系统文件选择器或其他应用分享 ZIP。"); return; }
        pendingZip = uri; pendingZipName = displayName(uri); zipLabel.setText("已接收：" + pendingZipName); currentPage=1; if(!busy)layout.showPage(1);
        if (external && automatic.isChecked() && !busy && !prefs.getString("tree", "").isEmpty()) importZip(false);
        else if (busy) log("正在处理当前任务，新 ZIP 已等待。完成后点击“导入已接收 ZIP”。");
    }
    String displayName(Uri uri) { try (Cursor cursor = getContentResolver().query(uri, new String[] { OpenableColumns.DISPLAY_NAME }, null, null, null)) { if (cursor != null && cursor.moveToFirst()) return cursor.getString(0); } catch (Exception ignored) {} return "收到的压缩包.zip"; }
    void importZip(boolean repeat) {
        if (!requireTarget()) return; if (pendingZip == null) { alert("先选择 ZIP，或从微信交给这个应用。"); return; }
        final Uri inputUri = pendingZip; final String name = pendingZipName; final boolean merge = importMode.getCheckedRadioButtonId() == 102; String chosen = mergeFolder.getText().toString().trim();
        try { if (!chosen.isEmpty()) { chosen = Engine.normalize(chosen); if (Engine.protectedPath(chosen)) throw new IOException("请选择普通笔记目录"); } } catch (IOException ex) { alert(ex.getMessage()); return; }
        final String prefix = chosen; prefs.edit().putString("mergeFolder", prefix).apply();
        runWork("正在验证 ZIP，校验完成前不会写入笔记库…", () -> {
            File temp = work("zip"); File archive = new File(temp, "input.zip");
            try (InputStream input = getContentResolver().openInputStream(inputUri); OutputStream output = new FileOutputStream(archive)) { if (input == null) throw new IOException("无法读取微信文件，请重新用其他应用打开"); Engine.copy(input, output, Engine.MAX_ARCHIVE); }
            String archiveHash; try (InputStream input = new FileInputStream(archive)) { java.security.MessageDigest digest = java.security.MessageDigest.getInstance("SHA-256"); byte[] bytes = new byte[65536]; int n; while ((n = input.read(bytes)) != -1) digest.update(bytes, 0, n); archiveHash = Engine.hex(digest.digest()); }
            String duplicateKey = "zip-" + prefs.getString("tree", "") + "|" + merge + "|" + prefix + "|" + archiveHash;
            if (!repeat && prefs.getBoolean(duplicateKey, false)) { result("这个 ZIP 已完整处理过。为避免重复笔记，本次跳过。"); runOnUiThread(() -> { if (!destroyed) dialog().setTitle("重复 ZIP").setMessage("需要重新处理这个压缩包吗？").setNegativeButton("跳过", null).setPositiveButton("重新处理", (dialog, which) -> importZip(true)).show(); }); return; }
            Engine.Staged staged = Engine.stageZip(archive, new File(temp, "staged")); String source = "zip:ordinary";
            if (staged.metadata != null) {
                JSONObject meta = new JSONObject(new String(java.nio.file.Files.readAllBytes(staged.metadata.toPath()), StandardCharsets.UTF_8));
                String origin = meta.getString("vaultId"); if (!origin.matches("[a-zA-Z0-9-]{1,64}")) throw new IOException("ZIP 来源标识无效"); source = "pc:" + origin;
                Map<String, JSONObject> declared = new HashMap<>(); JSONArray items = meta.getJSONArray("files");
                for (int i = 0; i < items.length(); i++) { JSONObject item = items.getJSONObject(i); String path = Engine.normalize(item.getString("path")); if (declared.put(path, item) != null) throw new IOException("ZIP 版本清单重名"); }
                if (declared.size() != staged.items.size()) throw new IOException("ZIP 内容和版本清单数量不一致");
                for (Engine.Item item : staged.items) { JSONObject expected = declared.get(item.path); if (expected == null || !item.hash.equals(expected.getString("sha256")) || item.size != expected.getLong("size")) throw new IOException("ZIP 内容和版本清单不一致：" + item.path); }
            }
            DocumentStore destination = store(); AndroidLedger records = ledger(); records.requireReady(); String batch = Engine.batchId(), root = prefix;
            if (!merge) { String base = name.replaceFirst("(?i)\\.zip$", "").replaceAll("[\\\\/:<>\"|?*\\p{Cntrl}]", "_").trim(); if (base.isEmpty() || base.startsWith(".")) base = "笔记"; if (base.length() > 80) base = base.substring(0, 80); while (base.endsWith(".") || base.endsWith(" ")) base = base.substring(0, base.length() - 1); root = "微信导入/" + base; int suffix = 2; while (destination.find(root, false, true) != null) root = "微信导入/" + base + "-" + suffix++; }
            if (!merge) source = "zip-batch"; else if (!root.isEmpty()) source += "@" + root;
            int[] counts = new int[4]; int failed = 0, done = 0; List<String> errors = new ArrayList<>();
            List<String> logical=new ArrayList<>();for(Engine.Item item:staged.items)logical.add(root.isEmpty()?item.path:root+"/"+item.path);Map<String,String> targets=records.targets(logical);List<String> linkWarnings=new ArrayList<>();
            for(Engine.Item item:staged.items){String raw=root.isEmpty()?item.path:root+"/"+item.path;log("正在导入 "+(++done)+"/"+staged.items.size()+"\n"+raw);try{String path=targets.get(raw);Engine.Item incoming=localize(item,raw,logical,records,new File(temp,"mapped-zip-"+done),linkWarnings);Engine.Outcome outcome=Engine.apply(destination,records,incoming,path,source,batch,false);counts[outcome.ordinal()]++;if(outcome!=Engine.Outcome.CONFLICT)records.sourceHash(source,raw,item.hash);}catch(Exception ex){failed++;errors.add(raw+"："+ex.getMessage());}}
            if (failed == 0 && counts[3] == 0) prefs.edit().putBoolean(duplicateKey, true).commit();
            if(!linkWarnings.isEmpty())errors.add("有 "+linkWarnings.size()+" 条引用待检查："+String.join("；",linkWarnings.subList(0,Math.min(5,linkWarnings.size()))));
            result(summary("ZIP 导入", counts, failed) + "\n位置：" + (root.isEmpty() ? "所选库根目录" : root) + (staged.warnings.isEmpty() ? "" : "\n已跳过 " + staged.warnings.size() + " 个配置或隐藏条目。") + (errors.isEmpty() ? "" : "\n" + String.join("\n", errors.subList(0, Math.min(8, errors.size())))));
        });
    }
    void showHistory() {
        if (!requireTarget()) return;
        runWork("读取备份与冲突记录…", () -> {
            List<Engine.History> history = ledger().histories();
            runOnUiThread(() -> { if (destroyed) return; if (history.isEmpty()) { alert("还没有备份或冲突版本。"); return; } String[] labels = new String[history.size()]; for (int i = 0; i < history.size(); i++) { Engine.History item = history.get(i); labels[i] = (item.type.equals("conflict") ? "待处理来源版本" : "更新前备份") + " · " + item.time + "\n" + item.path; } dialog().setTitle("备份与冲突版本").setItems(labels, (dialog, index) -> historyDetail(history.get(index))).setNegativeButton("关闭", null).show(); });
        });
    }
    void historyDetail(Engine.History item) { dialog().setTitle(item.path).setMessage((item.type.equals("conflict") ? "来源版本已保留，原笔记没有覆盖。" : "这是更新前的旧版本。") + "\n" + item.time + "\n采用此版本前，会先备份当前笔记。目录调整后的旧版链接需要检查。").setNeutralButton("预览版本", (dialog, which) -> previewHistory(item)).setNegativeButton("保留当前", null).setPositiveButton(item.type.equals("conflict") ? "采用来源版本" : "恢复此版本", (dialog, which) -> restoreHistory(item)).show(); }
    void previewHistory(Engine.History item) { runWork("读取保留的版本…", () -> { String value; try (InputStream input = store().read(item.storedPath)) { ByteArrayOutputStream bytes = new ByteArrayOutputStream(); byte[] buffer = new byte[4096]; int n; while (bytes.size() < 65536 && (n = input.read(buffer, 0, Math.min(buffer.length, 65536 - bytes.size()))) > 0) bytes.write(buffer, 0, n); value = new String(bytes.toByteArray(), StandardCharsets.UTF_8); } final String display = value; runOnUiThread(() -> { if (!destroyed) dialog().setTitle(item.path).setMessage(display + "\n\n（最多预览 64 KB，图片/PDF 请恢复后用对应应用查看）").setPositiveButton("关闭", null).show(); }); }); }
    void restoreHistory(Engine.History item) { runWork("备份当前笔记并恢复所选版本…", () -> { DocumentStore destination = store(); ledger().requireReady(); File temporary = new File(work("restore"), "version"); try (InputStream input = destination.read(item.storedPath); OutputStream output = new FileOutputStream(temporary)) { Engine.copy(input, output, Engine.MAX_FILE); } if (!item.hash.equals(Engine.hash(temporary))) throw new IOException("备份内容校验失败，已取消恢复"); String source = item.type.equals("conflict") ? item.source : "restore:" + Engine.batchId(); Engine.apply(destination, ledger(), new Engine.Item(item.path, item.hash, temporary.length(), temporary), item.path, source, Engine.batchId(), true); result("已恢复：" + item.path + "\n恢复前的内容也已保留在版本记录中。"); }); }
    void openObsidian() { Intent intent = getPackageManager().getLaunchIntentForPackage("md.obsidian"); if (intent == null) alert("未找到可启动的 Obsidian。请从桌面打开；笔记已保存在所选目录。"); else startActivity(intent); }
    @Override protected void onActivityResult(int request, int result, Intent data) {
        super.onActivityResult(request, result, data);if(updateUi!=null&&updateUi.result(request,result))return; if (result != RESULT_OK || data == null) return;
        if(transferUi!=null&&transferUi.result(request,data))return;
        if(request==TREE&&data.getData()!=null){boolean first=prefs.getString("tree","").isEmpty();Uri uri=data.getData();int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);try{libraryUi.accepted(uri,flags);updateLabels();if(first&&pendingZip!=null&&automatic.isChecked()&&!busy&&!prefs.getString("tree","").isEmpty())importZip(false);}catch(Exception ex){alert("目录授权未完成："+ex.getMessage());}}
        else if (request == ZIP && data.getData() != null) acceptZip(data.getData(), false);
        else if (request == SCAN) pair(data.getStringExtra("pairing"));
    }
    @Override protected void onDestroy() {if(recentFilesUi!=null)recentFilesUi.close();if(updateUi!=null)updateUi.close(); if(libraryUi!=null&&!busy)try{libraryUi.saveOptions();}catch(IOException ignored){} destroyed = true;if(transferUi!=null&&transferUi.active!=null)transferUi.active.pause(); worker.shutdown(); super.onDestroy(); }
}
