package io.vaultbridge.app;

import android.app.AlertDialog;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.view.View;
import android.widget.*;

/** Persistent preferences; operational destinations remain next to the operation. */
final class SettingsUi {
    final MainActivity a;
    SettingsUi(MainActivity activity){a=activity;}
    void refresh(){a.preserveAppearanceInput();a.rebuildUi();}
    void action(LinearLayout parent,String title,Runnable run){a.button(parent,title,run);}
    void pick(String title,String key,String[] labels,String[] values,String fallback){
        String selected=a.prefs.getString(key,fallback);int current=0;for(int i=0;i<values.length;i++)if(values[i].equals(selected))current=i;
        a.dialog().setTitle(title).setSingleChoiceItems(labels,current,(d,which)->{a.prefs.edit().putString(key,values[which]).apply();d.dismiss();refresh();}).setNegativeButton("取消",null).show();
    }
    void build(LinearLayout page){
        a.content=page;LinearLayout appearance=a.card("外观");
        appearance.addView(a.text(a.resolvedAppearance().summary(),13,false));
        String source=a.prefs.getString(AppearancePreferences.SOURCE,"obsidian"),mode=a.prefs.getString(AppearancePreferences.MODE,"obsidian");
        action(appearance,"主题色："+(source.equals("custom")?"自定义":"跟随 Obsidian"),()->pick("主题色来源",AppearancePreferences.SOURCE,new String[]{"跟随当前 Obsidian 库","自定义颜色"},new String[]{"obsidian","custom"},"obsidian"));
        if(source.equals("custom"))action(appearance,"选择自定义颜色 · "+a.prefs.getString(AppearancePreferences.COLOR,"#d9b55c"),this::customColor);
        String modeLabel=mode.equals("light")?"浅色":mode.equals("dark")?"深色":mode.equals("system")?"跟随系统":"跟随 Obsidian";
        action(appearance,"深浅模式："+modeLabel,()->pick("深浅模式",AppearancePreferences.MODE,new String[]{"浅色","深色","跟随系统","跟随 Obsidian"},new String[]{"light","dark","system","obsidian"},"obsidian"));
        a.explain(appearance,"主题色和深浅模式独立设置。只有少量主要操作使用主题色，其余保持中性黑白灰。");
        action(appearance,"重新检测 Obsidian 外观",a::refreshAppearance);
        a.notice(appearance,"仅读取当前所选库 .obsidian/appearance.json，不改动配置。跟随失败时使用系统深浅色和默认紫色；手动选择保持不变。");
        CheckBox help=check(appearance,"显示选项说明",a.prefs.getBoolean("showHelp",true));help.setOnCheckedChangeListener((v,on)->{a.prefs.edit().putBoolean("showHelp",on).apply();a.helpViews.forEach(t->t.setVisibility(on?View.VISIBLE:View.GONE));});
        a.notice(appearance,"目录、授权、覆盖影响和反馈可见性提示始终保留。");
        LinearLayout transfer=a.card("传输偏好");
        transfer.addView(a.text("已传文件显示方式",14,true));
        SentDisplayControl sentDisplay=new SentDisplayControl(a,a.theme,a.prefs.getString("sentDisplay","marked").equals("hidden"),hidden->a.prefs.edit().putString("sentDisplay",hidden?"hidden":"marked").apply());
        transfer.addView(sentDisplay,new LinearLayout.LayoutParams(-1,a.dp(44)));
        a.notice(transfer,"与选择文件页的快捷开关联动。只隐藏本次目标路径和内容均已核验相同的文件；历史记录、内容变化或无法核验的文件继续显示。隐藏时取消对应勾选。");
        a.automatic=check(transfer,"收到微信 ZIP 后自动导入",a.prefs.getBoolean("autoZip",true));a.automatic.setOnCheckedChangeListener((v,on)->a.prefs.edit().putBoolean("autoZip",on).apply());
        a.explain(transfer,"按 ZIP 导入页当前选择的模式和目录处理。关闭后，先检查本次目标再手动导入。");
        LinearLayout updates=a.card("更新");a.updateUi.settings(updates);
        LinearLayout feedback=a.card("建议与交流");
        a.notice(feedback,"需登录 GitHub，Issue 内容公开，请勿填写隐私信息。");
        action(feedback,"在 GitHub 提交问题或建议",a.feedbackUi::show);
        a.explain(feedback,"点击后打开外部浏览器中的固定 Issue 页面，不附带旧草稿、笔记、文件、目录或设备信息。");
        feedback.addView(a.text("欢迎 +v 交流",14,true));TextView handle=a.text(FeedbackUi.WECHAT,18,true);handle.setTextIsSelectable(true);feedback.addView(handle);
        action(feedback,"复制微信号",a.feedbackUi::copyWechat);
    }
    CheckBox check(LinearLayout parent,String title,boolean value){CheckBox box=new CheckBox(a);box.setText(title);box.setTextSize(14);box.setTextColor(a.theme.ink);box.setButtonTintList(UiChrome.controlTint(a.theme));box.setChecked(value);parent.addView(box);return box;}
    void customColor(){
        AccentColorPicker picker=new AccentColorPicker(a,a.theme,a.prefs.getString(AppearancePreferences.COLOR,"#d9b55c"));
        ScrollView scroll=UiChrome.scroll(a,a.theme);scroll.setFillViewport(false);scroll.addView(picker);
        AlertDialog dialog=a.dialog().setTitle("自定义主题色").setView(scroll).setNegativeButton("取消",null).setPositiveButton("应用",null).create();
        dialog.setOnShowListener(v->{
            Button apply=dialog.getButton(AlertDialog.BUTTON_POSITIVE);
            picker.listen((valid,preview)->{apply.setEnabled(valid);UiChrome.button(apply,preview,true);});
            apply.setOnClickListener(button->{if(!picker.draft.valid())return;a.prefs.edit().putString(AppearancePreferences.COLOR,picker.draft.applied()).putString(AppearancePreferences.SOURCE,"custom").apply();dialog.dismiss();refresh();});
            // Keep actions reachable in landscape, split screen and with the keyboard visible.
            android.view.Window window=dialog.getWindow();if(window!=null){window.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE|android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_ALWAYS_HIDDEN);window.setLayout(Math.min(a.getResources().getDisplayMetrics().widthPixels-a.dp(24),a.dp(440)),Math.min(a.getResources().getDisplayMetrics().heightPixels-a.dp(80),a.dp(660)));}
        });dialog.show();
    }
}
