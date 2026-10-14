using System;
using System.Collections.Generic;
using System.Globalization;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.NetworkInformation;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Security.Cryptography.X509Certificates;
using System.Text;
using System.Threading;
using System.Web.Script.Serialization;

namespace VaultBridge {
    public static class Json {
        public static string Encode(object value) { return new JavaScriptSerializer { MaxJsonLength = Wire.MaxFrame }.Serialize(value); }
        public static T Decode<T>(string value) { return new JavaScriptSerializer { MaxJsonLength = Wire.MaxFrame }.Deserialize<T>(value); }
    }
    public static class Paths {
        public const long MaxFile = 256L * 1024 * 1024;
        public const int MaxFiles = 20000;
        public static string Normalize(string value) {
            if (String.IsNullOrEmpty(value) || value.Length > 1024 || value[0] == '/' || value[0] == '\\') throw new IOException("无效的相对路径");
            string result = value.Replace('\\', '/');
            string[] parts = result.Split('/');
            if (parts.Length > 32) throw new IOException("目录层级过深");
            foreach (string part in parts) {
                if (part.Length == 0 || part.Length > 240 || part == "." || part == ".." || part.EndsWith(".") || part.EndsWith(" ") || part.Any(c => c < 32 || ":<>\"|?*".IndexOf(c) >= 0)) throw new IOException("无效的文件名");
                if (part.StartsWith(".", StringComparison.Ordinal) || part.Equals("VaultBridge-History", StringComparison.OrdinalIgnoreCase)) throw new IOException("受保护的目录");
                string stem = part.Split('.')[0].ToUpperInvariant();
                if (new[] { "CON", "PRN", "AUX", "NUL", "COM1", "COM2", "COM3", "COM4", "COM5", "COM6", "COM7", "COM8", "COM9", "LPT1", "LPT2", "LPT3", "LPT4", "LPT5", "LPT6", "LPT7", "LPT8", "LPT9" }.Contains(stem)) throw new IOException("受保护的设备文件名");
            }
            return result;
        }
        public static string Resolve(string root, string relative) {
            string normalized = Normalize(relative);
            string basePath = Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
            string target = Path.GetFullPath(Path.Combine(basePath, normalized.Replace('/', Path.DirectorySeparatorChar)));
            if (!target.StartsWith(basePath, StringComparison.OrdinalIgnoreCase)) throw new IOException("路径超出笔记库");
            string current = basePath.TrimEnd(Path.DirectorySeparatorChar);
            if ((File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0) throw new IOException("不允许分享链接目录");
            foreach (string part in normalized.Split('/')) {
                current = Path.Combine(current, part);
                if ((File.Exists(current) || Directory.Exists(current)) && (File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0) throw new IOException("不允许分享符号链接");
            }
            return target;
        }
        public static bool IsInScope(string path, string scope) { return scope == "" || path == scope || path.StartsWith(scope + "/", StringComparison.Ordinal); }
    }
    public class FileItem { public string path; public long size; public string sha256; public long modified; }
    public class VaultManifest { public string vaultId; public string name; public List<FileItem> files; public List<string> warnings; public long generated; }
    public class Device { public string name; public string token; public string pairedAt; }
    public class Settings {
        public string style = "lake";
        public bool showHelp = true;
        public bool checkUpdatesOnStart = true;
        public string vault = "";
        public int port = 27183;
        public string vaultId = Guid.NewGuid().ToString("N");
        public string certificate;
        public string inbox = "";
        public List<Device> devices = new List<Device>();
        public List<string> scopes = new List<string> { "" };
    }
    public static class Crypto {
        public static string Hash(Stream input) { using (SHA256 sha = SHA256.Create()) return Hex(sha.ComputeHash(input)); }
        public static string HashFile(string file) { using (FileStream input = File.Open(file, FileMode.Open, FileAccess.Read, FileShare.Read)) return Hash(input); }
        public static string Hex(byte[] data) { return BitConverter.ToString(data).Replace("-", "").ToLowerInvariant(); }
        public static string RandomToken() { byte[] data = new byte[32]; using (RandomNumberGenerator rng = RandomNumberGenerator.Create()) rng.GetBytes(data); return Hex(data); }
        public static bool Equal(string a, string b) { if (a == null || b == null) return false; byte[] aa = Encoding.UTF8.GetBytes(a), bb = Encoding.UTF8.GetBytes(b); int difference = aa.Length ^ bb.Length; for (int i = 0; i < Math.Max(aa.Length, bb.Length); i++) difference |= (i < aa.Length ? aa[i] : 0) ^ (i < bb.Length ? bb[i] : 0); return difference == 0; }
        public static X509Certificate2 NewCertificate() {
            using (RSA rsa = RSA.Create(2048)) {
                CertificateRequest request = new CertificateRequest("CN=VaultBridge", rsa, HashAlgorithmName.SHA256, RSASignaturePadding.Pkcs1);
                request.CertificateExtensions.Add(new X509KeyUsageExtension(X509KeyUsageFlags.DigitalSignature | X509KeyUsageFlags.KeyEncipherment, false));
                request.CertificateExtensions.Add(new X509EnhancedKeyUsageExtension(new OidCollection { new Oid("1.3.6.1.5.5.7.3.1") }, false));
                using (X509Certificate2 certificate = request.CreateSelfSigned(DateTimeOffset.UtcNow.AddDays(-1), DateTimeOffset.UtcNow.AddYears(5)))
                    return new X509Certificate2(certificate.Export(X509ContentType.Pfx), "", X509KeyStorageFlags.EphemeralKeySet | X509KeyStorageFlags.Exportable);
            }
        }
        public static string Fingerprint(X509Certificate2 certificate) { using (SHA256 sha = SHA256.Create()) return Hex(sha.ComputeHash(certificate.RawData)); }
    }
    public class SettingsStore {
        readonly string file;
        readonly object gate = new object();
        public SettingsStore(string directory) { Directory.CreateDirectory(directory); file = Path.Combine(directory, "settings.protected"); }
        public Settings Load() {
            if (!File.Exists(file)) return new Settings();
            byte[] clear = ProtectedData.Unprotect(File.ReadAllBytes(file), null, DataProtectionScope.CurrentUser);
            return Json.Decode<Settings>(Encoding.UTF8.GetString(clear));
        }
        public void Save(Settings value) { lock (gate) File.WriteAllBytes(file, ProtectedData.Protect(Encoding.UTF8.GetBytes(Json.Encode(value)), null, DataProtectionScope.CurrentUser)); }
    }
    public class VaultIndex {
        readonly string root;
        readonly string id;
        readonly List<string> allowed;
        public VaultIndex(string directory, string vaultId, IEnumerable<string> scopes) {
            root = Path.GetFullPath(directory); id = vaultId; allowed = new List<string>(scopes);
            if (!Directory.Exists(root)) throw new IOException("笔记库不存在");
            if ((File.GetAttributes(root) & FileAttributes.ReparsePoint) != 0) throw new IOException("请选择真实目录，不使用链接目录");
            foreach (string scope in allowed) if (scope != "") Paths.Normalize(scope);
        }
        public bool Allowed(string path) { return allowed.Any(s => Paths.IsInScope(path, s)); }
        public string FilePath(string relative) { relative = Paths.Normalize(relative); if (!Allowed(relative)) throw new IOException("该文件未获分享授权"); return Paths.Resolve(root, relative); }
        public VaultManifest Scan(IEnumerable<string> selected) {
            List<string> selection = selected == null ? new List<string> { "" } : selected.ToList();
            foreach (string scope in selection) if (scope != "") Paths.Normalize(scope);
            VaultManifest result = new VaultManifest { vaultId = id, name = new DirectoryInfo(root).Name, files = new List<FileItem>(), warnings = new List<string>(), generated = DateTimeOffset.UtcNow.ToUnixTimeSeconds() };
            Walk(root, "", selection, result);
            result.files.Sort((a, b) => String.CompareOrdinal(a.path, b.path));
            return result;
        }
        void Walk(string folder, string prefix, List<string> selected, VaultManifest result) {
            foreach (string entry in Directory.EnumerateFileSystemEntries(folder)) {
                string name = Path.GetFileName(entry), relative = prefix + name;
                try {
                    Paths.Normalize(relative);
                    FileAttributes attributes = File.GetAttributes(entry);
                    if ((attributes & FileAttributes.ReparsePoint) != 0) { result.warnings.Add(relative + "：跳过链接"); continue; }
                    if ((attributes & FileAttributes.Directory) != 0) {
                        if (allowed.Any(s => Paths.IsInScope(relative, s) || Paths.IsInScope(s, relative)) && selected.Any(s => Paths.IsInScope(relative, s) || Paths.IsInScope(s, relative))) Walk(entry, relative + "/", selected, result);
                    } else if (Allowed(relative) && selected.Any(s => Paths.IsInScope(relative, s))) {
                        FileInfo info = new FileInfo(entry);
                        if (info.Length > Paths.MaxFile) { result.warnings.Add(relative + "：超过 256 MB 上限"); continue; }
                        if (result.files.Count >= Paths.MaxFiles) throw new InvalidOperationException("文件超过 20,000 个，请缩小分享范围");
                        result.files.Add(new FileItem { path = relative, size = info.Length, sha256 = Crypto.HashFile(entry), modified = new DateTimeOffset(info.LastWriteTimeUtc).ToUnixTimeSeconds() });
                    }
                } catch (IOException ex) { if (!name.StartsWith(".")) result.warnings.Add(relative + "：" + ex.Message); }
                catch (UnauthorizedAccessException) { result.warnings.Add(relative + "：无法读取"); }
            }
        }
        public void Export(string destination, IEnumerable<string> selected) {
            string absolute = Path.GetFullPath(destination), basePath = root.TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
            if (absolute.StartsWith(basePath, StringComparison.OrdinalIgnoreCase)) throw new IOException("请将导出 ZIP 保存到库外，避免把 ZIP 再打包进库");
            VaultManifest manifest = Scan(selected);
            if (manifest.files.Sum(item => item.size) > 1024L * 1024 * 1024) throw new IOException("所选内容超过 1 GB，请缩小范围并分批导出");
            using (FileStream output = new FileStream(absolute, FileMode.CreateNew, FileAccess.Write))
            using (ZipArchive archive = new ZipArchive(output, ZipArchiveMode.Create, false, Encoding.UTF8)) {
                foreach (FileItem item in manifest.files) {
                    using (Stream input = File.Open(FilePath(item.path), FileMode.Open, FileAccess.Read, FileShare.Read)) {
                        if (!Crypto.Equal(Crypto.Hash(input), item.sha256)) throw new IOException("笔记在导出过程中变化，请重新导出");
                        input.Position = 0;
                        using (Stream target = archive.CreateEntry(item.path, CompressionLevel.Optimal).Open()) input.CopyTo(target);
                    }
                }
                using (StreamWriter writer = new StreamWriter(archive.CreateEntry(".vaultbridge-manifest.json").Open(), new UTF8Encoding(false))) writer.Write(Json.Encode(manifest));
            }
        }
    }
    public static class Wire {
        public const int MaxFrame = 8 * 1024 * 1024;
        public static void Write(Stream stream, object value) {
            byte[] data = Encoding.UTF8.GetBytes(Json.Encode(value));
            if (data.Length > MaxFrame) throw new IOException("清单太大，请缩小范围");
            byte[] length = BitConverter.GetBytes(IPAddress.HostToNetworkOrder(data.Length));
            stream.Write(length, 0, 4); stream.Write(data, 0, data.Length); stream.Flush();
        }
        public static Dictionary<string, object> Read(Stream stream) {
            byte[] prefix = ReadBytes(stream, 4);
            int size = IPAddress.NetworkToHostOrder(BitConverter.ToInt32(prefix, 0));
            if (size <= 0 || size > MaxFrame) throw new IOException("无效的消息大小");
            return Json.Decode<Dictionary<string, object>>(Encoding.UTF8.GetString(ReadBytes(stream, size)));
        }
        public static byte[] ReadBytes(Stream stream, int count) { byte[] data = new byte[count]; int read = 0; while (read < count) { int n = stream.Read(data, read, count - read); if (n <= 0) throw new EndOfStreamException(); read += n; } return data; }
        public static string Text(Dictionary<string, object> value, string key) { object field; return value.TryGetValue(key, out field) ? Convert.ToString(field, CultureInfo.InvariantCulture) : ""; }
        public static string[] Strings(Dictionary<string, object> value, string key) { object field; if (!value.TryGetValue(key, out field)) return new[] { "" }; System.Collections.IEnumerable list = field as System.Collections.IEnumerable; if (list == null || field is string) throw new IOException("范围格式错误"); return list.Cast<object>().Select(Convert.ToString).ToArray(); }
    }
    public class VaultServer : IDisposable {
        readonly Settings settings;
        readonly Action save;
        readonly Action<string> log;
        readonly object gate = new object();
        TcpListener listener;
        Thread thread;
        volatile bool running;
        X509Certificate2 certificate;
        string pairingCode;
        DateTime pairingExpires;
        readonly Dictionary<string, int> failures = new Dictionary<string, int>();
        readonly HashSet<TcpClient> activeClients = new HashSet<TcpClient>();
        int clients;
        public int Port { get; private set; }
        public TransferHub Transfers;
        public string Fingerprint { get { return Crypto.Fingerprint(certificate); } }
        public VaultServer(Settings state, Action persist, Action<string> logger) { settings = state; save = persist; log = logger; }
        public void Start(bool loopback) {
            if (running) return;
            if (settings.certificate == null) { using (X509Certificate2 generated = Crypto.NewCertificate()) settings.certificate = Convert.ToBase64String(generated.Export(X509ContentType.Pfx)); save(); }
            // Windows Schannel in .NET Framework cannot use ephemeral PFX keys.
            // Default user key lifetime is tied to this certificate; no certificate-store import.
            certificate = new X509Certificate2(Convert.FromBase64String(settings.certificate), "", X509KeyStorageFlags.UserKeySet);
            listener = new TcpListener(loopback ? IPAddress.Loopback : IPAddress.Any, settings.port);
            listener.Start(16); Port = ((IPEndPoint)listener.LocalEndpoint).Port; running = true; RenewPairing();
            thread = new Thread(AcceptLoop) { IsBackground = true }; thread.Start();
        }
        public void RenewPairing() { lock (gate) { byte[] bytes = new byte[4]; using (RandomNumberGenerator rng = RandomNumberGenerator.Create()) rng.GetBytes(bytes); pairingCode = (BitConverter.ToUInt32(bytes, 0) % 1000000).ToString("D6"); pairingExpires = DateTime.UtcNow.AddMinutes(5); failures.Clear(); } }
        public string PairingText(string address) { lock (gate) return "vaultbridge:" + Convert.ToBase64String(Encoding.UTF8.GetBytes(Json.Encode(new { v = 1, host = address, port = Port, pin = Fingerprint, code = pairingCode, name = Environment.MachineName }))); }
        public DateTime PairingExpires { get { return pairingExpires; } }
        void AcceptLoop() {
            while (running) {
                try { TcpClient client = listener.AcceptTcpClient(); if (Interlocked.Increment(ref clients) > 6) { Interlocked.Decrement(ref clients); client.Close(); continue; } lock (gate) activeClients.Add(client); ThreadPool.QueueUserWorkItem(_ => Handle(client)); }
                catch (SocketException) { if (running) log("连接监听失败，请重新启动分享"); }
                catch (ObjectDisposedException) { break; }
            }
        }
        void Handle(TcpClient client) {
            try {
                using (client) {
                    client.ReceiveTimeout = 120000; client.SendTimeout = 120000;
                    using (SslStream ssl = new SslStream(client.GetStream(), false)) {
                        ssl.ReadTimeout = 120000; ssl.WriteTimeout = 120000;
                        ssl.AuthenticateAsServer(certificate, false, SslProtocols.Tls12, false);
                        Dictionary<string, object> request = Wire.Read(ssl);
                        try {
                            string action = Wire.Text(request, "action");
                            if (action == "pair") {
                                string remote = ((IPEndPoint)client.Client.RemoteEndPoint).Address.ToString();
                                lock (gate) {
                                    int count; failures.TryGetValue(remote, out count);
                                    if (count >= 8 || DateTime.UtcNow >= pairingExpires || !Crypto.Equal(pairingCode, Wire.Text(request, "code"))) { failures[remote] = count + 1; throw new IOException("配对信息已过期或不正确，请在电脑刷新二维码"); }
                                    if (settings.devices.Count >= 20) throw new IOException("已配对设备达到上限，请先撤销旧设备");
                                    Device device = new Device { name = Wire.Text(request, "device").Substring(0, Math.Min(60, Wire.Text(request, "device").Length)), token = Crypto.RandomToken(), pairedAt = DateTime.UtcNow.ToString("o") };
                                    settings.devices.Add(device); save(); pairingExpires = DateTime.MinValue;
                                    Wire.Write(ssl, new { ok = true, token = device.token, vaultId = settings.vaultId, name = Directory.Exists(settings.vault)?new DirectoryInfo(settings.vault).Name:"未选择笔记库" });
                                    log("已配对一台平板；原二维码失效");
                                }
                                return;
                            }
                            lock (gate) if (!settings.devices.Any(d => Crypto.Equal(d.token, Wire.Text(request, "token")))) throw new IOException("设备尚未配对或授权已撤销");
                            if(Transfers!=null&&Transfers.Handle(action,request,ssl,Wire.Text(request,"token")))return;
                            VaultIndex index = new VaultIndex(settings.vault, settings.vaultId, settings.scopes);
                            if (action == "manifest") {
                                VaultManifest manifest = index.Scan(Wire.Strings(request, "scopes"));
                                if(Transfers!=null)Transfers.RememberNotes(manifest);
                                Wire.Write(ssl, new { ok = true, manifest = manifest }); log("已提供笔记清单：" + manifest.files.Count + " 个文件");
                            } else if (action == "get") {
                                string relative = Paths.Normalize(Wire.Text(request, "path"));
                                using (FileStream input = File.Open(index.FilePath(relative), FileMode.Open, FileAccess.Read, FileShare.Read)) {
                                    if (input.Length > Paths.MaxFile) throw new IOException("文件超过 256 MB 上限");
                                    string hash = Crypto.Hash(input); input.Position = 0;
                                    if (!Crypto.Equal(hash, Wire.Text(request, "sha256"))) throw new IOException("电脑文件刚刚变化，请重新获取清单");
                                    Wire.Write(ssl, new { ok = true, size = input.Length, sha256 = hash }); input.CopyTo(ssl); ssl.Flush();
                                }
                            } else throw new IOException("不支持的操作");
                        } catch (Exception ex) { if (ex is IOException || ex is UnauthorizedAccessException || ex is InvalidOperationException) Wire.Write(ssl, new { ok = false, error = ex.Message }); else throw; }
                    }
                }
            } catch (Exception) { if (running) log("连接中断或握手失败；平板可重新连接"); }
            finally { lock (gate) activeClients.Remove(client); Interlocked.Decrement(ref clients); }
        }
        public void Dispose() { running = false; if (listener != null) listener.Stop(); lock (gate) foreach (TcpClient client in activeClients.ToArray()) client.Close(); if (thread != null) thread.Join(2000); }
        public static List<string> Addresses() {
            List<string> result = new List<string>();
            foreach (NetworkInterface adapter in NetworkInterface.GetAllNetworkInterfaces().OrderByDescending(item => item.NetworkInterfaceType == NetworkInterfaceType.Wireless80211).ThenByDescending(item => item.GetIPProperties().GatewayAddresses.Any(gateway => gateway.Address.AddressFamily == AddressFamily.InterNetwork && !gateway.Address.Equals(IPAddress.Any)))) {
                if (adapter.OperationalStatus != OperationalStatus.Up || adapter.NetworkInterfaceType == NetworkInterfaceType.Loopback) continue;
                foreach (UnicastIPAddressInformation address in adapter.GetIPProperties().UnicastAddresses) if (address.Address.AddressFamily == AddressFamily.InterNetwork && !address.Address.ToString().StartsWith("169.254.")) result.Add(address.Address.ToString());
            }
            return result.Distinct().ToList();
        }
    }
}
