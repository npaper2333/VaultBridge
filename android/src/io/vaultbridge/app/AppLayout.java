package io.vaultbridge.app;

import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.os.Build;
import android.view.*;
import android.widget.*;
import java.util.*;

/** Native responsive screens. All operations remain in MainActivity/the tested core. */
final class AppLayout {
    final MainActivity a;
    final List<LinearLayout> pages=new ArrayList<>();
    final List<Button> navigation=new ArrayList<>();
    final String[] names={"电脑同步","ZIP 导入","库与目录","版本与恢复","设置与说明","文件传输","文件"};
    ScrollView scroll;
    AppLayout(MainActivity activity){a=activity;}
    LinearLayout column(){LinearLayout v=new LinearLayout(a);v.setOrientation(LinearLayout.VERTICAL);return v;}
    GradientDrawable bg(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(a.dp(radius));return d;}
    void action(LinearLayout parent,String label,String explanation,Runnable handler){a.button(parent,label,handler);a.explain(parent,explanation);}
    void primaryAction(LinearLayout parent,String label,String explanation,Runnable handler){a.button(parent,label,handler,true);a.explain(parent,explanation);}
    void build(){
        a.actions.clear();a.helpViews.clear();pages.clear();navigation.clear();UiTheme t=a.theme;
        UiChrome.bars(a,t);
        LinearLayout root=column();root.setBackgroundColor(t.canvas);root.setPadding(0,0,0,0);a.setContentView(root);
        if(Build.VERSION.SDK_INT>=35)root.setOnApplyWindowInsetsListener((v,insets)->{android.graphics.Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left,bars.top,bars.right,bars.bottom);return insets;});
        LinearLayout header=new LinearLayout(a);header.setOrientation(LinearLayout.HORIZONTAL);header.setPadding(a.dp(20),a.dp(15),a.dp(20),a.dp(10));header.setGravity(Gravity.CENTER_VERTICAL);root.addView(header);
        LinearLayout branding=column();branding.addView(a.text("VaultBridge",22,true));TextView subtitle=a.text("笔记与普通文件 · 局域网直传 · 0.5.2",11,false);subtitle.setTextColor(t.muted);branding.addView(subtitle);header.addView(branding,new LinearLayout.LayoutParams(0,-2,1));
        a.updateUi.attachNotice(root);
        boolean wide=a.getResources().getConfiguration().screenWidthDp>=760;
        LinearLayout body=new LinearLayout(a);body.setOrientation(wide?LinearLayout.HORIZONTAL:LinearLayout.VERTICAL);root.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout nav=new LinearLayout(a);nav.setOrientation(wide?LinearLayout.VERTICAL:LinearLayout.HORIZONTAL);nav.setPadding(a.dp(9),a.dp(8),a.dp(9),a.dp(8));nav.setBackground(bg(t.nav,wide?0:8));
        if(wide)body.addView(nav,new LinearLayout.LayoutParams(a.dp(178),-1));else{HorizontalScrollView wrapper=new HorizontalScrollView(a);wrapper.setHorizontalScrollBarEnabled(false);UiChrome.scrolling(wrapper,t);wrapper.addView(nav);body.addView(wrapper,new LinearLayout.LayoutParams(-1,-2));}
        for(int i=0;i<names.length;i++)navigation.add(null);
        for(int index:new int[]{0,6,5,1,2,3,4}){final int page=index;Button b=a.makeButton(names[page],()->showPage(page));if(page==6){a.actions.remove(b);b.setEnabled(true);b.setOnClickListener(v->showPage(page));}b.setMinWidth(a.dp(100));nav.addView(b,new LinearLayout.LayoutParams(wide?-1:-2,a.dp(52)));navigation.set(page,b);}
        scroll=UiChrome.scroll(a,t);scroll.setBackgroundColor(t.canvas);if(wide)body.addView(scroll,new LinearLayout.LayoutParams(0,-1,1));else body.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        LinearLayout all=column();all.setPadding(a.dp(t.padding),a.dp(10),a.dp(t.padding),a.dp(24));scroll.addView(all);
        for(int i=0;i<names.length;i++){LinearLayout p=column();all.addView(p);pages.add(p);}
        a.content=pages.get(0);
        LinearLayout target=a.card(a.deviceName()+"接收位置");a.targetLabel=a.text("",15,true);target.addView(a.targetLabel);action(target,"选择 / 切换 Obsidian 库", "记住多个已授权位置。切换只影响新的同步与导入，原库文件保留。",a::showLibraryManager);
        a.explain(target,"第一次使用新库，在 Obsidian 中选择同一个现有文件夹。VaultBridge 不替 Obsidian 更改当前库。");
        LinearLayout computer=a.card("从电脑获取笔记");a.computerLabel=a.text("",14,false);computer.addView(a.computerLabel);
        LinearLayout pair=a.row(computer);a.button(pair,"扫码配对",a::scan);a.button(pair,"粘贴配对文字",a::pastePairing);a.explain(computer,"扫码绑定电脑；相机不方便时粘贴电脑端配对文字。电脑需启动分享，两端需网络可达。");
        primaryAction(computer,"选择笔记并同步","全部内容包含以后新增笔记；文件夹范围包含后续新增内容；具体文件需要重新勾选新增项。",()->a.fetchManifest(false));
        action(computer,"同步上次范围","沿用当前库、当前电脑来源保存的范围，比较内容后只传变化文件。",()->a.fetchManifest(true));
        primaryAction(computer,"选择"+a.deviceName()+"笔记传回电脑","从当前"+a.deviceName()+"库勾选笔记和附件，默认电脑主库根目录，也可填库内子目录。电脑逐次确认；同名内容变化另存，不自动覆盖主库。",a.transferUi::sendNotes);
        Button pauseNotes=new Button(a);pauseNotes.setText("暂停当前笔记传输");pauseNotes.setAllCaps(false);UiChrome.button(pauseNotes,t,false);pauseNotes.setOnClickListener(v->{if(a.transferUi.active!=null)a.transferUi.active.pause();});computer.addView(pauseNotes);a.explain(computer,"电脑端需更新到 0.4.0。重试上次同步范围时复用已校验分块；电脑笔记有新变化则获取新版本，旧暂存保留。");
        action(computer,"更换连接地址","电脑换网络或地址变化时填写新 IPv4 地址，仍验证同一台电脑。",a::changeAddress);
        action(computer,"打开 Obsidian","打开已有笔记。新库第一次需在 Obsidian 中打开相同文件夹。",a::openObsidian);
        a.content=pages.get(1);LinearLayout zip=a.card("导入微信 ZIP");a.zipLabel=a.text(a.pendingZip==null?"微信“用其他应用打开”，或在这里选择 ZIP。":"已接收："+a.pendingZipName,14,false);zip.addView(a.zipLabel);
        a.importMode=new RadioGroup(a);RadioButton batch=new RadioButton(a);batch.setId(101);batch.setText("按压缩包新建批次文件夹");batch.setTextColor(t.ink);batch.setButtonTintList(UiChrome.controlTint(t));a.importMode.addView(batch);a.explain(a.importMode,"每个包放进“微信导入/包名”，同名另起序号，适合临时课程资料。");
        RadioButton merge=new RadioButton(a);merge.setId(102);merge.setText("合并更新到指定目录");merge.setTextColor(t.ink);merge.setButtonTintList(UiChrome.controlTint(t));a.importMode.addView(merge);a.explain(a.importMode,"适合持续更新同一套笔记。新文件添加，同名更新先比较内容，保留本地修改。");zip.addView(a.importMode);a.importMode.check(a.prefs.getBoolean("mergeMode",false)?102:101);
        a.mergeFolder=new EditText(a);a.styleInput(a.mergeFolder);a.mergeFolder.setSingleLine(true);a.mergeFolder.setText(a.prefs.getString("mergeFolder",""));a.mergeFolder.setHint("合并子目录，留空放在当前库根目录");zip.addView(a.mergeFolder);a.mergeFolder.setVisibility(a.importMode.getCheckedRadioButtonId()==102?View.VISIBLE:View.GONE);
        a.importMode.setOnCheckedChangeListener((group,id)->{a.prefs.edit().putBoolean("mergeMode",id==102).apply();a.mergeFolder.setVisibility(id==102?View.VISIBLE:View.GONE);});a.explain(zip,"输入库内的相对目录，或去“库与目录”浏览并设为导入位置。与电脑同步接续时，使用合并模式且保持相同来源根目录。");
        a.explain(zip,"自动导入偏好已移到设置页；本次模式和目录仍在这里选择。");
        LinearLayout zipButtons=a.row(zip);a.button(zipButtons,"选择 ZIP",a::chooseZip);a.button(zipButtons,"导入已接收 ZIP",()->a.importZip(false),true);a.explain(zip,"选择 ZIP 打开系统文件选择器；导入会先验证整个包，再解压保存。");
        a.notice(zip,"保存到当前"+a.deviceName()+"库；重复 ZIP 默认跳过。更新先备份，冲突保留。隐藏配置不导入；加密、分卷、ZIP64 暂不支持。");
        a.content=pages.get(2);LinearLayout directory=a.card("库位置与文件夹");action(directory,"管理已授权库位置","切换或添加位置，分别保存导入规则、同步范围和版本记录。",a::showLibraryManager);
        a.directoryArea=column();directory.addView(a.directoryArea);action(directory,"浏览 / 刷新当前目录","查看已授权库中的笔记与附件。隐藏配置和备份目录不参与整理。",a::refreshDirectory);
        LinearLayout tools=a.row(directory);a.button(tools,"新建文件夹",a::newDirectory);a.button(tools,"设为导入位置",a::setImportDirectory);a.explain(directory,"新建只添加目录；设为导入位置只改变以后 ZIP 合并的目标，不移动已有笔记。");
        action(directory,"查看 / 调整同步目录对应","展示电脑来源在"+a.deviceName()+"的接收位置；通过重命名、移动已同步目录调整。",a::showMappings);
        action(directory,"恢复中断的目录记录","系统移动已经发生但记录未写完时，按实际新旧位置恢复对应；状态不明时保持停止。",a::recoverDirectory);
        action(directory,"复制笔记到另一库位置","复制普通笔记、附件和目录对应，校验完成后可切换；原库与原库备份保留，新目标普通目录需为空。",a::copyLibrary);
        a.notice(directory,"重命名或移动先预览文件、同步对应和标准链接影响。系统不支持移动时会说明原因，不用复制后删除替代。");
        a.content=pages.get(3);LinearLayout versions=a.card("查看和恢复保留版本");action(versions,"备份与冲突版本","预览来源副本、采用来源版本或恢复旧版。操作前先备份当前内容，电脑笔记不会变化。",a::showHistory);a.notice(versions,"备份保留在原库，不自动清理。恢复旧版视为"+a.deviceName()+"本地修改，下次同步会保护它。");action(versions,"打开 Obsidian","在 Obsidian 中查看当前库的笔记。",a::openObsidian);
        new SettingsUi(a).build(pages.get(4));
        a.transferUi.build(pages.get(5));a.recentFilesUi.build(pages.get(6));a.content=all;LinearLayout status=a.card("任务结果");a.progress=new ProgressBar(a,null,android.R.attr.progressBarStyleHorizontal);a.progress.setIndeterminate(true);a.progress.setVisibility(View.GONE);a.progress.setProgressTintList(ColorStateList.valueOf(t.accent));a.progress.setIndeterminateTintList(ColorStateList.valueOf(t.accent));status.addView(a.progress);a.message=a.text(a.prefs.getString("lastResult","先选择库位置，再配对电脑或接收 ZIP。"),13,false);a.message.setTextIsSelectable(true);status.addView(a.message);TextView foot=a.text("电脑保留主库 · 回传逐批确认 · 不自动同步删除",12,false);foot.setTextColor(t.muted);all.addView(foot);
        a.updateLabels();showPage(a.currentPage);
    }
    void showPage(int index){if(index<0||index>=pages.size())index=0;a.currentPage=index;for(int i=0;i<pages.size();i++){pages.get(i).setVisibility(i==index?View.VISIBLE:View.GONE);navigation.get(i).setBackground(bg(i==index?a.theme.wash:a.theme.nav,7));navigation.get(i).setTextColor(i==index?a.theme.ink:a.theme.muted);}scroll.scrollTo(0,0);if(index==2)a.refreshDirectory();if(index==6)a.recentFilesUi.refresh();}
}
