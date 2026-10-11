using System;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Reflection;
using System.Threading.Tasks;
using System.Windows.Forms;
using QRCoder;

namespace VaultBridge {
    static class Program {
        [STAThread] static int Main(string[] args) {
            AppDomain.CurrentDomain.AssemblyResolve += delegate(object sender, ResolveEventArgs request) {
                if (new AssemblyName(request.Name).Name != "QRCoder") return null;
                using (Stream input = Assembly.GetExecutingAssembly().GetManifestResourceStream("QRCoder.dll")) { byte[] data = new byte[input.Length]; input.Read(data, 0, data.Length); return Assembly.Load(data); }
            };
            if (args.Length > 0 && args[0] == "--self-test") return CoreTests.Run(args.Length > 1 ? args[1] : Path.Combine(Path.GetTempPath(), "VaultBridge-tests"), args.Length > 2 ? args[2] : null, args.Length > 3 ? args[3] : null);
            Application.EnableVisualStyles(); Application.SetCompatibleTextRenderingDefault(false);
            if (args.Length > 1 && args[0] == "--preview") { using (MainForm preview = new MainForm(true, args.Length>2?args[2]:"lake")) { preview.ShowInTaskbar = false; preview.StartPosition = FormStartPosition.Manual; preview.Location = new Point(-32000, -32000); preview.Opacity = 0; preview.Show(); Application.DoEvents(); using (Bitmap bitmap = new Bitmap(preview.Width, preview.Height)) { preview.DrawToBitmap(bitmap, new Rectangle(Point.Empty, bitmap.Size)); bitmap.Save(args[1]); } } return 0; }
            if(args.Length>1&&args[0]=="--ui-test"){Application.SetUnhandledExceptionMode(UnhandledExceptionMode.CatchException);return MainForm.CheckAppearance(args[1]);}
            Application.Run(new MainForm(false)); return 0;
        }
    }
    class ScopeChoice {
        public string Path;
        public string Label;
        public override string ToString() { return Label; }
    }
    partial class MainForm : Form {
        Color ink;
        Color accent;
        readonly TextBox vault = new TextBox();
        readonly CheckedListBox scopes = new ScrollScopeList();
        readonly CheckBox wholeVault = new CheckBox();
        readonly Button refreshScopes = new RoundedButton();
        readonly ComboBox addresses = new ThemedComboBox();
        readonly PictureBox qr = new PictureBox();
        readonly TextBox activity = new ScrollLog();
        readonly Label status = new Label();
        readonly Label pairingStatus = new Label();
        readonly Button start = new RoundedButton();
        readonly Button copy = new RoundedButton();
        readonly Button renew = new RoundedButton();
        readonly Button browse = new RoundedButton();
        readonly Button export = new RoundedButton();
        readonly NotifyIcon tray = new NotifyIcon();
        readonly Icon applicationIcon=AppIcon.Load(new Size(32,32));
        readonly Icon trayIcon=AppIcon.Load(SystemInformation.SmallIconSize);
        readonly System.Windows.Forms.Timer timer = new System.Windows.Forms.Timer();
        Settings state;
        SettingsStore store;
        VaultServer server;
        bool exiting;
        readonly bool previewMode;
        public MainForm(bool preview) : this(preview,null) { }
        public MainForm(bool preview,string initialStyle) {
            SuspendLayout();AutoScaleMode=AutoScaleMode.Dpi;previewMode=preview;Text="VaultBridge · 电脑主库";ClientSize=new Size(1120,850);MinimumSize=new Size(1000,780);StartPosition=FormStartPosition.CenterScreen;Font=new Font("Microsoft YaHei UI",10F);Icon=applicationIcon;
            if(preview)state=new Settings();else{store=new SettingsStore(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"VaultBridge"));try{state=store.Load();}catch(Exception){MessageBox.Show("无法读取已有设置。不会覆盖它，请检查 Windows 账户或设置备份。",Text,MessageBoxButtons.OK,MessageBoxIcon.Error);throw;}}
            if(preview&&initialStyle!=null)detectedAppearance=ObsidianAppearance.Parse(initialStyle=="dark"?"{\"theme\":\"obsidian\"}":"{\"theme\":\"moonstone\"}",false);
            BuildLayout();
            browse.Click+=SelectVault;refreshScopes.Click+=delegate{state.scopes=SelectedScopes().ToList();LoadScopes();};wholeVault.CheckedChanged+=delegate{scopes.Enabled=server==null&&!wholeVault.Checked;state.scopes=SelectedScopes().ToList();Save();};scopes.ItemCheck+=delegate{if(IsHandleCreated)BeginInvoke(new Action(delegate{if(!IsDisposed&&server==null){state.scopes=SelectedScopes().ToList();Save();}}));};addresses.SelectedIndexChanged+=delegate{UpdateQr();};
            copy.Click+=delegate{if(server!=null&&addresses.SelectedItem!=null){Clipboard.SetText(server.PairingText(addresses.SelectedItem.ToString()));Log("已复制配对文字，只提供给自己的平板。");}};
            renew.Click+=delegate{if(server!=null){server.RenewPairing();UpdateQr();}};start.Click+=ToggleServer;export.Click+=ExportZip;
            ContextMenuStrip menu=new ContextMenuStrip();menu.Items.Add("打开主窗口",null,delegate{Show();WindowState=FormWindowState.Normal;Activate();});menu.Items.Add("停止分享",null,delegate{StopServer();});menu.Items.Add("退出",null,delegate{exiting=true;Close();});tray.Text="VaultBridge · 未分享";tray.Icon=trayIcon;tray.ContextMenuStrip=menu;tray.Visible=!preview;tray.DoubleClick+=delegate{Show();WindowState=FormWindowState.Normal;Activate();};
            FormClosing+=delegate(object sender,FormClosingEventArgs args){if(!exiting&&!preview&&args.CloseReason==CloseReason.UserClosing){args.Cancel=true;Hide();}else{if(server!=null)server.Dispose();timer.Stop();tray.Dispose();}};
            timer.Interval=1000;timer.Tick+=delegate{if(++appearanceTicks%2==0)RefreshAppearance(false);if(server!=null)pairingStatus.Text=server.PairingExpires>DateTime.UtcNow?"二维码有效至 "+server.PairingExpires.ToLocalTime().ToString("HH:mm:ss"):"二维码已失效；已配对设备仍可同步，新增设备请刷新";};timer.Start();
            LoadScopes();RefreshState();if(!preview||detectedAppearance==null)RefreshAppearance(true);else ApplyAppearance();Log("选择电脑主库并开始分享，平板可通过局域网获取笔记。");
            if(preview){vault.Text="D:\\Obsidian\\学习笔记（示例）";scopes.Items.Add(new ScopeChoice{Path="课程笔记",Label="文件夹  课程笔记"},true);scopes.Items.Add(new ScopeChoice{Path="项目资料",Label="文件夹  项目资料"},true);scopes.Items.Add(new ScopeChoice{Path="附件",Label="文件夹  附件"},true);pairingStatus.Text="开始分享后显示一次性配对二维码";}
            // Set the design baseline after Font and all controls are created; otherwise
            // Font initialization can consume the DPI scaling pass on an empty form.
            AutoScaleDimensions=new SizeF(96F,96F);ResumeLayout(true);
        }
        protected override void Dispose(bool disposing) {
            if(disposing){timer.Stop();tray.Dispose();}
            base.Dispose(disposing);
            if(disposing){applicationIcon.Dispose();trayIcon.Dispose();timer.Dispose();}
        }
        void Save() { if (store != null) store.Save(state); }
        void Log(string message) { if (IsDisposed) return; if (InvokeRequired) { try { BeginInvoke(new Action<string>(Log), message); } catch (InvalidOperationException) { } return; } activity.AppendText(DateTime.Now.ToString("HH:mm:ss") + "  " + message + Environment.NewLine); }
        void SelectVault(object sender, EventArgs args) {
            using (FolderBrowserDialog dialog = new FolderBrowserDialog { Description = "选择电脑上的 Obsidian 库目录", SelectedPath = state.vault, ShowNewFolderButton = false }) {
                if (dialog.ShowDialog(this) != DialogResult.OK || dialog.SelectedPath == state.vault) return;
                try {
                    new VaultIndex(dialog.SelectedPath, "check", new[] { "" });
                    StopServer(); state.vault = dialog.SelectedPath; state.vaultId = Guid.NewGuid().ToString("N"); state.scopes = new System.Collections.Generic.List<string> { "" }; wholeVault.Checked = true; state.devices.Clear(); Save(); vault.Text = state.vault; LoadScopes(); RefreshAppearance(true); Log("已选择主库。切换主库后需要重新配对。");
                } catch (Exception ex) { MessageBox.Show(this, ex.Message, "无法选择目录"); }
            }
        }
        void LoadScopes() {
            scopes.Items.Clear(); if (!Directory.Exists(state.vault)) return;
            foreach (string entry in Directory.EnumerateFileSystemEntries(state.vault).OrderBy(x => x)) {
                string name = Path.GetFileName(entry); try { Paths.Normalize(name); if ((File.GetAttributes(entry) & FileAttributes.ReparsePoint) != 0) continue; } catch (IOException) { continue; }
                ScopeChoice item = new ScopeChoice { Path = name, Label = (Directory.Exists(entry) ? "文件夹  " : "文件     ") + name };
                scopes.Items.Add(item, state.scopes.Contains("") || state.scopes.Contains(name));
            }
        }
        string[] SelectedScopes() { return wholeVault.Checked ? new[] { "" } : scopes.CheckedItems.Cast<ScopeChoice>().Select(item => item.Path).ToArray(); }
        void ToggleServer(object sender, EventArgs args) {
            if (server != null) { StopServer(); return; }
            try {
                if (!Directory.Exists(state.vault)) throw new IOException("请先选择电脑笔记库");
                string[] selected = SelectedScopes(); if (selected.Length == 0) throw new IOException("请至少勾选一项内容");
                state.scopes = selected.ToList(); Save();
                server = new VaultServer(state, Save, Log); server.Start(false); UpdateQr(); Log("分享已启动，端口 " + server.Port + "。系统询问防火墙时，按实际网络选择允许。"); RefreshState();
            } catch (Exception ex) { if (server != null) { server.Dispose(); server = null; } MessageBox.Show(this, ex.Message, "无法启动分享"); RefreshState(); }
        }
        void StopServer() { if (server != null) { server.Dispose(); server = null; Log("分享已停止"); } if (qr.Image != null) { qr.Image.Dispose(); qr.Image = null; } if(palette!=null)qr.BackColor=palette.Surface; RefreshState(); }
        void RefreshState() { bool active = server != null; status.Text = active ? "● 正在分享" : "○ 分享未启动"; if(palette!=null)status.ForeColor=active?palette.AccentInk:palette.Muted; start.Text = active ? "停止分享" : "开始分享"; browse.Enabled = wholeVault.Enabled = refreshScopes.Enabled = !active; scopes.Enabled = !active && !wholeVault.Checked; copy.Enabled = renew.Enabled = active; addresses.Enabled = active; pairingStatus.Text = active ? "配对信息仅提供给自己的平板" : "启动分享后显示二维码"; tray.Text = active ? "VaultBridge · 正在分享" : "VaultBridge · 未分享"; }
        void UpdateQr() {
            if (server == null || addresses.SelectedItem == null) return;
            using (QRCodeGenerator generator = new QRCodeGenerator())
            using (QRCodeData data = generator.CreateQrCode(server.PairingText(addresses.SelectedItem.ToString()), QRCodeGenerator.ECCLevel.M))
            using (QRCode code = new QRCode(data)) { if (qr.Image != null) qr.Image.Dispose(); qr.Image = code.GetGraphic(6); qr.BackColor=Color.White; }
        }
        async void ExportZip(object sender, EventArgs args) {
            string[] selected = SelectedScopes(); if (!Directory.Exists(state.vault) || selected.Length == 0) { MessageBox.Show(this, "先选择库并勾选要导出的内容。"); return; }
            using (SaveFileDialog dialog = new SaveFileDialog { Filter = "ZIP 文件|*.zip", FileName = new DirectoryInfo(state.vault).Name + "-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + ".zip", OverwritePrompt = true }) {
                if (dialog.ShowDialog(this) != DialogResult.OK) return;
                if (File.Exists(dialog.FileName)) { MessageBox.Show(this, "为保留已有文件，请使用新的 ZIP 文件名。"); return; }
                export.Enabled = false; Log("正在校验并导出 ZIP…");
                try { string path = dialog.FileName; VaultIndex index = new VaultIndex(state.vault, state.vaultId, selected); await Task.Run(() => index.Export(path, selected)); Log("ZIP 导出完成：" + Path.GetFileName(path)); }
                catch (Exception ex) { Log("ZIP 导出失败，未完成的输出请勿导入：" + ex.Message); MessageBox.Show(this, ex.Message, "导出失败"); }
                finally { export.Enabled = true; }
            }
        }
        void ManageDevices(object sender, EventArgs args) {
            string names = String.Join(Environment.NewLine, state.devices.Select(d => d.name + " · " + d.pairedAt.Substring(0, 10)));
            if (state.devices.Count == 0) { MessageBox.Show(this, "尚未配对设备。"); return; }
            if (MessageBox.Show(this, "已配对设备：\n" + names + "\n\n撤销全部设备授权？平板需要重新扫码。", "管理设备", MessageBoxButtons.YesNo, MessageBoxIcon.Question) == DialogResult.Yes) { state.devices.Clear(); Save(); if (server != null) { server.RenewPairing(); UpdateQr(); } Log("已撤销设备授权；笔记未改动"); }
        }
    }
}
