package io.vaultbridge.app;

import android.content.Context;
import android.graphics.Color;
import android.view.*;
import android.widget.*;

/** Accessible two-position preference with an animated neutral thumb and swipe handling. */
final class SentDisplayControl extends FrameLayout {
    interface Listener {void changed(boolean hidden);}
    final View thumb;final Button marked,hiddenButton;final Listener listener;final UiTheme theme;boolean hidden;float start;
    SentDisplayControl(Context context,UiTheme theme,boolean value,Listener listener){super(context);this.theme=theme;this.listener=listener;int inset=UiChrome.dp(context,3);setPadding(inset,inset,inset,inset);setBackground(UiChrome.rounded(context,theme.canvas,8,theme.line));setMinimumHeight(UiChrome.dp(context,44));thumb=new View(context);thumb.setBackground(UiChrome.rounded(context,theme.wash,6,0));addView(thumb,new FrameLayout.LayoutParams(1,-1));LinearLayout labels=new LinearLayout(context);marked=button(context,"标记已传",false);hiddenButton=button(context,"隐藏已传",true);labels.addView(marked,new LinearLayout.LayoutParams(0,-1,1));labels.addView(hiddenButton,new LinearLayout.LayoutParams(0,-1,1));addView(labels,new FrameLayout.LayoutParams(-1,-1));setHidden(value,false);setFocusable(true);setContentDescription("已传文件显示方式，可左右滑动或用方向键切换");}
    Button button(Context c,String label,boolean value){Button b=new Button(c);b.setText(label);b.setTextSize(12);b.setAllCaps(false);b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(0);b.setMinimumHeight(0);b.setPadding(0,0,0,0);b.setBackgroundColor(Color.TRANSPARENT);b.setOnClickListener(v->choose(value));return b;}
    void choose(boolean value){if(value==hidden)return;setHidden(value,true);if(listener!=null)listener.changed(value);}
    void setHidden(boolean value,boolean animate){hidden=value;marked.setSelected(!hidden);hiddenButton.setSelected(hidden);marked.setTextColor(hidden?theme.muted:theme.ink);hiddenButton.setTextColor(hidden?theme.ink:theme.muted);marked.setContentDescription("标记已传"+(!hidden?"，已选":""));hiddenButton.setContentDescription("隐藏已传"+(hidden?"，已选":""));float target=hidden?(getWidth()-getPaddingLeft()-getPaddingRight())/2f:0;thumb.animate().cancel();if(animate&&isLaidOut())thumb.animate().translationX(target).setDuration(160).start();else thumb.setTranslationX(target);}
    @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)thumb.getLayoutParams();p.width=Math.max(1,(w-getPaddingLeft()-getPaddingRight())/2);thumb.setLayoutParams(p);setHidden(hidden,false);}
    @Override public boolean onInterceptTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN)start=e.getX();return e.getActionMasked()==MotionEvent.ACTION_MOVE&&Math.abs(e.getX()-start)>UiChrome.dp(getContext(),12);}
    @Override public boolean onTouchEvent(MotionEvent e){if(e.getActionMasked()==MotionEvent.ACTION_DOWN)start=e.getX();if(e.getActionMasked()==MotionEvent.ACTION_MOVE)getParent().requestDisallowInterceptTouchEvent(true);if(e.getActionMasked()==MotionEvent.ACTION_UP){choose(e.getX()>getWidth()/2f);performClick();getParent().requestDisallowInterceptTouchEvent(false);}return true;}
    @Override public boolean performClick(){super.performClick();return true;}
    @Override public boolean onKeyDown(int code,KeyEvent event){if(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT){choose(code==KeyEvent.KEYCODE_DPAD_RIGHT);return true;}return super.onKeyDown(code,event);}
}
