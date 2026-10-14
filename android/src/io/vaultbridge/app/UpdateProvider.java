package io.vaultbridge.app;

import android.content.*;
import android.database.*;
import android.net.Uri;
import android.os.ParcelFileDescriptor;
import android.provider.OpenableColumns;
import java.io.*;
import java.util.*;

/** A single verified APK, exposed only through a temporary read grant. No caller-provided paths. */
public final class UpdateProvider extends ContentProvider {
    static final String AUTHORITY="io.vaultbridge.app.updates";
    static final String PATH="updateVerifiedPath",INFO="updateVerifiedInfo",TOKEN="updateVerifiedToken";
    static SharedPreferences preferences(Context context){return context.getSharedPreferences("vaultbridge",Context.MODE_PRIVATE);}
    static File root(Context context){return new File(context.getCacheDir(),"updates");}
    static boolean own(Uri uri){return uri!=null&&"content".equals(uri.getScheme())&&AUTHORITY.equals(uri.getAuthority());}
    static File bounded(Context context,String path,UpdateInfo info)throws IOException {
        File root=root(context).getCanonicalFile(),file=new File(path).getCanonicalFile();
        if(!file.getPath().startsWith(root.getPath()+File.separator)||!file.getName().equals(info.name)||!file.isFile()||file.length()!=info.size||info.sha256.isEmpty())throw new IOException("已验证更新文件不可用，请重新下载");return file;
    }
    static Uri authorize(Context context,File file,UpdateInfo info)throws IOException {
        file=bounded(context,file.getCanonicalPath(),info);String token=UUID.randomUUID().toString().replace("-","")+UUID.randomUUID().toString().replace("-","");
        if(!preferences(context).edit().putString(PATH,file.getCanonicalPath()).putString(INFO,info.encode()).putString(TOKEN,token).commit())throw new IOException("无法保存已验证更新记录");
        return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("apk").appendPath(token).build();
    }
    static Uri currentUri(Context context)throws IOException {String token=preferences(context).getString(TOKEN,"");if(!token.matches("[0-9a-f]{64}"))throw new IOException("没有可安装的已验证更新");return new Uri.Builder().scheme("content").authority(AUTHORITY).appendPath("apk").appendPath(token).build();}
    File file(Uri uri)throws IOException {
        SharedPreferences prefs=preferences(getContext());List<String> parts=uri==null?Collections.emptyList():uri.getPathSegments();
        if(!own(uri)||uri.getQuery()!=null||uri.getFragment()!=null||parts.size()!=2||!parts.get(0).equals("apk")||!parts.get(1).matches("[0-9a-f]{64}")||!parts.get(1).equals(prefs.getString(TOKEN,"")))throw new IOException("更新读取授权无效");
        return bounded(getContext(),prefs.getString(PATH,""),UpdateInfo.decode(prefs.getString(INFO,"")));
    }
    @Override public boolean onCreate(){return true;}
    @Override public String getType(Uri uri){try{file(uri);return "application/vnd.android.package-archive";}catch(IOException e){return null;}}
    @Override public Cursor query(Uri uri,String[] projection,String selection,String[] selectionArgs,String sortOrder){
        try{File file=file(uri);String[] columns=projection==null?new String[]{OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE}:projection;MatrixCursor cursor=new MatrixCursor(columns,1);Object[] row=new Object[columns.length];for(int i=0;i<columns.length;i++)row[i]=OpenableColumns.DISPLAY_NAME.equals(columns[i])?file.getName():OpenableColumns.SIZE.equals(columns[i])?file.length():null;cursor.addRow(row);return cursor;}
        catch(IOException e){return null;}
    }
    @Override public ParcelFileDescriptor openFile(Uri uri,String mode)throws FileNotFoundException {
        try{if(!"r".equals(mode))throw new IOException("更新安装包仅允许读取");File file=file(uri);UpdateInfo info=UpdateInfo.decode(preferences(getContext()).getString(INFO,""));UpdateUi.verifyApk(getContext(),file,info);return ParcelFileDescriptor.open(file,ParcelFileDescriptor.MODE_READ_ONLY);}
        catch(IOException|RuntimeException e){throw new FileNotFoundException("更新安装包不可用或验证失败");}
    }
    @Override public Uri insert(Uri uri,ContentValues values){throw new UnsupportedOperationException("只读更新安装包");}
    @Override public int update(Uri uri,ContentValues values,String selection,String[] args){throw new UnsupportedOperationException("只读更新安装包");}
    @Override public int delete(Uri uri,String selection,String[] args){throw new UnsupportedOperationException("不删除更新安装包");}
}
