package io.vaultbridge.app;

import android.content.*;
import android.graphics.*;
import android.media.ExifInterface;
import android.os.*;
import android.util.LruCache;
import android.view.*;
import android.widget.ImageView;
import java.io.*;
import java.lang.ref.WeakReference;
import java.util.*;
import java.util.concurrent.*;

/** One visible-row decoder, byte-counted LRU and one replaceable binding per ImageView. */
final class SourceThumbnailLoader {
    final Context context;final Handler main=new Handler(Looper.getMainLooper());
    final ExecutorService worker=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<Runnable>(12),new ThreadPoolExecutor.AbortPolicy());
    final LruCache<String,Bitmap> cache=new LruCache<String,Bitmap>(8*1024*1024){protected int sizeOf(String k,Bitmap b){return b.getAllocationByteCount();}};
    final Map<ImageView,Binding> bindings=new WeakHashMap<>();volatile boolean closed;volatile InputStream active;long epoch;
    SourceThumbnailLoader(Context context){this.context=context.getApplicationContext();}
    interface Source {RecentFiles.Entry resolve()throws IOException;}
    void source(ImageView view,TransferUi.Source source){bind(view,"source:"+epoch+":"+source.uri+":"+source.path+":"+source.expectedSize+":"+source.expectedSha,()->new RecentFiles.Entry("","",0,"",source.path,source.uri.toString(),source.expectedSha,source.expectedSize,0,0,false,"ready"));}
    void received(ImageView view,RecentFiles.Entry entry){bind(view,"received:"+entry.id+":"+entry.sha256+":"+entry.state,()->{PreviewRules.checkSize(entry.size,PreviewRules.MAX_IMAGE);return RecentFilesUi.resolve(context,entry.id,this::check);});}
    void check()throws IOException {if(closed||Thread.currentThread().isInterrupted())throw new IOException("缩略图已关闭");}
    void check(Binding binding)throws IOException {check();if(!binding.alive||!binding.visible)throw new IOException("图片行已离开可见区");}
    final class Binding implements View.OnAttachStateChangeListener,ViewTreeObserver.OnScrollChangedListener {
        final WeakReference<ImageView> image;final String key;final Source source;volatile boolean alive=true,visible;boolean running,failed;ViewTreeObserver observer;
        Binding(ImageView view,String key,Source source){image=new WeakReference<>(view);this.key=key;this.source=source;}
        void observe(){ImageView view=image.get();if(view==null||!alive)return;if(observer!=null&&observer.isAlive())observer.removeOnScrollChangedListener(this);observer=view.getViewTreeObserver();observer.addOnScrollChangedListener(this);view.post(this::inspect);}
        void inspect(){ImageView view=image.get();visible=alive&&!closed&&view!=null&&view.isAttachedToWindow()&&view.getGlobalVisibleRect(new Rect());if(visible)load(this);}
        public void onScrollChanged(){inspect();}
        public void onViewAttachedToWindow(View view){observe();}
        public void onViewDetachedFromWindow(View view){visible=false;if(observer!=null&&observer.isAlive())observer.removeOnScrollChangedListener(this);observer=null;}
        void detach(){alive=false;visible=false;ImageView view=image.get();if(view!=null)view.removeOnAttachStateChangeListener(this);if(observer!=null&&observer.isAlive())observer.removeOnScrollChangedListener(this);observer=null;}
    }
    void unbind(ImageView view){Binding old=bindings.remove(view);if(old!=null)old.detach();view.setImageDrawable(null);}
    void bind(ImageView view,String key,Source source){unbind(view);if(closed)return;view.setScaleType(ImageView.ScaleType.CENTER_CROP);view.setImageResource(android.R.drawable.ic_menu_gallery);Binding binding=new Binding(view,key,source);bindings.put(view,binding);view.addOnAttachStateChangeListener(binding);if(view.isAttachedToWindow())binding.observe();}
    void load(Binding binding){ImageView view=binding.image.get();if(closed||view==null||!binding.alive||!binding.visible)return;Bitmap hit=cache.get(binding.key);if(hit!=null){view.setImageBitmap(hit);return;}if(binding.running||binding.failed)return;binding.running=true;
        try{worker.execute(()->{Bitmap bitmap=null;try{check(binding);RecentFiles.Entry entry=binding.source.resolve();byte[] bytes;
            try(InputStream input=context.getContentResolver().openInputStream(PreviewActivity.sourceUri(entry));ByteArrayOutputStream output=new ByteArrayOutputStream()){if(input==null)throw new IOException("图片无法读取");active=input;byte[] buffer=new byte[32768];int n;while((n=input.read(buffer))!=-1){check(binding);if(output.size()+(long)n>PreviewRules.MAX_IMAGE)throw new IOException("图片超过缩略图上限");output.write(buffer,0,n);}bytes=output.toByteArray();}finally{active=null;}
            if(!entry.sha256.isEmpty()&&(bytes.length!=entry.size||!PreviewRules.sha256(bytes).equals(entry.sha256)))throw new IOException("来源内容已变化，请重新对比");
            BitmapFactory.Options bounds=new BitmapFactory.Options();bounds.inJustDecodeBounds=true;BitmapFactory.decodeByteArray(bytes,0,bytes.length,bounds);int sample=PreviewRules.imageSample(bounds.outWidth,bounds.outHeight);while(Math.max(bounds.outWidth,bounds.outHeight)/(long)sample>320)sample*=2;
            BitmapFactory.Options options=new BitmapFactory.Options();options.inSampleSize=sample;options.inPreferredConfig=Bitmap.Config.ARGB_8888;bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.length,options);if(bitmap==null)throw new IOException("图片格式暂不支持");if(bitmap.getAllocationByteCount()>1024*1024)throw new IOException("缩略图超过内存限制");
            try{Matrix matrix=orientation(new ExifInterface(new ByteArrayInputStream(bytes)).getAttributeInt(ExifInterface.TAG_ORIENTATION,1));if(!matrix.isIdentity()){Bitmap rotated=Bitmap.createBitmap(bitmap,0,0,bitmap.getWidth(),bitmap.getHeight(),matrix,true);if(rotated!=bitmap){bitmap.recycle();bitmap=rotated;}}}catch(IOException ignored){}
            check(binding);final Bitmap ready=bitmap;bitmap=null;main.post(()->{binding.running=false;if(closed||!binding.alive){ready.recycle();return;}cache.put(binding.key,ready);ImageView image=binding.image.get();if(image!=null&&bindings.get(image)==binding&&binding.visible)image.setImageBitmap(ready);});
        }catch(Exception|OutOfMemoryError e){if(bitmap!=null&&!bitmap.isRecycled())bitmap.recycle();main.post(()->{binding.running=false;if(closed||!binding.alive)return;binding.failed=binding.visible;ImageView image=binding.image.get();if(binding.failed&&image!=null&&bindings.get(image)==binding)image.setImageResource(android.R.drawable.ic_menu_report_image);});}});}
        catch(RejectedExecutionException e){binding.running=false;if(!closed)main.postDelayed(binding::inspect,250);}
    }
    void invalidateSources(){epoch++;cache.evictAll();for(Map.Entry<ImageView,Binding> entry:new ArrayList<>(bindings.entrySet())){entry.getValue().detach();entry.getKey().setImageDrawable(null);}bindings.clear();}
    static Matrix orientation(int o){Matrix m=new Matrix();switch(o){case 2:m.setScale(-1,1);break;case 3:m.setRotate(180);break;case 4:m.setScale(1,-1);break;case 5:m.setRotate(90);m.postScale(-1,1);break;case 6:m.setRotate(90);break;case 7:m.setRotate(270);m.postScale(-1,1);break;case 8:m.setRotate(270);break;default:break;}return m;}
    void close(){closed=true;InputStream input=active;if(input!=null)try{input.close();}catch(IOException ignored){}worker.shutdownNow();invalidateSources();}
}