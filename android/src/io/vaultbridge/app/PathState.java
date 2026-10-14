package io.vaultbridge.app;

import java.io.*;
import java.util.*;
import org.json.*;

/** Per-vault paths, baselines and a durable directory-operation journal. */
class PathState implements Engine.Ledger {
    interface Writer { void save(String value) throws IOException; }
    private JSONObject state;
    private final Writer writer;
    PathState(String saved, Writer output) throws IOException {
        writer = output;
        try {
            state = new JSONObject(saved);
            if (state.optJSONObject("baselines") == null || state.optJSONArray("history") == null) throw new IOException("版本记录格式异常");
            if (!state.has("routes")) state.put("routes", new JSONArray());
            if (!state.has("sources")) state.put("sources", new JSONObject());
            JSONArray routes = state.getJSONArray("routes");
            for (int i=0;i<routes.length();i++) { normal(routes.getJSONObject(i).getString("from")); normal(routes.getJSONObject(i).getString("to")); }
        } catch (JSONException ex) { throw new IOException("目录记录无法读取，已停止以保护笔记",ex); }
    }
    static String empty() { return "{\"baselines\":{},\"history\":[]}"; }
    static String normal(String path) throws IOException { String n=Engine.normalize(path); if(Engine.protectedPath(n))throw new IOException("隐藏配置与备份目录不能整理");return n; }
    static String replace(String path,String from,String to) { return path.equals(from)||path.startsWith(from+"/") ? to+path.substring(from.length()) : path; }
    private JSONObject copy() throws IOException { try{return new JSONObject(state.toString());}catch(JSONException ex){throw new IOException(ex);} }
    private void commit(JSONObject next) throws IOException { writer.save(next.toString());state=next; }
    boolean pending() { return state.has("pending"); }
    boolean unused() { return !pending() && state.optJSONObject("baselines").length()==0 && state.optJSONArray("history").length()==0 && state.optJSONArray("routes").length()==0 && state.optJSONObject("sources").length()==0; }
    JSONObject pendingInfo() throws IOException { try{return state.getJSONObject("pending");}catch(JSONException ex){throw new IOException("没有待恢复的目录操作",ex);} }
    void requireReady() throws IOException { if(pending())throw new IOException("上次目录操作中断。先到“库与目录”点击“恢复目录记录”，再同步或导入。"); }
    String target(String original) throws IOException {
        String result=normal(original);
        try {JSONArray routes=state.getJSONArray("routes");for(int i=0;i<routes.length();i++){JSONObject r=routes.getJSONObject(i);result=replace(result,r.getString("from"),r.getString("to"));}return normal(result);}
        catch(JSONException ex){throw new IOException(ex);}
    }
    String original(String target) throws IOException {
        String result=normal(target);
        try {JSONArray routes=state.getJSONArray("routes");for(int i=routes.length()-1;i>=0;i--){JSONObject r=routes.getJSONObject(i);result=replace(result,r.getString("to"),r.getString("from"));}return normal(result);}
        catch(JSONException ex){throw new IOException(ex);}
    }
    Map<String,String> targets(Collection<String> originals) throws IOException {
        Map<String,String> result=new LinkedHashMap<>();Set<String> unique=new HashSet<>();
        for(String p:originals){String t=target(p);if(!unique.add(t.toLowerCase(Locale.ROOT)))throw new IOException("多个来源指向同一接收位置："+t+"。先调整目录对应。");result.put(p,t);}return result;
    }
    @Override public String baseline(String source,String path){return state.optJSONObject("baselines").optString(source+"|"+path,null);}
    @Override public void baseline(String source,String path,String hash) throws IOException {if(Objects.equals(baseline(source,path),hash))return;JSONObject n=copy();try{n.getJSONObject("baselines").put(source+"|"+path,hash);commit(n);}catch(JSONException ex){throw new IOException(ex);} }
    String sourceHash(String source,String path){return state.optJSONObject("sources").optString(source+"|"+path,null);}
    void sourceHash(String source,String path,String hash) throws IOException {JSONObject n=copy();try{n.getJSONObject("sources").put(source+"|"+normal(path),hash);commit(n);}catch(JSONException ex){throw new IOException(ex);} }
    List<String> managedPaths(String source) throws IOException {
        List<String> paths=new ArrayList<>();JSONObject bases=state.optJSONObject("baselines");Iterator<String> keys=bases.keys();String prefix=source+"|";
        while(keys.hasNext()){String key=keys.next();if(key.startsWith(prefix))paths.add(original(key.substring(prefix.length())));}return paths;
    }
    @Override public void history(Engine.History item) throws IOException {JSONObject n=copy();try{JSONObject r=new JSONObject();r.put("path",item.path).put("storedPath",item.storedPath).put("hash",item.hash).put("type",item.type).put("time",item.time).put("source",item.source);n.getJSONArray("history").put(r);commit(n);}catch(JSONException ex){throw new IOException(ex);} }
    List<Engine.History> histories() throws IOException {
        List<Engine.History> result=new ArrayList<>();try{JSONArray a=state.getJSONArray("history");for(int i=a.length()-1;i>=0;i--){JSONObject r=a.getJSONObject(i);Engine.History h=new Engine.History(r.getString("path"),r.getString("storedPath"),r.getString("hash"),r.getString("type"),r.getString("source"));h.time=r.getString("time");result.add(h);}return result;}catch(JSONException ex){throw new IOException(ex);}
    }
    private JSONObject relocated(String from,String to) throws IOException {
        normal(from);normal(to);if(from.equals(to)||to.startsWith(from+"/"))throw new IOException("不能移动到原位置或自身内部");JSONObject n=copy();
        try {
            JSONArray routes=n.getJSONArray("routes");if(routes.length()>=2000)throw new IOException("目录对应数量过多，请先整理来源范围");
            JSONObject old=n.getJSONObject("baselines"),mapped=new JSONObject();Set<String> folded=new HashSet<>();Iterator<String> keys=old.keys();
            while(keys.hasNext()){String k=keys.next();int split=k.indexOf('|');if(split<0)throw new IOException("基线记录格式异常");String next=k.substring(0,split+1)+replace(k.substring(split+1),from,to);if(!folded.add(next.toLowerCase(Locale.ROOT)))throw new IOException("新位置已被版本记录使用，不能覆盖对应");mapped.put(next,old.get(k));}
            n.put("baselines",mapped);JSONArray h=n.getJSONArray("history");for(int i=0;i<h.length();i++){JSONObject item=h.getJSONObject(i);item.put("path",replace(item.getString("path"),from,to));}
            routes.put(new JSONObject().put("from",from).put("to",to));return n;
        }catch(JSONException ex){throw new IOException(ex);}
    }
    void beginChange(String from,String to,boolean directory) throws IOException {
        requireReady();relocated(from,to);JSONObject n=copy();try{n.put("pending",new JSONObject().put("from",from).put("to",to).put("directory",directory));commit(n);}catch(JSONException ex){throw new IOException(ex);}
    }
    void finishChange() throws IOException {JSONObject p=pendingInfo();try{JSONObject n=relocated(p.getString("from"),p.getString("to"));n.remove("pending");commit(n);}catch(JSONException ex){throw new IOException(ex);} }
    void cancelUnperformedChange() throws IOException {JSONObject n=copy();n.remove("pending");commit(n);}
    void refreshMechanicalBaseline(String path,String oldHash,String newHash) throws IOException {
        JSONObject n=copy();try{JSONObject b=n.getJSONObject("baselines");List<String> keys=new ArrayList<>();Iterator<String> all=b.keys();while(all.hasNext())keys.add(all.next());for(String k:keys){if(k.endsWith("|"+path)&&oldHash.equals(b.getString(k)))b.put(k,newHash);}commit(n);}catch(JSONException ex){throw new IOException(ex);}
    }
    String copyWithoutHistory() throws IOException {requireReady();JSONObject n=copy();try{n.put("history",new JSONArray());return n.toString();}catch(JSONException ex){throw new IOException(ex);} }
    List<String[]> routes() throws IOException {List<String[]> r=new ArrayList<>();try{JSONArray a=state.getJSONArray("routes");for(int i=0;i<a.length();i++){JSONObject item=a.getJSONObject(i);r.add(new String[]{item.getString("from"),item.getString("to")});}return r;}catch(JSONException ex){throw new IOException(ex);} }
}
