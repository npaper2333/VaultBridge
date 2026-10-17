package io.vaultbridge.app;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import org.json.*;

/** Feedback is optional and isolated from LAN transfers. Never carries author credentials. */
final class FeedbackClient {
    // Configure only after the author's HTTPS endpoint and private service are reviewed.
    static final String DEFAULT_ENDPOINT="";
    static final int MAX_BYTES=16*1024;
    volatile HttpURLConnection active;
    volatile boolean closed;
    static URL endpoint(String base)throws IOException {
        if(base==null||base.trim().isEmpty())throw new IOException("反馈服务尚未接通。草稿已保留，也可以通过微信联系作者。");
        try{URI uri=new URI(base);if(!"https".equalsIgnoreCase(uri.getScheme())||uri.getHost()==null||uri.getRawUserInfo()!=null||uri.getRawQuery()!=null||uri.getRawFragment()!=null||uri.getPort()!=-1&&uri.getPort()!=443||!uri.getPath().equals("/wp-json/vaultbridge-feedback/v1"))throw new IOException("反馈服务地址未通过验证");return new URL(base+"/submit");}catch(URISyntaxException e){throw new IOException("反馈服务地址无效");}
    }
    static JSONObject payload(String id,String type,String title,String body,String contact,boolean context,String version,String platform)throws IOException{
        if(id==null||!id.matches("[0-9a-f]{32}"))throw new IOException("反馈请求编号无效");
        title=title.trim();body=body.trim();contact=contact.trim();
        if(!type.equals("suggestion")&&!type.equals("bug")&&!type.equals("other"))throw new IOException("请选择反馈类型");
        if(title.isEmpty()||title.length()>100||body.isEmpty()||body.length()>3000||contact.length()>120)throw new IOException("请填写标题与正文；最多 100 / 3000 字，联系方式最多 120 字。");
        try{JSONObject json=new JSONObject().put("schema",1).put("clientRequestId",id).put("type",type).put("title",title).put("body",body);if(!contact.isEmpty())json.put("contact",contact);if(context){if(version==null||version.length()>32||!platform.equals("android")&&!platform.equals("windows"))throw new IOException("应用版本信息无效");json.put("contextOptional",new JSONObject().put("version",version).put("platform",platform));}if(json.toString().getBytes(StandardCharsets.UTF_8).length>MAX_BYTES)throw new IOException("建议内容过长，请缩短后再提交");return json;}catch(JSONException e){throw new IOException("反馈内容格式错误",e);}
    }
    static String receipt(int code,String text,String expected)throws IOException{
        if(code!=200&&code!=201){if(code==409)throw new IOException("同一请求编号对应的内容不同，草稿已保留，请修改内容后再提交");if(code==429||code==503)throw new IOException("收件服务繁忙或正在确认，请稍后用原草稿重试");throw new IOException("未确认送达（HTTP "+code+"），草稿已保留");}
        try{JSONObject reply=new JSONObject(text);Object schema=reply.opt("schema"),received=reply.opt("receivedAt"),idValue=reply.opt("id"),receiptValue=reply.opt("receipt"),request=reply.opt("clientRequestId");if(!Boolean.TRUE.equals(reply.opt("ok"))||!(schema instanceof Integer||schema instanceof Long)||((Number)schema).longValue()!=1||!(received instanceof Integer||received instanceof Long)||((Number)received).longValue()<=0||!(idValue instanceof String)||!(receiptValue instanceof String)||!(request instanceof String)||!expected.equals(request))throw new IOException("未取得有效的收件回执，请保留草稿并重试");String id=(String)idValue,number=(String)receiptValue;if(!id.matches("[1-9][0-9]{0,18}")||Long.parseLong(id)<=0||!number.equals("VB-"+id))throw new IOException("收件编号无效，请保留草稿并重试");return number;}catch(JSONException|NumberFormatException e){throw new IOException("收件回执格式错误，请保留草稿并重试");}
    }
    String submit(String base,JSONObject data)throws IOException{
        URL url=endpoint(base);byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>MAX_BYTES)throw new IOException("反馈内容过长");if(closed)throw new IOException("提交已中断，草稿已保留");
        HttpURLConnection connection=(HttpURLConnection)url.openConnection();active=connection;
        try{if(closed)throw new IOException("提交已中断，草稿已保留");connection.setInstanceFollowRedirects(false);connection.setConnectTimeout(10000);connection.setReadTimeout(10000);connection.setRequestMethod("POST");connection.setDoOutput(true);connection.setUseCaches(false);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setRequestProperty("Accept","application/json");connection.setRequestProperty("User-Agent","VaultBridge-Feedback");connection.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=connection.getOutputStream()){out.write(bytes);}int code=connection.getResponseCode();if(code>=300&&code<400)throw new IOException("反馈服务发生跳转，已停止发送；草稿已保留");if(connection.getContentLengthLong()>MAX_BYTES)throw new IOException("反馈服务响应异常，草稿已保留");InputStream stream=code>=400?connection.getErrorStream():connection.getInputStream();ByteArrayOutputStream content=new ByteArrayOutputStream();long deadline=System.nanoTime()+20000000000L;try(InputStream input=stream){if(input!=null){byte[] buffer=new byte[2048];int n;while((n=input.read(buffer))!=-1){if(closed||Thread.currentThread().isInterrupted()||System.nanoTime()>deadline||content.size()+n>MAX_BYTES)throw new IOException("收件确认中断，草稿已保留，可用原编号重试");content.write(buffer,0,n);}}}return receipt(code,new String(content.toByteArray(),StandardCharsets.UTF_8),data.optString("clientRequestId"));}
        finally{active=null;connection.disconnect();}
    }
    void close(){closed=true;HttpURLConnection connection=active;if(connection!=null)connection.disconnect();}
}
