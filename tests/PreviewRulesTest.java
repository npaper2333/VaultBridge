package io.vaultbridge.app;

import java.io.*;
import java.nio.charset.*;
import java.util.*;

/** Synthetic files only: never opens Android, external applications, or a user document. */
public final class PreviewRulesTest {
    static int passed;
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);passed++;System.out.println("PASS: "+label);}
    interface Action{void run()throws Exception;}
    static void rejects(Action action,String label)throws Exception {try{action.run();throw new AssertionError(label);}catch(IOException expected){check(true,label);}}
    static byte[] bom(String text,String encoding,byte[] prefix)throws Exception {byte[] body=text.getBytes(encoding),bytes=Arrays.copyOf(prefix,prefix.length+body.length);System.arraycopy(body,0,bytes,prefix.length,body.length);return bytes;}
    public static void main(String[] args)throws Exception {
        check(PreviewRules.validCatalogId(new String(new char[64]).replace('\0','a')),"opaque catalog SHA id accepted");
        for(String id:new String[]{"content://provider/file","../escape",new String(new char[32]).replace('\0','a'),new String(new char[64]).replace('\0','A'),""})check(!PreviewRules.validCatalogId(id),"external URI or noncatalog id rejected");
        check(PreviewRules.kind("docs/IMAGE.JpG").equals("image"),"case-insensitive image extension");
        check(PreviewRules.kind("notes.md","image/png").equals("markdown")&&PreviewRules.kind("notes.html","application/pdf").equals("text"),"source extension takes priority over provider MIME");
        check(PreviewRules.kind("payload.exe","text/plain").equals("other")&&PreviewRules.kind("image.svg","image/png").equals("other"),"MIME cannot promote executable or SVG to internal viewing");
        check(PreviewRules.kind("without-extension","application/pdf").equals("pdf"),"MIME may identify extensionless bounded native PDF");
        check(PreviewRules.kind("deck.pptx").equals("office")&&PreviewRules.kind("archive.zip").equals("archive"),"Office and ZIP remain external formats");
        check(PreviewRules.mime("notes.md").equals("text/markdown")&&PreviewRules.mime("slides.pptx").contains("presentationml"),"external app MIME uses known source extension");
        String source="# 中文源文本\n<script>alert('plain text')</script>";PreviewRules.Text utf8=PreviewRules.decode(source.getBytes("UTF-8"),"auto");check(utf8.value.equals(source)&&utf8.encoding.equals("UTF-8")&&!utf8.fallback,"Markdown and HTML remain exact source text without execution");
        check(PreviewRules.decode(bom(source,"UTF-8",new byte[]{(byte)239,(byte)187,(byte)191}),"auto").value.equals(source),"UTF-8 BOM removed");
        check(PreviewRules.decode(bom(source,"UTF-16LE",new byte[]{(byte)255,(byte)254}),"auto").value.equals(source),"UTF-16LE BOM decoded");
        check(PreviewRules.decode(bom(source,"UTF-16BE",new byte[]{(byte)254,(byte)255}),"auto").value.equals(source),"UTF-16BE BOM decoded");
        PreviewRules.Text chinese=PreviewRules.decode("中文文本".getBytes("GB18030"),"auto");check(chinese.value.equals("中文文本")&&chinese.fallback&&chinese.encoding.equals("GB18030"),"invalid UTF-8 can fall back to GB18030 explicitly");
        check(PreviewRules.decode(source.getBytes("UTF-16LE"),"UTF-16LE").value.equals(source),"user-selected UTF-16 works without a BOM");
        check(PreviewRules.decode(new byte[0],"auto").value.isEmpty(),"empty text is a valid read-only document");
        rejects(()->PreviewRules.decode(new byte[]{0,1,2},"auto"),"binary NUL does not silently become text");
        rejects(()->PreviewRules.decode(new byte[]{(byte)0xff},"UTF-8"),"selected UTF-8 decoder does not silently replace invalid bytes");
        rejects(()->PreviewRules.decode(source.getBytes("UTF-8"),"UTF-7"),"unapproved encoding rejected");
        PreviewRules.checkSize(PreviewRules.MAX_TEXT,PreviewRules.MAX_TEXT);check(true,"exact text byte limit accepted");
        rejects(()->PreviewRules.checkSize(PreviewRules.MAX_TEXT+1,PreviewRules.MAX_TEXT),"text over 2 MiB gives a bounded failure");
        rejects(()->PreviewRules.checkSize(-1,PreviewRules.MAX_PDF),"unknown source size cannot allocate an unbounded preview");
        byte[] bytes=source.getBytes("UTF-8");String hash=PreviewRules.sha256(bytes);ByteArrayOutputStream output=new ByteArrayOutputStream();PreviewRules.copyVerified(new ByteArrayInputStream(bytes),output,bytes.length,hash,PreviewRules.MAX_TEXT,null);check(Arrays.equals(bytes,output.toByteArray()),"private snapshot contains exactly the catalog content");
        rejects(()->PreviewRules.copyVerified(new ByteArrayInputStream(bytes),new ByteArrayOutputStream(),bytes.length,new String(new char[64]).replace('\0','0'),PreviewRules.MAX_TEXT,null),"changed complete content cannot become a preview");
        rejects(()->PreviewRules.copyVerified(new ByteArrayInputStream(bytes),null,bytes.length-1,hash,PreviewRules.MAX_TEXT,null),"stream growth beyond catalog length rejected");
        rejects(()->PreviewRules.copyVerified(new ByteArrayInputStream(bytes),null,bytes.length+1,hash,PreviewRules.MAX_TEXT,null),"truncated source rejected");
        ByteArrayOutputStream cancelled=new ByteArrayOutputStream();rejects(()->PreviewRules.copyVerified(new ByteArrayInputStream(bytes),cancelled,bytes.length,hash,PreviewRules.MAX_TEXT,()->{throw new IOException("synthetic closed viewer");}),"lifecycle cancellation stops private source reading");check(cancelled.size()==0,"cancelled input was not published into a snapshot");
        for(int[] dimensions:new int[][]{{4000,3000},{1,Integer.MAX_VALUE},{Integer.MAX_VALUE,Integer.MAX_VALUE},{8192,256},{20,20}}){int sample=PreviewRules.imageSample(dimensions[0],dimensions[1]);long width=((long)dimensions[0]+sample-1)/sample,height=((long)dimensions[1]+sample-1)/sample;check(sample>0&&(sample&(sample-1))==0&&width<=PreviewRules.MAX_SIDE&&height<=PreviewRules.MAX_SIDE&&width*height<=PreviewRules.MAX_PIXELS,"large image metadata stays within side and pixel limits");}
        rejects(()->PreviewRules.imageSample(0,100),"damaged image bounds rejected");
        for(int[] dimensions:new int[][]{{595,842},{100000,10},{1,Integer.MAX_VALUE},{Integer.MAX_VALUE,Integer.MAX_VALUE}}){int[] size=PreviewRules.pdfSize(dimensions[0],dimensions[1],1600);check(size[0]>=1&&size[1]>=1&&size[0]<=PreviewRules.MAX_SIDE&&size[1]<=PreviewRules.MAX_SIDE&&(long)size[0]*size[1]<=PreviewRules.MAX_PIXELS,"one rendered PDF page has bounded bitmap allocation");}
        rejects(()->PreviewRules.pdfSize(100,-1,1000),"invalid PDF page geometry rejected");
        PreviewRules.pages(PreviewRules.MAX_PAGES);check(true,"maximum supported PDF page count accepted");rejects(()->PreviewRules.pages(0),"empty PDF rejected");rejects(()->PreviewRules.pages(PreviewRules.MAX_PAGES+1),"huge PDF page count rejected before rendering");
        System.out.println("Preview rules checks passed: "+passed);
    }
}
