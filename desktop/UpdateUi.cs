using System;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Threading;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace VaultBridge {
    partial class MainForm {
        readonly CancellationTokenSource updateLifetime = new CancellationTokenSource();
        bool updateStarted, updateBusy, updateStopped;
        Label updateMessage;
        Button updateBadge, updateCheck, updateDetails, updateDownload, updateSwitch;
        CheckBox updateAutomatic;
        UpdateInfo availableUpdate;
        UpdatePackage preparedUpdate;

        void BuildUpdateHeader(TableLayoutPanel brand) {
            FlowLayoutPanel row = new FlowLayoutPanel { AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink, WrapContents = true, Margin = Padding.Empty };
            Label subtitle = Caption("笔记与普通文件   /   局域网直传 · " + UpdateInfo.CurrentVersion, 9, false); subtitle.Tag = "muted"; subtitle.Margin = new Padding(0, 3, 12, 0); row.Controls.Add(subtitle);
            updateBadge = ActionButton("查看新版", ShowUpdateDetails, false); updateBadge.Visible = false; updateBadge.AutoSize = true; updateBadge.Font = new Font(Font.FontFamily, 9F); updateBadge.Margin = Padding.Empty; updateBadge.Padding = new Padding(8, 2, 8, 2); row.Controls.Add(updateBadge); Add(brand, row);
        }
        void BuildUpdateSection(TableLayoutPanel settings) {
            TableLayoutPanel section = Section(settings, "版本与更新");
            updateMessage = Caption(previewMode ? "当前版本 " + UpdateInfo.CurrentVersion + "；预览模式不联网。" : "当前版本 " + UpdateInfo.CurrentVersion + "。检查更新不会影响分享与笔记。", 10, false); Add(section, updateMessage);
            updateAutomatic = new CheckBox { Text = "启动时在后台检查更新", AutoSize = true, Checked = state.checkUpdatesOnStart, Margin = new Padding(0, 2, 0, 8) }; Add(section, updateAutomatic);
            updateAutomatic.CheckedChanged += delegate { state.checkUpdatesOnStart = updateAutomatic.Checked; try { Save(); } catch (Exception ex) { Log("更新偏好未保存：" + ex.Message); } };
            updateCheck = ActionButton("检查更新", delegate { CheckForUpdate(true); }, false);
            updateDetails = ActionButton("查看新版说明", ShowUpdateDetails, false);
            updateDownload = ActionButton("下载更新", DownloadUpdate, true);
            updateSwitch = ActionButton("切换到新版", SwitchUpdate, true);
            Buttons(section, updateCheck, updateDetails, updateDownload, updateSwitch);
            Help(section, "仅检查本项目公开 GitHub Release，包含预发布。不上传笔记、目录、配对材料或设备名；关闭此选项后仍可手动检查。");
            Notice(section, "下载后先校验 SHA-256。点击“切换到新版”会停止当前分享并打开新程序；旧 EXE、设置和更新文件均保留。网络失败时继续使用当前版本。");
            RefreshUpdateButtons();
        }
        void StartUpdateCheck() {
            if (previewMode || updateStarted || updateStopped) return;
            updateStarted = true; if (state.checkUpdatesOnStart) CheckForUpdate(false);
        }
        void StopUpdateWork() { if (updateStopped) return; updateStopped = true; updateLifetime.Cancel(); }
        void RefreshUpdateButtons() {
            if (updateCheck == null) return;
            updateCheck.Enabled = !updateBusy;
            updateDetails.Visible = availableUpdate != null;
            updateDownload.Visible = availableUpdate != null && availableUpdate.CanDownload && (preparedUpdate == null || preparedUpdate.info.version != availableUpdate.version);
            updateDownload.Enabled = !updateBusy;
            updateSwitch.Visible = preparedUpdate != null;
            updateSwitch.Enabled = !updateBusy;
            if (updateBadge != null) { updateBadge.Visible = availableUpdate != null; if (availableUpdate != null) updateBadge.Text = "发现 " + availableUpdate.version + " · 查看更新"; }
        }
        async void CheckForUpdate(bool manual) {
            if (previewMode) { updateMessage.Text = "预览模式不发起网络请求。"; return; }
            if (updateBusy || updateStopped) return;
            updateBusy = true; updateMessage.Text = "正在检查 GitHub 上的 Windows 新版…"; RefreshUpdateButtons();
            try {
                CancellationToken token = updateLifetime.Token;
                UpdateInfo found = await Task.Run(() => UpdateClient.Check(token));
                if (IsDisposed || updateStopped) return;
                availableUpdate = found;
                if (found == null) updateMessage.Text = "未发现高于 " + UpdateInfo.CurrentVersion + " 的可用 Windows 版本。";
                else {
                    if (preparedUpdate != null && preparedUpdate.info.version != found.version) preparedUpdate = null;
                    updateMessage.Text = "发现 " + found.version + (found.prerelease ? "（预发布）" : "") + (found.CanDownload ? "，可查看说明后下载。" : "；资产缺少可验证的 SHA-256，只能打开版本页。");
                }
            } catch (OperationCanceledException) { }
            catch (Exception ex) { if (!IsDisposed && !updateStopped) { updateMessage.Text = "检查更新失败；仍可继续使用当前版本。请稍后重试。"; if (manual) Log("检查更新失败：" + ex.Message); } }
            finally { updateBusy = false; if (!IsDisposed && !updateStopped) RefreshUpdateButtons(); }
        }
        void ShowUpdateDetails(object sender, EventArgs e) {
            UpdateInfo info = availableUpdate; if (info == null) return;
            using (Form dialog = new Form { Text = "VaultBridge " + info.version + (info.prerelease ? " · 预发布" : ""), StartPosition = FormStartPosition.CenterParent, Size = new Size(Px(650), Px(520)), MinimumSize = new Size(Px(480), Px(360)), Font = Font, BackColor = palette.Surface, ForeColor = palette.Ink, Padding = new Padding(Px(16)) }) {
                TextBox notes = new ScrollLog { Multiline = true, ReadOnly = true, BorderStyle = BorderStyle.None, ScrollBars = ScrollBars.Vertical, Text = info.notes, Font = Font, BackColor = palette.Surface, ForeColor = palette.Ink };
                FlowLayoutPanel controls = new FlowLayoutPanel { Dock = DockStyle.Bottom, AutoSize = true, AutoSizeMode = AutoSizeMode.GrowAndShrink, Padding = new Padding(0, Px(12), 0, 0) };
                Button page = ActionButton("打开 GitHub 版本页", delegate { OpenUpdatePage(info); }, false); page.AutoSize = true; controls.Controls.Add(page);
                if (info.CanDownload) { Button download = ActionButton("下载更新", delegate { dialog.Close(); DownloadUpdate(null, EventArgs.Empty); }, true); download.AutoSize = true; download.Enabled = !updateBusy; controls.Controls.Add(download); }
                Button close = ActionButton("关闭", delegate { dialog.Close(); }, false); close.AutoSize = true; controls.Controls.Add(close);
                ScrollFrame frame = new ScrollFrame(notes) { Dock = DockStyle.Fill }; dialog.Controls.Add(frame); dialog.Controls.Add(controls); Colorize(dialog); dialog.ShowDialog(this);
            }
            if (availableUpdate != null && !availableUpdate.CanDownload) { SelectPage(3); updateMessage.Text = "此资产没有可验证的 SHA-256，不能在应用内下载；可打开版本页手动查看。"; }
        }
        void OpenUpdatePage(UpdateInfo info) {
            try { if (!UpdateRules.Exact(info.page, UpdateRules.Repo + "/releases/tag/v" + info.version)) throw new IOException("版本页地址无效"); Process.Start(new ProcessStartInfo(info.page) { UseShellExecute = true }); }
            catch (Exception ex) { MessageBox.Show(this, ex.Message, "无法打开版本页"); }
        }
        async void DownloadUpdate(object sender, EventArgs e) {
            if (previewMode || updateBusy || updateStopped || availableUpdate == null || !availableUpdate.CanDownload) return;
            UpdateInfo info = availableUpdate; updateBusy = true; SelectPage(3); updateMessage.Text = "正在下载 " + info.version + "，随后校验并解包…"; RefreshUpdateButtons();
            try {
                string folder = Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData), "VaultBridge", "Updates");
                CancellationToken token = updateLifetime.Token; int last = -1;
                Action<long> progress = bytes => { int percent = (int)(bytes * 100 / info.size); if (percent == last) return; last = percent; if (IsDisposed || updateStopped || !IsHandleCreated) return; try { BeginInvoke(new Action(delegate { if (!IsDisposed && !updateStopped) updateMessage.Text = "正在下载 " + info.version + " · " + percent + "%"; })); } catch (InvalidOperationException) { } };
                preparedUpdate = await Task.Run(() => UpdateClient.Download(info, folder, token, progress));
                if (!IsDisposed && !updateStopped) updateMessage.Text = info.version + " 已完成 SHA-256 校验，可点击“切换到新版”。当前分享仍在原程序中继续。";
            } catch (OperationCanceledException) { }
            catch (Exception ex) { if (!IsDisposed && !updateStopped) { updateMessage.Text = "更新未准备完成，当前版本仍可使用。可重试；已下载内容保留。"; MessageBox.Show(this, ex.Message, "下载或校验未完成"); } }
            finally { updateBusy = false; if (!IsDisposed && !updateStopped) RefreshUpdateButtons(); }
        }
        async void SwitchUpdate(object sender, EventArgs e) {
            if (previewMode || updateBusy || updateStopped || preparedUpdate == null) return;
            if (publishing || !export.Enabled) { MessageBox.Show(this, "正在导出 ZIP 或建立发送清单，请完成后再切换版本。", "先完成当前任务"); return; }
            UpdatePackage package = preparedUpdate; updateBusy = true; RefreshUpdateButtons();
            try {
                await Task.Run(() => package.Validate()); if (IsDisposed || updateStopped) return;
                if (publishing || !export.Enabled) { MessageBox.Show(this, "当前导出或发送清单尚未完成，请完成后再切换。", "先完成当前任务"); return; }
                if (MessageBox.Show(this, "切换到 " + package.info.version + " 会停止当前局域网分享并关闭此窗口，然后启动新版。\n\n旧 EXE、笔记、配对设置和下载文件均保留。正在传输的设备可在新版重新开启分享后继续。\n\n现在切换？", "切换 VaultBridge", MessageBoxButtons.YesNo, MessageBoxIcon.Question, MessageBoxDefaultButton.Button2) != DialogResult.Yes) return;
                await Task.Run(() => package.Validate()); if (IsDisposed || updateStopped) return;
                if (publishing || !export.Enabled) { MessageBox.Show(this, "当前导出或发送清单尚未完成，请完成后再切换。", "先完成当前任务"); return; }
                StopServer(); Save();
                Process launched = Process.Start(new ProcessStartInfo(package.executable) { WorkingDirectory = package.directory, UseShellExecute = false });
                if (launched == null) throw new IOException("新版未能启动，请继续使用当前版本"); launched.Dispose(); exiting = true; Close();
            } catch (Exception ex) { if (!IsDisposed && !updateStopped) MessageBox.Show(this, "切换未完成，旧程序与文件保留。\n" + ex.Message, "未切换版本"); }
            finally { updateBusy = false; if (!IsDisposed && !updateStopped) RefreshUpdateButtons(); }
        }
    }
}
