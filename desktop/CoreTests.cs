using System;
using System.Collections.Generic;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Net;
using System.Net.Security;
using System.Net.Sockets;
using System.Security.Authentication;
using System.Text;
using QRCoder;

namespace VaultBridge {
    static class CoreTests {
        static int passed;
        static readonly List<string> reports = new List<string>();
        static void Check(bool result, string label) { if (!result) throw new Exception("FAIL: " + label); passed++; reports.Add("PASS: " + label); }
        static void Reject(Action action, string label) { bool rejected = false; try { action(); } catch (IOException) { rejected = true; } Check(rejected, label); }
        static Dictionary<string, object> Call(VaultServer server, object request, out byte[] file) {
            file = null;
            using (TcpClient client = new TcpClient()) {
                client.Connect(IPAddress.Loopback, server.Port);
                using (SslStream ssl = new SslStream(client.GetStream(), false, (sender, certificate, chain, errors) => Crypto.Equal(Crypto.Fingerprint(new System.Security.Cryptography.X509Certificates.X509Certificate2(certificate)), server.Fingerprint))) {
                    ssl.AuthenticateAsClient("VaultBridge", null, SslProtocols.Tls12, false); Wire.Write(ssl, request); Dictionary<string, object> reply = Wire.Read(ssl);
                    object size; if (reply.TryGetValue("size", out size)) file = Wire.ReadBytes(ssl, Convert.ToInt32(size)); return reply;
                }
            }
        }
        public static int Run(string workspace, string java, string classpath) {
            Directory.CreateDirectory(workspace); string sandbox = Path.Combine(workspace, "desktop-" + Guid.NewGuid().ToString("N")); Directory.CreateDirectory(sandbox);
            try {
                string vault = Path.Combine(sandbox, "vault"); Directory.CreateDirectory(Path.Combine(vault, "课程")); Directory.CreateDirectory(Path.Combine(vault, ".obsidian"));
                File.WriteAllText(Path.Combine(vault, "课程", "笔记.md"), "# 中文笔记\n![图片](image.png)\n", new UTF8Encoding(false)); File.WriteAllBytes(Path.Combine(vault, "课程", "image.png"), new byte[] { 1, 2, 3, 4 }); File.WriteAllText(Path.Combine(vault, "private.md"), "unshared"); File.WriteAllText(Path.Combine(vault, ".obsidian", "config.json"), "protected");
                Check(Paths.Normalize("课程\\笔记.md") == "课程/笔记.md", "cross-platform path normalization");
                foreach (string invalid in new[] { "../outside.md", "/absolute.md", "a/../../b", "a//b", "C:/escape.md", ".obsidian/config.json", "CON.md", "a/../b", "a/space ", "a/new\nline" }) Reject(() => Paths.Normalize(invalid), "reject unsafe path " + invalid.Replace("\n", "[newline]"));
                VaultIndex index = new VaultIndex(vault, "test-vault", new[] { "课程" }); VaultManifest initial = index.Scan(new[] { "" });
                Check(initial.files.Count == 2, "authorized scope excludes private notes and configuration"); Check(initial.files[0].sha256.Length == 64, "content hashes present");
                File.WriteAllText(Path.Combine(vault, "new-root-note.md"), "new root note"); Check(new VaultIndex(vault, "test-vault", new[] { "" }).Scan(new[] { "" }).files.Count == 4, "whole-vault scope includes newly added root notes");
                Reject(() => index.FilePath("private.md"), "download authorization checks scope"); Reject(() => index.FilePath("../private.md"), "download rejects traversal");
                string zip = Path.Combine(sandbox, "export.zip"); index.Export(zip, new[] { "" });
                using (ZipArchive archive = ZipFile.OpenRead(zip)) { Check(archive.Entries.Count == 3, "ZIP preserves notes, attachments and manifest"); Check(archive.GetEntry("课程/笔记.md") != null && archive.GetEntry("课程/image.png") != null, "ZIP retains relative attachment paths"); using (Stream input = archive.GetEntry("课程/笔记.md").Open()) Check(Crypto.Hash(input) == initial.files.First(f => f.path.EndsWith(".md")).sha256, "exported note matches manifest"); }
                Reject(() => index.Export(zip, new[] { "" }), "export never overwrites existing ZIP"); Reject(() => index.Export(Path.Combine(vault, "nested.zip"), new[] { "" }), "export cannot recursively include itself");
                Settings settings = new Settings { vault = vault, port = 0, vaultId = "test-vault", scopes = new List<string> { "课程" } }; byte[] payload;
                using (VaultServer server = new VaultServer(settings, () => {}, value => {})) {
                    server.Start(true);
                    string pairing = server.PairingText("127.0.0.1"); Dictionary<string, object> pairInfo = Json.Decode<Dictionary<string, object>>(Encoding.UTF8.GetString(Convert.FromBase64String(pairing.Substring("vaultbridge:".Length))));
                    Dictionary<string, object> bad = Call(server, new { action = "pair", code = "incorrect", device = "test" }, out payload); Check(!(bool)bad["ok"], "incorrect pairing code rejected");
                    Dictionary<string, object> paired = Call(server, new { action = "pair", code = (string)pairInfo["code"], device = "Test tablet" }, out payload); Check((bool)paired["ok"], "TLS pairing succeeds with pinned certificate"); string token = (string)paired["token"];
                    Check(!(bool)Call(server, new { action = "pair", code = (string)pairInfo["code"], device = "replay" }, out payload)["ok"], "pairing code is single use");
                    Check(!(bool)Call(server, new { action = "manifest", token = "forged", scopes = new[] { "" } }, out payload)["ok"], "unauthorized manifest rejected");
                    Check((bool)Call(server, new { action = "manifest", token = token, scopes = new[] { "" } }, out payload)["ok"], "paired manifest succeeds");
                    FileItem note = initial.files.First(f => f.path.EndsWith(".md"));
                    Check((bool)Call(server, new { action = "get", token = token, path = note.path, sha256 = note.sha256 }, out payload)["ok"], "authorized file download succeeds"); Check(Crypto.Hash(new MemoryStream(payload)) == note.sha256, "wire content matches SHA-256");
                    Check(!(bool)Call(server, new { action = "get", token = token, path = "private.md", sha256 = "x" }, out payload)["ok"], "wire enforces allowed scope");
                    Check(!(bool)Call(server, new { action = "get", token = token, path = "../outside.md", sha256 = "x" }, out payload)["ok"], "wire rejects path traversal");
                    File.AppendAllText(Path.Combine(vault, "课程", "笔记.md"), "changed"); Check(!(bool)Call(server, new { action = "get", token = token, path = note.path, sha256 = note.sha256 }, out payload)["ok"], "changed file requires fresh manifest");
                    settings.devices.Clear(); Check(!(bool)Call(server, new { action = "manifest", token = token }, out payload)["ok"], "revoked device rejected");
                    if (java != null && classpath != null) {
                        server.RenewPairing();
                        System.Diagnostics.ProcessStartInfo processInfo = new System.Diagnostics.ProcessStartInfo(java, "-cp \"" + classpath + "\" io.vaultbridge.app.LanInteropTest \"" + workspace + "\"") { UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true, RedirectStandardOutput = true, RedirectStandardError = true, StandardOutputEncoding = Encoding.UTF8, StandardErrorEncoding = Encoding.UTF8 };
                        using (System.Diagnostics.Process process = System.Diagnostics.Process.Start(processInfo)) {
                            process.StandardInput.WriteLine(server.PairingText("127.0.0.1")); process.StandardInput.Close();
                            string output = process.StandardOutput.ReadToEnd(), error = process.StandardError.ReadToEnd(); process.WaitForExit();
                            reports.Add(output.Trim()); Check(process.ExitCode == 0, "actual Android network client interoperates with Windows TLS server" + (process.ExitCode == 0 ? "" : ": " + error));
                        }
                    }
                }
                SettingsStore protectedStore = new SettingsStore(Path.Combine(sandbox, "protected-settings")); protectedStore.Save(settings); Check(protectedStore.Load().vaultId == "test-vault", "DPAPI settings round trip"); Check(!Encoding.UTF8.GetString(File.ReadAllBytes(Path.Combine(sandbox, "protected-settings", "settings.protected"))).Contains("test-vault"), "settings not stored as plaintext");
                using (QRCodeGenerator generator = new QRCodeGenerator()) using (QRCodeData data = generator.CreateQrCode("vaultbridge:TEST-FOR-QR-ROUNDTRIP", QRCodeGenerator.ECCLevel.M)) using (QRCode code = new QRCode(data)) using (System.Drawing.Bitmap bitmap = code.GetGraphic(8)) bitmap.Save(Path.Combine(workspace, "qrcode-test.png"));
                reports.Add("PASS: offline QR image generated for Android decoder interoperability"); passed++;
                passed+=TransferTests.Run(workspace,java,classpath);passed+=UpdateTests.Run(workspace);passed+=Desktop050Tests.Run(workspace);reports.Add("Desktop tests passed: " + passed); File.WriteAllLines(Path.Combine(workspace, "desktop-results.txt"), reports, new UTF8Encoding(false)); Console.WriteLine("Desktop tests passed: " + passed); return 0;
            } catch (Exception ex) { reports.Add("FAIL: " + ex); File.WriteAllLines(Path.Combine(workspace, "desktop-results.txt"), reports, new UTF8Encoding(false)); Console.Error.WriteLine(ex); return 1; }
        }
    }
}
