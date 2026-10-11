package io.vaultbridge.app;
import java.io.*;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.zip.*;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import com.google.zxing.*;
import com.google.zxing.common.HybridBinarizer;

public class EngineTest {
    static int passed;
    static File root;
    interface Action { void run() throws Exception; }
    static void check(boolean value, String label) { if (!value) throw new AssertionError(label); passed++; System.out.println("PASS: " + label); }
    static void rejects(Action action, String label) throws Exception { boolean rejected = false; try { action.run(); } catch (IOException ex) { rejected = true; } check(rejected, label); }
    static class LocalStore implements Engine.Store {
        final File folder; boolean failNext; Action afterBackup;
        LocalStore(File directory) throws IOException { folder = directory; if (!folder.mkdirs()) throw new IOException("test directory already exists"); }
        public boolean exists(String path) throws IOException { return Engine.resolve(folder, path).exists(); }
        public InputStream read(String path) throws IOException { return new FileInputStream(Engine.resolve(folder, path)); }
        public void write(String path, InputStream input) throws IOException { File file = Engine.resolve(folder, path); file.getParentFile().mkdirs(); try (OutputStream output = new FileOutputStream(file)) { if (failNext && !path.startsWith(".vaultbridge/")) { failNext = false; output.write(88); throw new IOException("simulated partial write"); } Engine.copy(input, output, Engine.MAX_FILE); } if (path.startsWith(".vaultbridge/history/") && afterBackup != null) { Action action = afterBackup; afterBackup = null; try { action.run(); } catch (Exception ex) { throw new IOException(ex); } } }
    }
    static class Ledger implements Engine.Ledger {
        Map<String, String> baselines = new HashMap<>(); List<Engine.History> history = new ArrayList<>();
        public String baseline(String source, String path) { return baselines.get(source + "|" + path); }
        public void baseline(String source, String path, String hash) { baselines.put(source + "|" + path, hash); }
        public void history(Engine.History entry) { history.add(entry); }
    }
    static Engine.Item incoming(String text) throws Exception { File file = new File(root, UUID.randomUUID() + ".md"); Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8)); return new Engine.Item("课程/笔记.md", Engine.hash(file), file.length(), file); }
    static File zip(String[] names, Charset charset, boolean stored) throws Exception { File file = new File(root, UUID.randomUUID() + ".zip"); byte[] bytes = "中文笔记\n![图片](image.png)".getBytes(StandardCharsets.UTF_8); try (ZipOutputStream output = new ZipOutputStream(new FileOutputStream(file), charset)) { for (String name : names) { ZipEntry entry = new ZipEntry(name); if (stored) { CRC32 crc = new CRC32(); crc.update(bytes); entry.setMethod(ZipEntry.STORED); entry.setSize(bytes.length); entry.setCrc(crc.getValue()); } output.putNextEntry(entry); output.write(bytes); output.closeEntry(); } } return file; }
    static Engine.Staged stage(File file) throws Exception { return Engine.stageZip(file, new File(root, "stage-" + UUID.randomUUID())); }
    static long central(File archive) throws Exception { try (RandomAccessFile input = new RandomAccessFile(archive, "r")) { for (long i = 0; i < input.length() - 4; i++) { input.seek(i); if (input.readInt() == 0x504b0102) return i; } } throw new Exception("no central directory"); }
    public static void main(String[] args) throws Exception {
        root = new File(args[0], "engine-" + UUID.randomUUID()); check(root.mkdirs(), "isolated test workspace");
        check(Engine.normalize("课程\\笔记.md").equals("课程/笔记.md"), "Chinese and Windows separators");
        Set<String> scopeSelection = Engine.selectScopes(Arrays.asList("课程/旧笔记.md", "课程/新笔记.md", "课程二/其他.md"), Collections.singletonList("课程"));
        check(scopeSelection.size() == 2 && scopeSelection.contains("课程/新笔记.md"), "saved folder scope includes future notes and excludes prefix neighbors");
        for (String path : new String[] { "../outside.md", "/absolute", "C:/outside", "a//b", "a/../b", "CON.md", "a/b ", "a/new\nline" }) rejects(() -> Engine.normalize(path), "reject path " + path.replace("\n", "[newline]"));
        File good = zip(new String[] { "课程/笔记.md", "课程/image.png", ".obsidian/config.json" }, StandardCharsets.UTF_8, false); Engine.Staged content = stage(good); check(content.items.size() == 2 && content.warnings.size() == 1, "ZIP keeps notes and attachments, skips configuration");
        check(content.items.get(0).path.startsWith("课程/"), "ZIP directory structure preserved");
        check(stage(zip(new String[] { "中文目录/课程.md" }, Charset.forName("GB18030"), false)).items.get(0).path.equals("中文目录/课程.md"), "GB18030 ZIP filename compatibility");
        rejects(() -> stage(zip(new String[] { "../escape.md" }, StandardCharsets.UTF_8, false)), "ZIP traversal rejected");
        rejects(() -> stage(zip(new String[] { "A.md", "a.md" }, StandardCharsets.UTF_8, false)), "ZIP case collisions rejected");
        File symlink = zip(new String[] { "link.md" }, StandardCharsets.UTF_8, false); try (RandomAccessFile bytes = new RandomAccessFile(symlink, "rw")) { bytes.seek(central(symlink) + 38); bytes.write(new byte[] { 0, 0, 0, (byte)0xa0 }); } rejects(() -> stage(symlink), "ZIP Unix symlink rejected");
        File encrypted = zip(new String[] { "encrypted.md" }, StandardCharsets.UTF_8, false); try (RandomAccessFile bytes = new RandomAccessFile(encrypted, "rw")) { bytes.seek(central(encrypted) + 8); int flags = bytes.readUnsignedByte(); bytes.seek(central(encrypted) + 8); bytes.write(flags | 1); } rejects(() -> stage(encrypted), "encrypted ZIP rejected with clear limitation");
        File corrupt = zip(new String[] { "a.md" }, StandardCharsets.UTF_8, true); try (RandomAccessFile bytes = new RandomAccessFile(corrupt, "rw")) { bytes.seek(34); bytes.write(88); } rejects(() -> stage(corrupt), "damaged ZIP CRC rejected before writes");
        File oversize = zip(new String[] { "huge.md" }, StandardCharsets.UTF_8, false); try (RandomAccessFile bytes = new RandomAccessFile(oversize, "rw")) { bytes.seek(central(oversize) + 24); long size = Engine.MAX_FILE + 1; for (int i = 0; i < 4; i++) bytes.write((int)(size >>> (8 * i)) & 255); } rejects(() -> stage(oversize), "oversize ZIP entry rejected without allocation");
        LocalStore store = new LocalStore(new File(root, "vault")); Ledger ledger = new Ledger(); String source = "pc:test", path = "课程/笔记.md";
        Engine.Item first = incoming("computer v1"); check(Engine.apply(store, ledger, first, path, source, Engine.batchId(), false) == Engine.Outcome.ADDED, "first sync adds note");
        check(Engine.apply(store, ledger, first, path, source, Engine.batchId(), false) == Engine.Outcome.UNCHANGED, "repeat sync skips identical content");
        Engine.Item second = incoming("computer v2"); check(Engine.apply(store, ledger, second, path, source, Engine.batchId(), false) == Engine.Outcome.UPDATED, "computer update applied when tablet unchanged");
        check(ledger.history.size() == 1 && Engine.hash(store, ledger.history.get(0).storedPath).equals(first.hash), "old version backed up before update");
        store.write(path, new ByteArrayInputStream("tablet local edit".getBytes(StandardCharsets.UTF_8))); String localHash = Engine.hash(store, path); Engine.Item third = incoming("computer v3");
        check(Engine.apply(store, ledger, third, path, source, Engine.batchId(), false) == Engine.Outcome.CONFLICT, "tablet edit prevents overwrite");
        check(Engine.hash(store, path).equals(localHash), "conflict preserves tablet original"); Engine.History conflict = ledger.history.get(ledger.history.size() - 1); check(conflict.type.equals("conflict") && Engine.hash(store, conflict.storedPath).equals(third.hash), "computer conflict version retained");
        check(Engine.apply(store, ledger, third, path, source, Engine.batchId(), true) == Engine.Outcome.UPDATED, "explicit adoption updates after backup");
        Engine.Item fourth = incoming("computer v4"); store.failNext = true; rejects(() -> Engine.apply(store, ledger, fourth, path, source, Engine.batchId(), false), "partial provider write reported as failure");
        check(Engine.hash(store, path).equals(third.hash), "failed overwrite restores prior version"); check(ledger.baseline(source, path).equals(third.hash), "failed write never advances sync baseline");
        store.afterBackup = () -> store.write(path, new ByteArrayInputStream("edit during backup".getBytes(StandardCharsets.UTF_8))); rejects(() -> Engine.apply(store, ledger, fourth, path, source, Engine.batchId(), false), "concurrent tablet edit detected before overwrite"); check(Engine.hash(store, path).equals(Engine.hash(new ByteArrayInputStream("edit during backup".getBytes(StandardCharsets.UTF_8)))), "concurrent edit remains intact");
        rejects(() -> Engine.apply(store, ledger, first, ".obsidian/config.json", source, Engine.batchId(), true), "manual force cannot overwrite Obsidian configuration");
        Engine.Item tampered = incoming("staged original"); Files.write(tampered.file.toPath(), "tampered".getBytes(StandardCharsets.UTF_8)); rejects(() -> Engine.apply(store, ledger, tampered, "new.md", source, Engine.batchId(), false), "staged content checked again before write");
        LocalStore preexisting = new LocalStore(new File(root, "preexisting")); preexisting.write("old.md", new ByteArrayInputStream("existing user note".getBytes(StandardCharsets.UTF_8))); check(Engine.apply(preexisting, new Ledger(), first, "old.md", source, Engine.batchId(), false) == Engine.Outcome.CONFLICT, "first sync protects different existing file");
        BufferedImage qr = ImageIO.read(new File(args[0], "qrcode-test.png")); int[] pixels = qr.getRGB(0, 0, qr.getWidth(), qr.getHeight(), null, 0, qr.getWidth()); Result decoded = new MultiFormatReader().decode(new BinaryBitmap(new HybridBinarizer(new RGBLuminanceSource(qr.getWidth(), qr.getHeight(), pixels)))); check(decoded.getText().equals("vaultbridge:TEST-FOR-QR-ROUNDTRIP"), "Windows QR decoded by Android ZXing library");
        System.out.println("Android core tests passed: " + passed);
    }
}
