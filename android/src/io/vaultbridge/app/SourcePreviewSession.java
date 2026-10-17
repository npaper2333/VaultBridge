package io.vaultbridge.app;

import android.content.*;
import android.net.Uri;
import android.database.Cursor;
import android.provider.OpenableColumns;
import java.io.*;
import java.security.*;
import java.util.*;

/** In-memory allowlist for sources selected by the user. It is separate from received-file IDs. */
final class SourcePreviewSession {
    static final String EXTRA_TOKEN="sourceSession",EXTRA_INDEX="sourceIndex";
    static final SourceSessionModel sessions=new SourceSessionModel();
    static String create(List<TransferUi.Source> sources)throws IOException {List<SourceSessionModel.Item> items=new ArrayList<>();for(TransferUi.Source s:sources)items.add(new SourceSessionModel.Item(s.path,s.uri.toString()));return sessions.create(items);}
    static void close(String token){sessions.close(token);}
    static TransferUi.Source source(String token,int index)throws IOException {SourceSessionModel.Item item=sessions.resolve(token,index);return new TransferUi.Source(item.path,Uri.parse(item.uri));}
    static RecentFiles.Entry resolve(Context context,String token,int index,TransferEngine.Control control)throws IOException {
        TransferUi.Source source=source(token,index);String mime="";if(PreviewRules.extension(source.path).isEmpty())try{mime=context.getContentResolver().getType(source.uri);}catch(RuntimeException ignored){}String kind=PreviewRules.kind(source.path,mime);long limit=kind.equals("image")?PreviewRules.MAX_IMAGE:kind.equals("pdf")?PreviewRules.MAX_PDF:kind.equals("markdown")||kind.equals("text")?PreviewRules.MAX_TEXT:0;
        long declared=-1;try(Cursor cursor=context.getContentResolver().query(source.uri,new String[]{OpenableColumns.SIZE},null,null,null)){if(cursor!=null&&cursor.moveToFirst()&&!cursor.isNull(0))declared=cursor.getLong(0);}catch(RuntimeException ex){throw new IOException("来源授权已失效，请重新选择",ex);}
        // Unsupported formats are passed only to an explicitly selected external application.
        if(limit==0)return new RecentFiles.Entry("","",index,"",source.path,source.uri.toString(),"",Math.max(0,declared),0,0,false,"ready");
        if(declared>limit)throw new IOException("来源超过内置预览大小限制，可使用其他应用只读打开");
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");long size=0;try(InputStream input=context.getContentResolver().openInputStream(source.uri)){if(input==null)throw new IOException("无法读取所选来源");byte[] buffer=new byte[65536];int n;while((n=input.read(buffer))!=-1){control.check();size+=n;if(size>limit)throw new IOException("来源超过内置预览大小限制，可使用其他应用只读打开");digest.update(buffer,0,n);}}control.check();return new RecentFiles.Entry("","",index,"",source.path,source.uri.toString(),Engine.hex(digest.digest()),size,0,0,false,"ready");}catch(NoSuchAlgorithmException ex){throw new IOException(ex);}
    }
}
