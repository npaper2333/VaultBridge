package io.vaultbridge.app;

import java.io.*;
import java.nio.charset.Charset;
import java.security.MessageDigest;
import java.text.SimpleDateFormat;
import java.util.*;
import java.util.zip.*;

/** Pure Java core. File-system adapters and UI never decide overwrite rules. */
public final class Engine {
    public static final long MAX_FILE = 256L * 1024 * 1024, MAX_TOTAL = 1024L * 1024 * 1024, MAX_ARCHIVE = 512L * 1024 * 1024;
    public static final int MAX_FILES = 20000;
    public interface Store {
        boolean exists(String path) throws IOException;
        InputStream read(String path) throws IOException;
        void write(String path, InputStream data) throws IOException;
    }
    public interface Ledger {
        String baseline(String source, String path);
        void baseline(String source, String path, String hash) throws IOException;
        void history(History record) throws IOException;
    }
    public static final class History {
        public String path, storedPath, hash, type, time, source;
        public History(String p, String stored, String h, String kind, String origin) { path = p; storedPath = stored; hash = h; type = kind; source = origin; time = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT).format(new Date()); }
    }
    public static final class Item {
        public final String path, hash;
        public final long size;
        public final File file;
        public Item(String p, String h, long s, File f) { path = p; hash = h; size = s; file = f; }
    }
    public static final class Staged {
        public final List<Item> items = new ArrayList<>();
        public final List<String> warnings = new ArrayList<>();
        public File metadata;
    }
    public enum Outcome { ADDED, UPDATED, UNCHANGED, CONFLICT }
    public static String normalize(String input) throws IOException {
        if (input == null || input.isEmpty() || input.length() > 1024 || input.startsWith("/") || input.startsWith("\\")) throw new IOException("无效的相对路径");
        String path = input.replace('\\', '/');
        String[] parts = path.split("/", -1);
        if (parts.length > 32) throw new IOException("目录层级超过上限");
        for (String part : parts) {
            if (part.isEmpty() || part.length() > 240 || part.equals(".") || part.equals("..") || part.endsWith(".") || part.endsWith(" ")) throw new IOException("无效的目录或文件名");
            for (int i = 0; i < part.length(); i++) if (part.charAt(i) < 32 || ":<>\"|?*".indexOf(part.charAt(i)) >= 0) throw new IOException("文件名包含非法字符");
            String stem = part.split("\\.", 2)[0].toUpperCase(Locale.ROOT);
            if (stem.matches("CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9]")) throw new IOException("受保护的设备文件名");
        }
        return path;
    }
    public static boolean protectedPath(String path) { for (String part : path.split("/")) if (part.startsWith(".") || part.equalsIgnoreCase("VaultBridge-History")) return true; return false; }
    public static Set<String> selectScopes(Collection<String> paths, Collection<String> scopes) throws IOException {
        for (String scope : scopes) if (!scope.isEmpty()) normalize(scope);
        Set<String> selected = new HashSet<>();
        for (String path : paths) for (String scope : scopes) if (scope.isEmpty() || path.equals(scope) || path.startsWith(scope + "/")) { selected.add(path); break; }
        return selected;
    }
    public static File resolve(File root, String relative) throws IOException {
        File target = new File(root, normalize(relative));
        String base = root.getCanonicalPath() + File.separator;
        if (!target.getCanonicalPath().startsWith(base)) throw new IOException("路径超出目标目录");
        return target;
    }
    public static String hex(byte[] bytes) { StringBuilder result = new StringBuilder(); for (byte value : bytes) result.append(String.format(Locale.ROOT, "%02x", value & 255)); return result.toString(); }
    public static String hash(InputStream input) throws IOException {
        try { MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] buffer = new byte[65536]; long size = 0; int count; while ((count = input.read(buffer)) != -1) { size += count; if (size > MAX_FILE) throw new IOException("文件超过 256 MB 上限"); digest.update(buffer, 0, count); } return hex(digest.digest()); }
        catch (java.security.NoSuchAlgorithmException ex) { throw new IOException(ex); }
    }
    public static String hash(File file) throws IOException { try (InputStream input = new FileInputStream(file)) { return hash(input); } }
    public static String hash(Store store, String path) throws IOException { try (InputStream input = store.read(path)) { return hash(input); } }
    public static long copy(InputStream input, OutputStream output, long limit) throws IOException { byte[] buffer = new byte[65536]; long total = 0; int count; while ((count = input.read(buffer)) != -1) { total += count; if (total > limit) throw new IOException("文件大小超过上限"); output.write(buffer, 0, count); } return total; }
    public static String batchId() { return new SimpleDateFormat("yyyyMMdd-HHmmss", Locale.ROOT).format(new Date()) + "-" + UUID.randomUUID().toString().substring(0, 8); }
    public static Staged stageZip(File archive, File folder) throws IOException {
        if (archive.length() > MAX_ARCHIVE) throw new IOException("ZIP 超过 512 MB 上限，请分批导出");
        if (folder.exists()) throw new IOException("临时目录已存在");
        if (!folder.mkdirs()) throw new IOException("无法创建临时目录");
        rejectLinkEntries(archive);
        ZipFile zip;
        try { zip = new ZipFile(archive, Charset.forName("UTF-8")); }
        catch (IllegalArgumentException | ZipException ex) { zip = new ZipFile(archive, Charset.forName("GB18030")); }
        try (ZipFile opened = zip) {
            Staged result = new Staged(); Set<String> names = new HashSet<>(); long total = 0; int entries = 0;
            Enumeration<? extends ZipEntry> all = opened.entries();
            while (all.hasMoreElements()) {
                ZipEntry entry = all.nextElement(); if (++entries > MAX_FILES) throw new IOException("ZIP 条目超过 20,000 个上限");
                String raw = entry.getName(); if (entry.isDirectory()) raw = raw.substring(0, raw.length() - 1);
                String path = normalize(raw); String key = path.toLowerCase(Locale.ROOT);
                if (!names.add(key)) throw new IOException("ZIP 中有重名或大小写冲突：" + path);
                if (path.equals(".vaultbridge-manifest.json")) {
                    if (entry.isDirectory()) throw new IOException("版本清单不是文件");
                    File meta = new File(folder, "manifest.json");
                    try (InputStream input = opened.getInputStream(entry); OutputStream output = new FileOutputStream(meta)) { copy(input, output, 8L * 1024 * 1024); }
                    result.metadata = meta; continue;
                }
                if (protectedPath(path)) { result.warnings.add("跳过配置或隐藏内容：" + path); continue; }
                if (entry.isDirectory()) continue;
                if (entry.getSize() > MAX_FILE) throw new IOException("ZIP 单文件超过 256 MB：" + path);
                File target = resolve(folder, "files/" + path); if (!target.getParentFile().isDirectory() && !target.getParentFile().mkdirs()) throw new IOException("无法创建临时文件夹");
                long size;
                try (InputStream input = opened.getInputStream(entry); OutputStream output = new FileOutputStream(target)) { size = copy(input, output, MAX_FILE); }
                total += size; if (total > MAX_TOTAL) throw new IOException("总解压大小超过 1 GB，请分批导出");
                if (entry.getSize() >= 0 && size != entry.getSize()) throw new IOException("ZIP 文件长度不一致：" + path);
                CRC32 crc = new CRC32(); try (InputStream input = new FileInputStream(target)) { byte[] bytes = new byte[65536]; int n; while ((n = input.read(bytes)) != -1) crc.update(bytes, 0, n); }
                if (entry.getCrc() >= 0 && crc.getValue() != entry.getCrc()) throw new IOException("ZIP 内容损坏：" + path);
                result.items.add(new Item(path, hash(target), size, target));
            }
            if (result.items.isEmpty()) throw new IOException("ZIP 中没有可导入的文件");
            Collections.sort(result.items, Comparator.comparing(item -> item.path)); return result;
        }
    }
    // ZIP central directory external mode bits distinguish Unix symlinks; no links are extracted.
    static void rejectLinkEntries(File archive) throws IOException {
        try (RandomAccessFile file = new RandomAccessFile(archive, "r")) {
            long end = file.length(), start = Math.max(0, end - 65557), offset = -1;
            for (long p = end - 22; p >= start; p--) { file.seek(p); if (readLE(file, 4) == 0x06054b50L) { file.seek(p + 20); int comment = (int)readLE(file, 2); if (p + 22 + comment == end) { offset = p; break; } } }
            if (offset < 0) throw new IOException("不是完整的 ZIP 文件");
            file.seek(offset + 4); long disk = readLE(file, 2), centralDisk = readLE(file, 2), diskCount = readLE(file, 2), count = readLE(file, 2);
            long centralSize = readLE(file, 4), centralOffset = readLE(file, 4);
            if (disk != 0 || centralDisk != 0 || count != diskCount || count == 65535 || centralOffset == 0xffffffffL || centralSize == 0xffffffffL) throw new IOException("暂不支持分卷或 ZIP64 压缩包，请分批导出普通 ZIP");
            if (count > MAX_FILES || centralOffset + centralSize > offset) throw new IOException("ZIP 清单无效");
            long p = centralOffset;
            for (int i = 0; i < count; i++) {
                file.seek(p); if (readLE(file, 4) != 0x02014b50L) throw new IOException("ZIP 目录损坏");
                file.seek(p + 8); long flags = readLE(file, 2); if ((flags & 1) != 0) throw new IOException("暂不支持加密 ZIP");
                file.seek(p + 28); long name = readLE(file, 2), extra = readLE(file, 2), comment = readLE(file, 2);
                file.seek(p + 38); long attributes = readLE(file, 4); int type = (int)((attributes >>> 16) & 0xf000);
                if (type == 0xa000) throw new IOException("ZIP 含符号链接，已拒绝导入");
                p += 46 + name + extra + comment; if (p > centralOffset + centralSize) throw new IOException("ZIP 目录越界");
            }
            if (p != centralOffset + centralSize) throw new IOException("ZIP 目录长度不匹配");
        }
    }
    static long readLE(RandomAccessFile file, int count) throws IOException { long value = 0; for (int i = 0; i < count; i++) value |= (long)file.readUnsignedByte() << (8 * i); return value; }
    public static Outcome apply(Store store, Ledger ledger, Item incoming, String path, String source, String batch, boolean force) throws IOException {
        path = normalize(path); if (protectedPath(path)) throw new IOException("禁止写入配置或隐藏目录");
        if (!hash(incoming.file).equals(incoming.hash)) throw new IOException("临时文件校验失败");
        boolean exists = store.exists(path); String current = exists ? hash(store, path) : null;
        if (incoming.hash.equals(current)) { ledger.baseline(source, path, current); return Outcome.UNCHANGED; }
        if (exists && !force && !Objects.equals(current, ledger.baseline(source, path))) {
            String conflict = ".vaultbridge/conflicts/" + batch + "/" + path;
            writeChecked(store, conflict, incoming.file, incoming.hash);
            ledger.history(new History(path, conflict, incoming.hash, "conflict", source)); return Outcome.CONFLICT;
        }
        String backup = null;
        if (exists) {
            backup = ".vaultbridge/history/" + batch + "/" + path;
            try (InputStream input = store.read(path)) { store.write(backup, input); }
            if (!current.equals(hash(store, backup))) throw new IOException("旧版本备份校验失败，已取消更新");
            ledger.history(new History(path, backup, current, "backup", source));
            if (!current.equals(hash(store, path))) throw new IOException("目标笔记在备份期间又被修改，已取消更新，请重新同步");
        }
        try { writeChecked(store, path, incoming.file, incoming.hash); }
        catch (IOException ex) {
            if (backup != null) {
                try { try (InputStream original = store.read(backup)) { store.write(path, original); } if (!current.equals(hash(store, path))) throw new IOException("恢复校验失败"); }
                catch (IOException restore) { throw new IOException("写入失败且自动恢复失败；旧版本仍保存在导入记录中。" + restore.getMessage(), ex); }
                throw new IOException("写入失败，已恢复旧版本：" + ex.getMessage(), ex);
            }
            throw new IOException("新文件写入失败，目标可能有未完成文件：" + path + "。重试时会保留冲突，不会覆盖其他内容。", ex);
        }
        ledger.baseline(source, path, incoming.hash); return exists ? Outcome.UPDATED : Outcome.ADDED;
    }
    static void writeChecked(Store store, String path, File source, String expected) throws IOException { try (InputStream input = new FileInputStream(source)) { store.write(path, input); } if (!expected.equals(hash(store, path))) throw new IOException("目标文件写入后校验失败"); }
}
