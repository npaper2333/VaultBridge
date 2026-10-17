package io.vaultbridge.app;

import java.io.*;
import java.net.*;
import java.util.*;

/** Process-local source allowlist. Tokens cannot be replaced by a URI or a catalog ID. */
final class SourceSessionModel {
    static final class Item {final String path,uri;Item(String path,String uri)throws IOException {this.path=TransferEngine.path(path);try{URI value=new URI(uri);if(!"content".equals(value.getScheme())||value.getRawAuthority()==null||value.getRawAuthority().isEmpty()||value.getRawUserInfo()!=null||"io.vaultbridge.app.updates".equalsIgnoreCase(value.getRawAuthority())||value.getRawFragment()!=null)throw new IOException("来源必须是用户选择的系统文档");}catch(URISyntaxException e){throw new IOException("来源URI无效",e);}this.uri=uri;}}
    final Map<String,List<Item>> sessions=new HashMap<>();
    synchronized String create(List<Item> items)throws IOException {if(items.isEmpty()||items.size()>Engine.MAX_FILES||sessions.size()>=4)throw new IOException("来源会话为空、过多或先前页面未关闭");String token=UUID.randomUUID().toString().replace("-","");sessions.put(token,Collections.unmodifiableList(new ArrayList<>(items)));return token;}
    synchronized Item resolve(String token,int index)throws IOException {List<Item> items=token==null?null:sessions.get(token);if(token==null||!token.matches("[0-9a-f]{32}")||items==null||index<0||index>=items.size())throw new IOException("来源会话已结束，请重新选择");return items.get(index);}
    synchronized void close(String token){sessions.remove(token);}
}
