package io.vaultbridge.app;

import android.content.Context;
import android.graphics.*;
import android.graphics.drawable.GradientDrawable;
import android.text.*;
import android.view.*;
import android.view.accessibility.AccessibilityNodeInfo;
import android.widget.*;
import java.util.*;

/** Native, scrollable color editor. Its semantic swatches survive dialog decoration. */
final class AccentColorPicker extends LinearLayout implements UiChrome.ThemedContent {
    interface Listener { void changed(boolean valid,UiTheme preview); }
    final AccentDraft draft;
    final EditText input;
    final TextView error,previewTitle;
    final LinearLayout sample;
    final Button sampleButton;
    final SeekBar[] sliders=new SeekBar[3];
    final TextView[] sliderLabels=new TextView[3];
    final List<Swatch> swatches=new ArrayList<>();
    final List<TextView> labels=new ArrayList<>();
    final float[] hsv=new float[3];
    UiTheme theme;
    Listener listener;
    boolean updating;

    AccentColorPicker(Context context,UiTheme current,String saved){
        super(context);theme=current;draft=new AccentDraft(saved,current.accent);setOrientation(VERTICAL);setPadding(dp(20),dp(4),dp(20),dp(8));
        TextView hint=label("选一个色块，或拖动滑杆调整",13,false);addView(hint);
        LinearLayout presets=new LinearLayout(context);
        String[] colors={"#d9b55c","#7f6df2","#397dba","#15816b","#bf5e47"};
        String[] names={"暖金","紫罗兰","湖蓝","青绿","陶红"};
        for(int i=0;i<colors.length;i++){final int color=Color.parseColor(colors[i]);LinearLayout item=new LinearLayout(context);item.setOrientation(VERTICAL);Swatch swatch=new Swatch(context,color,names[i]);swatch.setOnClickListener(v->choose(color));swatches.add(swatch);item.addView(swatch,new LayoutParams(-1,dp(50)));TextView name=label(names[i],11,false);name.setGravity(Gravity.CENTER);item.addView(name);presets.addView(item,new LayoutParams(0,-2,1));}
        addView(presets);
        LinearLayout hexRow=new LinearLayout(context);hexRow.setGravity(Gravity.CENTER_VERTICAL);TextView hexLabel=label("色值",13,true);LayoutParams hexLp=new LayoutParams(-2,-2);hexLp.rightMargin=dp(12);hexRow.addView(hexLabel,hexLp);
        input=new EditText(context);input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);input.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});input.setHint("#RRGGBB");input.setContentDescription("自定义主题色，十六进制色值");input.setText(draft.hex());hexRow.addView(input,new LayoutParams(0,dp(48),1));LayoutParams rowLp=new LayoutParams(-1,-2);rowLp.topMargin=dp(12);addView(hexRow,rowLp);
        error=label("",12,false);error.setMinHeight(dp(26));error.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);addView(error);
        for(int i=0;i<sliders.length;i++){final int index=i;sliderLabels[i]=label("",12,false);addView(sliderLabels[i]);SeekBar bar=new SeekBar(context);bar.setMax(i==0?359:100);bar.setPadding(dp(12),0,dp(12),0);bar.setSplitTrack(false);sliders[i]=bar;addView(bar,new LayoutParams(-1,dp(36)));bar.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener(){public void onProgressChanged(SeekBar b,int value,boolean user){if(!user||updating)return;hsv[index]=index==0?value:value/100f;draft.select(Color.HSVToColor(hsv));sync(true,false);}public void onStartTrackingTouch(SeekBar b){}public void onStopTrackingTouch(SeekBar b){}});}
        sample=new LinearLayout(context);sample.setOrientation(VERTICAL);sample.setPadding(dp(14),dp(10),dp(14),dp(12));previewTitle=label("",12,false);sample.addView(previewTitle);sampleButton=new Button(context);sampleButton.setText("开始分享 · 效果预览");sampleButton.setTextSize(14);sampleButton.setMinimumHeight(dp(44));sampleButton.setMinHeight(dp(44));sampleButton.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);sampleButton.setFocusable(false);sampleButton.setClickable(false);sample.addView(sampleButton,new LayoutParams(-1,dp(46)));LayoutParams sampleLp=new LayoutParams(-1,-2);sampleLp.topMargin=dp(8);addView(sample,sampleLp);
        TextView reset=label("还原本次修改",13,false);reset.setGravity(Gravity.CENTER);reset.setPadding(dp(8),dp(12),dp(8),dp(8));reset.setMinHeight(dp(48));reset.setFocusable(true);reset.setClickable(true);reset.setOnClickListener(v->{draft.reset();sync(true,true);});addView(reset);
        input.addTextChangedListener(new TextWatcher(){public void beforeTextChanged(CharSequence s,int start,int count,int after){}public void onTextChanged(CharSequence s,int start,int before,int count){if(updating)return;draft.edit(s.toString());sync(false,true);}public void afterTextChanged(Editable e){}});
        applyChrome(current);
    }
    int dp(int n){return UiChrome.dp(getContext(),n);}
    TextView label(String text,int size,boolean bold){TextView view=new TextView(getContext());view.setText(text);view.setTextSize(size);view.setTextColor(theme.ink);if(bold)view.setTypeface(Typeface.DEFAULT,Typeface.BOLD);labels.add(view);return view;}
    void choose(int color){draft.select(color);sync(true,true);}
    void listen(Listener value){listener=value;sync(false,true);}
    public void applyChrome(UiTheme value){theme=value;for(TextView label:labels)label.setTextColor(value.ink);UiChrome.input(input,value);input.setTextSize(16);sync(false,true);}
    void sync(boolean writeText,boolean readHsv){
        updating=true;
        try{
            if(writeText){input.setText(draft.hex());input.setSelection(input.length());}
            if(readHsv)Color.colorToHSV(draft.preview(),hsv);
            for(int i=0;i<3;i++)sliders[i].setProgress(Math.round(i==0?hsv[0]:hsv[i]*100));
            sliderLabels[0].setText("色相  "+Math.round(hsv[0])+"°");sliderLabels[1].setText("饱和度  "+Math.round(hsv[1]*100)+"%");sliderLabels[2].setText("明亮度  "+Math.round(hsv[2]*100)+"%");
            sliders[0].setContentDescription("色相");sliders[1].setContentDescription("饱和度");sliders[2].setContentDescription("明亮度");
            rail(sliders[0],new int[]{Color.RED,Color.YELLOW,Color.GREEN,Color.CYAN,Color.BLUE,Color.MAGENTA,Color.RED});
            rail(sliders[1],new int[]{Color.HSVToColor(new float[]{hsv[0],0,hsv[2]}),Color.HSVToColor(new float[]{hsv[0],1,hsv[2]})});
            rail(sliders[2],new int[]{Color.BLACK,Color.HSVToColor(new float[]{hsv[0],hsv[1],1})});
            for(Swatch swatch:swatches){swatch.setSelected(draft.valid()&&swatch.color==draft.preview());swatch.invalidate();}
            error.setText(draft.valid()?"":"请输入 #RRGGBB 或 #RGB 色值");error.setTextColor(theme.dark?0xffffb4ab:0xffa82920);
            UiTheme preview=UiTheme.from(new AppearanceConfig(theme.dark,draft.preview(),""));sample.setBackground(UiChrome.rounded(getContext(),theme.canvas,9,theme.line));previewTitle.setText((theme.dark?"深色":"浅色")+"界面预览 · "+draft.hex()+(draft.valid()?"":"（保留上次有效颜色）"));previewTitle.setTextColor(theme.muted);UiChrome.button(sampleButton,preview,true);sampleButton.setClickable(false);
            if(listener!=null)listener.changed(draft.valid(),preview);
        }finally{updating=false;}
    }
    void rail(SeekBar bar,int[] colors){
        GradientDrawable gradient=new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,colors);gradient.setCornerRadius(dp(5));gradient.setSize(dp(180),dp(8));bar.setProgressTintList(null);bar.setProgressBackgroundTintList(null);bar.setProgressDrawable(gradient);
        GradientDrawable thumb=new GradientDrawable();thumb.setShape(GradientDrawable.OVAL);thumb.setColor(draft.preview());thumb.setSize(dp(24),dp(24));thumb.setStroke(dp(3),theme.ink);bar.setThumbTintList(null);bar.setThumb(thumb);
    }
    final class Swatch extends View {
        final int color;final String name;final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        Swatch(Context context,int value,String label){super(context);color=value;name=label;setFocusable(true);setClickable(true);setContentDescription(name+" "+AccentDraft.hex(color));}
        @Override protected void onDraw(Canvas c){super.onDraw(c);float inset=dp(6),side=Math.min(getWidth()-2*inset,getHeight()-2*inset),x=(getWidth()-side)/2,y=(getHeight()-side)/2;RectF r=new RectF(x,y,x+side,y+side);paint.setStyle(Paint.Style.FILL);paint.setColor(color);c.drawRoundRect(r,dp(9),dp(9),paint);paint.setStyle(Paint.Style.STROKE);paint.setStrokeWidth(dp(1));paint.setColor(theme.muted);c.drawRoundRect(r,dp(9),dp(9),paint);if(isSelected()||isFocused()){paint.setStrokeWidth(dp(2));paint.setColor(theme.ink);r.inset(-dp(3),-dp(3));c.drawRoundRect(r,dp(11),dp(11),paint);}if(isSelected()){paint.setStrokeWidth(dp(2));paint.setStrokeCap(Paint.Cap.ROUND);paint.setColor(UiTheme.contrast(color,Color.BLACK)>UiTheme.contrast(color,Color.WHITE)?Color.BLACK:Color.WHITE);Path tick=new Path();tick.moveTo(x+side*.27f,y+side*.5f);tick.lineTo(x+side*.44f,y+side*.68f);tick.lineTo(x+side*.74f,y+side*.32f);c.drawPath(tick,paint);}paint.setStyle(Paint.Style.FILL);}
        @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info){super.onInitializeAccessibilityNodeInfo(info);info.setClassName(Button.class.getName());info.setCheckable(true);info.setChecked(isSelected());}
    }
}
