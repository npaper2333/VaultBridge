package io.vaultbridge.app;

import android.util.Base64;
import org.json.*;
import java.io.*;
import java.net.*;
import java.security.*;
import java.security.cert.*;
import javax.net.ssl.*;

final class LanClient {
    static class RemoteError extends IOException {RemoteError(String message){super(message);}}
    static final int MAX_FRAME = 8 * 1024 * 1024;
    final String host, pin;
    final int port;
    String token;
    LanClient(String address, int p, String fingerprint, String authorization) throws IOException {
        if (address == null || !address.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) throw new IOException("配对地址需要是电脑的 IPv4 地址");
        for (String component : address.split("\\.")) if (Integer.parseInt(component) > 255) throw new IOException("电脑地址无效");
        if (p < 1 || p > 65535 || fingerprint == null || !fingerprint.matches("[0-9a-fA-F]{64}")) throw new IOException("配对信息不完整");
        host = address; port = p; pin = fingerprint.toLowerCase(java.util.Locale.ROOT); token = authorization;
    }
    static JSONObject parsePairing(String text) throws IOException {
        try {
            text = text.trim(); if (!text.startsWith("vaultbridge:") || text.length() > 4096) throw new IOException("请粘贴电脑端复制的完整配对文字");
            JSONObject result = new JSONObject(new String(Base64.decode(text.substring(12), Base64.DEFAULT), "UTF-8"));
            if (result.getInt("v") != 1 || !result.getString("code").matches("[0-9]{6}")) throw new IOException("配对版本或验证码无效");
            return result;
        } catch (JSONException | IllegalArgumentException ex) { throw new IOException("配对文字损坏，请重新复制", ex); }
    }
    SSLSocket connect() throws IOException {
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(host, port), 8000); plain.setSoTimeout(120000);
            SSLContext context = SSLContext.getInstance("TLSv1.2");
            context.init(null, new TrustManager[] { new X509TrustManager() {
                public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { throw new CertificateException("Client certificates are not used"); }
                public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
                    if (chain == null || chain.length == 0) throw new CertificateException("Missing certificate");
                    try { String actual = Engine.hex(MessageDigest.getInstance("SHA-256").digest(chain[0].getEncoded())); if (!MessageDigest.isEqual(actual.getBytes("US-ASCII"), pin.getBytes("US-ASCII"))) throw new CertificateException("电脑身份变化，请重新扫码配对"); chain[0].checkValidity(); }
                    catch (GeneralSecurityException | UnsupportedEncodingException ex) { throw new CertificateException(ex); }
                }
            } }, new SecureRandom());
            SSLSocket socket = (SSLSocket)context.getSocketFactory().createSocket(plain, host, port, true); socket.setEnabledProtocols(new String[] { "TLSv1.2" }); socket.setSoTimeout(120000); socket.startHandshake(); return socket;
        } catch (Exception ex) { try { plain.close(); } catch (IOException ignored) {} throw new IOException("连接失败：检查电脑分享是否启动、地址和防火墙；校园网可能隔离设备。" + ex.getMessage(), ex); }
    }
    JSONObject request(JSONObject request) throws IOException {
        try (SSLSocket socket = connect()) { write(socket.getOutputStream(), request); return checked(read(socket.getInputStream())); }
    }
    JSONObject pair(String code, String name) throws IOException { try { JSONObject request = new JSONObject().put("action", "pair").put("code", code).put("device", name); JSONObject reply = request(request); token = reply.getString("token"); return reply; } catch (JSONException ex) { throw new IOException("配对响应无效", ex); } }
    JSONObject manifest() throws IOException { try { return request(new JSONObject().put("action", "manifest").put("token", token).put("scopes", new JSONArray().put(""))).getJSONObject("manifest"); } catch (JSONException ex) { throw new IOException("电脑清单无效", ex); } }
    Engine.Item download(JSONObject item, File destination) throws IOException {
        try {
            String path = Engine.normalize(item.getString("path")), expected = item.getString("sha256"); long size = item.getLong("size");
            if (Engine.protectedPath(path) || size < 0 || size > Engine.MAX_FILE || !expected.matches("[0-9a-f]{64}")) throw new IOException("文件清单包含不允许的内容");
            try (SSLSocket socket = connect()) {
                write(socket.getOutputStream(), new JSONObject().put("action", "get").put("token", token).put("path", path).put("sha256", expected));
                InputStream input = socket.getInputStream(); JSONObject response = checked(read(input));
                if (response.getLong("size") != size || !expected.equals(response.getString("sha256"))) throw new IOException("电脑文件信息变化，请刷新清单");
                try (OutputStream output = new FileOutputStream(destination)) {
                    byte[] buffer = new byte[65536]; long remaining = size;
                    while (remaining > 0) { int n = input.read(buffer, 0, (int)Math.min(buffer.length, remaining)); if (n < 0) throw new IOException("文件传输中断，可重新同步"); output.write(buffer, 0, n); remaining -= n; }
                }
                if (!expected.equals(Engine.hash(destination))) throw new IOException("下载内容校验失败，可重新同步");
                return new Engine.Item(path, expected, size, destination);
            }
        } catch (JSONException ex) { throw new IOException("电脑文件信息无效", ex); }
    }
    static void write(OutputStream stream, JSONObject object) throws IOException { byte[] bytes = object.toString().getBytes("UTF-8"); if (bytes.length == 0 || bytes.length > MAX_FRAME) throw new IOException("消息超过上限"); DataOutputStream data = new DataOutputStream(stream); data.writeInt(bytes.length); data.write(bytes); data.flush(); }
    static JSONObject read(InputStream stream) throws IOException { DataInputStream input = new DataInputStream(stream); int length = input.readInt(); if (length <= 0 || length > MAX_FRAME) throw new IOException("消息长度无效"); byte[] bytes = new byte[length]; input.readFully(bytes); try { return new JSONObject(new String(bytes, "UTF-8")); } catch (JSONException ex) { throw new IOException("消息格式无效", ex); } }
    static JSONObject checked(JSONObject response) throws IOException { if (!response.optBoolean("ok", false)) throw new RemoteError(response.optString("error", "电脑拒绝请求")); return response; }
}
