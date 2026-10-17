package io.vaultbridge.app;

import android.app.*;
import android.content.*;
import android.content.pm.*;
import android.graphics.*;
import android.graphics.pdf.PdfRenderer;
import android.net.Uri;
import android.os.*;
import android.system.*;
import android.text.TextUtils;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.util.*;
import java.util.concurrent.*;

/** Non-exported, read-only viewer. The Intent identifies a private received-file record only. */
public final class PreviewActivity extends Activity {
    final ExecutorService worker=Executors.newSingleThreadExecutor();
    final CancellationSignal cancellation=new CancellationSignal();
    volatile boolean closed;
    volatile InputStream activeInput;
    volatile ParcelFileDescriptor activeDescriptor;
    UiTheme theme;FrameLayout body;TextView title,status;Button other,fit,encoding,previous,next,jump;
    LinearLayout pdfControls;ZoomView image;Bitmap shown;
    RecentFiles.Entry entry;String id,kind="",charset="auto",sourceToken;int sourceIndex=-1;boolean sourceMode;byte[] textBytes;
    PdfRenderer pdf;int pageCount,currentPage,wantedPage;boolean pageBusy,viewed;

    @Override public void onCreate(Bundle state){
        boolean dark=(getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES;
        theme=UiTheme.from(new AppearanceConfig(getIntent().getBooleanExtra("appearanceDark",dark),getIntent().getIntExtra("appearanceAccent",AppearanceConfig.DEFAULT_ACCENT),"只读文件预览"));setTheme(UiChrome.themeResource(this,theme.dark,false));super.onCreate(state);UiChrome.bars(this,theme);
        if(state!=null){wantedPage=state.getInt("page",0);charset=state.getString("encoding","auto");}
        build();id=getIntent().getStringExtra(RecentFilesUi.EXTRA_ID);sourceToken=getIntent().getStringExtra(SourcePreviewSession.EXTRA_TOKEN);sourceIndex=getIntent().getIntExtra(SourcePreviewSession.EXTRA_INDEX,-1);sourceMode=sourceToken!=null;
        if(getIntent().getData()!=null||(!sourceMode&&!PreviewRules.validCatalogId(id))||(sourceMode&&id!=null)){status.setText("此入口只能查看已接收记录或本轮明确选择的来源，请返回重新选择。");return;}
        submit(()->{
            if(sourceMode){TransferUi.Source allowed=SourcePreviewSession.source(sourceToken,sourceIndex);entry=new RecentFiles.Entry("","",sourceIndex,"",allowed.path,allowed.uri.toString(),"",0,0,0,false,"ready");runOnUiThread(()->{if(!closed){title.setText(new File(allowed.path).getName());other.setEnabled(true);status.setText("来源只读预览，不会加入最近接收，也不会改变勾选。");}});entry=SourcePreviewSession.resolve(this,sourceToken,sourceIndex,this::check);}else entry=RecentFilesUi.resolve(this,id,this::check);
            Uri uri=sourceUri(entry);String mime="";if(PreviewRules.extension(entry.path).isEmpty())try{mime=getContentResolver().getType(uri);}catch(RuntimeException ignored){}kind=PreviewRules.kind(entry.path,mime);
            runOnUiThread(()->{if(closed)return;title.setText(new File(entry.path).getName());other.setEnabled(true);fit.setVisibility(kind.equals("image")||kind.equals("pdf")?View.VISIBLE:View.GONE);encoding.setVisibility(kind.equals("text")||kind.equals("markdown")?View.VISIBLE:View.GONE);});
            if(kind.equals("image"))loadImage();else if(kind.equals("pdf"))loadPdf();else if(kind.equals("text")||kind.equals("markdown"))loadText();else runOnUiThread(()->{if(!closed)status.setText("此格式暂不支持内置预览。可用其他应用打开；VaultBridge 不会编辑、解压或自动安装文件。");});
        });
    }
    void build(){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(16),dp(12),dp(16),dp(12));root.setBackgroundColor(theme.canvas);
        if(Build.VERSION.SDK_INT>=35)root.setOnApplyWindowInsetsListener((v,insets)->{Insets bars=insets.getInsets(WindowInsets.Type.systemBars());v.setPadding(bars.left+dp(16),bars.top+dp(12),bars.right+dp(16),bars.bottom+dp(12));return insets;});
        LinearLayout heading=row(root);title=text("只读预览",18);title.setTypeface(null,Typeface.BOLD);title.setMaxLines(2);title.setEllipsize(TextUtils.TruncateAt.END);heading.addView(title,new LinearLayout.LayoutParams(0,-2,1));Button close=button("关闭",()->finish());heading.addView(close);
        status=text("正在核对文件与访问权限…",12);status.setTextColor(theme.muted);status.setPadding(0,dp(10),0,dp(10));root.addView(status);
        LinearLayout controls=row(root);other=button("其他应用打开",this::requestOther);other.setEnabled(false);controls.addView(other);fit=button("适应屏幕",()->{if(image!=null)image.fit();});fit.setVisibility(View.GONE);controls.addView(fit);encoding=button("选择编码",this::chooseEncoding);encoding.setVisibility(View.GONE);encoding.setEnabled(false);controls.addView(encoding);
        pdfControls=row(root);previous=button("上一页",()->requestPage(currentPage-1));jump=button("跳页",this::choosePage);next=button("下一页",()->requestPage(currentPage+1));for(Button b:new Button[]{previous,jump,next})pdfControls.addView(b,new LinearLayout.LayoutParams(0,-2,1));pdfControls.setVisibility(View.GONE);
        body=new FrameLayout(this);body.setBackground(UiChrome.rounded(this,theme.surface,10,theme.line));LinearLayout.LayoutParams area=new LinearLayout.LayoutParams(-1,0,1);area.topMargin=dp(12);root.addView(body,area);setContentView(root);
    }
    int dp(int value){return UiChrome.dp(this,value);}
    TextView text(String value,int size){TextView v=new TextView(this);v.setText(value);v.setTextColor(theme.ink);v.setTextSize(size);return v;}
    LinearLayout row(LinearLayout parent){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);r.setGravity(Gravity.CENTER_VERTICAL);parent.addView(r,new LinearLayout.LayoutParams(-1,-2));return r;}
    Button button(String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextSize(13);b.setMinWidth(0);b.setMinHeight(dp(40));UiChrome.button(b,theme,false);b.setOnClickListener(v->action.run());return b;}
    void check()throws IOException {if(closed||Thread.currentThread().isInterrupted())throw new IOException("预览已关闭。");}
    interface Work{void run()throws Exception;}
    void submit(Work action){if(closed)return;try{worker.execute(()->{try{check();action.run();}catch(OutOfMemoryError error){closePdf();error("内存不足，已停止内置预览。请用其他应用打开此文件。");}catch(Exception error){if(!closed)error(error instanceof SecurityException?"文件授权已失效，请返回文件页重新核对授权位置。":error instanceof IOException?error.getMessage():"文件损坏、受密码保护或格式暂不支持，请用其他应用打开。");}});}catch(RejectedExecutionException ignored){}}
    void error(String message){runOnUiThread(()->{if(closed)return;pageBusy=false;status.setText(message==null?"暂时无法读取此文件，请用其他应用打开。":message);pageButtons();});}
    static Uri sourceUri(RecentFiles.Entry entry)throws IOException {Uri uri=Uri.parse(entry.uri);if(!"content".equals(uri.getScheme()))throw new IOException("接收记录不是已授权的系统文件，请返回文件页重新核对。");return uri;}
    ParcelFileDescriptor openSource()throws IOException {check();ParcelFileDescriptor fd=getContentResolver().openFileDescriptor(sourceUri(entry),"r",cancellation);if(fd==null)throw new IOException("文件无法读取，请返回文件页重新核对。");activeDescriptor=fd;try{check();return fd;}catch(IOException e){fd.close();activeDescriptor=null;throw e;}}
    File cacheFile(String suffix)throws IOException {File folder=new File(getCacheDir(),"previews");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("无法创建私有预览缓存，请用其他应用打开。");return File.createTempFile("preview-",suffix,folder);}
    File snapshot(long limit,String suffix)throws IOException {PreviewRules.checkSize(entry.size,limit);return snapshot(openSource(),limit,suffix);}
    File snapshot(ParcelFileDescriptor fd,long limit,String suffix)throws IOException {
        File file;
        try{file=cacheFile(suffix);}catch(IOException e){fd.close();if(activeDescriptor==fd)activeDescriptor=null;throw e;}
        try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd);FileOutputStream output=new FileOutputStream(file)){activeInput=input;PreviewRules.copyVerified(input,output,entry.size,entry.sha256,limit,this::check);output.getFD().sync();}
        finally{activeInput=null;if(activeDescriptor==fd)activeDescriptor=null;}return file;
    }
    void loadImage()throws IOException {
        File file=snapshot(PreviewRules.MAX_IMAGE,".image");check();BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeFile(file.getPath(),bounds);
        BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=PreviewRules.imageSample(bounds.outWidth,bounds.outHeight);options.inPreferredConfig=Bitmap.Config.ARGB_8888;
        Bitmap bitmap=BitmapFactory.decodeFile(file.getPath(),options);if(bitmap==null)throw new IOException("无法读取图片，文件可能损坏或格式暂不支持，请用其他应用打开。");
        if((long)bitmap.getWidth()*bitmap.getHeight()>PreviewRules.MAX_PIXELS||bitmap.getAllocationByteCount()>(long)PreviewRules.MAX_PIXELS*4){bitmap.recycle();throw new IOException("图片解码尺寸超过安全预览限制，请用其他应用打开。");}
        try{int orientation=new android.media.ExifInterface(file.getPath()).getAttributeInt(android.media.ExifInterface.TAG_ORIENTATION,1);Matrix transform=new Matrix();switch(orientation){case 2:transform.setScale(-1,1);break;case 3:transform.setRotate(180);break;case 4:transform.setScale(1,-1);break;case 5:transform.setRotate(90);transform.postScale(-1,1);break;case 6:transform.setRotate(90);break;case 7:transform.setRotate(270);transform.postScale(-1,1);break;case 8:transform.setRotate(270);break;default:break;}if(!transform.isIdentity()){Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),transform,true);if(rotated!=bitmap){bitmap.recycle();bitmap=rotated;}}}catch(IOException ignored){/* Unsupported EXIF does not prevent bounded bitmap viewing. */}catch(OutOfMemoryError e){bitmap.recycle();throw e;}
        bitmap(bitmap,"图片只读预览 · 双指缩放，单指拖动；已按内存上限采样。",-1,0);
    }
    void loadText()throws IOException {
        PreviewRules.checkSize(entry.size,PreviewRules.MAX_TEXT);ParcelFileDescriptor fd=openSource();
        try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(fd);ByteArrayOutputStream output=new ByteArrayOutputStream()){activeInput=input;PreviewRules.copyVerified(input,output,entry.size,entry.sha256,PreviewRules.MAX_TEXT,this::check);textBytes=output.toByteArray();}
        finally{activeInput=null;if(activeDescriptor==fd)activeDescriptor=null;}
        runOnUiThread(()->{if(!closed)encoding.setEnabled(true);});showText();
    }
    void showText()throws IOException {byte[] bytes=textBytes;if(bytes==null)throw new IOException("文本尚未读取完成。");PreviewRules.Text decoded=PreviewRules.decode(bytes,charset);check();
        runOnUiThread(()->{if(closed)return;ScrollView scroll=UiChrome.scroll(this,theme);scroll.setBackgroundColor(theme.surface);TextView text=text(decoded.value,14);text.setTypeface(Typeface.MONOSPACE);text.setTextIsSelectable(true);text.setAutoLinkMask(0);text.setPadding(dp(14),dp(14),dp(14),dp(14));scroll.addView(text,new ScrollView.LayoutParams(-1,-2));body.removeAllViews();body.addView(scroll,new FrameLayout.LayoutParams(-1,-1));status.setText((kind.equals("markdown")?"Markdown 源文本":"源文本")+" · "+decoded.encoding+(decoded.fallback?"（自动回退，可切换编码）":"")+" · 长按选择复制；不执行 HTML 或脚本。 ");rememberViewed();});
    }
    void chooseEncoding(){if(textBytes==null)return;String[] labels={"自动识别（UTF-8 / BOM / GB18030）","UTF-8","UTF-16LE","UTF-16BE","GB18030"},values={"auto","UTF-8","UTF-16LE","UTF-16BE","GB18030"};UiChrome.dialog(this,theme).setTitle("源文本编码").setItems(labels,(d,w)->{charset=values[w];submit(this::showText);}).setNegativeButton("取消",null).show();}
    void loadPdf()throws Exception {
        PreviewRules.checkSize(entry.size,PreviewRules.MAX_PDF);ParcelFileDescriptor source=openSource();
        try{
            boolean seekable=true;try{Os.lseek(source.getFileDescriptor(),0,OsConstants.SEEK_CUR);}catch(ErrnoException ex){seekable=false;}
            if(seekable){ParcelFileDescriptor duplicate=ParcelFileDescriptor.dup(source.getFileDescriptor());try(InputStream input=new ParcelFileDescriptor.AutoCloseInputStream(duplicate)){activeInput=input;PreviewRules.copyVerified(input,null,entry.size,entry.sha256,PreviewRules.MAX_PDF,this::check);}finally{activeInput=null;}Os.lseek(source.getFileDescriptor(),0,OsConstants.SEEK_SET);check();pdf=createPdf(source);source=null;activeDescriptor=null;}
            else{ParcelFileDescriptor pipe=source;source=null;File cached=snapshot(pipe,PreviewRules.MAX_PDF,".pdf");ParcelFileDescriptor local=ParcelFileDescriptor.open(cached,ParcelFileDescriptor.MODE_READ_ONLY);try{check();pdf=createPdf(local);}catch(Exception|OutOfMemoryError e){local.close();throw e;}}
            pageCount=pdf.getPageCount();PreviewRules.pages(pageCount);renderPage(Math.max(0,Math.min(wantedPage,pageCount-1)));
        }catch(Exception|OutOfMemoryError e){closePdf();throw e;
        }finally{if(source!=null){source.close();if(activeDescriptor==source)activeDescriptor=null;}}
    }
    PdfRenderer createPdf(ParcelFileDescriptor source)throws IOException {try{return new PdfRenderer(source);}catch(SecurityException e){throw new IOException("PDF 可能受密码保护或加密方式不受支持，请用其他应用打开。",e);}}
    void renderPage(int index)throws IOException {check();if(pdf==null)throw new IOException("PDF 预览已关闭。");Bitmap bitmap=null;
        try(PdfRenderer.Page page=pdf.openPage(index)){int[] size=PreviewRules.pdfSize(page.getWidth(),page.getHeight(),getResources().getDisplayMetrics().widthPixels);bitmap=Bitmap.createBitmap(size[0],size[1],Bitmap.Config.ARGB_8888);bitmap.eraseColor(Color.WHITE);Matrix transform=new Matrix();transform.setScale(size[0]/(float)page.getWidth(),size[1]/(float)page.getHeight());page.render(bitmap,null,transform,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY);}
        catch(RuntimeException|OutOfMemoryError e){if(bitmap!=null)bitmap.recycle();throw e;}
        bitmap(bitmap,"PDF 只读预览 · 第 "+(index+1)+" / "+pageCount+" 页 · 双指缩放，单指拖动。",index,pageCount);
    }
    void bitmap(Bitmap bitmap,String message,int index,int count){if(closed){bitmap.recycle();return;}runOnUiThread(()->{if(closed){bitmap.recycle();return;}if(image==null){image=new ZoomView(this);body.removeAllViews();body.addView(image,new FrameLayout.LayoutParams(-1,-1));image.setContentDescription("只读图片或 PDF 页面。双指缩放、单指拖动，可使用适应屏幕按钮重置。");}Bitmap old=shown;shown=bitmap;image.bitmap(bitmap);if(old!=null&&old!=bitmap)old.recycle();status.setText(message);if(index>=0){currentPage=index;pageCount=count;pageBusy=false;pdfControls.setVisibility(View.VISIBLE);pageButtons();}rememberViewed();});}
    void pageButtons(){if(previous==null)return;previous.setEnabled(!pageBusy&&currentPage>0&&pageCount>0);next.setEnabled(!pageBusy&&currentPage+1<pageCount);jump.setEnabled(!pageBusy&&pageCount>0);if(pageCount>0)jump.setText((currentPage+1)+" / "+pageCount+" · 跳页");}
    void requestPage(int index){if(pageBusy||index<0||index>=pageCount||closed)return;pageBusy=true;pageButtons();status.setText("正在读取第 "+(index+1)+" 页…");submit(()->renderPage(index));}
    void choosePage(){if(pageCount<1||pageBusy)return;EditText input=new EditText(this);UiChrome.input(input,theme);input.setInputType(android.text.InputType.TYPE_CLASS_NUMBER);input.setSingleLine(true);input.setText(Integer.toString(currentPage+1));input.selectAll();AlertDialog dialog=UiChrome.dialog(this,theme).setTitle("前往页面（1–"+pageCount+"）").setView(input).setNegativeButton("取消",null).setPositiveButton("前往",null).create();dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{String value=input.getText().toString().trim();int number;try{if(!value.matches("[1-9][0-9]{0,4}"))throw new NumberFormatException();number=Integer.parseInt(value);if(number>pageCount)throw new NumberFormatException();}catch(NumberFormatException e){input.setError("填写 1–"+pageCount+" 的页码");return;}dialog.dismiss();requestPage(number-1);}));dialog.show();}
    void rememberViewed(){if(sourceMode||viewed||closed)return;viewed=true;try{worker.execute(()->{try{RecentFilesUi.rememberViewed(this,id);}catch(IOException e){viewed=false;runOnUiThread(()->{if(!closed)status.append(" 最近查看记录未保存，可继续阅读。");});}});}catch(RejectedExecutionException ignored){}}
    void requestOther(){if(entry==null)return;submit(()->{if(sourceMode){TransferUi.Source allowed=SourcePreviewSession.source(sourceToken,sourceIndex);RecentFiles.Entry fresh=new RecentFiles.Entry("","",sourceIndex,"",allowed.path,allowed.uri.toString(),"",0,0,0,false,"ready");runOnUiThread(()->{if(!closed)openOther(this,fresh,theme,true);});}else{RecentFiles.Entry fresh=RecentFilesUi.resolve(this,id,this::check);runOnUiThread(()->{if(!closed)openOther(this,fresh,theme);});}});}
    static void openOther(Activity activity,RecentFiles.Entry entry,UiTheme theme){
        openOther(activity,entry,theme,false);
    }
    private static void openOther(Activity activity,RecentFiles.Entry entry,UiTheme theme,boolean allowedSource){
        try{if(activity.isFinishing()||activity.isDestroyed()||(!allowedSource&&!PreviewRules.validCatalogId(entry.id)))return;Uri uri=sourceUri(entry);String mime=PreviewRules.mime(entry.path);if(mime.equals("application/octet-stream")){String actual=null;try{actual=activity.getContentResolver().getType(uri);}catch(RuntimeException ignored){}mime=actual==null?"*/*":actual;}
            Intent base=new Intent(Intent.ACTION_VIEW).setDataAndType(uri,mime).addCategory(Intent.CATEGORY_DEFAULT);base.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);base.setClipData(ClipData.newRawUri("VaultBridge 文件",uri));
            List<ResolveInfo> targets=new ArrayList<>();Set<String> seen=new HashSet<>();for(ResolveInfo found:activity.getPackageManager().queryIntentActivities(base,PackageManager.MATCH_DEFAULT_ONLY)){ActivityInfo component=found.activityInfo;if(component==null||!component.exported||component.packageName.equals(activity.getPackageName())||!seen.add(component.packageName+"/"+component.name))continue;targets.add(found);}
            if(targets.isEmpty()){UiChrome.dialog(activity,theme).setTitle("没有可打开此文件的应用").setMessage("可安装支持此格式的应用后再试，或在系统文件管理器中打开。源文件保持不变。").setPositiveButton("知道了",null).show();return;}
            final Intent request=base;Runnable single=()->launchOther(activity,request,targets.get(0),theme);if(targets.size()==1){single.run();return;}
            String[] labels=new String[targets.size()];for(int i=0;i<labels.length;i++)labels[i]=targets.get(i).loadLabel(activity.getPackageManager()).toString();UiChrome.dialog(activity,theme).setTitle("选择其他应用（临时只读）").setItems(labels,(d,w)->launchOther(activity,request,targets.get(w),theme)).setNegativeButton("取消",null).show();
        }catch(Exception e){UiChrome.dialog(activity,theme).setTitle("无法交给其他应用").setMessage("文件可能已移动或授权失效，请返回文件页重新核对。").setPositiveButton("知道了",null).show();}
    }
    static void launchOther(Activity activity,Intent base,ResolveInfo target,UiTheme theme){try{Intent explicit=new Intent(base);explicit.setComponent(new ComponentName(target.activityInfo.packageName,target.activityInfo.name));activity.startActivity(explicit);Toast.makeText(activity,"已请求其他应用打开；不表示文件已经阅读。",Toast.LENGTH_LONG).show();}catch(ActivityNotFoundException|SecurityException e){UiChrome.dialog(activity,theme).setTitle("未能打开").setMessage("此应用无法访问文件，请选择其他应用或重新核对目录授权。").setPositiveButton("知道了",null).show();}}
    void closePdf(){if(pdf!=null){try{pdf.close();}catch(RuntimeException ignored){}pdf=null;}pageCount=0;}
    @Override protected void onSaveInstanceState(Bundle out){out.putInt("page",currentPage);out.putString("encoding",charset);super.onSaveInstanceState(out);}
    @Override protected void onDestroy(){closed=true;cancellation.cancel();InputStream input=activeInput;if(input!=null)try{input.close();}catch(IOException ignored){}ParcelFileDescriptor descriptor=activeDescriptor;if(descriptor!=null)try{descriptor.close();}catch(IOException ignored){}if(image!=null)image.bitmap(null);if(shown!=null){shown.recycle();shown=null;}textBytes=null;try{worker.execute(this::closePdf);}catch(RejectedExecutionException ignored){}worker.shutdown();super.onDestroy();}

    /** One bounded bitmap, fit-relative pinch zoom and clamped single-finger pan. */
    static final class ZoomView extends View {
        Bitmap source;float zoom=1,panX,panY,lastX,lastY;final Matrix matrix=new Matrix();final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG|Paint.FILTER_BITMAP_FLAG);final ScaleGestureDetector scale;final GestureDetector gestures;
        ZoomView(Context context){super(context);scale=new ScaleGestureDetector(context,new ScaleGestureDetector.SimpleOnScaleGestureListener(){@Override public boolean onScale(ScaleGestureDetector d){float next=Math.max(1,Math.min(8,zoom*d.getScaleFactor())),factor=next/zoom;panX=d.getFocusX()-getWidth()/2f-(d.getFocusX()-getWidth()/2f-panX)*factor;panY=d.getFocusY()-getHeight()/2f-(d.getFocusY()-getHeight()/2f-panY)*factor;zoom=next;clamp();invalidate();return true;}});gestures=new GestureDetector(context,new GestureDetector.SimpleOnGestureListener(){@Override public boolean onDown(MotionEvent e){return true;}@Override public boolean onDoubleTap(MotionEvent e){if(zoom>1)fit();else{zoom=2.5f;panX=(getWidth()/2f-e.getX())*(zoom-1);panY=(getHeight()/2f-e.getY())*(zoom-1);clamp();invalidate();}return true;}});setFocusable(true);}
        void bitmap(Bitmap value){source=value;fit();}
        void fit(){zoom=1;panX=panY=0;invalidate();}
        float base(){return source==null?1:Math.min(getWidth()/(float)source.getWidth(),getHeight()/(float)source.getHeight());}
        void clamp(){if(source==null)return;float x=Math.max(0,(source.getWidth()*base()*zoom-getWidth())/2f),y=Math.max(0,(source.getHeight()*base()*zoom-getHeight())/2f);panX=Math.max(-x,Math.min(x,panX));panY=Math.max(-y,Math.min(y,panY));}
        @Override protected void onSizeChanged(int w,int h,int oldw,int oldh){super.onSizeChanged(w,h,oldw,oldh);clamp();}
        @Override protected void onDraw(Canvas canvas){super.onDraw(canvas);if(source==null||source.isRecycled())return;float s=base()*zoom;matrix.setScale(s,s);matrix.postTranslate((getWidth()-source.getWidth()*s)/2f+panX,(getHeight()-source.getHeight()*s)/2f+panY);canvas.drawBitmap(source,matrix,paint);}
        @Override public boolean onTouchEvent(MotionEvent event){scale.onTouchEvent(event);gestures.onTouchEvent(event);if(event.getActionMasked()==MotionEvent.ACTION_DOWN){lastX=event.getX();lastY=event.getY();getParent().requestDisallowInterceptTouchEvent(true);}else if(event.getActionMasked()==MotionEvent.ACTION_MOVE&&event.getPointerCount()==1&&!scale.isInProgress()){panX+=event.getX()-lastX;panY+=event.getY()-lastY;clamp();invalidate();lastX=event.getX();lastY=event.getY();}else if(event.getActionMasked()==MotionEvent.ACTION_POINTER_UP){int survivor=event.getActionIndex()==0?1:0;if(survivor<event.getPointerCount()){lastX=event.getX(survivor);lastY=event.getY(survivor);}}else if(event.getActionMasked()==MotionEvent.ACTION_UP){performClick();getParent().requestDisallowInterceptTouchEvent(false);}return true;}
        @Override public boolean performClick(){super.performClick();return true;}
    }
}
