package io.vaultbridge.app;

import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;

/** All network responses and installer identities are synthetic; no external requests or installation. */
public final class UpdateTest {
    static int passed;
    static void check(boolean condition,String name){if(!condition)throw new AssertionError(name);passed++;System.out.println("PASS: "+name);}
    interface Action{void run()throws Exception;}
    static String rejects(Action action,String name)throws Exception {try{action.run();throw new AssertionError(name);}catch(IOException e){check(true,name);return e.getMessage();}}
    static JSONObject release(String version,byte[] bytes)throws Exception {
        String file="VaultBridge-Android-"+version+".apk",sha=hash(bytes);
        return new JSONObject().put("draft",false).put("tag_name","v"+version).put("html_url","https://github.com/npaper2333/VaultBridge/releases/tag/v"+version).put("prerelease",true).put("body","Synthetic release notes\n<text only>").put("assets",new JSONArray().put(new JSONObject().put("name",file).put("state","uploaded").put("size",bytes.length).put("digest","sha256:"+sha).put("browser_download_url","https://github.com/npaper2333/VaultBridge/releases/download/v"+version+"/"+file)));
    }
    static JSONObject asset(JSONObject release)throws Exception{return release.getJSONArray("assets").getJSONObject(0);}
    static UpdateInfo parse(JSONObject release)throws IOException{return UpdateInfo.parse(new JSONArray().put(release).toString(),"0.4.2");}
    static String hash(byte[] bytes)throws Exception{return UpdateClient.hex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));}
    static final class Response extends HttpURLConnection {
        final int status;final byte[] bytes;final String location;final long declared;boolean disconnected;
        Response(URL url,int status,byte[] bytes,String location,long declared){super(url);this.status=status;this.bytes=bytes;this.location=location;this.declared=declared;}
        @Override public void connect(){}
        @Override public void disconnect(){disconnected=true;}
        @Override public boolean usingProxy(){return false;}
        @Override public int getResponseCode(){return status;}
        @Override public String getHeaderField(String key){return "Location".equals(key)?location:null;}
        @Override public long getContentLengthLong(){return declared;}
        @Override public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
    }
    static final class Fake implements UpdateClient.Connections {
        final Map<String,List<Response>> responses=new HashMap<>();final List<Response> opened=new ArrayList<>();
        void add(String url,int status,byte[] bytes,String location,long length)throws Exception{responses.computeIfAbsent(url,k->new ArrayList<>()).add(new Response(new URL(url),status,bytes,location,length));}
        public HttpURLConnection open(URL url)throws IOException {List<Response> found=responses.get(url.toString());if(found==null||found.isEmpty())throw new IOException("synthetic unreachable endpoint");Response result=found.remove(0);opened.add(result);return result;}
    }
    static File next(File root,String name)throws IOException {File directory=new File(root,name);if(!directory.mkdir())throw new IOException("test folder already exists");return directory;}
    public static void main(String[] args)throws Exception {
        File root=new File(args[0],"update-synthetic");if(!root.isDirectory()&&!root.mkdirs())throw new IOException("test folder");byte[] apk=new byte[140000];new Random(31).nextBytes(apk);
        check(UpdateInfo.compare("0.4.10","0.4.2")>0&&UpdateInfo.compare("1.0.0","0.999.999")>0,"versions compare numerically across components");
        check(UpdateInfo.compare("0.4.2","0.4.2")==0,"equal versions are not updates");
        rejects(()->UpdateInfo.parts("v0.4.2"),"version parser rejects extra tag prefix");rejects(()->UpdateInfo.parts("0.04.2"),"version parser rejects ambiguous leading zeros");rejects(()->UpdateInfo.parts("2147483648.0.0"),"version components are bounded");
        JSONObject release=release("0.4.3",apk);UpdateInfo info=parse(release);
        check(info!=null&&info.prerelease&&info.version.equals("0.4.3")&&info.sha256.equals(hash(apk)),"matching uploaded prerelease APK is eligible");
        JSONObject old=release("0.4.1",apk);check(parse(old)==null,"older versions are ignored");JSONObject equal=release("0.4.2",apk);check(parse(equal)==null,"currently installed version is ignored");
        JSONObject draft=release("0.4.3",apk).put("draft",true);check(parse(draft)==null,"draft releases are ignored");
        JSONObject wrongTag=release("0.4.3",apk).put("tag_name","next");check(parse(wrongTag)==null,"non-numeric release tags are ignored");
        JSONObject wrongName=release("0.4.3",apk);asset(wrongName).put("name","VaultBridge-Android-latest.apk");check(parse(wrongName)==null,"APK asset must exactly match tag version");
        JSONObject incomplete=release("0.4.3",apk);asset(incomplete).put("state","new");check(parse(incomplete)==null,"unfinished GitHub uploads are ignored");
        JSONObject anotherRepo=release("0.4.3",apk);asset(anotherRepo).put("browser_download_url",info.url.replace("npaper2333/","someone/"));check(parse(anotherRepo)==null,"asset URL from another repository is ignored");
        JSONObject plainHttp=release("0.4.3",apk);asset(plainHttp).put("browser_download_url",info.url.replace("https:","http:"));check(parse(plainHttp)==null,"unencrypted asset URLs are rejected");
        JSONObject wrongPage=release("0.4.3",apk).put("html_url","https://example.invalid/release");check(parse(wrongPage)==null,"release page must belong to the fixed GitHub tag");
        JSONObject duplicate=release("0.4.3",apk);duplicate.getJSONArray("assets").put(new JSONObject(asset(duplicate).toString()));check(parse(duplicate)==null,"ambiguous duplicated platform assets are ignored");
        JSONObject oversize=release("0.4.3",apk);asset(oversize).put("size",UpdateInfo.MAX_APK+1);check(parse(oversize)==null,"oversized APK metadata is ignored");
        JSONObject fractional=release("0.4.3",apk);asset(fractional).put("size",1.5);check(parse(fractional)==null,"fractional asset size is rejected");
        JSONObject noDigest=release("0.4.3",apk);asset(noDigest).remove("digest");check(parse(noDigest)!=null&&parse(noDigest).sha256.isEmpty(),"missing digest retains a manual-release-page candidate only");
        JSONObject badDigest=release("0.4.3",apk);asset(badDigest).put("digest","sha1:"+hash(apk));check(parse(badDigest).sha256.isEmpty(),"unsupported digest never enables automatic download");
        UpdateInfo newest=UpdateInfo.parse(new JSONArray().put(release("0.4.9",apk)).put(release("0.4.12",apk)).put(release("0.4.10",apk)).toString(),"0.4.2");check(newest.version.equals("0.4.12"),"release choice does not rely on creation order");
        rejects(()->UpdateInfo.parse("{broken","0.4.2"),"malformed feed is an error rather than latest");char[] huge=new char[UpdateInfo.MAX_FEED+1];Arrays.fill(huge,' ');rejects(()->UpdateInfo.parse(new String(huge),"0.4.2"),"oversized metadata is rejected");
        check(UpdateInfo.decode(info.encode()).url.equals(info.url),"saved candidate round trips through validated metadata");rejects(()->UpdateInfo.decode(info.encode().replace("github.com","evil.invalid")),"saved candidate cannot bypass URL validation");
        check(UpdateClient.redirect("https://release-assets.githubusercontent.com/asset?signature=synthetic",info),"official release asset CDN is allowed");
        check(!UpdateClient.redirect("https://release-assets.githubusercontent.com.evil.invalid/asset",info),"CDN lookalike domains are rejected");
        check(!UpdateClient.redirect("https://github.com@evil.invalid/asset",info),"userinfo-host confusion is rejected");
        check(!UpdateClient.redirect("https://release-assets.githubusercontent.com:444/asset",info),"unapproved HTTPS ports are rejected");
        check(!UpdateClient.redirect("https://raw.githubusercontent.com/other/repo/main/file",info),"arbitrary GitHub raw hosting is not an update CDN");
        check(!UpdateClient.redirect("http://release-assets.githubusercontent.com/asset",info),"CDN redirects cannot downgrade HTTPS");
        check(!UpdateClient.redirect("https://api.github.com/repos/another/repo/releases",null),"metadata request remains on the fixed repository API");
        Fake feed=new Fake();byte[] feedBytes=new JSONArray().put(release).toString().getBytes("UTF-8");feed.add(UpdateInfo.API,200,feedBytes,null,feedBytes.length);check(new UpdateClient(feed).latest("0.4.2").version.equals("0.4.3"),"bounded fake HTTPS feed reaches strict metadata parser");
        Response request=feed.opened.get(0);check(request.getConnectTimeout()==10000&&request.getReadTimeout()==15000&&!request.getInstanceFollowRedirects(),"connections apply bounded timeouts and manual redirects");
        check(request.getRequestProperty("Authorization")==null&&"VaultBridge-Updater".equals(request.getRequestProperty("User-Agent")),"public checks send no token or device identity");
        Fake badHttp=new Fake();badHttp.add(UpdateInfo.API,429,new byte[0],null,0);rejects(()->new UpdateClient(badHttp).latest("0.4.2"),"rate-limited feed is not reported as latest");
        Fake feedHuge=new Fake();feedHuge.add(UpdateInfo.API,200,new byte[0],null,UpdateInfo.MAX_FEED+1);rejects(()->new UpdateClient(feedHuge).latest("0.4.2"),"declared oversized response is rejected before reading");
        Fake feedStream=new Fake();feedStream.add(UpdateInfo.API,200,new byte[UpdateInfo.MAX_FEED+1],null,-1);rejects(()->new UpdateClient(feedStream).latest("0.4.2"),"unknown-length feed is bounded during reading");
        String cdn="https://release-assets.githubusercontent.com/synthetic-apk?signature=synthetic";Fake download=new Fake();download.add(info.url,302,new byte[0],cdn,0);download.add(cdn,200,apk,null,apk.length);File downloaded=new UpdateClient(download).download(info,next(root,"valid"),null);
        check(downloaded.isFile()&&downloaded.getName().equals(info.name)&&UpdateClient.hash(downloaded).equals(info.sha256),"verified CDN download publishes the exact APK name");
        check(download.opened.stream().allMatch(r->r.disconnected),"download responses are disconnected after completion");
        Fake evil=new Fake();evil.add(info.url,302,new byte[0],"https://evil.invalid/file?private=synthetic",0);String failure=rejects(()->new UpdateClient(evil).download(info,next(root,"wrong-redirect"),null),"download blocks external redirect before requesting it");check(evil.opened.size()==1&&!failure.contains("private="),"redirect errors do not reveal signed URL queries");
        Fake loop=new Fake();for(int i=0;i<6;i++)loop.add(info.url,302,new byte[0],info.url,0);rejects(()->new UpdateClient(loop).download(info,next(root,"loop"),null),"sixth redirect is rejected");check(loop.opened.size()==6,"redirect requests are bounded to five hops");
        Fake damaged=new Fake();byte[] wrong=apk.clone();wrong[0]^=1;damaged.add(info.url,200,wrong,null,wrong.length);rejects(()->new UpdateClient(damaged).download(info,next(root,"damaged"),null),"corrupt download fails SHA-256 before final APK publish");
        Fake shortBody=new Fake();shortBody.add(info.url,200,Arrays.copyOf(apk,apk.length-1),null,-1);rejects(()->new UpdateClient(shortBody).download(info,next(root,"short"),null),"short response is rejected despite unknown content length");
        Fake longBody=new Fake();longBody.add(info.url,200,Arrays.copyOf(apk,apk.length+1),null,-1);rejects(()->new UpdateClient(longBody).download(info,next(root,"long"),null),"long response is rejected during bounded copy");
        Fake declared=new Fake();declared.add(info.url,200,apk,null,apk.length+1);rejects(()->new UpdateClient(declared).download(info,next(root,"declared"),null),"content length must agree with GitHub metadata");
        Fake noNetwork=new Fake();rejects(()->new UpdateClient(noNetwork).download(parse(noDigest),next(root,"no-digest"),null),"no-digest candidate never performs a download");check(noNetwork.opened.isEmpty(),"missing digest fails before any connection");
        UpdateClient cancelled=new UpdateClient(new Fake());cancelled.cancel();rejects(()->cancelled.latest("0.4.2"),"cancelled updater never starts a connection");
        UpdateClient expired=new UpdateClient(new Fake());expired.deadline=System.nanoTime()-1;rejects(expired::check,"total request deadline bounds slow trickle responses");
        File interrupted=new File(next(root,"cancelled-copy"),"download.partial");UpdateClient pausedCopy=new UpdateClient(new Fake());rejects(()->pausedCopy.copy(new ByteArrayInputStream(apk),interrupted,apk.length,info.sha256,(done,total)->pausedCopy.cancel()),"cancelled copy stops before an APK is published");check(interrupted.isFile()&&interrupted.length()>0&&interrupted.length()<apk.length,"interrupted private partial is retained without deleting files");
        Set<String> signer=new HashSet<>(Arrays.asList("same signing certificate"));UpdateInfo.identity("io.vaultbridge.app","io.vaultbridge.app","0.4.3","0.4.3",8,9,signer,signer);check(true,"matching package version code and current signer identity is eligible");
        rejects(()->UpdateInfo.identity("io.vaultbridge.app","evil.app","0.4.3","0.4.3",8,9,signer,signer),"different package identity cannot install");
        rejects(()->UpdateInfo.identity("io.vaultbridge.app","io.vaultbridge.app","0.4.3","0.4.4",8,9,signer,signer),"APK version must equal selected release");
        rejects(()->UpdateInfo.identity("io.vaultbridge.app","io.vaultbridge.app","0.4.3","0.4.3",8,8,signer,signer),"equal or lower version code cannot replace current install");
        rejects(()->UpdateInfo.identity("io.vaultbridge.app","io.vaultbridge.app","0.4.3","0.4.3",8,9,signer,Collections.singleton("another certificate")),"different signing certificate cannot install");
        rejects(()->UpdateInfo.identity("io.vaultbridge.app","io.vaultbridge.app","0.4.3","0.4.3",8,9,Collections.emptySet(),Collections.emptySet()),"missing signers do not compare as a valid match");
        System.out.println("Android update checks passed: "+passed);
    }
}
