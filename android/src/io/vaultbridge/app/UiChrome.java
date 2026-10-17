package io.vaultbridge.app;

import android.app.*;
import android.content.*;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.drawable.*;
import android.os.Build;
import android.view.*;
import android.widget.*;

/** Neutral native drawing from the first frame, including lists and dialogs. */
final class UiChrome {
    /** Components that own semantic colors must not be recolored as ordinary text. */
    interface ThemedContent { void applyChrome(UiTheme theme); }
    static int dp(Context c,int value){return Math.round(value*c.getResources().getDisplayMetrics().density);}
    static int themeResource(Context c,boolean dark,boolean dialog){String name=dialog?(dark?"VaultBridgeDialogDark":"VaultBridgeDialogLight"):(dark?"VaultBridgeDark":"VaultBridgeLight");int resource=c.getResources().getIdentifier(name,"style",c.getPackageName());if(resource==0)throw new IllegalStateException("Missing native theme: "+name);return resource;}
    static ColorStateList controlTint(UiTheme t){return new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{android.R.attr.state_checked},new int[]{}},new int[]{t.muted,t.ink,t.muted});}
    static GradientDrawable rounded(Context c,int color,int radius,int line){GradientDrawable shape=new GradientDrawable();shape.setColor(color);shape.setCornerRadius(dp(c,radius));if(line!=0)shape.setStroke(dp(c,1),line);return shape;}
    static void bars(Activity a,UiTheme t){Window window=a.getWindow();window.setStatusBarColor(t.canvas);window.setNavigationBarColor(t.canvas);View decor=window.getDecorView();int light=View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR|View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR;decor.setSystemUiVisibility((decor.getSystemUiVisibility()&~light)|(t.dark?0:light));if(Build.VERSION.SDK_INT>=29){window.setStatusBarContrastEnforced(false);window.setNavigationBarContrastEnforced(false);decor.setForceDarkAllowed(false);}}
    static void button(Button b,UiTheme t,boolean primary){
        Context c=b.getContext();b.setAllCaps(false);b.setStateListAnimator(null);b.setElevation(0);b.setBackgroundTintList(null);
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{}},new int[]{t.muted,primary?t.primaryInk:t.ink}));
        StateListDrawable states=new StateListDrawable();states.addState(new int[]{-android.R.attr.state_enabled},rounded(c,t.wash,7,primary?0:t.line));states.addState(new int[]{},rounded(c,primary?t.accent:t.wash,7,primary?0:t.line));
        b.setBackground(new RippleDrawable(ColorStateList.valueOf((t.dark?0x30:0x20)<<24|(t.muted&0xffffff)),states,rounded(c,Color.WHITE,7,0)));b.setPadding(dp(c,14),dp(c,8),dp(c,14),dp(c,8));
    }
    static void input(EditText field,UiTheme t){Context c=field.getContext();field.setTextColor(t.ink);field.setHintTextColor(t.muted);field.setTextSize(14);field.setBackgroundTintList(null);field.setBackground(rounded(c,t.wash,6,t.line));field.setPadding(dp(c,12),dp(c,10),dp(c,12),dp(c,10));if(Build.VERSION.SDK_INT>=29){GradientDrawable cursor=rounded(c,t.ink,1,0);cursor.setSize(dp(c,2),dp(c,22));field.setTextCursorDrawable(cursor);}}
    static void scrolling(View view,UiTheme t){view.setOverScrollMode(View.OVER_SCROLL_NEVER);view.setVerticalFadingEdgeEnabled(false);view.setHorizontalFadingEdgeEnabled(false);view.setScrollBarStyle(View.SCROLLBARS_INSIDE_OVERLAY);if(Build.VERSION.SDK_INT>=29){GradientDrawable thumb=rounded(view.getContext(),t.thumb,3,0);thumb.setSize(dp(view.getContext(),4),dp(view.getContext(),4));view.setVerticalScrollbarThumbDrawable(thumb);view.setVerticalScrollbarTrackDrawable(new ColorDrawable(Color.TRANSPARENT));}if(view instanceof AbsListView){AbsListView list=(AbsListView)view;list.setCacheColorHint(t.surface);list.setBackgroundColor(t.surface);}}
    static ScrollView scroll(Context c,UiTheme t){ScrollView view=new ScrollView(c);view.setFillViewport(true);scrolling(view,t);return view;}
    static AlertDialog.Builder dialog(Activity a,UiTheme t){return new DialogBuilder(a,t);}
    private static final class DialogBuilder extends AlertDialog.Builder {
        final Activity activity;final UiTheme theme;
        DialogBuilder(Activity a,UiTheme t){super(a,themeResource(a,t.dark,true));activity=a;theme=t;}
        @Override public AlertDialog create(){
            final AlertDialog dialog=super.create();Window window=dialog.getWindow();if(window!=null){window.setBackgroundDrawable(rounded(activity,theme.surface,12,theme.line));View decor=window.getDecorView();decor.setClipToOutline(true);if(Build.VERSION.SDK_INT>=29)decor.setForceDarkAllowed(false);
                // A caller may own OnShowListener to keep a validation dialog open.
                // Decor attachment runs before its first draw and does not replace that listener.
                decor.addOnAttachStateChangeListener(new View.OnAttachStateChangeListener(){public void onViewAttachedToWindow(View view){decorate(view,theme);for(int which:new int[]{AlertDialog.BUTTON_NEGATIVE,AlertDialog.BUTTON_NEUTRAL,AlertDialog.BUTTON_POSITIVE}){Button b=dialog.getButton(which);if(b!=null){button(b,theme,false);if(which==AlertDialog.BUTTON_POSITIVE)b.setTextColor(theme.accentInk);}}ListView list=dialog.getListView();if(list!=null){scrolling(list,theme);list.setOnHierarchyChangeListener(new ViewGroup.OnHierarchyChangeListener(){public void onChildViewAdded(View parent,View child){decorate(child,theme);}public void onChildViewRemoved(View parent,View child){}});}}public void onViewDetachedFromWindow(View view){}});
            }return dialog;
        }
    }
    static void decorate(View view,UiTheme theme){
        if(view instanceof ThemedContent){((ThemedContent)view).applyChrome(theme);return;}
        scrolling(view,theme);if(view instanceof EditText)input((EditText)view,theme);else if(view instanceof TextView)((TextView)view).setTextColor(theme.ink);if(view instanceof CompoundButton)((CompoundButton)view).setButtonTintList(controlTint(theme));if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)decorate(group.getChildAt(i),theme);}
    }
}
