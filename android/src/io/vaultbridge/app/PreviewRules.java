package io.vaultbridge.app;

import java.io.*;
import java.nio.*;
import java.nio.charset.*;
import java.security.*;
import java.util.*;

/** Read-only preview policy, independent of Android and providers. */
final class PreviewRules {
    static final long MAX_TEXT=2L*1024*1024,MAX_IMAGE=32L*1024*1024,MAX_PDF=128L*1024*1024;
    static final int MAX_PIXELS=4*1024*1024,MAX_SIDE=4096,MAX_PAGES=2000;
    interface Control {void check()throws IOException;}
    static final class Text {
        final String value,encoding;final boolean fallback;
        Text(String text,String charset,boolean recovered){value=text;encoding=charset;fallback=recovered;}
    }
    static boolean validCatalogId(String id){return id!=null&&id.matches("[0-9a-f]{64}");}
    static String extension(String name){if(name==null)return "";String base=name.substring(Math.max(name.lastIndexOf('/'),name.lastIndexOf('\\'))+1);int dot=base.lastIndexOf('.');return dot<0?"":base.substring(dot+1).toLowerCase(Locale.ROOT);}
    static boolean oneOf(String ext,String values){return Arrays.asList(values.split(" ")).contains(ext);}
    static String kind(String name){return kind(name,"");}
    static String kind(String name,String mime){
        String ext=extension(name),type=mime==null?"":mime.toLowerCase(Locale.ROOT).split(";",2)[0].trim();
        if(oneOf(ext,"png jpg jpeg webp bmp gif heic heif avif"))return "image";
        if(ext.equals("pdf"))return "pdf";
        if(oneOf(ext,"md markdown mdx"))return "markdown";
        if(oneOf(ext,"txt csv tsv log json xml html htm css js jsx ts tsx java py c h cpp hpp cs ini cfg conf yaml yml toml tex sql sh bat ps1 rst"))return "text";
        if(oneOf(ext,"doc docx xls xlsx ppt pptx odt ods odp rtf"))return "office";
        if(oneOf(ext,"zip rar 7z tar gz bz2 xz tgz"))return "archive";
        if(!ext.isEmpty())return "other";
        if(type.startsWith("image/")&&!type.equals("image/svg+xml"))return "image";
        if(type.equals("application/pdf"))return "pdf";
        if(type.startsWith("text/")||oneOf(type,"application/json application/xml"))return "text";
        return "other";
    }
    static String mime(String name){
        String ext=extension(name);
        if(ext.equals("pdf"))return "application/pdf";
        if(oneOf(ext,"jpg jpeg"))return "image/jpeg";
        if(ext.equals("png"))return "image/png";
        if(ext.equals("webp"))return "image/webp";
        if(ext.equals("gif"))return "image/gif";
        if(ext.equals("bmp"))return "image/bmp";
        if(oneOf(ext,"heic heif"))return "image/heic";
        if(ext.equals("avif"))return "image/avif";
        if(ext.equals("svg"))return "image/svg+xml";
        if(ext.equals("doc"))return "application/msword";
        if(ext.equals("docx"))return "application/vnd.openxmlformats-officedocument.wordprocessingml.document";
        if(ext.equals("xls"))return "application/vnd.ms-excel";
        if(ext.equals("xlsx"))return "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
        if(ext.equals("ppt"))return "application/vnd.ms-powerpoint";
        if(ext.equals("pptx"))return "application/vnd.openxmlformats-officedocument.presentationml.presentation";
        if(ext.equals("zip"))return "application/zip";
        if(ext.equals("7z"))return "application/x-7z-compressed";
        if(ext.equals("rar"))return "application/vnd.rar";
        if(ext.equals("apk"))return "application/vnd.android.package-archive";
        if(ext.equals("md")||ext.equals("markdown"))return "text/markdown";
        if(kind(name).equals("text"))return "text/plain";
        return "application/octet-stream";
    }
    static void checkSize(long size,long limit)throws IOException {if(size<0||size>limit)throw new IOException(limit==MAX_TEXT?"文本超过 2 MiB，或大小无法确认，请用其他应用打开。":"文件超过内置预览大小限制，请用其他应用打开。");}
    static String sha256(byte[] bytes)throws IOException {try{return hex(MessageDigest.getInstance("SHA-256").digest(bytes));}catch(NoSuchAlgorithmException e){throw new IOException(e);}}
    static String hex(byte[] bytes){StringBuilder out=new StringBuilder();for(byte b:bytes)out.append(String.format(Locale.ROOT,"%02x",b&255));return out.toString();}
    static void copyVerified(InputStream input,OutputStream output,long size,String hash,long limit,Control control)throws IOException {
        checkSize(size,limit);if(hash==null||!hash.matches("[0-9a-f]{64}"))throw new IOException("文件记录缺少有效校验，返回文件页重新核对后再打开。");
        try{MessageDigest digest=MessageDigest.getInstance("SHA-256");byte[] buffer=new byte[65536];long total=0;int n;
            while((n=input.read(buffer))!=-1){if(control!=null)control.check();total+=n;if(total>limit||total>size)throw new IOException("源文件大小已变化，未打开，请返回文件页重新核对。");digest.update(buffer,0,n);if(output!=null)output.write(buffer,0,n);}
            if(control!=null)control.check();if(total!=size||!MessageDigest.isEqual(hex(digest.digest()).getBytes(StandardCharsets.US_ASCII),hash.getBytes(StandardCharsets.US_ASCII)))throw new IOException("源文件内容已变化或读取不完整，请返回文件页重新核对。");
        }catch(NoSuchAlgorithmException e){throw new IOException(e);}
    }
    static int imageSample(int width,int height)throws IOException {
        if(width<=0||height<=0)throw new IOException("无法识别图片，文件可能损坏或格式暂不支持。");int sample=1;
        while(((long)width+sample-1)/sample>MAX_SIDE||((long)height+sample-1)/sample>MAX_SIDE||(((long)width+sample-1)/sample)*(((long)height+sample-1)/sample)>MAX_PIXELS){if(sample>1<<28)throw new IOException("图片尺寸异常。");sample*=2;}return sample;
    }
    static int[] pdfSize(int width,int height,int viewport)throws IOException {
        if(width<=0||height<=0)throw new IOException("PDF 页面尺寸无效。");
        double desired=Math.max(1024,Math.min(MAX_SIDE,(long)Math.max(1,viewport)*2));double scale=Math.min(desired/width,Math.min(MAX_SIDE/(double)Math.max(width,height),Math.sqrt(MAX_PIXELS/((double)width*height))));
        return new int[]{Math.max(1,(int)Math.floor(width*scale)),Math.max(1,(int)Math.floor(height*scale))};
    }
    static void pages(int count)throws IOException {if(count<1||count>MAX_PAGES)throw new IOException("PDF 页数超过内置预览限制（最多 2,000 页），或文档无有效页面，请用其他应用打开。");}
    static String strict(byte[] bytes,int offset,String charset)throws CharacterCodingException {return Charset.forName(charset).newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(bytes,offset,bytes.length-offset)).toString();}
    static Text decode(byte[] bytes,String requested)throws IOException {
        if(bytes==null||bytes.length>MAX_TEXT)throw new IOException("文本预览最多支持 2 MiB，请用其他应用打开全文。");
        String bom=null;int skip=0;if(bytes.length>=3&&(bytes[0]&255)==239&&(bytes[1]&255)==187&&(bytes[2]&255)==191){bom="UTF-8";skip=3;}else if(bytes.length>=2&&(bytes[0]&255)==255&&(bytes[1]&255)==254){bom="UTF-16LE";skip=2;}else if(bytes.length>=2&&(bytes[0]&255)==254&&(bytes[1]&255)==255){bom="UTF-16BE";skip=2;}
        boolean auto=requested==null||requested.equals("auto");String encoding=auto?bom:requested;boolean fallback=false;
        if(!auto&&!oneOf(encoding,"UTF-8 UTF-16LE UTF-16BE GB18030"))throw new IOException("不支持此文本编码。");
        String text;
        try{if(encoding!=null)text=strict(bytes,encoding.equals(bom)?skip:0,encoding);else{try{encoding="UTF-8";text=strict(bytes,0,encoding);}catch(CharacterCodingException e){encoding="GB18030";fallback=true;text=strict(bytes,0,encoding);}}}
        catch(CharacterCodingException|IllegalArgumentException e){throw new IOException("此编码不能完整读取文本，可切换编码或用其他应用打开。",e);}
        if(text.indexOf('\0')>=0)throw new IOException("文本含有二进制内容或编码未识别，可手动选择 UTF-16 或用其他应用打开。");
        return new Text(text,encoding,fallback);
    }
}
