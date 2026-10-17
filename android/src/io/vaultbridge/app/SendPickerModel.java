package io.vaultbridge.app;

import java.io.*;
import java.util.*;
import org.json.*;

/** Target-bound evidence for a single send selection. Never persists current confirmation. */
final class SendPickerModel {
    static final class Item {
        final String key,path,kind;long size=-1;String sha="",state="unknown",other="";boolean selected,history;
        Item(String key,String path,boolean selected)throws IOException {this.key=key;this.path=TransferEngine.path(path);this.kind=PreviewRules.kind(path);this.selected=selected;}
    }
    final List<Item> items;String prefix="",identity,targetId="";boolean hidden;int deselected;long checkedAt;
    SendPickerModel(List<Item> items,String identity){this.items=items;this.identity=identity;}
    static String prefix(String value)throws IOException {String v=value==null?"":value.trim();return v.isEmpty()?"":TransferEngine.path(v);}
    String desired(Item item){return prefix.isEmpty()?item.path:prefix+"/"+item.path;}
    void target(String value,String identity)throws IOException {String p=prefix(value);if(!p.equals(prefix)||!this.identity.equals(identity)){prefix=p;this.identity=identity;invalidate();}}
    void invalidate(){targetId="";checkedAt=0;for(Item item:items){item.state=item.history?"history":"unknown";item.other="";}}
    void fingerprint(Item item,long size,String sha)throws IOException {TransferEngine.count(size);if(sha==null||!sha.matches("[0-9a-f]{64}"))throw new IOException("来源校验无效");if(item.size!=size||!item.sha.equals(sha)){item.size=size;item.sha=sha;item.state=item.history?"history":"unknown";item.other="";}}
    int hide(boolean value){hidden=value;return cancelHidden();}
    int cancelHidden(){int count=0;if(hidden)for(Item item:items)if(item.state.equals("same")&&item.selected){item.selected=false;count++;}deselected=count;return count;}
    int sameCount(){int count=0;for(Item item:items)if(item.state.equals("same"))count++;return count;}
    List<Item> visible(String query,String kind){String q=(query==null?"":query.trim()).toLowerCase(Locale.ROOT);List<Item> result=new ArrayList<>();for(Item item:items)if((!hidden||!item.state.equals("same"))&&(kind.equals("all")||item.kind.equals(kind))&&item.path.toLowerCase(Locale.ROOT).contains(q))result.add(item);return result;}
    List<Item> selected(){List<Item> result=new ArrayList<>();for(Item item:items)if(item.selected)result.add(item);return result;}
    int selectMissing(){int count=0;for(Item item:items)if(item.state.equals("missing")&&!item.selected){item.selected=true;count++;}return count;}
    static String key(long size,String hash){return size+":"+hash;}
    void compare(JSONObject response,String vault)throws IOException {
        try{
            if(!"notes".equals(response.getString("mode"))||!prefix.equals(response.getString("prefix"))||!vault.equals(response.getString("vaultId"))||response.optString("targetId").isEmpty()||response.optLong("checkedAt",0)<=0)throw new IOException("电脑目标或核验身份变化，请重新配对/对比");
            JSONArray files=response.getJSONArray("files"),scopes=response.getJSONArray("scopes"),warnings=response.getJSONArray("warnings");if(files.length()>Engine.MAX_FILES)throw new IOException("电脑对比清单超过容量");
            boolean whole=false;List<String> allowed=new ArrayList<>();for(int i=0;i<scopes.length();i++){String scope=scopes.getString(i);if(scope.isEmpty())whole=true;else allowed.add(TransferEngine.path(scope).toLowerCase(Locale.ROOT));}
            boolean complete=response.optBoolean("complete",false)&&whole&&warnings.length()==0;
            Map<String,JSONObject> byPath=new HashMap<>();Map<String,String> byContent=new HashMap<>();
            for(int i=0;i<files.length();i++){JSONObject f=files.getJSONObject(i);String path=TransferEngine.path(f.getString("path")),hash=f.getString("sha256"),lower=path.toLowerCase(Locale.ROOT);long size=f.getLong("size");TransferEngine.count(size);boolean inScope=whole;for(String scope:allowed)if(lower.equals(scope)||lower.startsWith(scope+"/")){inScope=true;break;}if(!inScope||!hash.matches("[0-9a-f]{64}")||byPath.put(lower,f)!=null)throw new IOException("电脑清单包含范围外、歧义或无效内容");byContent.put(key(size,hash),path);}
            // Parse all evidence before replacing a previous result: malformed frames prove nothing.
            Map<Item,String[]> results=new LinkedHashMap<>();
            for(Item item:items){String state="unknown",other="";if(item.size>=0&&!item.sha.isEmpty()){JSONObject same=byPath.get(desired(item).toLowerCase(Locale.ROOT));if(same!=null)state=same.getLong("size")==item.size&&same.getString("sha256").equals(item.sha)?"same":"changed";else if(byContent.containsKey(key(item.size,item.sha))){state="elsewhere";other=byContent.get(key(item.size,item.sha));}else if(complete)state="missing";}results.put(item,new String[]{state,other});}
            targetId=response.getString("targetId");checkedAt=response.getLong("checkedAt");for(Map.Entry<Item,String[]> result:results.entrySet()){result.getKey().state=result.getValue()[0];result.getKey().other=result.getValue()[1];}cancelHidden();
        }catch(JSONException e){invalidate();throw new IOException("电脑不支持完整目标核验，请更新电脑端；原选择保留",e);}catch(IOException e){invalidate();throw e;}
    }
    void verify(Item item,JSONObject response,JSONObject receipt)throws IOException {
        try{
            if(!prefix.equals(response.getString("prefix"))||!"files".equals(response.getString("mode"))||!receipt.getString("targetId").equals(response.getString("targetId"))||!receipt.optString("vaultId").equals(response.optString("vaultId"))||response.optLong("checkedAt",0)<=0||response.getLong("size")!=item.size||!response.getString("sha256").equals(item.sha)||receipt.optLong("size",-1)!=item.size||!receipt.optString("sha256").equals(item.sha)||!receipt.optString("saved").equals(response.optString("saved")))return;
            String bound=response.getString("targetId");if(bound.isEmpty()||(!targetId.isEmpty()&&!targetId.equals(bound)))return;targetId=bound;checkedAt=response.getLong("checkedAt");String state=response.optString("result","unknown");if(!Arrays.asList("same","changed","missing","unknown").contains(state))return;
            if(!desired(item).equalsIgnoreCase(response.optString("saved"))){if(state.equals("same")){item.state="elsewhere";item.other=response.optString("saved");}}else item.state=state;cancelHidden();
        }catch(JSONException e){throw new IOException("旧任务缺少目标回执，保留历史待核验",e);}
    }
    static String label(String state){switch(state){case "same":return "✓ 电脑已确认相同";case "history":return "◷ 历史传过 · 待核验";case "changed":return "↻ 同路径内容变化 · 原件保护另存";case "elsewhere":return "≈ 其他位置相同 · 可保留目录副本";case "missing":return "+ 当前授权目标未传";default:return "? 尚未核验 / 范围外";}}
}
