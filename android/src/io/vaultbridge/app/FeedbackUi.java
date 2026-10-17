package io.vaultbridge.app;

import android.app.AlertDialog;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

/** Explicit external feedback links only. Legacy local drafts remain untouched. */
final class FeedbackUi {
    static final String ISSUE_URL="https://github.com/npaper2333/VaultBridge/issues/new";
    static final String WECHAT="nitro1447";
    private final MainActivity a;
    private AlertDialog fallback;
    private boolean closed;
    FeedbackUi(MainActivity activity){a=activity;}

    void show(){
        if(closed||a.destroyed)return;
        try{
            Intent open=new Intent(Intent.ACTION_VIEW,Uri.parse(ISSUE_URL));
            open.addCategory(Intent.CATEGORY_BROWSABLE);
            a.startActivity(open);
        }catch(RuntimeException e){showFallback();}
    }

    void copyWechat(){copy("Nitro 微信",WECHAT,"已复制 "+WECHAT+"，添加时可备注 VaultBridge 反馈。","无法自动复制，请长按微信号手动复制。");}

    private void copy(String label,String value,String success,String failure){
        if(closed||a.destroyed)return;
        try{
            ClipboardManager clipboard=(ClipboardManager)a.getSystemService(Context.CLIPBOARD_SERVICE);
            if(clipboard==null)throw new IllegalStateException();
            clipboard.setPrimaryClip(ClipData.newPlainText(label,value));
            Toast.makeText(a,success,Toast.LENGTH_SHORT).show();
        }catch(RuntimeException e){Toast.makeText(a,failure,Toast.LENGTH_LONG).show();}
    }

    private void showFallback(){
        if(closed||a.destroyed||fallback!=null&&fallback.isShowing())return;
        LinearLayout content=new LinearLayout(a);content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(a.dp(18),a.dp(8),a.dp(18),a.dp(16));
        content.addView(a.text("未能打开外部浏览器。可以复制下方链接，在浏览器中打开。",14,false));
        content.addView(a.text("需登录 GitHub，Issue 内容公开，请勿填写隐私信息。",13,true));
        TextView link=a.text(ISSUE_URL,14,false);link.setTextIsSelectable(true);content.addView(link);
        ScrollView scroll=UiChrome.scroll(a,a.theme);scroll.addView(content);
        fallback=a.dialog().setTitle("打开 GitHub Issue").setView(scroll).setNegativeButton("关闭",null)
            .setPositiveButton("复制链接",null).create();
        fallback.setOnShowListener(v->fallback.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(button->copy("VaultBridge GitHub Issue",ISSUE_URL,"已复制 GitHub Issue 链接。","无法自动复制，请长按上方链接手动复制。")));
        fallback.show();
    }

    void retheme(){if(fallback!=null&&fallback.isShowing()){fallback.dismiss();fallback=null;showFallback();}}
    void close(){closed=true;if(fallback!=null){fallback.dismiss();fallback=null;}}
}
