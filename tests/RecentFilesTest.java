package io.vaultbridge.app;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import org.json.*;

/** Private synthetic catalog only; no Android provider, real file content or network. */
public final class RecentFilesTest {
    static int passed;
    static final String TREE="content://example.documents/tree/root",URI="content://example.documents/tree/root/document/root%3Anote.md";
    static void check(boolean condition,String label){if(!condition)throw new AssertionError(label);passed++;System.out.println("PASS: "+label);}
    interface Action{void run()throws Exception;}
    static void rejects(Action action,String label)throws Exception {try{action.run();throw new AssertionError(label);}catch(IOException expected){check(true,label);}}
    static JSONObject item(String path,byte[] bytes,String uri,long received)throws Exception {JSONObject item=new JSONObject().put("path",path).put("saved",path).put("done",true).put("size",bytes.length).put("sha256",TransferEngine.hash(bytes));if(uri!=null)item.put("finalUri",uri);if(received>0)item.put("receivedAt",received);return item;}
    static JSONObject job(String id,String direction,String mode,JSONObject...items)throws Exception {JSONArray files=new JSONArray();for(JSONObject item:items)files.put(item);return new JSONObject().put("id",id).put("direction",direction).put("mode",mode).put("created",100).put("tree",TREE).put("state","cancelled").put("pin","private pairing must not be indexed").put("owner","private owner must not be indexed").put("files",files);}
    static String key(String job,int index)throws IOException{return RecentFiles.id(job,index);}
    public static void main(String[] args)throws Exception {
        File workspace=new File(args[0],"recent-files-synthetic");if(!workspace.isDirectory()&&!workspace.mkdirs())throw new IOException("test folder");RecentFiles catalog=new RecentFiles(workspace);byte[] bytes="only synthetic bytes".getBytes("UTF-8");String first="aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",second="bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",old="cccccccccccccccccccccccccccccccc";
        check(catalog.page("","all",false,0,30).total==0,"empty catalog starts without scanning any storage");
        JSONObject complete=item("课程/笔记.md",bytes,URI,1000);JSONObject original=job(first,"down","files",complete);String jobText=original.toString();catalog.indexJob(original);
        RecentFiles.Entry entry=catalog.get(key(first,0));check(entry.uri.equals(URI)&&entry.receivedAt==1000&&!entry.legacy&&!entry.estimatedTime,"completed download retains actual final URI and receipt timestamp");
        check(original.toString().equals(jobText),"catalog indexing does not rewrite or migrate source job records");
        check(catalog.page("","all",false,0,30).total==1,"completed item remains visible when its batch is cancelled");
        String serialized=new String(Files.readAllBytes(new File(catalog.root,"catalog.json").toPath()),"UTF-8");check(!serialized.contains("private pairing")&&!serialized.contains("private owner")&&!serialized.contains("source body"),"catalog excludes pairing data and file bodies");
        catalog.indexJob(job(second,"up","files",item("sent.md",bytes,URI+"-up",2000)));catalog.indexJob(job(second,"down","notes",item("note-pull.md",bytes,URI+"-notes",2000)));
        check(catalog.page("","all",false,0,30).total==1,"sending and ordinary-note synchronization are excluded");
        JSONObject partial=item("VaultBridge-123.partial",bytes,URI+"-partial",2000),unfinished=item("unfinished.md",bytes,URI+"-unfinished",2000).put("done",false);catalog.indexJob(job(second,"down","files",partial,unfinished));
        check(catalog.page("","all",false,0,30).total==1,"partial documents and unfinished items are excluded");
        check(catalog.page("笔记","markdown",false,0,30).total==1&&catalog.page("笔记","image",false,0,30).total==0,"name search and type filter operate only on metadata");
        catalog.viewed(entry.id,4000);check(catalog.page("","all",true,0,30).entries.get(0).viewedAt==4000,"successful viewed timestamp persists independently");
        catalog.indexJob(job(second,"down","files",item("课程/笔记.md",bytes,URI,2000)));check(catalog.entries().size()==2&&catalog.page("","all",false,0,30).total==1,"same final URI is deduplicated in the view while task records remain");
        check(catalog.page("","all",true,0,30).entries.get(0).viewedAt==4000&&catalog.page("","all",false,0,30).entries.get(0).job.equals(second),"stable file identity keeps viewed time across repeated receives");
        RecentFiles restarted=new RecentFiles(workspace);check(restarted.get(key(second,0)).viewedAt==4000,"catalog and view statistics survive model restart");
        catalog.indexJob(job(old,"down","files",item("旧笔记.txt",bytes,null,0)));RecentFiles.Entry legacy=catalog.get(key(old,0));check(legacy.legacy&&legacy.state.equals("pending")&&legacy.estimatedTime&&legacy.receivedAt==100,"old completed tasks remain pending with explicitly estimated task time");
        rejects(()->catalog.viewed(legacy.id,5000),"unverified old records cannot appear as recently viewed");
        check(RecentFiles.verified(new ByteArrayInputStream(bytes),bytes.length,TransferEngine.hash(bytes),null),"legacy exact byte count and full SHA can be verified before URI backfill");
        byte[] bad=bytes.clone();bad[0]^=1;check(!RecentFiles.verified(new ByteArrayInputStream(bad),bytes.length,TransferEngine.hash(bytes),null),"legacy same-size changed contents fail verification");
        check(!RecentFiles.verified(new ByteArrayInputStream(Arrays.copyOf(bytes,bytes.length-1)),bytes.length,TransferEngine.hash(bytes),null),"legacy short files fail actual byte-count verification");
        check(!RecentFiles.verified(new ByteArrayInputStream(Arrays.copyOf(bytes,bytes.length+1)),bytes.length,TransferEngine.hash(bytes),null),"legacy oversized streams stop at the expected length bound");
        rejects(()->RecentFiles.verified(new ByteArrayInputStream(bytes),bytes.length,TransferEngine.hash(bytes),()->{throw new IOException("cancelled");}),"legacy verification obeys lifecycle cancellation");
        catalog.backfill(legacy.id,URI+"-legacy");check(catalog.get(legacy.id).uri.equals(URI+"-legacy")&&!catalog.get(legacy.id).legacy,"verified legacy URI is stored in private index without source job mutation");
        catalog.states(Collections.singletonMap(legacy.id,"permission"));check(catalog.get(legacy.id).state.equals("permission"),"lost permission has an explicit durable state");rejects(()->catalog.viewed(legacy.id,6000),"unavailable files cannot be marked recently viewed");
        catalog.states(Collections.singletonMap(legacy.id,"changed"));check(catalog.get(legacy.id).state.equals("changed"),"changed or replaced file identity is preserved as unavailable metadata");
        rejects(()->catalog.backfill(legacy.id,"content://another.documents/tree/other/document/file"),"backfill cannot change the original file provider");
        rejects(()->catalog.get("content://example.documents/arbitrary"),"catalog lookup cannot accept an arbitrary URI or path");
        rejects(()->catalog.indexJob(job("dddddddddddddddddddddddddddddddd","down","files",item("../escape.md",bytes,URI,7000))),"unsafe saved paths are rejected before index persistence");
        rejects(()->catalog.indexJob(job(first,"down","files",item("changed-name.md",bytes,URI,7000))),"same job position cannot silently change an existing catalog identity");
        rejects(()->catalog.page("","all",false,-1,30),"negative page numbers are rejected");rejects(()->catalog.page("","all",false,0,51),"render page size is bounded");
        File paginationRoot=new File(workspace,"pagination");RecentFiles many=new RecentFiles(paginationRoot);JSONObject[] rows=new JSONObject[65];for(int i=0;i<rows.length;i++)rows[i]=item("File"+i+".txt",bytes,URI+"-"+i,10000+i);many.indexJob(job(first,"down","files",rows));
        check(many.page("","text",false,0,30).entries.size()==30&&many.page("","text",false,0,30).hasNext,"first page renders a bounded subset");check(many.page("","text",false,2,30).entries.size()==5&&!many.page("","text",false,2,30).hasNext,"last page has an accurate end marker");check(many.page("FILE6","text",false,0,30).total==6,"search is case insensitive and composes with pagination");
        Set<String> ids=new HashSet<>();for(int p=0;p<3;p++)for(RecentFiles.Entry row:many.page("","all",false,p,30).entries)ids.add(row.id);check(ids.size()==65,"pagination neither duplicates nor silently drops indexed files");
        many.viewed(key(first,2),20000);many.viewed(key(first,8),30000);check(many.page("","all",true,0,30).entries.get(0).index==8,"recently viewed order uses successful view time");
        String before=new String(Files.readAllBytes(new File(catalog.root,"catalog.json").toPath()),"UTF-8");char[] large=new char[RecentFiles.MAX_BYTES+1];Arrays.fill(large,'x');JSONObject huge=new JSONObject().put("padding",new String(large));rejects(()->catalog.write("catalog.json",huge),"oversized index fails explicitly before replacing existing metadata");check(new String(Files.readAllBytes(new File(catalog.root,"catalog.json").toPath()),"UTF-8").equals(before),"index capacity failure preserves previous records and files");
        File malformedRoot=new File(workspace,"malformed");RecentFiles malformed=new RecentFiles(malformedRoot);malformed.write("catalog.json",new JSONObject().put("schema",1).put("items","bad"));rejects(()->malformed.indexJob(job(first,"down","files",complete)),"malformed existing catalog is not silently replaced by a new index");
        System.out.println("Recent file checks passed: "+passed);
    }
}
