package io.vaultbridge.app;

import android.app.Activity;
import android.content.*;
import android.content.pm.*;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.view.View;
import android.widget.*;
import java.io.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.*;

/** Update work and notices are independent of notebook tasks and their saved result text. */
final class UpdateUi {
    static final int INSTALL=40,SOURCES=41;
    final MainActivity a;
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    volatile UpdateClient active;
    boolean closed,checking,downloading,verifying,showNotice,waitingSources;
    UpdateInfo candidate;
    File apk;
    LinearLayout notice;
    TextView noticeText,settingsText;
    Button noticeAction,checkButton;
    String status="启动时会检查 GitHub 公开版本；笔记与配对信息不会发送。";
    UpdateUi(MainActivity activity){a=activity;}
    static long code(PackageInfo info){return Build.VERSION.SDK_INT>=28?info.getLongVersionCode():info.versionCode;}
    static PackageInfo installed(Context context)throws IOException {try{return context.getPackageManager().getPackageInfo(context.getPackageName(),Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES);}catch(PackageManager.NameNotFoundException|RuntimeException e){throw new IOException("无法读取当前应用身份",e);}}
    static Set<String> signers(PackageInfo info)throws IOException {
        android.content.pm.Signature[] signatures;
        if(Build.VERSION.SDK_INT>=28){if(info.signingInfo==null)throw new IOException("无法验证安装签名");signatures=info.signingInfo.getApkContentsSigners();}else signatures=info.signatures;
        if(signatures==null||signatures.length==0)throw new IOException("安装包缺少可验证签名");Set<String> hashes=new HashSet<>();
        try{for(android.content.pm.Signature signature:signatures){if(signature==null)throw new IOException("安装包签名信息无效");hashes.add(UpdateClient.hex(MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())));}}catch(NoSuchAlgorithmException e){throw new IOException("无法校验签名",e);}return hashes;
    }
    static void verifyApk(Context context,File file,UpdateInfo info)throws IOException {
        if(file==null||info==null||!file.isFile()||file.length()!=info.size||info.sha256.isEmpty()||!UpdateClient.hash(file).equals(info.sha256))throw new IOException("安装包 SHA-256 或大小校验失败，请重新下载");
        PackageInfo current=installed(context),archive;
        try{archive=context.getPackageManager().getPackageArchiveInfo(file.getAbsolutePath(),Build.VERSION.SDK_INT>=28?PackageManager.GET_SIGNING_CERTIFICATES:PackageManager.GET_SIGNATURES);}catch(RuntimeException e){throw new IOException("系统无法验证更新安装包",e);}
        if(archive==null)throw new IOException("下载文件不是有效的 Android 安装包");
        UpdateInfo.identity(context.getPackageName(),archive.packageName,info.version,archive.versionName,code(current),code(archive),signers(current),signers(archive));
        if(UpdateInfo.compare(info.version,current.versionName)<=0)throw new IOException("此更新版本不高于当前应用，已停止安装");
    }
    void post(Runnable task){a.runOnUiThread(()->{if(!closed&&!a.destroyed)task.run();});}
    Button button(String label,Runnable task){Button button=new Button(a);button.setText(label);button.setTextSize(14);button.setAllCaps(false);button.setStateListAnimator(null);UiChrome.button(button,a.theme,false);button.setOnClickListener(v->task.run());return button;}
    void attachNotice(LinearLayout parent){
        notice=new LinearLayout(a);notice.setOrientation(LinearLayout.VERTICAL);notice.setPadding(a.dp(14),a.dp(10),a.dp(14),a.dp(8));GradientDrawable background=new GradientDrawable();background.setColor(a.theme.surface);background.setCornerRadius(a.dp(10));background.setStroke(a.dp(1),a.theme.line);notice.setBackground(background);LinearLayout.LayoutParams layout=new LinearLayout.LayoutParams(-1,-2);layout.setMargins(a.dp(20),0,a.dp(20),a.dp(8));parent.addView(notice,layout);
        noticeText=a.text("",12,false);noticeText.setMaxLines(7);noticeText.setEllipsize(android.text.TextUtils.TruncateAt.END);notice.addView(noticeText);noticeAction=button("下载更新",this::primary);notice.addView(noticeAction,new LinearLayout.LayoutParams(-2,-2));render();
    }
    void settings(LinearLayout parent){
        parent.addView(a.text("应用更新",16,true));CheckBox automatic=new CheckBox(a);automatic.setText("启动时检查 GitHub 更新");automatic.setTextColor(a.theme.ink);automatic.setButtonTintList(UiChrome.controlTint(a.theme));automatic.setChecked(a.prefs.getBoolean("updateStartup",true));automatic.setOnCheckedChangeListener((v,checked)->a.prefs.edit().putBoolean("updateStartup",checked).apply());parent.addView(automatic);
        settingsText=a.text("",12,false);settingsText.setTextIsSelectable(true);parent.addView(settingsText);checkButton=button("检查新版本",()->check(true));parent.addView(checkButton,new LinearLayout.LayoutParams(-2,-2));a.notice(parent,"只连接 npaper2333/VaultBridge 的公开 GitHub Release，含预发布。下载后核对完整 SHA-256、包名、版本和当前安装签名；安装仍需你在系统页面确认。首次安装带更新功能的版本需手动打开 APK。");render();
    }
    void render(){
        if(closed||a.destroyed)return;if(settingsText!=null)settingsText.setText(status);if(checkButton!=null)checkButton.setEnabled(!checking&&!downloading&&!verifying);
        if(notice==null)return;notice.setVisibility(showNotice?View.VISIBLE:View.GONE);String text=status;
        if(candidate!=null){text="发现 "+candidate.version+(candidate.prerelease?" · 预发布":"")+"\n"+status;if(!candidate.notes.isEmpty())text+="\n"+candidate.notes;}
        noticeText.setText(text);boolean actionable=candidate!=null&&!checking&&!downloading&&!verifying;noticeAction.setVisibility(candidate==null?View.GONE:View.VISIBLE);noticeAction.setEnabled(actionable);noticeAction.setText(apk!=null?"安装更新":candidate!=null&&candidate.sha256.isEmpty()?"打开版本页":"下载更新");
    }
    void start(){
        worker.submit(()->{
            UpdateInfo restored=null;File ready=null;
            try{String saved=a.prefs.getString(UpdateProvider.INFO,"");if(!saved.isEmpty()){restored=UpdateInfo.decode(saved);ready=UpdateProvider.bounded(a,a.prefs.getString(UpdateProvider.PATH,""),restored);verifyApk(a,ready,restored);}}catch(IOException|RuntimeException ignored){restored=null;ready=null;}
            final UpdateInfo info=restored;final File file=ready;post(()->{if(info!=null){candidate=info;apk=file;showNotice=true;status="已有完整校验的安装包，点击安装更新。";render();}if(a.prefs.getBoolean("updateStartup",true))check(false);});
        });
    }
    void check(boolean manual){
        if(closed||checking||downloading||verifying)return;checking=true;if(manual)showNotice=true;status="正在检查 GitHub 公开版本…";render();
        worker.submit(()->{UpdateClient client=new UpdateClient();active=client;try{UpdateInfo latest=client.latest(installed(a).versionName);post(()->{checking=false;if(latest!=null){if(candidate==null||!candidate.version.equals(latest.version)||!candidate.sha256.equals(latest.sha256)||candidate.size!=latest.size){candidate=latest;apk=null;}else candidate=latest;showNotice=true;status=apk!=null?"安装包已验证，点击后打开系统安装页。":latest.sha256.isEmpty()?"此版本缺少 GitHub SHA-256，应用内下载已禁用；可查看版本页。":"可下载并校验安装包，随后由系统确认覆盖安装。";}else status="未发现比当前版本更新的可用 Android 安装包。";render();});}
            catch(IOException|RuntimeException e){post(()->{checking=false;status=message(e,"检查未完成，请稍后重试；当前功能不受影响。");render();});}finally{active=null;}});
    }
    static String message(Exception error,String fallback){return error instanceof UpdateClient.Failure?error.getMessage():fallback;}
    void primary(){if(candidate==null||checking||downloading||verifying)return;if(apk!=null){install();return;}if(candidate.sha256.isEmpty()){try{a.startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(candidate.page)));}catch(ActivityNotFoundException e){status="没有可打开 GitHub 版本页的浏览器。";render();}return;}download();}
    void download(){
        final UpdateInfo info=candidate;downloading=true;status="正在下载更新；笔记任务继续正常工作。";render();
        worker.submit(()->{UpdateClient client=new UpdateClient();active=client;try{final int[] last={-1};File file=client.download(info,UpdateProvider.root(a),(done,total)->{int percent=(int)(done*100/total);if(percent!=last[0]){last[0]=percent;post(()->{status="下载更新 "+percent+"% · 完成后会验证安装包。";render();});}});verifyApk(a,file,info);UpdateProvider.authorize(a,file,info);post(()->{downloading=false;candidate=info;apk=file;status="下载、SHA-256 与当前签名校验通过。点击安装更新。";render();});}
            catch(IOException|RuntimeException e){post(()->{downloading=false;status=message(e,"安装包验证未完成或签名/身份不符，未开放安装；可重新下载。");render();});}finally{active=null;}});
    }
    void install(){
        if(a.busy){a.alert("先完成或暂停当前笔记/文件任务，再安装更新。");return;}if(apk==null||candidate==null||verifying)return;verifying=true;status="正在再次核对安装包身份与内容…";render();final File file=apk;final UpdateInfo info=candidate;
        worker.submit(()->{try{verifyApk(a,file,info);post(()->{verifying=false;if(a.busy){status="安装包已核对，请完成当前传输后再点击安装。";render();return;}offerInstall();});}catch(IOException|RuntimeException e){post(()->{verifying=false;apk=null;status="安装包身份或内容核对失败，未启动安装；请重新下载。";render();});}});
    }
    void offerInstall(){
        if(!a.getPackageManager().canRequestPackageInstalls()){
            status="系统尚未允许本应用安装更新；授权后返回，再点安装更新。";render();a.dialog().setTitle("允许安装 VaultBridge 更新").setMessage("系统需要你允许 VaultBridge 作为安装来源。仅打开本应用的系统授权页；返回后再次核对安装包，再由你确认安装。").setNegativeButton("暂不授权",null).setPositiveButton("打开系统授权页",(d,w)->{try{waitingSources=true;a.startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+a.getPackageName())),SOURCES);}catch(ActivityNotFoundException e){waitingSources=false;status="系统未提供安装来源授权页，请在系统设置中允许本应用后重试。";render();}}).show();return;
        }
        try{Uri uri=UpdateProvider.currentUri(a);Intent intent=new Intent(Intent.ACTION_INSTALL_PACKAGE).setData(uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);intent.setClipData(ClipData.newRawUri("VaultBridge update",uri));intent.putExtra(Intent.EXTRA_RETURN_RESULT,true);a.startActivityForResult(intent,INSTALL);status="已打开系统安装页；安装是否完成以系统结果为准。";render();}
        catch(IOException|ActivityNotFoundException|SecurityException e){status="无法打开系统安装页，请稍后重试或手动安装官方 APK。";render();}
    }
    void resume(){if(waitingSources){waitingSources=false;status=a.getPackageManager().canRequestPackageInstalls()?"已允许安装来源。点击安装更新，会再次校验后打开系统确认页。":"尚未允许安装来源；可稍后再次点击安装更新。";render();}}
    boolean result(int request,int result){if(request==SOURCES){resume();return true;}if(request==INSTALL){status="系统安装页面已返回；未完成时可再次点击安装更新。";render();return true;}return false;}
    void close(){closed=true;UpdateClient client=active;if(client!=null)client.cancel();worker.shutdownNow();}
}
