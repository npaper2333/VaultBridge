using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Security.Authentication;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Web.Script.Serialization;

namespace VaultBridge {
    internal sealed class UpdateInfo {
        internal const string CurrentVersion = "0.4.2";
        public string version, page, download, sha256, notes;
        public long size;
        public bool prerelease;
        public bool CanDownload { get { return sha256 != null; } }
        public string ExeName { get { return "VaultBridge-Desktop-" + version + ".exe"; } }
        public string AssetName { get { return "VaultBridge-Desktop-" + version + "-portable.zip"; } }
    }
    internal sealed class UpdateRelease {
        public string tag_name, html_url, body;
        public bool draft, prerelease;
        public List<UpdateAsset> assets;
    }
    internal sealed class UpdateAsset {
        public string name, state, browser_download_url, digest;
        public long size;
    }
    internal static class UpdateRules {
        internal const string Api = "https://api.github.com/repos/npaper2333/VaultBridge/releases?per_page=20";
        internal const string Repo = "https://github.com/npaper2333/VaultBridge";
        internal const int MaxMetadata = 4 * 1024 * 1024;
        internal const long MaxDownload = 128L * 1024 * 1024;
        internal const long MaxExpanded = 256L * 1024 * 1024;
        internal const int MaxEntries = 128;
        internal static Version VersionOf(string tag) {
            if (tag == null || !Regex.IsMatch(tag, "\\Av(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\.(0|[1-9][0-9]{0,8})\\z")) return null;
            Version value;
            return Version.TryParse(tag.Substring(1), out value) ? value : null;
        }
        internal static bool Https(Uri uri) {
            return uri != null && uri.IsAbsoluteUri && uri.Scheme == Uri.UriSchemeHttps && uri.IsDefaultPort && uri.UserInfo.Length == 0 && uri.Fragment.Length == 0;
        }
        internal static bool Exact(string actual, string expected) {
            Uri uri;
            return Uri.TryCreate(actual, UriKind.Absolute, out uri) && Https(uri) && String.Equals(uri.AbsoluteUri, expected, StringComparison.Ordinal);
        }
        internal static void RequestUrl(Uri uri, UpdateInfo info) {
            if (!Https(uri)) throw new IOException("更新地址必须是官方 HTTPS 地址");
            if (info == null) {
                if (!Exact(uri.AbsoluteUri, Api)) throw new IOException("更新清单只能来自固定 GitHub 仓库");
                return;
            }
            string initial = Repo + "/releases/download/v" + info.version + "/" + info.AssetName;
            if (uri.Host == "github.com") {
                if (!Exact(uri.AbsoluteUri, initial)) throw new IOException("更新资产不能跳转到其他仓库或文件");
                return;
            }
            bool cdn = uri.Host == "release-assets.githubusercontent.com" || uri.Host == "objects.githubusercontent.com" || uri.Host == "github-releases.githubusercontent.com";
            if (!cdn || !(uri.AbsolutePath.StartsWith("/github-production-release-asset/", StringComparison.Ordinal) || uri.AbsolutePath.StartsWith("/github-production-release-asset-", StringComparison.Ordinal))) throw new IOException("更新跳转不属于允许的 GitHub 资产地址");
        }
        internal static UpdateInfo Select(string json, string current) {
            if (json == null || Encoding.UTF8.GetByteCount(json) > MaxMetadata) throw new IOException("更新清单超过大小限制");
            string content = json.Trim(); if (!content.StartsWith("[", StringComparison.Ordinal) || !content.EndsWith("]", StringComparison.Ordinal)) throw new IOException("GitHub 未返回有效的 Release 数组");
            Version installed = VersionOf("v" + current);
            if (installed == null) throw new IOException("当前版本无效");
            UpdateRelease[] releases;
            try { releases = new JavaScriptSerializer { MaxJsonLength = MaxMetadata, RecursionLimit = 32 }.Deserialize<UpdateRelease[]>(json); }
            catch (Exception ex) { if (!(ex is ArgumentException || ex is InvalidOperationException || ex is MissingMethodException || ex is FormatException || ex is OverflowException)) throw; throw new IOException("GitHub 更新清单格式无效", ex); }
            if (releases == null || releases.Length > 20) throw new IOException("GitHub 更新清单格式或条目数量无效");
            UpdateInfo chosen = null; Version highest = installed;
            foreach (UpdateRelease release in releases) {
                if (release == null || release.draft || release.assets == null) continue;
                Version version = VersionOf(release.tag_name);
                if (version == null || version <= highest) continue;
                string number = version.ToString();
                if (!Exact(release.html_url, Repo + "/releases/tag/v" + number)) continue;
                string name = "VaultBridge-Desktop-" + number + "-portable.zip";
                List<UpdateAsset> matches = release.assets.Where(a => a != null && a.name == name).ToList();
                if (matches.Count != 1) continue;
                UpdateAsset asset = matches[0];
                string download = Repo + "/releases/download/v" + number + "/" + name;
                if (asset.state != "uploaded" || asset.size <= 0 || asset.size > MaxDownload || !Exact(asset.browser_download_url, download)) continue;
                string digest = asset.digest != null && Regex.IsMatch(asset.digest, "\\Asha256:[0-9a-fA-F]{64}\\z") ? asset.digest.Substring(7).ToLowerInvariant() : null;
                string notes = release.body ?? "此版本尚未填写更新说明。";
                notes = new string(notes.Where(c => c == '\n' || c == '\r' || c == '\t' || !Char.IsControl(c)).Take(6000).ToArray());
                chosen = new UpdateInfo { version = number, page = release.html_url, download = download, size = asset.size, sha256 = digest, notes = notes, prerelease = release.prerelease };
                highest = version;
            }
            return chosen;
        }
        internal static void DirectorySafe(string path) {
            string current = Path.GetFullPath(path);
            while (!String.IsNullOrEmpty(current)) {
                if ((Directory.Exists(current) || File.Exists(current)) && (File.GetAttributes(current) & FileAttributes.ReparsePoint) != 0) throw new IOException("更新目录不能包含链接或重解析点");
                string parent = Path.GetDirectoryName(current); if (parent == current) break; current = parent;
            }
        }
        internal static byte[] ReadBounded(Stream input, int limit, CancellationToken token) {
            using (MemoryStream data = new MemoryStream()) { byte[] buffer = new byte[16384]; int n; while ((n = input.Read(buffer, 0, buffer.Length)) != 0) { token.ThrowIfCancellationRequested(); if (data.Length + n > limit) throw new IOException("更新响应超过大小限制"); data.Write(buffer, 0, n); } return data.ToArray(); }
        }
        internal static void WriteVerified(Stream input, string destination, long expectedSize, string expectedHash, CancellationToken token, Action<long> progress) {
            if (expectedSize <= 0 || expectedSize > MaxDownload || expectedHash == null || !Regex.IsMatch(expectedHash, "\\A[0-9a-f]{64}\\z")) throw new IOException("更新资产大小或 SHA-256 无效");
            DirectorySafe(Path.GetDirectoryName(destination));
            using (SHA256 sha = SHA256.Create()) using (FileStream output = new FileStream(destination, FileMode.CreateNew, FileAccess.Write)) {
                byte[] buffer = new byte[65536]; long total = 0; int n;
                while ((n = input.Read(buffer, 0, buffer.Length)) != 0) { token.ThrowIfCancellationRequested(); total += n; if (total > expectedSize) throw new IOException("更新文件超出 Release 声明的大小"); sha.TransformBlock(buffer, 0, n, null, 0); output.Write(buffer, 0, n); if (progress != null) progress(total); }
                sha.TransformFinalBlock(new byte[0], 0, 0); output.Flush(true);
                if (total != expectedSize || !Crypto.Equal(Crypto.Hex(sha.Hash), expectedHash)) throw new IOException("更新文件不完整或 SHA-256 不匹配，不能切换");
            }
        }
        internal static UpdatePackage Extract(string archivePath, string directory, UpdateInfo info, CancellationToken token) {
            DirectorySafe(directory);
            if (Directory.Exists(directory) || File.Exists(directory)) throw new IOException("更新必须解包到新的目录");
            List<KeyValuePair<ZipArchiveEntry, string>> entries = new List<KeyValuePair<ZipArchiveEntry, string>>();
            using (ZipArchive archive = ZipFile.OpenRead(archivePath)) {
                if (archive.Entries.Count == 0 || archive.Entries.Count > MaxEntries) throw new IOException("更新 ZIP 条目数量异常");
                HashSet<string> allowed = new HashSet<string>(StringComparer.Ordinal) { info.ExeName, info.ExeName + ".config", "VaultBridge-Desktop-" + info.version + "-guide.md", "SHA256SUMS-Desktop-" + info.version + ".txt", "LICENSE.txt", "README.md", "file-transfer.md", "acceptance.md", "updates.md", "licenses/QRCoder-MIT.txt", "licenses/ZXing-Apache-2.0.txt", "licenses/ZXing-NOTICE.txt", "licenses/JSON-java-Public-Domain.txt" };
                HashSet<string> names = new HashSet<string>(StringComparer.OrdinalIgnoreCase), files = new HashSet<string>(StringComparer.OrdinalIgnoreCase); long total = 0;
                foreach (ZipArchiveEntry entry in archive.Entries) {
                    token.ThrowIfCancellationRequested(); string raw = entry.FullName; bool folder = raw.EndsWith("/", StringComparison.Ordinal);
                    if (raw.IndexOf('\\') >= 0) throw new IOException("更新 ZIP 路径必须使用正斜线");
                    string relative = Paths.Normalize(folder ? raw.Substring(0, raw.Length - 1) : raw);
                    if ((folder && relative != "licenses") || (!folder && !allowed.Contains(relative))) throw new IOException("更新 ZIP 包含非预期条目，请到版本页查看完整包说明");
                    int unixType = (entry.ExternalAttributes >> 16) & 0xf000;
                    if ((entry.ExternalAttributes & (int)FileAttributes.ReparsePoint) != 0 || (unixType != 0 && unixType != (folder ? 0x4000 : 0x8000))) throw new IOException("更新 ZIP 不能包含链接或特殊文件");
                    if (!names.Add(relative)) throw new IOException("更新 ZIP 包含重复或大小写冲突条目");
                    if (entry.Length < 0 || entry.Length > MaxDownload || (folder && entry.Length != 0)) throw new IOException("更新 ZIP 单条目大小异常");
                    total += entry.Length; if (total > MaxExpanded) throw new IOException("更新 ZIP 解压总量超过限制");
                    if (!folder) { files.Add(relative); if (relative.EndsWith(".exe", StringComparison.OrdinalIgnoreCase) && relative != info.ExeName) throw new IOException("更新 ZIP 含有非预期程序"); }
                    entries.Add(new KeyValuePair<ZipArchiveEntry, string>(entry, relative));
                }
                foreach (string name in names) { string parent = name; while (parent.IndexOf('/') >= 0) { parent = parent.Substring(0, parent.LastIndexOf('/')); if (files.Contains(parent)) throw new IOException("更新 ZIP 文件与目录路径冲突"); } }
                if (!entries.Any(e => e.Key.FullName == info.ExeName && e.Key.Length > 0) || !entries.Any(e => e.Key.FullName == info.ExeName + ".config" && e.Key.Length > 0)) throw new IOException("更新 ZIP 缺少版本对应的 EXE 或同名配置");
                Directory.CreateDirectory(directory); DirectorySafe(directory);
                foreach (KeyValuePair<ZipArchiveEntry, string> item in entries) {
                    token.ThrowIfCancellationRequested(); string target = Paths.Resolve(directory, item.Value);
                    if (item.Key.FullName.EndsWith("/", StringComparison.Ordinal)) { Directory.CreateDirectory(target); continue; }
                    Directory.CreateDirectory(Path.GetDirectoryName(target)); DirectorySafe(Path.GetDirectoryName(target));
                    using (Stream input = item.Key.Open()) using (FileStream output = new FileStream(target, FileMode.CreateNew, FileAccess.Write)) {
                        long copied = 0; byte[] buffer = new byte[65536]; int n; while ((n = input.Read(buffer, 0, buffer.Length)) != 0) { token.ThrowIfCancellationRequested(); copied += n; if (copied > item.Key.Length || copied > MaxDownload) throw new IOException("更新 ZIP 实际解压大小异常"); output.Write(buffer, 0, n); } if (copied != item.Key.Length) throw new IOException("更新 ZIP 条目不完整"); output.Flush(true);
                    }
                }
            }
            string exe = Paths.Resolve(directory, info.ExeName), config = Paths.Resolve(directory, info.ExeName + ".config");
            UpdatePackage package = new UpdatePackage { info = info, directory = directory, executable = exe, configuration = config, executableHash = Crypto.HashFile(exe), configurationHash = Crypto.HashFile(config) }; package.Validate(); return package;
        }
    }
    internal sealed class UpdatePackage {
        internal UpdateInfo info;
        internal string directory, executable, configuration, executableHash, configurationHash;
        internal void Validate() {
            UpdateRules.DirectorySafe(executable); UpdateRules.DirectorySafe(configuration);
            if (!File.Exists(executable) || !File.Exists(configuration) || !Crypto.Equal(Crypto.HashFile(executable), executableHash) || !Crypto.Equal(Crypto.HashFile(configuration), configurationHash)) throw new IOException("下载后的程序或配置发生变化，请重新下载更新");
            Version expected = UpdateRules.VersionOf("v" + info.version), actual;
            if (expected == null || !Version.TryParse(FileVersionInfo.GetVersionInfo(executable).FileVersion, out actual) || actual != new Version(expected.Major, expected.Minor, expected.Build, 0)) throw new IOException("EXE 文件版本与 GitHub Release 不一致，不能切换");
        }
    }
    internal static class UpdateClient {
        // This only selects TLS for this process; Windows trust and system settings stay intact.
        static UpdateClient() { ServicePointManager.SecurityProtocol = SecurityProtocolType.Tls12; }
        static HttpWebResponse Open(Uri original, UpdateInfo info, CancellationToken token) {
            Uri current = original;
            for (int redirects = 0; ; redirects++) {
                token.ThrowIfCancellationRequested(); UpdateRules.RequestUrl(current, info);
                HttpWebRequest request = (HttpWebRequest)WebRequest.Create(current);
                request.Method = "GET"; request.AllowAutoRedirect = false; request.Timeout = 15000; request.ReadWriteTimeout = 30000; request.UserAgent = "VaultBridge/" + UpdateInfo.CurrentVersion; request.Accept = info == null ? "application/vnd.github+json" : "application/octet-stream"; request.UseDefaultCredentials = false; request.Credentials = null;
                if (info == null) request.Headers["X-GitHub-Api-Version"] = "2022-11-28";
                HttpWebResponse response;
                try { using (token.Register(request.Abort)) response = (HttpWebResponse)request.GetResponse(); }
                catch (WebException ex) { if (ex.Response != null) ex.Response.Dispose(); token.ThrowIfCancellationRequested(); throw new IOException("无法读取 GitHub 更新：请检查网络，稍后重试", ex); }
                int status = (int)response.StatusCode;
                if (status == 301 || status == 302 || status == 303 || status == 307 || status == 308) {
                    string location = response.Headers["Location"]; response.Dispose(); Uri next;
                    if (redirects >= 5 || String.IsNullOrEmpty(location) || !Uri.TryCreate(current, location, out next)) throw new IOException("GitHub 更新跳转次数过多或地址无效");
                    UpdateRules.RequestUrl(next, info); current = next; continue;
                }
                if (response.StatusCode != HttpStatusCode.OK) { response.Dispose(); throw new IOException("GitHub 未返回有效的更新响应"); }
                return response;
            }
        }
        internal static UpdateInfo Check(CancellationToken token) {
            using (CancellationTokenSource budget = CancellationTokenSource.CreateLinkedTokenSource(token)) {
                budget.CancelAfter(45000); CancellationToken active = budget.Token;
                try {
                    using (HttpWebResponse response = Open(new Uri(UpdateRules.Api), null, active)) using (active.Register(response.Close)) {
                        if (response.ContentLength > UpdateRules.MaxMetadata) throw new IOException("GitHub 更新清单超过大小限制");
                        using (Stream input = response.GetResponseStream()) { byte[] bytes = UpdateRules.ReadBounded(input, UpdateRules.MaxMetadata, active); string json; try { json = new UTF8Encoding(false, true).GetString(bytes); } catch (DecoderFallbackException ex) { throw new IOException("GitHub 更新清单不是有效的 UTF-8", ex); } active.ThrowIfCancellationRequested(); return UpdateRules.Select(json, UpdateInfo.CurrentVersion); }
                    }
                } catch (Exception) { token.ThrowIfCancellationRequested(); if (active.IsCancellationRequested) throw new IOException("检查 GitHub 更新超时，请稍后重试"); throw; }
            }
        }
        internal static UpdatePackage Download(UpdateInfo info, string root, CancellationToken token, Action<long> progress) {
            if (info == null || !info.CanDownload) throw new IOException("此资产没有可用的 SHA-256，请打开版本页手动获取");
            using (CancellationTokenSource budget = CancellationTokenSource.CreateLinkedTokenSource(token)) {
                budget.CancelAfter(15 * 60 * 1000); CancellationToken active = budget.Token;
                try {
                    UpdateRules.RequestUrl(new Uri(info.download), info); UpdateRules.DirectorySafe(root); Directory.CreateDirectory(root); UpdateRules.DirectorySafe(root);
                    string directory = Path.Combine(root, info.version + "-" + Guid.NewGuid().ToString("N")); if (Directory.Exists(directory)) throw new IOException("更新目录已存在，请重试"); Directory.CreateDirectory(directory);
                    string partial = Path.Combine(directory, "package.partial"), archive = Path.Combine(directory, "package.zip");
                    using (HttpWebResponse response = Open(new Uri(info.download), info, active)) using (active.Register(response.Close)) {
                        if (response.ContentLength >= 0 && response.ContentLength != info.size) throw new IOException("更新资产大小与 Release 不一致");
                        using (Stream input = response.GetResponseStream()) UpdateRules.WriteVerified(input, partial, info.size, info.sha256, active, progress);
                    }
                    active.ThrowIfCancellationRequested(); File.Move(partial, archive); UpdatePackage package = UpdateRules.Extract(archive, Path.Combine(directory, "app"), info, active); active.ThrowIfCancellationRequested();
                    byte[] record = Encoding.UTF8.GetBytes(Json.Encode(new { version = info.version, packageSha256 = info.sha256, executable = info.ExeName, executableSha256 = package.executableHash, configuration = info.ExeName + ".config", configurationSha256 = package.configurationHash, checkedAt = DateTime.UtcNow.ToString("o") }));
                    using (FileStream output = new FileStream(Path.Combine(directory, "verified.json"), FileMode.CreateNew, FileAccess.Write)) { output.Write(record, 0, record.Length); output.Flush(true); }
                    return package;
                } catch (Exception) { token.ThrowIfCancellationRequested(); if (active.IsCancellationRequested) throw new IOException("下载或准备更新超过 15 分钟，文件保留，可稍后重新下载"); throw; }
            }
        }
    }
}
