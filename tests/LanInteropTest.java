package io.vaultbridge.app;
import java.io.*;
import org.json.*;
public final class LanInteropTest {
    static int passed;
    static void check(boolean condition, String label) { if (!condition) throw new AssertionError(label); passed++; System.out.println("PASS: " + label); }
    public static void main(String[] args) throws Exception {
        // Pairing data arrives only through private redirected stdin, never argv or logs.
        String pairing = new BufferedReader(new InputStreamReader(System.in, "UTF-8")).readLine();
        JSONObject information = LanClient.parsePairing(pairing);
        LanClient client = new LanClient(information.getString("host"), information.getInt("port"), information.getString("pin"), null);
        check(client.pair(information.getString("code"), "JVM Android client test").getBoolean("ok"), "Android client pairs with Windows server");
        JSONObject manifest = client.manifest(); JSONArray files = manifest.getJSONArray("files"); check(files.length() == 2, "Android client receives Unicode manifest and allowed scope");
        JSONObject selected = null; for (int i = 0; i < files.length(); i++) if (files.getJSONObject(i).getString("path").endsWith(".md")) selected = files.getJSONObject(i);
        File output = new File(args[0], "interop-download.md"); Engine.Item item = client.download(selected, output); check(item.hash.equals(Engine.hash(output)), "Android client downloads Windows content and verifies SHA-256");
        LanClient wrongPin = new LanClient(client.host, client.port, new String(new char[64]).replace('\0', '0'), client.token); boolean pinRejected = false; try { wrongPin.manifest(); } catch (IOException ex) { pinRejected = true; } check(pinRejected, "Android client rejects mismatched server fingerprint");
        client.token = "forged"; boolean rejected = false; try { client.manifest(); } catch (IOException ex) { rejected = true; } check(rejected, "Windows rejects forged Android authorization");
        System.out.println("Cross-platform protocol tests passed: " + passed);
    }
}
