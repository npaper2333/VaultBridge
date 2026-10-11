package io.vaultbridge.app;

import android.content.ContentResolver;
import android.content.SharedPreferences;
import android.database.Cursor;
import android.net.Uri;
import android.provider.DocumentsContract;
import org.json.*;
import java.io.*;
import java.util.*;

final class DocumentStore implements DirectoryEngine.Tree {
    final ContentResolver resolver;
    final Uri tree;
    final Map<String, Uri> cache = new HashMap<>();
    final Map<String, Map<String, Uri>> children = new HashMap<>();
    DocumentStore(ContentResolver r, Uri t) { resolver = r; tree = t; cache.put("", DocumentsContract.buildDocumentUriUsingTree(t, DocumentsContract.getTreeDocumentId(t))); }
    Map<String, Uri> children(String prefix, Uri parent) throws IOException {
        if (children.containsKey(prefix)) return children.get(prefix);
        Map<String, Uri> items = new HashMap<>();
        Uri listing = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getDocumentId(parent));
        try (Cursor cursor = resolver.query(listing, new String[] { DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) {
            if (cursor == null) throw new IOException("无法读取目标目录，重新授权后再试");
            while (cursor.moveToNext()) { String name = cursor.getString(1); if (items.containsKey(name)) throw new IOException("目标目录有重名文件：" + name); items.put(name, DocumentsContract.buildDocumentUriUsingTree(tree, cursor.getString(0))); }
        } catch (RuntimeException ex) { throw new IOException("目录授权失效或文件提供器不可用", ex); }
        children.put(prefix, items); return items;
    }
    Uri find(String relative, boolean create, boolean directory) throws IOException {
        String path = Engine.normalize(relative); Uri existing = cache.get(path);
        if (existing != null) return existing;
        String[] parts = path.split("/"); String prefix = ""; Uri parent = cache.get("");
        for (int i = 0; i < parts.length; i++) {
            Map<String, Uri> listing = children(prefix, parent); String name = parts[i]; boolean dir = i < parts.length - 1 || directory;
            Uri uri = listing.get(name);
            if (uri == null) {
                for (String other : listing.keySet()) if (other.equalsIgnoreCase(name)) throw new IOException("目标文件名大小写冲突：" + name);
                if (!create) return null;
                try { uri = DocumentsContract.createDocument(resolver, parent, dir ? DocumentsContract.Document.MIME_TYPE_DIR : mime(name), name); }
                catch (Exception ex) { throw new IOException("无法创建文件：" + name, ex); }
                if (uri == null) throw new IOException("文件提供器拒绝创建：" + name);
                String actual = displayName(uri); if (!name.equals(actual)) throw new IOException("文件提供器改变了文件名：" + actual + "，已停止导入");
                listing.put(name, uri);
            }
            if (dir && !DocumentsContract.Document.MIME_TYPE_DIR.equals(type(uri))) throw new IOException("目标同名文件占用了目录：" + name);
            if (!dir && DocumentsContract.Document.MIME_TYPE_DIR.equals(type(uri))) throw new IOException("目标同名目录占用了文件：" + name);
            prefix = prefix.isEmpty() ? name : prefix + "/" + name; cache.put(prefix, uri); parent = uri;
        }
        return parent;
    }
    String type(Uri uri) throws IOException { try { String type = resolver.getType(uri); if (type == null) throw new IOException("无法读取文件类型"); return type; } catch (RuntimeException ex) { throw new IOException("目录授权失效", ex); } }
    String displayName(Uri uri) throws IOException { try (Cursor cursor = resolver.query(uri, new String[] { DocumentsContract.Document.COLUMN_DISPLAY_NAME }, null, null, null)) { if (cursor == null || !cursor.moveToFirst()) throw new IOException("文件名读取失败"); return cursor.getString(0); } }
    static String mime(String name) { String lower = name.toLowerCase(Locale.ROOT); if (lower.endsWith(".md")) return "text/markdown"; if (lower.endsWith(".png")) return "image/png"; if (lower.endsWith(".jpg") || lower.endsWith(".jpeg")) return "image/jpeg"; if (lower.endsWith(".pdf")) return "application/pdf"; return "application/octet-stream"; }
    @Override public boolean exists(String path) throws IOException { return find(path, false, false) != null; }
    @Override public InputStream read(String path) throws IOException { Uri file = find(path, false, false); if (file == null) throw new IOException("文件不存在：" + path); InputStream input; try { input = resolver.openInputStream(file); } catch (RuntimeException ex) { throw new IOException("无法读取文件", ex); } if (input == null) throw new IOException("无法读取文件"); return input; }
    @Override public void write(String path, InputStream input) throws IOException { Uri file = find(path, true, false); try (OutputStream output = resolver.openOutputStream(file, "wt")) { if (output == null) throw new IOException("无法写入目标文件"); Engine.copy(input, output, Engine.MAX_FILE); } catch (RuntimeException ex) { throw new IOException("目录授权失效或写入被拒绝", ex); } }
    Uri directory(String path) throws IOException { Uri uri=path.isEmpty()?cache.get(""):find(path,false,true);if(uri==null)throw new IOException("目录不存在："+path);return uri; }
    int flags(Uri uri) throws IOException {try(Cursor c=resolver.query(uri,new String[]{DocumentsContract.Document.COLUMN_FLAGS},null,null,null)){if(c==null||!c.moveToFirst())throw new IOException("无法检查系统目录能力");return c.getInt(0);}catch(RuntimeException ex){throw new IOException("目录授权失效",ex);} }
    @Override public List<DirectoryEngine.Entry> list(String path) throws IOException {
        if(!path.isEmpty())PathState.normal(path);Uri parent=directory(path),listing=DocumentsContract.buildChildDocumentsUriUsingTree(tree,DocumentsContract.getDocumentId(parent));List<DirectoryEngine.Entry> result=new ArrayList<>();Set<String> names=new HashSet<>();
        try(Cursor c=resolver.query(listing,new String[]{DocumentsContract.Document.COLUMN_DISPLAY_NAME,DocumentsContract.Document.COLUMN_MIME_TYPE,DocumentsContract.Document.COLUMN_SIZE,DocumentsContract.Document.COLUMN_FLAGS},null,null,null)){
            if(c==null)throw new IOException("目录无法读取，请重新授权");while(c.moveToNext()){String name=c.getString(0),relative=path.isEmpty()?name:path+"/"+name;if(Engine.protectedPath(relative))continue;PathState.normal(relative);if(!names.add(name.toLowerCase(Locale.ROOT)))throw new IOException("目录存在大小写重名："+name);boolean dir=DocumentsContract.Document.MIME_TYPE_DIR.equals(c.getString(1));int f=c.getInt(3);result.add(new DirectoryEngine.Entry(relative,dir,c.isNull(2)?0:c.getLong(2),(f&DocumentsContract.Document.FLAG_SUPPORTS_RENAME)!=0,(f&DocumentsContract.Document.FLAG_SUPPORTS_MOVE)!=0));if(result.size()>Engine.MAX_FILES)throw new IOException("目录文件过多");}
        }catch(RuntimeException ex){throw new IOException("目录权限或文件提供器不可用",ex);}result.sort((a,b)->a.directory!=b.directory?(a.directory?-1:1):a.path.compareToIgnoreCase(b.path));return result;
    }
    @Override public void createFolder(String path) throws IOException {PathState.normal(path);if(find(path,false,true)!=null)throw new IOException("同名文件夹已经存在");find(path,true,true);}
    void checkOrdinarySubtree(String path) throws IOException { checkOrdinarySubtree(path,new int[]{0}); }
    private void checkOrdinarySubtree(String path,int[] count) throws IOException {
        Uri parent=directory(path);Map<String,Uri> raw=children(path,parent);
        for(Map.Entry<String,Uri> e:raw.entrySet()){String child=path+"/"+e.getKey();if(++count[0]>Engine.MAX_FILES)throw new IOException("目录条目超过上限");if(Engine.protectedPath(child))throw new IOException("这个文件夹含有隐藏配置或备份，不能整体调整："+child);PathState.normal(child);if(DocumentsContract.Document.MIME_TYPE_DIR.equals(type(e.getValue())))checkOrdinarySubtree(child,count);}
    }
    @Override public void relocate(String from,String to,boolean dir) throws IOException {
        PathState.normal(from);PathState.normal(to);Uri source=find(from,false,dir);if(source==null)throw new IOException("来源不存在");if(dir)checkOrdinarySubtree(from);Uri targetParent=directory(DirectoryEngine.parent(to));for(DirectoryEngine.Entry e:list(DirectoryEngine.parent(to)))if(e.path.equalsIgnoreCase(to))throw new IOException("目标同名条目已经存在");Uri moved;
        try {
            if(DirectoryEngine.parent(from).equals(DirectoryEngine.parent(to))){if((flags(source)&DocumentsContract.Document.FLAG_SUPPORTS_RENAME)==0)throw new IOException("系统不支持重命名");moved=DocumentsContract.renameDocument(resolver,source,DirectoryEngine.name(to));}
            else {if(!DirectoryEngine.name(from).equals(DirectoryEngine.name(to)))throw new IOException("跨目录移动时请保持名称；移动后可再重命名");if((flags(source)&DocumentsContract.Document.FLAG_SUPPORTS_MOVE)==0)throw new IOException("系统不支持此项移动；不会复制后删除");moved=DocumentsContract.moveDocument(resolver,source,directory(DirectoryEngine.parent(from)),targetParent);}
            if(moved==null)throw new IOException("文件提供器未完成目录调整");
        }catch(RuntimeException ex){throw new IOException("目录调整被系统拒绝，请恢复目录记录后再试",ex);}
        cache.clear();children.clear();cache.put("",DocumentsContract.buildDocumentUriUsingTree(tree,DocumentsContract.getTreeDocumentId(tree)));if(find(to,false,dir)==null||find(from,false,dir)!=null)throw new IOException("目录调整后的路径与预期不符，先恢复目录记录再同步");
    }
}

final class AndroidLedger extends PathState {
    final SharedPreferences preferences;
    final String key;
    AndroidLedger(SharedPreferences prefs, String tree) throws IOException {
        this(prefs, keyFor(tree), true);
    }
    private AndroidLedger(SharedPreferences prefs,String storageKey,boolean internal) throws IOException {
        super(prefs.getString(storageKey,PathState.empty()),value->{if(!prefs.edit().putString(storageKey,value).commit())throw new IOException("无法保存版本/目录记录，请检查空间");});preferences=prefs;key=storageKey;
    }
    static String keyFor(String tree) throws IOException {return "ledger-"+Engine.hash(new ByteArrayInputStream(tree.getBytes("UTF-8")));}
}
