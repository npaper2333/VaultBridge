package io.vaultbridge.app;

import android.content.ContentResolver;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import java.io.*;

/** Separate read-only access; never relax the note engine's hidden-path protection. */
final class AppearanceReader {
    static Uri child(ContentResolver resolver,Uri tree,Uri parent,String name,boolean directory)throws IOException{
        Uri children=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent));Uri found=null;
        try(Cursor c=resolver.query(children,new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE},null,null,null)){
            if(c==null)throw new IOException("无法读取库配置目录");
            while(c.moveToNext())if(name.equals(c.getString(1))){if(found!=null)throw new IOException("配置文件重名");boolean dir=DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(2));if(dir!=directory)throw new IOException("配置路径类型不符");found=DocumentsContract.buildDocumentUriUsingTree(tree,c.getString(0));}
        }return found;
    }
    static AppearanceConfig read(ContentResolver resolver,String location,boolean systemDark){
        if(location.isEmpty())return AppearanceConfig.fallback(systemDark,"尚未选择 Obsidian 库");
        try{
            Uri tree=Uri.parse(location),root=DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree));
            Uri folder=child(resolver,tree,root,".obsidian",true);if(folder==null)return AppearanceConfig.fallback(systemDark,"当前库未找到 .obsidian/appearance.json");
            Uri file=child(resolver,tree,folder,"appearance.json",false);if(file==null)return AppearanceConfig.fallback(systemDark,"当前库未找到 .obsidian/appearance.json");
            try(InputStream input=resolver.openInputStream(file)){if(input==null)throw new IOException("无法打开外观配置");return AppearanceConfig.read(input,systemDark);}
        }catch(Exception ex){return AppearanceConfig.fallback(systemDark,"无法读取当前库外观配置，请检查目录授权");}
    }
}
