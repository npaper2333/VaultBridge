package io.vaultbridge.app;

import java.io.*;
import java.util.*;
import org.json.*;

/** Synthetic document provider only: no Android API, user data or network. */
public final class TransferLandingTest {
    static int passed;
    static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);passed++;System.out.println("PASS: "+name);}
    interface Action {void run()throws Exception;}
    static String fails(Action action)throws Exception {try{action.run();throw new AssertionError("expected failure");}catch(IOException expected){return expected.getMessage();}}
    static final class MemoryTree implements TransferLanding.Tree {
        static final class Node {
            String id,path;byte[] bytes=new byte[0];boolean directory;long metadata=Long.MIN_VALUE;
            Node(String id,String path){this.id=id;this.path=path;}
            TransferLanding.Entry entry(){return new TransferLanding.Entry(id,path,metadata==Long.MIN_VALUE?bytes.length:metadata,directory);}
        }
        final Map<String,Node> nodes=new LinkedHashMap<>();final List<String> readNames=new ArrayList<>(),writeNames=new ArrayList<>();
        int created,renames,written;boolean renameSupport=true,writeFailure,renameFailure,renameAckFailure,corruptWrite,corruptRename,wrongRename;Runnable onWrite;
        Node add(String path,byte[] data){Node n=new Node("document-"+(++created),path);n.bytes=data.clone();nodes.put(path.toLowerCase(Locale.ROOT),n);return n;}
        Node node(TransferLanding.Entry entry)throws IOException {for(Node n:nodes.values())if(n.id.equals(entry.id))return n;throw new IOException("missing document");}
        public void ensureParent(String parent){}
        public TransferLanding.Entry find(String path){Node n=nodes.get(path.toLowerCase(Locale.ROOT));return n==null?null:n.entry();}
        public TransferLanding.Entry create(String path)throws IOException {if(find(path)!=null)throw new IOException("collision");return add(path,new byte[0]).entry();}
        public InputStream read(TransferLanding.Entry entry)throws IOException {Node n=node(entry);readNames.add(n.path);return new ByteArrayInputStream(n.bytes);}
        public OutputStream rewrite(TransferLanding.Entry entry)throws IOException {
            Node n=node(entry);writeNames.add(n.path);n.bytes=new byte[0];written=0;
            return new OutputStream(){final ByteArrayOutputStream out=new ByteArrayOutputStream();
                public void write(int value)throws IOException{write(new byte[]{(byte)value},0,1);}
                public void write(byte[] b,int offset,int length)throws IOException{if(writeFailure)throw new IOException("provider write failed");out.write(b,offset,length);written+=length;n.bytes=out.toByteArray();if(onWrite!=null){Runnable action=onWrite;onWrite=null;action.run();}}
                public void close(){n.bytes=out.toByteArray();if(corruptWrite&&n.bytes.length>0)n.bytes[0]^=1;}
            };
        }
        public boolean canRename(TransferLanding.Entry entry){return renameSupport;}
        public TransferLanding.Entry rename(TransferLanding.Entry entry,String target)throws IOException {
            if(renameFailure)throw new IOException("provider rename failed");Node n=node(entry);if(find(target)!=null)throw new IOException("new collision");nodes.remove(n.path.toLowerCase(Locale.ROOT));n.path=wrongRename?target+".changed":target;n.id="renamed-"+(++renames);if(corruptRename&&n.bytes.length>0)n.bytes[0]^=1;nodes.put(n.path.toLowerCase(Locale.ROOT),n);if(renameAckFailure)throw new IOException("provider failed after rename");return n.entry();
        }
        int partials(){int count=0;for(Node n:nodes.values())if(n.path.endsWith(".partial"))count++;return count;}
    }
    static final class Record implements TransferLanding.Save {
        JSONObject item,persisted;boolean failDone;
        Record(JSONObject value){item=value;}
        public void run()throws IOException{if(failDone&&item.optBoolean("done"))throw new IOException("record interrupted after rename");persisted=new JSONObject(item.toString());}
    }
    static JSONObject item(String path,File content)throws IOException{return new JSONObject().put("path",path).put("size",content.length()).put("sha256",TransferEngine.hash(content));}
    static String publish(MemoryTree tree,File content,Record record,TransferEngine.Control control)throws IOException{return TransferLanding.publish(tree,content,record.item,control,record);}
    static String repeat(int length){char[] text=new char[length];Arrays.fill(text,'a');return new String(text);}
    public static void main(String[] args)throws Exception {
        File folder=new File(args[0],"landing-synthetic");if(!folder.isDirectory()&&!folder.mkdirs())throw new IOException("test directory");
        File content=new File(folder,"verified.bin");byte[] bytes=new byte[200000];new Random(72).nextBytes(bytes);try(FileOutputStream out=new FileOutputStream(content)){out.write(bytes);}
        MemoryTree tree=new MemoryTree();Record record=new Record(item("README",content));
        fails(()->publish(tree,content,record,()->{if(tree.written>=65536)throw new TransferEngine.Paused();}));
        check(tree.partials()==1&&tree.find("README")==null,"pause retains only an explicit partial document");
        check(!record.persisted.optBoolean("done")&&!record.persisted.optString("landing").isEmpty(),"owned temporary URI is persisted before copy");
        String partial=record.persisted.getJSONObject("landing").getString("temporary"),id=record.persisted.getJSONObject("landing").getString("uri");
        check(tree.find(partial).id.equals(id)&&tree.find(partial).size==65536,"paused partial is bound to its actual URI and bytes");
        final Record resume=new Record(new JSONObject(record.persisted.toString()));String saved=publish(tree,content,resume,null);
        check(saved.equals("README")&&resume.persisted.optBoolean("done"),"restart completes and publishes original extensionless name");
        check(resume.persisted.getString("finalUri").equals(tree.find("README").id)&&!resume.persisted.getString("finalUri").equals(id),"completion records the actual renamed final URI rather than temporary URI");
        check(resume.persisted.getLong("receivedAt")>0,"verified receipt time is recorded with completion");
        check(tree.created==1&&tree.writeNames.size()==2&&tree.writeNames.get(0).equals(tree.writeNames.get(1)),"restart rewrites the same owned temporary only");
        check(Arrays.equals(tree.nodes.get("readme").bytes,bytes)&&tree.partials()==0,"rename publishes fully verified bytes");

        MemoryTree collision=new MemoryTree();byte[] old="original must stay".getBytes("UTF-8");collision.add("README",old);Record collisionRecord=new Record(item("README",content));
        check(publish(collision,content,collisionRecord,null).equals("README (VaultBridge 1)"),"extensionless collision receives a numbered final name");
        check(Arrays.equals(collision.nodes.get("readme").bytes,old),"original collision content stays untouched");
        check(collisionRecord.persisted.getString("finalUri").equals(collision.find("README (VaultBridge 1)").id),"numbered alternate completion records its final URI");
        check(collision.writeNames.stream().allMatch(n->n.endsWith(".partial")),"provider writes target owned partial names only");

        MemoryTree unknown=new MemoryTree();unknown.add("README",bytes).metadata=-1;Record unknownRecord=new Record(item("README",content));
        check(publish(unknown,content,unknownRecord,null).equals("README (VaultBridge 1)"),"unknown existing size conservatively uses alternate name");
        check(!unknown.readNames.contains("README"),"unknown existing size does not trigger unbounded hashing");

        MemoryTree noRename=new MemoryTree();noRename.renameSupport=false;Record noRenameRecord=new Record(item("folder/file.md",content));
        check(fails(()->publish(noRename,content,noRenameRecord,null)).contains("不支持安全重命名"),"unsupported rename reports a clear provider limitation");
        check(noRename.partials()==1&&noRename.find("folder/file.md")==null&&!noRenameRecord.item.optBoolean("done"),"unsupported rename retains temporary and never marks complete");

        MemoryTree writeFail=new MemoryTree();writeFail.writeFailure=true;Record writeRecord=new Record(item("file.md",content));fails(()->publish(writeFail,content,writeRecord,null));
        check(writeFail.partials()==1&&writeFail.find("file.md")==null&&!writeRecord.item.optBoolean("done"),"provider write failure retains only partial and unfinished record");
        MemoryTree renameFail=new MemoryTree();renameFail.renameFailure=true;Record renameRecord=new Record(item("file.md",content));fails(()->publish(renameFail,content,renameRecord,null));
        check(renameFail.partials()==1&&renameFail.find("file.md")==null&&!renameRecord.item.optBoolean("done"),"provider rename failure does not publish or mark complete");
        MemoryTree corrupt=new MemoryTree();corrupt.corruptWrite=true;Record corruptRecord=new Record(item("file.md",content));fails(()->publish(corrupt,content,corruptRecord,null));
        check(corrupt.renames==0&&!corruptRecord.item.optBoolean("done"),"temporary checksum mismatch prevents rename");

        MemoryTree late=new MemoryTree();late.onWrite=()->late.add("file.md",old);Record lateRecord=new Record(item("file.md",content));
        check(publish(late,content,lateRecord,null).equals("file (VaultBridge 1).md")&&Arrays.equals(late.nodes.get("file.md").bytes,old),"collision created during SAF copy is retained and numbered around");

        MemoryTree interrupted=new MemoryTree();Record interruptedRecord=new Record(item("file.md",content));interruptedRecord.failDone=true;fails(()->publish(interrupted,content,interruptedRecord,null));
        check(interrupted.find("file.md")!=null&&!interruptedRecord.persisted.optBoolean("done"),"rename can finish before completion record is persisted");
        Record afterRestart=new Record(new JSONObject(interruptedRecord.persisted.toString()));
        check(publish(interrupted,content,afterRestart,null).equals("file.md")&&interrupted.created==1&&interrupted.renames==1,"restart reuses verified published target after record interruption");
        check(afterRestart.persisted.getString("finalUri").equals(interrupted.find("file.md").id),"rename-before-record recovery saves final URI for later file lookup");

        MemoryTree ack=new MemoryTree();ack.renameAckFailure=true;Record ackRecord=new Record(item("file.md",content));fails(()->publish(ack,content,ackRecord,null));ack.renameAckFailure=false;
        Record ackRestart=new Record(new JSONObject(ackRecord.persisted.toString()));
        check(publish(ack,content,ackRestart,null).equals("file.md")&&ack.renames==1,"lost provider rename acknowledgement recovers without duplicate publish");
        MemoryTree identical=new MemoryTree();identical.add("same.md",bytes);Record identicalRecord=new Record(item("same.md",content));publish(identical,content,identicalRecord,null);check(identical.created==1&&identicalRecord.persisted.getString("finalUri").equals(identical.find("same.md").id),"same-content skip also records the existing verified final URI");

        MemoryTree changed=new MemoryTree();Record changedRecord=new Record(item("file.md",content));fails(()->publish(changed,content,changedRecord,()->{if(changed.written>=65536)throw new TransferEngine.Paused();}));
        String temp=changedRecord.persisted.getJSONObject("landing").getString("temporary");changed.nodes.get(temp.toLowerCase(Locale.ROOT)).id="external replacement";int rewrites=changed.writeNames.size();Record changedResume=new Record(new JSONObject(changedRecord.persisted.toString()));fails(()->publish(changed,content,changedResume,null));
        check(changed.writeNames.size()==rewrites&&!changedResume.item.optBoolean("done"),"replaced temporary URI is never rewritten on restart");

        MemoryTree wrong=new MemoryTree();wrong.wrongRename=true;Record wrongRecord=new Record(item("file.md",content));fails(()->publish(wrong,content,wrongRecord,null));
        check(!wrongRecord.item.optBoolean("done"),"provider changed final display name prevents completion");
        MemoryTree wrongHash=new MemoryTree();wrongHash.corruptRename=true;Record wrongHashRecord=new Record(item("file.md",content));fails(()->publish(wrongHash,content,wrongHashRecord,null));
        check(!wrongHashRecord.item.optBoolean("done"),"post-rename content verification prevents false success");
        MemoryTree badSource=new MemoryTree();Record badRecord=new Record(item("file.md",content));badRecord.item.put("sha256",repeat(64));fails(()->publish(badSource,content,badRecord,null));
        check(badSource.created==0,"invalid private assembled checksum prevents any provider creation");

        File records=TransferEngine.folder(folder,"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");String large=repeat(9*1024*1024);JSONObject largeJob=new JSONObject().put("synthetic",large);TransferEngine.save(records,largeJob);
        check(new File(records,"job.json").length()>LanClient.MAX_FRAME&&TransferEngine.load(records).getString("synthetic").length()==large.length(),"task records larger than network frames persist and restore");
        final String oversized=repeat(TransferEngine.MAX_RECORD+1);JSONObject tooLarge=new JSONObject(){@Override public String toString(){return oversized;}};
        check(fails(()->TransferEngine.save(records,tooLarge)).equals("任务记录过大，请分批"),"oversized task encoding fails explicitly before write");
        check(TransferEngine.load(records).getString("synthetic").length()==large.length(),"oversized save preserves the previous readable task record");
        File excess=TransferEngine.folder(folder,"bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb");try(RandomAccessFile out=new RandomAccessFile(new File(excess,"job.json"),"rw")){out.setLength(TransferEngine.MAX_RECORD+1L);}
        check(fails(()->TransferEngine.load(excess)).equals("任务记录过大，请分批"),"oversized on-disk task records fail before allocation");
        System.out.println("SAF landing checks passed: "+passed);
    }
}
