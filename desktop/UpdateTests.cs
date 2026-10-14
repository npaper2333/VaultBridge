using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Text;
using System.Threading;

namespace VaultBridge {
    internal static class UpdateTests {
        static int passed;
        static List<string> results;
        static void Check(bool value, string label) { if (!value) throw new Exception("Update: " + label); passed++; results.Add("PASS: " + label); }
        static void Reject(Action action, string label) { bool rejected = false; try { action(); } catch (IOException) { rejected = true; } catch (InvalidDataException) { rejected = true; } Check(rejected, label); }
        static UpdateInfo Info(string version) { return new UpdateInfo { version = version, page = UpdateRules.Repo + "/releases/tag/v" + version, download = UpdateRules.Repo + "/releases/download/v" + version + "/VaultBridge-Desktop-" + version + "-portable.zip", size = 100, sha256 = new string('a', 64), notes = "Synthetic update" }; }
        static UpdateRelease Release(string version) { UpdateInfo info = Info(version); return new UpdateRelease { tag_name = "v" + version, html_url = info.page, body = "Synthetic release text", prerelease = true, assets = new List<UpdateAsset> { new UpdateAsset { name = info.AssetName, state = "uploaded", browser_download_url = info.download, size = 100, digest = "sha256:" + new string('a', 64) } } }; }
        static UpdateInfo Select(params UpdateRelease[] releases) { return UpdateRules.Select(Json.Encode(releases), UpdateInfo.CurrentVersion); }
        static string Zip(string folder, UpdateInfo info, Action<ZipArchive> extra, bool config) {
            string file = Path.Combine(folder, Guid.NewGuid().ToString("N") + ".zip");
            using (ZipArchive zip = ZipFile.Open(file, ZipArchiveMode.Create)) {
                using (Stream output = zip.CreateEntry(info.ExeName).Open()) using (Stream input = File.OpenRead(Assembly.GetExecutingAssembly().Location)) input.CopyTo(output);
                if (config) using (Stream output = zip.CreateEntry(info.ExeName + ".config").Open()) { byte[] data = Encoding.UTF8.GetBytes("<configuration />"); output.Write(data, 0, data.Length); }
                if (extra != null) extra(zip);
            }
            return file;
        }
        static void Text(ZipArchive zip, string name, string text) { using (StreamWriter writer = new StreamWriter(zip.CreateEntry(name).Open(), new UTF8Encoding(false))) writer.Write(text); }
        static void DeclaredLength(string zip, string entry, uint length) {
            byte[] bytes = File.ReadAllBytes(zip);
            for (int i = 0; i <= bytes.Length - 46; i++) {
                if (bytes[i] != 0x50 || bytes[i + 1] != 0x4b || bytes[i + 2] != 0x01 || bytes[i + 3] != 0x02) continue;
                int count = BitConverter.ToUInt16(bytes, i + 28); if (i + 46 + count > bytes.Length) continue;
                if (Encoding.UTF8.GetString(bytes, i + 46, count) != entry) continue;
                Buffer.BlockCopy(BitConverter.GetBytes(length), 0, bytes, i + 24, 4); File.WriteAllBytes(zip, bytes); return;
            }
            throw new Exception("Synthetic ZIP entry not found");
        }
        internal static int Run(string workspace) {
            passed = 0; results = new List<string>(); string folder = Path.Combine(workspace, "updates-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(folder); CancellationToken token = CancellationToken.None;
            Check(UpdateRules.VersionOf("v0.10.0") > UpdateRules.VersionOf("v0.9.99"), "numeric release versions do not use lexical ordering");
            foreach (string tag in new[] { "0.4.3", "v0.4.3-beta", "v01.4.3", "v0.-4.3", "v999999999999.1.1", "v0.4.3.1", "v0.4.3\n" }) Check(UpdateRules.VersionOf(tag) == null, "reject noncanonical tag " + tag.Trim());
            Check(Select(Release("0.4.1"), Release("0.4.2")) == null, "old and current releases are not updates");
            UpdateRelease draft = Release("9.0.0"); draft.draft = true; Check(Select(draft, Release("0.4.3"), Release("0.10.0")).version == "0.10.0", "highest numeric uploaded prerelease selected and draft excluded");
            UpdateRelease android = Release("8.0.0"); android.assets[0].name = "VaultBridge-Android-8.0.0.apk"; Check(Select(android) == null, "other platform assets ignored");
            UpdateRelease duplicate = Release("0.4.3"); duplicate.assets.Add(duplicate.assets[0]); Check(Select(duplicate) == null, "ambiguous duplicated asset is not downloaded");
            foreach (string url in new[] { "http://github.com/npaper2333/VaultBridge/releases/download/v0.4.3/VaultBridge-Desktop-0.4.3-portable.zip", "https://example.com/update.zip", "https://github.com/other/VaultBridge/releases/download/v0.4.3/VaultBridge-Desktop-0.4.3-portable.zip", "https://github.com/npaper2333/VaultBridge/releases/download/v0.4.3/VaultBridge-Desktop-0.4.3-portable.zip?other=1" }) { UpdateRelease r = Release("0.4.3"); r.assets[0].browser_download_url = url; Check(Select(r) == null, "nonproject asset URL excluded"); }
            UpdateRelease badPage = Release("0.4.3"); badPage.html_url = "https://github.com/other/other/releases/tag/v0.4.3"; Check(Select(badPage) == null, "release page belongs to the fixed repository");
            UpdateRelease upload = Release("0.4.3"); upload.assets[0].state = "new"; Check(Select(upload) == null, "unfinished asset excluded");
            foreach (long size in new long[] { 0, -1, UpdateRules.MaxDownload + 1 }) { UpdateRelease r = Release("0.4.3"); r.assets[0].size = size; Check(Select(r) == null, "unreasonable asset size excluded"); }
            UpdateRelease missing = Release("0.4.3"); missing.assets[0].digest = null; Check(!Select(missing).CanDownload, "missing GitHub digest offers only release page"); missing.assets[0].digest = "sha256:invalid"; Check(!Select(missing).CanDownload, "malformed digest never enables download");
            Reject(() => UpdateRules.Select("{\"message\":\"rate limited\"}", UpdateInfo.CurrentVersion), "API error object does not become up-to-date result");
            Reject(() => UpdateRules.Select(new string(' ', UpdateRules.MaxMetadata + 1), UpdateInfo.CurrentVersion), "bounded metadata size");
            Reject(() => UpdateRules.Select(Json.Encode(Enumerable.Range(0, 21).Select(i => Release("0.4.3")).ToArray()), UpdateInfo.CurrentVersion), "bounded release count");
            UpdateInfo future = Info("0.4.3"); UpdateRules.RequestUrl(new Uri(future.download), future); UpdateRules.RequestUrl(new Uri("https://release-assets.githubusercontent.com/github-production-release-asset/123/asset?signature=example"), future); Check(true, "official origin and fixed asset CDN permitted");
            foreach (string url in new[] { "http://release-assets.githubusercontent.com/github-production-release-asset/123/asset", "https://release-assets.githubusercontent.com.evil.example/github-production-release-asset/123/asset", "https://user@release-assets.githubusercontent.com/github-production-release-asset/123/asset", "https://release-assets.githubusercontent.com:444/github-production-release-asset/123/asset", "https://github.com/other/repo/releases/download/v0.4.3/asset.zip", "https://raw.githubusercontent.com/npaper2333/VaultBridge/asset.zip" }) Reject(() => UpdateRules.RequestUrl(new Uri(url), future), "unsafe redirect rejected");
            Reject(() => UpdateRules.RequestUrl(new Uri(future.download), null), "metadata request cannot redirect to release download");
            byte[] bytes = Encoding.UTF8.GetBytes("Synthetic complete update"); string digest = Crypto.Hash(new MemoryStream(bytes)); string good = Path.Combine(folder, "verified.partial"); UpdateRules.WriteVerified(new MemoryStream(bytes), good, bytes.Length, digest, token, null); Check(File.ReadAllBytes(good).SequenceEqual(bytes), "download complete length and SHA verified");
            Reject(() => UpdateRules.WriteVerified(new MemoryStream(bytes), good, bytes.Length, digest, token, null), "existing partial file never overwritten");
            Reject(() => UpdateRules.WriteVerified(new MemoryStream(bytes), Path.Combine(folder, "bad-hash.partial"), bytes.Length, new string('0', 64), token, null), "wrong complete digest rejected");
            Reject(() => UpdateRules.WriteVerified(new MemoryStream(bytes), Path.Combine(folder, "truncated.partial"), bytes.Length + 1, digest, token, null), "truncated download rejected");
            Reject(() => UpdateRules.WriteVerified(new MemoryStream(bytes), Path.Combine(folder, "oversized.partial"), bytes.Length - 1, digest, token, null), "download beyond declared size rejected");
            Reject(() => UpdateRules.ReadBounded(new MemoryStream(bytes), bytes.Length - 1, token), "stream metadata bound applies without Content-Length");
            UpdateInfo current = Info(UpdateInfo.CurrentVersion);
            string archive = Zip(folder, current, z => { Text(z, "LICENSE.txt", "MIT Nitro synthetic"); Text(z, "licenses/QRCoder-MIT.txt", "Synthetic license"); }, true);
            UpdatePackage prepared = UpdateRules.Extract(archive, Path.Combine(folder, "valid"), current, token); prepared.Validate(); Check(File.Exists(prepared.configuration) && File.Exists(prepared.executable), "versioned EXE and matching configuration extracted and reverified");
            File.AppendAllText(prepared.configuration, "changed"); Reject(prepared.Validate, "changed extracted configuration prevents switching");
            Reject(() => UpdateRules.Extract(archive, Path.Combine(folder, "valid"), current, token), "existing extraction directory preserved");
            foreach (string path in new[] { "../escape.txt", "/absolute.txt", "C:/drive.txt", "licenses\\escape.txt", ".git/config", "install.ps1", "install.bat", "nested/" + current.ExeName }) { string malicious = Zip(folder, current, z => Text(z, path, "bad"), true); Reject(() => UpdateRules.Extract(malicious, Path.Combine(folder, Guid.NewGuid().ToString("N")), current, token), "unsafe or unapproved ZIP entry rejected: " + path); }
            string repeated = Zip(folder, current, z => Text(z, current.ExeName, "duplicate"), true); Reject(() => UpdateRules.Extract(repeated, Path.Combine(folder, "duplicate"), current, token), "duplicate ZIP entry rejected");
            string casing = Zip(folder, current, z => Text(z, "LICENSE.txt", "one"), true); using (ZipArchive zip = ZipFile.Open(casing, ZipArchiveMode.Update)) Text(zip, "license.txt", "other"); Reject(() => UpdateRules.Extract(casing, Path.Combine(folder, "case"), current, token), "case collision or noncanonical entry rejected");
            string prefix = Zip(folder, current, z => { Text(z, "licenses", "not a directory"); Text(z, "licenses/QRCoder-MIT.txt", "child"); }, true); Reject(() => UpdateRules.Extract(prefix, Path.Combine(folder, "prefix"), current, token), "file and directory prefix conflict cannot be extracted");
            string link = Zip(folder, current, z => { ZipArchiveEntry e = z.CreateEntry("README.md"); e.ExternalAttributes = unchecked((int)0xa1ff0000); }, true); Reject(() => UpdateRules.Extract(link, Path.Combine(folder, "symlink"), current, token), "ZIP symbolic link rejected");
            string reparse = Zip(folder, current, z => { ZipArchiveEntry e = z.CreateEntry("README.md"); e.ExternalAttributes = (int)FileAttributes.ReparsePoint; }, true); Reject(() => UpdateRules.Extract(reparse, Path.Combine(folder, "reparse"), current, token), "ZIP reparse attribute rejected");
            string missingConfig = Zip(folder, current, null, false); Reject(() => UpdateRules.Extract(missingConfig, Path.Combine(folder, "missing-config"), current, token), "missing matching EXE configuration prevents switch");
            string wrongVersion = Zip(folder, future, null, true); Reject(() => UpdateRules.Extract(wrongVersion, Path.Combine(folder, "wrong-version"), future, token), "binary file version must match selected Release");
            string many = Zip(folder, current, z => { for (int i = 0; i < UpdateRules.MaxEntries; i++) z.CreateEntry("entry" + i + ".txt"); }, true); Reject(() => UpdateRules.Extract(many, Path.Combine(folder, "many"), current, token), "ZIP entry count bound checked before writing");
            string expandedEntry = Zip(folder, current, z => Text(z, "README.md", "small synthetic body"), true); DeclaredLength(expandedEntry, "README.md", (uint)UpdateRules.MaxDownload + 1); Reject(() => UpdateRules.Extract(expandedEntry, Path.Combine(folder, "large-entry"), current, token), "oversized declared ZIP entry rejected without allocating its payload");
            string expandedTotal = Zip(folder, current, z => Text(z, "README.md", "synthetic"), true); DeclaredLength(expandedTotal, current.ExeName, (uint)UpdateRules.MaxDownload); DeclaredLength(expandedTotal, current.ExeName + ".config", (uint)UpdateRules.MaxDownload); Reject(() => UpdateRules.Extract(expandedTotal, Path.Combine(folder, "large-total"), current, token), "ZIP declared expansion total bounded before writing");
            Check(new Settings().checkUpdatesOnStart && Json.Decode<Settings>("{}").checkUpdatesOnStart, "new and legacy settings default to background update check");
            Settings saved = new Settings { checkUpdatesOnStart = false }; Check(!Json.Decode<Settings>(Json.Encode(saved)).checkUpdatesOnStart, "disabled startup update check persists independently");
            File.WriteAllLines(Path.Combine(workspace, "update-results.txt"), results.Concat(new[] { "Windows update checks passed: " + passed }), new UTF8Encoding(false)); return passed;
        }
    }
}
