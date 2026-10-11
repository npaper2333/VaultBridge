package io.vaultbridge.app;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.net.URLDecoder;
import java.util.*;
import java.util.regex.*;

/** Directory plans and conservative standard Markdown/Wikilink rewriting, no Android API. */
final class DirectoryEngine {
    static final int MAX_NOTE=4*1024*1024;
    interface Mapper {String target(String path) throws IOException;}
    interface Tree extends Engine.Store {
        List<Entry> list(String directory) throws IOException;
        void createFolder(String path) throws IOException;
        void relocate(String from,String to,boolean directory) throws IOException;
    }
    static final class Entry {
        final String path;final boolean directory;final long size;final boolean rename,move;
        Entry(String p,boolean d,long s,boolean r,boolean m){path=p;directory=d;size=s;rename=r;move=m;}
    }
    static final class Rewrite {final String text;final int count;final List<String> warnings;Rewrite(String t,int c,List<String>w){text=t;count=c;warnings=w;}}
    static final class Edit {final String oldPath,newPath,hash,text;final int count;Edit(String o,String n,String h,String t,int c){oldPath=o;newPath=n;hash=h;text=t;count=c;}}
    static final class Plan {
        final String from,to;final boolean directory;final Map<String,String> signatures=new LinkedHashMap<>();final Set<String>structure=new HashSet<>();final List<Edit> edits=new ArrayList<>();final List<String>warnings=new ArrayList<>();int files;
        Plan(String f,String t,boolean d){from=f;to=t;directory=d;}
    }
    static String parent(String path){int i=path.lastIndexOf('/');return i<0?"":path.substring(0,i);}
    static String name(String path){return path.substring(path.lastIndexOf('/')+1);}
    static List<Entry> scan(Tree tree) throws IOException {List<Entry> result=new ArrayList<>();scan(tree,"",result,0);return result;}
    private static void scan(Tree tree,String path,List<Entry>result,int depth) throws IOException {
        if(depth>32)throw new IOException("目录层级超过上限");
        for(Entry e:tree.list(path)){PathState.normal(e.path);result.add(e);if(result.size()>Engine.MAX_FILES)throw new IOException("文件数量超过上限");if(e.directory)scan(tree,e.path,result,depth+1);}
    }
    static String readNote(Engine.Store store,String path) throws IOException {
        ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream in=store.read(path)){Engine.copy(in,bytes,MAX_NOTE);}
        try{return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes.toByteArray())).toString();}catch(CharacterCodingException ex){throw new IOException("笔记不是 UTF-8，无法检查引用："+path,ex);}
    }
    static Plan plan(Tree tree,PathState records,String from,String to,boolean directory) throws IOException {
        records.requireReady();PathState.normal(from);PathState.normal(to);
        if(from.equals(to)||to.startsWith(from+"/"))throw new IOException("不能移动到原位置或自身内部");
        List<Entry> all=scan(tree);Entry source=null;for(Entry e:all){if(e.path.equals(from))source=e;if(e.path.equalsIgnoreCase(to))throw new IOException("目标已有同名文件或文件夹："+to);}
        if(source==null||source.directory!=directory)throw new IOException("来源已经变化，请刷新目录");
        boolean rename=parent(from).equals(parent(to));if(rename&&!source.rename)throw new IOException("系统文件提供器不支持此项重命名");if(!rename&&!source.move)throw new IOException("系统文件提供器不支持移动。可在 Obsidian 中整理，不会用复制后删除替代。");
        if(!parent(to).isEmpty()&&all.stream().noneMatch(x->x.directory&&x.path.equals(parent(to))))throw new IOException("目标父目录不存在，请先新建文件夹");
        Plan result=new Plan(from,to,directory);Set<String>paths=new HashSet<>();for(Entry e:all){if(!e.directory)paths.add(e.path);result.structure.add(e.path+"|"+e.directory);}
        long textTotal=0;
        for(Entry item:all){if(item.directory)continue;boolean affected=item.path.equals(from)||item.path.startsWith(from+"/");if(affected){result.files++;result.signatures.put(item.path,Engine.hash(tree,item.path));}
            if(item.path.toLowerCase(Locale.ROOT).endsWith(".md")){if(item.size>MAX_NOTE)throw new IOException("笔记超过 4 MB，无法完整检查链接："+item.path);String text=readNote(tree,item.path);byte[] bytes=text.getBytes(StandardCharsets.UTF_8);textTotal+=bytes.length;if(textTotal>64L*1024*1024)throw new IOException("笔记扫描超过 64 MB，请缩小库范围后整理");String h=Engine.hash(new ByteArrayInputStream(bytes));result.signatures.put(item.path,h);Rewrite rewrite=rewrite(text,item.path,paths,p->PathState.replace(p,from,to));if(rewrite.count>0)result.edits.add(new Edit(item.path,PathState.replace(item.path,from,to),h,rewrite.text,rewrite.count));for(String w:rewrite.warnings)if(result.warnings.size()<100)result.warnings.add(item.path+"："+w);}
        }
        return result;
    }
    static void verify(Tree tree,Plan plan) throws IOException {Set<String>current=new HashSet<>();for(Entry e:scan(tree))current.add(e.path+"|"+e.directory);if(!current.equals(plan.structure))throw new IOException("预览后库内目录内容发生变化，请重新预览");for(Map.Entry<String,String>e:plan.signatures.entrySet())if(!e.getValue().equals(Engine.hash(tree,e.getKey())))throw new IOException("预览后笔记发生变化，请重新预览："+e.getKey());for(Entry e:tree.list(parent(plan.to)))if(e.path.equalsIgnoreCase(plan.to))throw new IOException("目标已出现同名条目，请重新选择");}
    static List<String> apply(Tree tree,PathState records,Plan plan,boolean updateLinks,File work) throws IOException {
        verify(tree,plan);records.beginChange(plan.from,plan.to,plan.directory);
        tree.relocate(plan.from,plan.to,plan.directory);records.finishChange();List<String> failures=new ArrayList<>();
        if(updateLinks)for(int i=0;i<plan.edits.size();i++){Edit edit=plan.edits.get(i);try{if(!edit.hash.equals(Engine.hash(tree,edit.newPath)))throw new IOException("笔记已被修改，保留当前内容");File file=new File(work,"link-"+i);try(OutputStream out=new FileOutputStream(file)){out.write(edit.text.getBytes(StandardCharsets.UTF_8));}String h=Engine.hash(file);Engine.apply(tree,records,new Engine.Item(edit.newPath,h,file.length(),file),edit.newPath,"organize:"+Engine.batchId(),Engine.batchId(),true);records.refreshMechanicalBaseline(edit.newPath,edit.hash,h);}catch(Exception ex){failures.add(edit.newPath+"："+ex.getMessage());}}
        return failures;
    }
    static String recover(Tree tree,PathState records) throws IOException {
        org.json.JSONObject p=records.pendingInfo();try{String from=p.getString("from"),to=p.getString("to");List<Entry>all=scan(tree);boolean old=all.stream().anyMatch(e->e.path.equals(from)),next=all.stream().anyMatch(e->e.path.equals(to));if(old&&!next){records.cancelUnperformedChange();return "原目录仍在，未发生搬动；待办记录已取消。";}if(!old&&next){records.finishChange();return "新目录已存在，已恢复同步对应。中断前尚未处理的引用请在 Obsidian 中检查。";}throw new IOException("新旧位置同时存在或均不存在，不能自动判断。请检查实际文件，保留记录并停止同步。");}catch(org.json.JSONException ex){throw new IOException(ex);}
    }
    private static final Pattern LINKS=Pattern.compile("(!?\\[\\[)([^\\]\\r\\n]+)(\\]\\])|(!?\\[[^\\]\\r\\n]*\\]\\()(<[^>\\r\\n]+>|[^\\s)]+)([^)\\r\\n]*\\))");
    static Rewrite rewrite(String text,String note,Collection<String> files,Mapper mapper) throws IOException {
        String masked=maskCode(text);Matcher matcher=LINKS.matcher(masked);StringBuilder output=new StringBuilder();List<String>warnings=new ArrayList<>();int start=0,count=0;String newNote=mapper.target(note);
        while(matcher.find()){
            boolean wiki=matcher.group(1)!=null;int begin=wiki?matcher.start(2):matcher.start(5),end=wiki?matcher.end(2):matcher.end(5);String raw=text.substring(begin,end),value=raw,alias="",suffix="";boolean angle=!wiki&&value.startsWith("<")&&value.endsWith(">");if(angle)value=value.substring(1,value.length()-1);
            if(wiki&&value.contains("|")){int a=value.indexOf('|');alias=value.substring(a);value=value.substring(0,a);}int hash=value.indexOf('#');if(hash>=0){suffix=value.substring(hash);value=value.substring(0,hash);}if(value.isEmpty()||value.matches("(?i)^[a-z][a-z0-9+.-]*:.*")||value.startsWith("//")){continue;}
            String decoded;try{decoded=URLDecoder.decode(value.replace("+","%2B"),"UTF-8");}catch(IllegalArgumentException ex){warnings.add("链接编码无法识别："+value);continue;}
            List<String> matches=resolveLink(decoded,note,files,wiki);if(matches.size()!=1){if(!matches.isEmpty()&&matches.stream().anyMatch(p->{try{return !mapper.target(p).equals(p);}catch(IOException ex){return true;}}))warnings.add("短链接或重名链接待检查："+value);continue;}
            String oldTarget=matches.get(0),newTarget=mapper.target(oldTarget);boolean shortWiki=wiki&&!decoded.contains("/");String replacement;
            if(wiki){if(shortWiki&&name(oldTarget).equals(name(newTarget)))continue;replacement=newTarget;if(!decoded.endsWith(".md")&&replacement.endsWith(".md"))replacement=replacement.substring(0,replacement.length()-3);}
            else {if(oldTarget.equals(newTarget)&&note.equals(newNote))continue;replacement=relative(parent(newNote),newTarget);if(decoded.startsWith("/"))replacement="/"+newTarget;if(!decoded.endsWith(".md")&&oldTarget.endsWith(".md")&&replacement.endsWith(".md"))replacement=replacement.substring(0,replacement.length()-3);replacement=replacement.replace("%","%25").replace(" ","%20").replace("(","%28").replace(")","%29").replace("#","%23");}
            if(wiki&&oldTarget.equals(newTarget))continue;replacement+=suffix+alias;if(angle)replacement="<"+replacement+">";if(!raw.equals(replacement)){output.append(text,start,begin).append(replacement);start=end;count++;}
        }
        output.append(text.substring(start));return new Rewrite(output.toString(),count,warnings);
    }
    private static List<String> resolveLink(String link,String note,Collection<String> files,boolean wiki) {
        LinkedHashSet<String> matches=new LinkedHashSet<>();String rooted=collapse(link.startsWith("/")?link.substring(1):link),relative=collapse((parent(note).isEmpty()?"":parent(note)+"/")+link);
        for(String candidate:link.startsWith("/")?new String[]{rooted}:wiki?new String[]{rooted,relative}:new String[]{relative,rooted})if(candidate!=null){if(files.contains(candidate))matches.add(candidate);if(!candidate.endsWith(".md")&&files.contains(candidate+".md"))matches.add(candidate+".md");if(!wiki&&!matches.isEmpty())break;}
        if(wiki&&!link.contains("/")){matches.clear();for(String p:files)if(name(p).equals(link)||name(p).equals(link+".md"))matches.add(p);}return new ArrayList<>(matches);
    }
    private static String collapse(String path){List<String>parts=new ArrayList<>();for(String p:path.split("/")){if(p.isEmpty()||p.equals("."))continue;if(p.equals("..")){if(parts.isEmpty())return null;parts.remove(parts.size()-1);}else parts.add(p);}return String.join("/",parts);}
    private static String relative(String parent,String target){List<String>a=new ArrayList<>(Arrays.asList(parent.isEmpty()?new String[0]:parent.split("/"))),b=new ArrayList<>(Arrays.asList(target.split("/")));int common=0;while(common<a.size()&&common<b.size()&&a.get(common).equals(b.get(common)))common++;List<String>r=new ArrayList<>();for(int i=common;i<a.size();i++)r.add("..");r.addAll(b.subList(common,b.size()));return String.join("/",r);}
    private static String maskCode(String text){char[] chars=text.toCharArray();Matcher fences=Pattern.compile("(?ms)^\\s{0,3}(`{3,}|~{3,})[^\\r\\n]*\\r?\\n.*?(?:^\\s{0,3}\\1\\s*$|\\z)").matcher(text);while(fences.find())for(int i=fences.start();i<fences.end();i++)if(chars[i]!='\n'&&chars[i]!='\r')chars[i]=' ';Matcher inline=Pattern.compile("(`+)[^`\\r\\n]*?\\1").matcher(new String(chars));while(inline.find())for(int i=inline.start();i<inline.end();i++)chars[i]=' ';return new String(chars);}
}
