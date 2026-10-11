package io.vaultbridge.app;

import java.io.*;
import java.util.*;
import org.json.*;

/** Known authorized directories and settings isolated by vault and computer source. */
final class LocationState {
    private JSONObject state;
    private final PathState.Writer writer;
    LocationState(String value,PathState.Writer output) throws IOException {writer=output;try{state=new JSONObject(value);if(!state.has("locations"))state.put("locations",new JSONArray());if(!state.has("options"))state.put("options",new JSONObject());state.getJSONArray("locations");state.getJSONObject("options");}catch(JSONException ex){throw new IOException("库位置记录无法读取",ex);} }
    private JSONObject copy()throws IOException{try{return new JSONObject(state.toString());}catch(JSONException ex){throw new IOException(ex);}}
    private void save(JSONObject n)throws IOException{writer.save(n.toString());state=n;}
    List<String[]> locations()throws IOException{List<String[]>result=new ArrayList<>();try{JSONArray a=state.getJSONArray("locations");for(int i=0;i<a.length();i++){JSONObject r=a.getJSONObject(i);result.add(new String[]{r.getString("uri"),r.getString("name")});}return result;}catch(JSONException ex){throw new IOException(ex);}}
    void add(String uri,String name)throws IOException{if(uri==null||uri.isEmpty()||name==null||name.trim().isEmpty())throw new IOException("库位置或名称为空");JSONObject n=copy();try{JSONArray a=n.getJSONArray("locations");for(int i=0;i<a.length();i++)if(a.getJSONObject(i).getString("uri").equals(uri)){a.getJSONObject(i).put("name",name);save(n);return;}if(a.length()>=32)throw new IOException("最多记住 32 个库位置");a.put(new JSONObject().put("uri",uri).put("name",name));save(n);}catch(JSONException ex){throw new IOException(ex);}}
    void options(String uri,String source,JSONObject settings)throws IOException{JSONObject n=copy();try{n.getJSONObject("options").put(uri+"|"+source,settings);save(n);}catch(JSONException ex){throw new IOException(ex);}}
    JSONObject options(String uri,String source)throws IOException{try{JSONObject o=state.getJSONObject("options").optJSONObject(uri+"|"+source);return o==null?new JSONObject():new JSONObject(o.toString());}catch(JSONException ex){throw new IOException(ex);}}
}
