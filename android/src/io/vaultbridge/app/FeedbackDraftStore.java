package io.vaultbridge.app;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.charset.CodingErrorAction;
import java.nio.ByteBuffer;
import java.nio.file.*;
import java.nio.channels.*;
import java.util.*;
import java.util.UUID;
import org.json.JSONObject;

/** Immutable attempted submissions. Editing the current draft never destroys an uncertain request. */
final class FeedbackDraftStore {
    static final int MAX_RECENT=20,MAX_SCAN=4096;
    static final class Snapshot {
        final String id,json;final long attemptedAt;
        Snapshot(String id,String json,long attemptedAt){this.id=id;this.json=json;this.attemptedAt=attemptedAt;}
        JSONObject payload()throws IOException {try{return new JSONObject(json);}catch(org.json.JSONException e){throw new IOException("历史草稿格式无法读取，记录已保留",e);}}
    }
    static final class Recent {
        final List<Snapshot> items;final int skipped;final boolean truncated;
        Recent(List<Snapshot> items,int skipped,boolean truncated){this.items=Collections.unmodifiableList(new ArrayList<>(items));this.skipped=skipped;this.truncated=truncated;}
    }
    static String id(String value)throws IOException {if(value==null||!value.matches("[0-9a-f]{32}"))throw new IOException("反馈编号无效");return value;}
    static void preserve(File directory,JSONObject payload)throws IOException {
        String id=payload.optString("clientRequestId");if(!id.matches("[0-9a-f]{32}"))throw new IOException("反馈编号无效");
        byte[] bytes=payload.toString().getBytes(StandardCharsets.UTF_8);if(bytes.length>FeedbackClient.MAX_BYTES)throw new IOException("反馈内容过长");
        Files.createDirectories(directory.toPath());Path target=new File(directory,id+".json").toPath();
        if(Files.exists(target)){verify(target,bytes);return;}
        Path temporary=new File(directory,id+"-"+UUID.randomUUID()+".pending").toPath();
        try(FileOutputStream stream=new FileOutputStream(temporary.toFile())){stream.write(bytes);stream.getFD().sync();}
        try{Files.move(temporary,target);}catch(FileAlreadyExistsException e){verify(target,bytes);}
    }
    static void verify(Path target,byte[] expected)throws IOException {
        if(Files.size(target)>FeedbackClient.MAX_BYTES||!java.util.Arrays.equals(Files.readAllBytes(target),expected))throw new IOException("原请求记录与草稿不一致，已停止发送并保留记录");
    }
    static Snapshot read(File directory,String requestId)throws IOException {
        String key=id(requestId);Path folder=directory.toPath(),target=new File(directory,key+".json").toPath();
        if(!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS)||!Files.isRegularFile(target,LinkOption.NOFOLLOW_LINKS))throw new IOException("历史草稿不存在或不是普通记录，当前编辑稿保留");
        if(Files.size(target)>FeedbackClient.MAX_BYTES)throw new IOException("历史草稿超过读取上限，原文件保留");
        byte[] bytes;
        try(SeekableByteChannel channel=Files.newByteChannel(target,StandardOpenOption.READ,LinkOption.NOFOLLOW_LINKS);InputStream input=Channels.newInputStream(channel);ByteArrayOutputStream output=new ByteArrayOutputStream()){
            byte[] buffer=new byte[2048];int n;while((n=input.read(buffer))!=-1){if(Thread.currentThread().isInterrupted())throw new IOException("历史草稿读取已取消");if(output.size()+(long)n>FeedbackClient.MAX_BYTES)throw new IOException("历史草稿读取超过上限，原文件保留");output.write(buffer,0,n);}bytes=output.toByteArray();
        }
        String json;try{json=StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes)).toString();}catch(java.nio.charset.CharacterCodingException e){throw new IOException("历史草稿编码损坏，原文件保留",e);}JSONObject payload;
        try{payload=new JSONObject(json);Object schema=payload.opt("schema");if(!key.equals(payload.optString("clientRequestId"))||!(schema instanceof Integer||schema instanceof Long)||((Number)schema).longValue()!=1)throw new IOException("历史草稿编号或格式不符，记录已保留");Set<String> allowed=new HashSet<>(Arrays.asList("schema","clientRequestId","type","title","body","contact","contextOptional"));Iterator<String> keys=payload.keys();while(keys.hasNext())if(!allowed.contains(keys.next()))throw new IOException("历史草稿含有未允许字段，已停止重试并保留记录");for(String field:new String[]{"clientRequestId","type","title","body"})if(!(payload.opt(field) instanceof String))throw new IOException("历史草稿字段类型无效，记录已保留");if(payload.has("contact")&&!(payload.opt("contact") instanceof String))throw new IOException("历史草稿联系方式格式无效");JSONObject context=payload.optJSONObject("contextOptional");if(payload.has("contextOptional")&&context==null)throw new IOException("历史草稿环境字段无效");if(context!=null){Iterator<String> contextKeys=context.keys();while(contextKeys.hasNext())if(!Arrays.asList("version","platform").contains(contextKeys.next()))throw new IOException("历史草稿包含额外环境字段，已停止重试");if(!(context.opt("version") instanceof String)||!(context.opt("platform") instanceof String))throw new IOException("历史草稿环境字段类型无效");}FeedbackClient.payload(key,payload.optString("type"),payload.optString("title"),payload.optString("body"),payload.optString("contact"),context!=null,context==null?"":context.optString("version"),context==null?"android":context.optString("platform"));}
        catch(org.json.JSONException|IllegalArgumentException e){throw new IOException("历史草稿无法读取，记录已保留",e);}
        return new Snapshot(key,json,Files.getLastModifiedTime(target,LinkOption.NOFOLLOW_LINKS).toMillis());
    }
    static JSONObject load(File directory,String requestId)throws IOException {return read(directory,requestId).payload();}
    static Recent recent(File directory,int limit)throws IOException {
        if(limit<1||limit>MAX_RECENT)throw new IOException("历史草稿一次最多读取20条");Path folder=directory.toPath();if(!Files.exists(folder,LinkOption.NOFOLLOW_LINKS))return new Recent(Collections.emptyList(),0,false);if(!Files.isDirectory(folder,LinkOption.NOFOLLOW_LINKS))throw new IOException("历史草稿目录无效，当前编辑稿保留");
        final class Candidate {final String id;final long time;Candidate(String id,long time){this.id=id;this.time=time;}}
        Comparator<Candidate> order=Comparator.comparingLong((Candidate c)->c.time).thenComparing(c->c.id);PriorityQueue<Candidate> newest=new PriorityQueue<>(limit,order);int scanned=0,skipped=0;boolean truncated=false;
        try(DirectoryStream<Path> entries=Files.newDirectoryStream(folder)){
            for(Path path:entries){if(Thread.currentThread().isInterrupted())throw new IOException("历史草稿读取已取消");if(scanned++>=MAX_SCAN){truncated=true;break;}String name=path.getFileName().toString();if(!name.matches("[0-9a-f]{32}\\.json"))continue;
                try{if(!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS)){skipped++;continue;}Candidate candidate=new Candidate(name.substring(0,32),Files.getLastModifiedTime(path,LinkOption.NOFOLLOW_LINKS).toMillis());if(newest.size()<limit)newest.add(candidate);else if(order.compare(candidate,newest.peek())>0){newest.poll();newest.add(candidate);}}catch(IOException e){skipped++;}
            }
        }
        List<Candidate> sorted=new ArrayList<>(newest);sorted.sort(order.reversed());List<Snapshot> items=new ArrayList<>();for(Candidate candidate:sorted){if(Thread.currentThread().isInterrupted())throw new IOException("历史草稿读取已取消");try{items.add(read(directory,candidate.id));}catch(IOException e){skipped++;}}
        return new Recent(items,skipped,truncated);
    }
}
