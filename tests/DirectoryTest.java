package io.vaultbridge.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import org.json.*;

public class DirectoryTest {
    static int passed;
    interface Action {void run()throws Exception;}
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);passed++;System.out.println("PASS: "+label);}
    static void rejects(Action action,String label)throws Exception{boolean failed=false;try{action.run();}catch(IOException ex){failed=true;}check(failed,label);}
    static class MemoryTree implements DirectoryEngine.Tree {
        Map<String,byte[]> files=new LinkedHashMap<>();Set<String>dirs=new LinkedHashSet<>();boolean allowMove=true,failMove;
        public List<DirectoryEngine.Entry> list(String parent){List<DirectoryEngine.Entry>r=new ArrayList<>();for(String d:dirs)if(DirectoryEngine.parent(d).equals(parent)&&!Engine.protectedPath(d))r.add(new DirectoryEngine.Entry(d,true,0,true,allowMove));for(Map.Entry<String,byte[]>e:files.entrySet())if(DirectoryEngine.parent(e.getKey()).equals(parent)&&!Engine.protectedPath(e.getKey()))r.add(new DirectoryEngine.Entry(e.getKey(),false,e.getValue().length,true,allowMove));return r;}
        void parents(String path){String p=DirectoryEngine.parent(path);while(!p.isEmpty()){dirs.add(p);p=DirectoryEngine.parent(p);}}
        public void createFolder(String path){parents(path);dirs.add(path);}
        public void relocate(String from,String to,boolean directory)throws IOException{if(failMove)throw new IOException("provider failed");Map<String,byte[]>mapped=new LinkedHashMap<>();for(Map.Entry<String,byte[]>e:files.entrySet())mapped.put(PathState.replace(e.getKey(),from,to),e.getValue());files=mapped;Set<String>next=new LinkedHashSet<>();for(String d:dirs)next.add(PathState.replace(d,from,to));dirs=next;}
        public boolean exists(String p){return files.containsKey(p);}
        public InputStream read(String p)throws IOException{if(!files.containsKey(p))throw new IOException("missing "+p);return new ByteArrayInputStream(files.get(p));}
        public void write(String p,InputStream in)throws IOException{parents(p);ByteArrayOutputStream out=new ByteArrayOutputStream();Engine.copy(in,out,Engine.MAX_FILE);files.put(p,out.toByteArray());}
        void note(String p,String text)throws IOException{write(p,new ByteArrayInputStream(text.getBytes(StandardCharsets.UTF_8)));}
        String note(String p){return new String(files.get(p),StandardCharsets.UTF_8);}
    }
    static PathState records()throws IOException{return new PathState(PathState.empty(),s->{});}
    public static void main(String[] args)throws Exception{
        File work=new File(args[0],"directory-"+UUID.randomUUID());if(!work.mkdirs())throw new IOException("test folder not created");
        Set<String>paths=new HashSet<>(Arrays.asList("课程/笔记.md","课程/复习.md","附件/图 1.png","首页.md"));
        DirectoryEngine.Mapper moved=p->PathState.replace(p,"课程","专业课");
        DirectoryEngine.Rewrite a=DirectoryEngine.rewrite("[[课程/笔记#推导|复习]]\n![图](../附件/图%201.png)\n`[[课程/笔记]]`\n[官网](https://example.com/a)","课程/复习.md",paths,moved);
        check(a.text.contains("[[专业课/笔记#推导|复习]]"),"Wikilink alias and heading survive folder rename");
        check(a.text.contains("`[[课程/笔记]]`")&&a.text.contains("https://example.com/a"),"inline code and external links remain untouched");
        DirectoryEngine.Rewrite b=DirectoryEngine.rewrite("![图](../附件/图%201.png)\n[笔记](../课程/笔记.md)","课程/复习.md",paths,p->PathState.replace(p,"课程","归档/课程"));
        check(b.text.contains("../../附件/图%201.png")&&b.text.contains("[笔记](笔记.md)"),"moved note rewrites relative attachments and internal target");
        DirectoryEngine.Rewrite fenced=DirectoryEngine.rewrite("```md\n[[课程/笔记]]\n```\n[[课程/笔记]]","首页.md",paths,moved);
        check(fenced.count==1&&fenced.text.startsWith("```md\n[[课程/笔记]]"),"fenced example code is not rewritten");
        Set<String>duplicates=new HashSet<>(paths);duplicates.add("项目/笔记.md");DirectoryEngine.Rewrite ambiguous=DirectoryEngine.rewrite("[[笔记]]","首页.md",duplicates,moved);
        check(ambiguous.count==0&&ambiguous.warnings.size()==1,"ambiguous short Wikilink is retained and flagged");
        DirectoryEngine.Rewrite unique=DirectoryEngine.rewrite("[[笔记|我的说明]]","首页.md",paths,p->PathState.replace(p,"课程/笔记.md","课程/新笔记.md"));
        check(unique.text.equals("[[课程/新笔记|我的说明]]"),"unique short link follows file rename without losing alias");
        PathState r=records();r.baseline("pc:test","课程/笔记.md","first");r.history(new Engine.History("课程/笔记.md",".vaultbridge/history/a", "h","backup","pc:test"));r.beginChange("课程","专业课",true);rejects(r::requireReady,"unfinished directory journal blocks writing");r.finishChange();
        check(r.target("课程/未来.md").equals("专业课/未来.md"),"folder mapping includes later added source notes");
        check("first".equals(r.baseline("pc:test","专业课/笔记.md"))&&r.baseline("pc:test","课程/笔记.md")==null,"baseline follows target path");
        check(r.histories().get(0).path.equals("专业课/笔记.md")&&r.histories().get(0).storedPath.equals(".vaultbridge/history/a"),"version target follows move while backup file stays intact");
        r.beginChange("专业课","归档/专业课",true);r.finishChange();check(r.target("课程/未来.md").equals("归档/专业课/未来.md")&&r.original("归档/专业课/笔记.md").equals("课程/笔记.md"),"multiple directory changes compose and invert");
        rejects(()->r.targets(Arrays.asList("课程/笔记.md","归档/专业课/笔记.md")),"source paths that converge on one target are rejected");
        rejects(()->r.beginChange("归档","归档/内部",true),"self descendant move rejected");rejects(()->r.target(".obsidian/config.json"),"mapping cannot enter protected configuration");
        r.refreshMechanicalBaseline("归档/专业课/笔记.md","first","mechanical");check("mechanical".equals(r.baseline("pc:test","归档/专业课/笔记.md")),"mechanical link rewrite advances unchanged accepted baseline");r.refreshMechanicalBaseline("归档/专业课/笔记.md","local-edit","wrong");check("mechanical".equals(r.baseline("pc:test","归档/专业课/笔记.md")),"mechanical rewrite never accepts unrelated local edits");
        PathState durable=new PathState(PathState.empty(),s->{throw new IOException("storage full");});rejects(()->durable.baseline("pc:a","a.md","hash"),"metadata save failure is reported");check(durable.baseline("pc:a","a.md")==null,"failed metadata save does not advance in-memory state");
        MemoryTree tree=new MemoryTree();tree.note("课程/笔记.md","# 课堂");tree.note("首页.md","[[课程/笔记]]");PathState ledger=records();ledger.baseline("pc:a","首页.md",Engine.hash(tree,"首页.md"));DirectoryEngine.Plan plan=DirectoryEngine.plan(tree,ledger,"课程","专业课",true);
        check(plan.files==1&&plan.edits.size()==1,"directory preview scans files and inbound links");DirectoryEngine.apply(tree,ledger,plan,true,work);check(tree.exists("专业课/笔记.md")&&!tree.exists("课程/笔记.md")&&tree.note("首页.md").equals("[[专业课/笔记]]"),"provider relocation and clear link repair applied");check(ledger.histories().size()==1&&tree.exists(ledger.histories().get(0).storedPath),"link repair stores old content before writing");
        check(Engine.hash(tree,"首页.md").equals(ledger.baseline("pc:a","首页.md")),"source baseline follows verified mechanical rewrite");
        DirectoryEngine.Plan stale=DirectoryEngine.plan(tree,ledger,"专业课","课程2",true);tree.note("专业课/新增.md","new");rejects(()->DirectoryEngine.apply(tree,ledger,stale,true,work),"new file after preview cancels relocation");check(tree.exists("专业课/笔记.md")&&!ledger.pending(),"cancelled stale preview leaves data and journal unchanged");
        DirectoryEngine.Plan modified=DirectoryEngine.plan(tree,ledger,"专业课","课程2",true);tree.note("首页.md","my edit");rejects(()->DirectoryEngine.apply(tree,ledger,modified,true,work),"note edit after preview cancels relocation");
        tree.allowMove=false;tree.createFolder("归档");rejects(()->DirectoryEngine.plan(tree,ledger,"专业课","归档/专业课",true),"unsupported provider move does not use copy and delete");tree.allowMove=true;
        DirectoryEngine.Plan fail=DirectoryEngine.plan(tree,ledger,"专业课","课程2",true);tree.failMove=true;rejects(()->DirectoryEngine.apply(tree,ledger,fail,true,work),"provider failure retains operation journal");check(ledger.pending(),"failed physical operation remains recoverable");tree.failMove=false;check(DirectoryEngine.recover(tree,ledger).contains("未发生")&&!ledger.pending(),"recovery clears only a demonstrably unperformed operation");
        ledger.beginChange("专业课","课程2",true);tree.relocate("专业课","课程2",true);check(DirectoryEngine.recover(tree,ledger).contains("恢复")&&ledger.target("课程/笔记.md").equals("课程2/笔记.md"),"recovery completes mapping after actual relocation");
        ledger.beginChange("课程2","下一次",true);tree.note("下一次/另一篇.md","foreign");rejects(()->DirectoryEngine.recover(tree,ledger),"ambiguous recovery remains blocked");
        LocationState locations=new LocationState("{}",s->{});locations.add("content:one","学习");locations.add("content:two","项目");locations.options("content:one","pc:a",new JSONObject().put("folder","课程"));locations.options("content:two","pc:a",new JSONObject().put("folder","项目"));check(locations.options("content:one","pc:a").getString("folder").equals("课程")&&locations.options("content:two","pc:a").getString("folder").equals("项目"),"library options remain isolated by authorized location");check(locations.options("content:one","pc:b").length()==0,"different computer source starts a fresh saved range");locations.add("content:one","重新授权");check(locations.locations().size()==2,"same directory is remembered once on reauthorization");
        check(records().unused()&&!r.unused(),"copy destination must have no existing version or routing state");
        MemoryTree receiving=new MemoryTree();receiving.note("课程/笔记.md","v1");receiving.note("首页.md","[[课程/笔记]]");PathState incoming=records();incoming.baseline("pc:main","课程/笔记.md",Engine.hash(receiving,"课程/笔记.md"));incoming.baseline("pc:main","首页.md",Engine.hash(receiving,"首页.md"));DirectoryEngine.apply(receiving,incoming,DirectoryEngine.plan(receiving,incoming,"课程","专业课",true),true,work);
        String raw="v2\n[[课程/未来]]";DirectoryEngine.Rewrite mapped=DirectoryEngine.rewrite(raw,"课程/笔记.md",Arrays.asList("课程/笔记.md","课程/未来.md"),incoming::target);File updated=new File(work,"incoming-update");java.nio.file.Files.write(updated.toPath(),mapped.text.getBytes(StandardCharsets.UTF_8));Engine.Item update=new Engine.Item("课程/笔记.md",Engine.hash(updated),updated.length(),updated);Engine.Outcome changed=Engine.apply(receiving,incoming,update,incoming.target(update.path),"pc:main",Engine.batchId(),false);check(changed==Engine.Outcome.UPDATED&&receiving.note("专业课/笔记.md").contains("[[专业课/未来]]")&&!receiving.exists("课程/笔记.md"),"later incoming update follows moved folder and localizes new references");
        receiving.note("专业课/笔记.md","my local changes");File newer=new File(work,"incoming-newer");java.nio.file.Files.write(newer.toPath(),"v3".getBytes(StandardCharsets.UTF_8));check(Engine.apply(receiving,incoming,new Engine.Item("课程/笔记.md",Engine.hash(newer),newer.length(),newer),incoming.target("课程/笔记.md"),"pc:main",Engine.batchId(),false)==Engine.Outcome.CONFLICT&&receiving.note("专业课/笔记.md").equals("my local changes"),"local edit after relocation remains protected on next update");
        DirectoryEngine.Plan externalChange=DirectoryEngine.plan(receiving,incoming,"专业课","最后",true);receiving.note("另外/笔记.md","duplicate short target");rejects(()->DirectoryEngine.apply(receiving,incoming,externalChange,true,work),"new outside note invalidates link resolution preview");
        DirectoryEngine.Rewrite absolute=DirectoryEngine.rewrite("[root](/课程/笔记.md)","其他/首页.md",Arrays.asList("课程/笔记.md","其他/课程/笔记.md"),moved);check(absolute.text.equals("[root](/专业课/笔记.md)"),"absolute Markdown link always resolves from vault root");
        System.out.println("Directory and location checks passed: "+passed);
    }
}
