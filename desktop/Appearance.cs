using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.IO;
using System.Linq;
using System.Windows.Forms;
using System.Runtime.InteropServices;

namespace VaultBridge {
    sealed class Palette {
        public Color Canvas, Surface, Nav, Ink, Muted, Accent, AccentInk, PrimaryInk, Wash, Line, Hover;
        public int Radius=10;
        static Color C(string hex){return ColorTranslator.FromHtml(hex);}
        static Color Mix(Color a,Color b,double weight){return Color.FromArgb((int)Math.Round(a.R*(1-weight)+b.R*weight),(int)Math.Round(a.G*(1-weight)+b.G*weight),(int)Math.Round(a.B*(1-weight)+b.B*weight));}
        static double Luminance(Color c){Func<byte,double> f=v=>{double x=v/255.0;return x<=0.04045?x/12.92:Math.Pow((x+0.055)/1.055,2.4);};return 0.2126*f(c.R)+0.7152*f(c.G)+0.0722*f(c.B);}
        static double Contrast(Color a,Color b){double x=Luminance(a),y=Luminance(b);return (Math.Max(x,y)+0.05)/(Math.Min(x,y)+0.05);}
        public static Palette For(ObsidianAppearance appearance){
            bool dark=appearance.Dark;Palette p=new Palette{Canvas=C(dark?"#1e1e1e":"#f6f6f6"),Surface=C(dark?"#252525":"#ffffff"),Ink=C(dark?"#e5e5e5":"#252525"),Muted=C(dark?"#a8a8a8":"#6b6b6b"),Accent=appearance.Accent};
            p.Wash=C(dark?"#2c2c2c":"#f1f1f1");p.Nav=C(dark?"#191919":"#ededed");p.Line=C(dark?"#383838":"#e5e5e5");p.Hover=C(dark?"#353535":"#e8e8e8");p.PrimaryInk=Contrast(p.Accent,Color.Black)>Contrast(p.Accent,Color.White)?Color.Black:Color.White;p.AccentInk=Contrast(p.Accent,p.Wash)>=4.5?p.Accent:p.Ink;return p;
        }
    }
    sealed class SurfacePanel : Panel {
        public Color Line;
        public int Radius;
        public SurfacePanel() { DoubleBuffered=true; }
        protected override void OnPaintBackground(PaintEventArgs e) {
            e.Graphics.Clear(Parent==null?SystemColors.Control:Parent.BackColor);e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;
            using(GraphicsPath shape=RoundShape.Path(new RectangleF(0.5F,0.5F,Math.Max(1,Width-1),Math.Max(1,Height-1)),Radius))using(Brush brush=new SolidBrush(BackColor))e.Graphics.FillPath(brush,shape);
        }
        protected override void OnPaint(PaintEventArgs e) {
            e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;
            using(GraphicsPath shape=RoundShape.Path(new RectangleF(0.5F,0.5F,Math.Max(1,Width-1),Math.Max(1,Height-1)),Radius))using(Pen pen=new Pen(Line))e.Graphics.DrawPath(pen,shape);
            base.OnPaint(e);
        }
    }
    partial class MainForm {
        Palette palette;
        Panel navigation, mainHost;
        TableLayoutPanel shell, pageHost;
        readonly List<Panel> pages=new List<Panel>();
        readonly List<Button> navButtons=new List<Button>();
        readonly List<Label> descriptions=new List<Label>();
        readonly List<SurfacePanel> cards=new List<SurfacePanel>();
        readonly List<SurfacePanel> inputFrames=new List<SurfacePanel>();
        Label appearanceInfo;
        ObsidianAppearance detectedAppearance;
        int appearanceTicks;
        CheckBox showHelp;
        int selectedPage;
        int layoutDpi=96;
        int Px(int value){return (int)Math.Round(value*layoutDpi/96.0);}
        protected override void OnShown(EventArgs e){base.OnShown(e);layoutDpi=DeviceDpi;ApplyAppearance();if(!previewMode){Rectangle area=Screen.FromHandle(Handle).WorkingArea;MinimumSize=new Size(Math.Min(Px(1000),area.Width-Px(24)),Math.Min(Px(780),area.Height-Px(24)));Size=new Size(Math.Min(Width,area.Width-Px(24)),Math.Min(Height,area.Height-Px(24)));if(!area.Contains(Bounds))Location=new Point(area.X+(area.Width-Width)/2,area.Y+(area.Height-Height)/2);}StartUpdateCheck();}
        protected override void OnDpiChanged(DpiChangedEventArgs e){base.OnDpiChanged(e);layoutDpi=e.DeviceDpiNew;if(IsHandleCreated)BeginInvoke(new Action(delegate{if(!IsDisposed)ApplyAppearance();}));}

        TableLayoutPanel Stack() { TableLayoutPanel p=new TableLayoutPanel { ColumnCount=1,AutoSize=true,AutoSizeMode=AutoSizeMode.GrowAndShrink,Dock=DockStyle.Top,Margin=Padding.Empty,Padding=Padding.Empty };p.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));return p; }
        void Add(TableLayoutPanel stack,Control view) { int row=stack.RowCount++;stack.RowStyles.Add(new RowStyle(SizeType.AutoSize));stack.Controls.Add(view,0,row);view.Dock=DockStyle.Top;Label label=view as Label;if(label!=null)stack.SizeChanged+=delegate{label.MaximumSize=new Size(Math.Max(80,stack.ClientSize.Width-stack.Padding.Horizontal),0);}; }
        Label Caption(string value,int size,bool bold) { return new Label { Text=value,AutoSize=true,MaximumSize=new Size(850,0),Font=new Font("Microsoft YaHei UI",size,bold?FontStyle.Bold:FontStyle.Regular),Margin=new Padding(0,0,0,10) }; }
        void Help(TableLayoutPanel parent,string value) { Label label=Caption(value,9,false);label.Tag="muted";descriptions.Add(label);Add(parent,label); }
        void Notice(TableLayoutPanel parent,string value) { Label label=Caption(value,9,false);label.Tag="notice";label.Padding=new Padding(12);label.Margin=new Padding(0,7,0,8);Add(parent,label); }
        TableLayoutPanel Section(TableLayoutPanel parent,string title) {
            SurfacePanel card=new SurfacePanel { AutoSize=true,AutoSizeMode=AutoSizeMode.GrowAndShrink,Padding=new Padding(18),Margin=new Padding(0,0,0,14),MinimumSize=new Size(0,20) };cards.Add(card);TableLayoutPanel inside=Stack();card.Controls.Add(inside);Add(inside,Caption(title,11,true));Add(parent,card);return inside;
        }
        Button ActionButton(string value,EventHandler handler,bool primary) { Button b=new RoundedButton();InitButton(b,value,primary);b.Click+=handler;return b; }
        void InitButton(Button b,string value,bool primary) { b.Text=value;b.Tag=primary?"primary":"button";b.FlatStyle=FlatStyle.Flat;b.Cursor=Cursors.Hand;b.Height=36;b.Padding=new Padding(12,7,12,7);b.Margin=new Padding(0,3,8,10);b.AutoSize=false; }
        FlowLayoutPanel Buttons(TableLayoutPanel parent,params Button[] values) {
            FlowLayoutPanel row=new FlowLayoutPanel{AutoSize=true,AutoSizeMode=AutoSizeMode.GrowAndShrink,WrapContents=true,Margin=new Padding(0,4,0,10)};
            foreach(Button b in values){b.Dock=DockStyle.None;b.AutoSize=true;b.AutoSizeMode=AutoSizeMode.GrowAndShrink;b.MinimumSize=new Size(100,36);b.Margin=new Padding(0,0,8,0);row.Controls.Add(b);}Add(parent,row);return row;
        }
        void Input(TableLayoutPanel parent,Control view,int height) {
            SurfacePanel frame=new SurfacePanel{Tag="input",Height=height,Padding=new Padding(10,7,10,7),Margin=new Padding(0,0,0,8),Radius=6};inputFrames.Add(frame);Control body=view is CheckedListBox||(view is TextBox&&((TextBox)view).Multiline)?new ScrollFrame(view):(Control)view;frame.Controls.Add(body);body.Dock=DockStyle.Fill;Add(parent,frame);
        }
        void BuildLayout() {
            shell=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=1,RowCount=3,Padding=new Padding(22,14,22,10)};shell.RowStyles.Add(new RowStyle(SizeType.Absolute,85));shell.RowStyles.Add(new RowStyle(SizeType.Percent,100));shell.RowStyles.Add(new RowStyle(SizeType.Absolute,27));Controls.Add(shell);
            TableLayoutPanel head=new TableLayoutPanel{ColumnCount=3,Dock=DockStyle.Fill,RowCount=1};head.RowStyles.Add(new RowStyle(SizeType.Percent,100));head.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,100));head.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,130));head.ColumnStyles.Add(new ColumnStyle(SizeType.Absolute,130));shell.Controls.Add(head,0,0);
            TableLayoutPanel brand=Stack();Add(brand,Caption("VaultBridge",20,true));BuildUpdateHeader(brand);head.Controls.Add(brand,0,0);
            status.Dock=DockStyle.Fill;status.TextAlign=ContentAlignment.MiddleCenter;head.Controls.Add(status,1,0);InitButton(start,"开始分享",true);start.Dock=DockStyle.Top;start.Margin=new Padding(0,14,12,0);head.Controls.Add(start,2,0);
            mainHost=new Panel{Dock=DockStyle.Fill};shell.Controls.Add(mainHost,0,1);
            navigation=new Panel();mainHost.Controls.Add(navigation);
            string[] names={"分享与配对","导出 ZIP","已配对设备","设置","普通文件"};for(int i=0;i<names.Length;i++){int index=i;Button b=ActionButton(names[i],delegate{SelectPage(index);},false);navButtons.Add(b);navigation.Controls.Add(b);}
            pageHost=new TableLayoutPanel{Dock=DockStyle.Fill,ColumnCount=1,RowCount=2,Padding=new Padding(16,0,0,0)};pageHost.RowStyles.Add(new RowStyle(SizeType.Percent,100));pageHost.RowStyles.Add(new RowStyle(SizeType.Absolute,105));mainHost.Controls.Add(pageHost);pageHost.BringToFront();
            Panel scrollHost=new Panel{Dock=DockStyle.Fill};pageHost.Controls.Add(scrollHost,0,0);
            List<TableLayoutPanel> contents=new List<TableLayoutPanel>();for(int i=0;i<5;i++){Panel page=new ScrollPage{Dock=DockStyle.Fill,AutoScroll=true,Padding=new Padding(0,0,8,0)};TableLayoutPanel content=Stack();page.Controls.Add(content);pages.Add(page);contents.Add(content);scrollHost.Controls.Add(new ScrollFrame(page){Dock=DockStyle.Fill});}
            TableLayoutPanel share=contents[0];Add(share,Caption("分享与配对",16,true));Help(share,"选择分享范围，开启后用平板扫码。电脑保持主库，平板按需获取副本。");
            TableLayoutPanel source=Section(share,"电脑主库");vault.ReadOnly=true;vault.BorderStyle=BorderStyle.None;vault.Text=state.vault;vault.Height=32;Input(source,vault,36);InitButton(browse,"选择库文件夹",false);Buttons(source,browse);Help(source,"选择已有库目录。换主库会停止分享并清除设备授权，平板需要重新配对。");
            TableLayoutPanel columns=new TableLayoutPanel{ColumnCount=2,AutoSize=true,AutoSizeMode=AutoSizeMode.GrowAndShrink,Dock=DockStyle.Top};columns.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,48));columns.ColumnStyles.Add(new ColumnStyle(SizeType.Percent,52));TableLayoutPanel left=Stack(),right=Stack();left.Padding=new Padding(0,0,12,0);columns.Controls.Add(left,0,0);columns.Controls.Add(right,1,0);Add(share,columns);
            TableLayoutPanel scope=Section(left,"分享范围");wholeVault.Text="分享整个库";wholeVault.Checked=state.scopes.Contains("");wholeVault.AutoSize=true;wholeVault.Margin=new Padding(0,0,0,8);Add(scope,wholeVault);Help(scope,"全库包含以后新增内容。取消后勾选文件夹或根目录文件；文件夹也包含以后新增笔记。");scopes.CheckOnClick=true;scopes.BorderStyle=BorderStyle.None;scopes.Height=118;Input(scope,scopes,132);InitButton(refreshScopes,"刷新目录列表",false);Buttons(scope,refreshScopes);Help(scope,"刷新只读取目录。分享期间范围锁定，要调整先停止分享。");
            TableLayoutPanel pair=Section(right,"平板配对");qr.SizeMode=PictureBoxSizeMode.Zoom;qr.Height=145;qr.BackColor=Color.White;qr.Tag="qr";qr.Paint+=delegate(object sender,PaintEventArgs e){if(qr.Image==null){Rectangle box=new Rectangle((qr.Width-Px(132))/2,Px(6),Px(132),Px(132));using(Pen pen=new Pen(palette==null?Color.LightGray:palette.Line)){pen.DashStyle=DashStyle.Dash;e.Graphics.DrawRectangle(pen,box);}using(Brush brush=new SolidBrush(palette==null?Color.Gray:palette.Muted))using(StringFormat format=new StringFormat{Alignment=StringAlignment.Center,LineAlignment=StringAlignment.Center})e.Graphics.DrawString("开始分享后\n显示配对二维码",Font,brush,box,format);}};Add(pair,qr);addresses.DropDownStyle=ComboBoxStyle.DropDownList;addresses.Height=32;foreach(string address in VaultServer.Addresses())addresses.Items.Add(address);if(addresses.Items.Count>0)addresses.SelectedIndex=0;Add(pair,addresses);Help(pair,"选择与平板同网可达的地址。二维码限时有效，已配对设备不受二维码过期影响。");InitButton(copy,"复制配对文字",false);InitButton(renew,"刷新二维码",false);Buttons(pair,copy,renew);pairingStatus.AutoSize=true;pairingStatus.MaximumSize=new Size(430,0);pairingStatus.Font=new Font(Font.FontFamily,9F);Add(pair,pairingStatus);Help(pair,"相机不方便时粘贴配对文字；刷新会替换当前二维码，只发给自己的设备。");
            Notice(share,"右上角“开始分享”开启局域网连接；分享中可随时停止。笔记获取仍以电脑主库为准；平板主动回传和普通文件接收需电脑逐次确认，不自动同步删除。");
            TableLayoutPanel zip=contents[1];Add(zip,Caption("导出 ZIP",16,true));Help(zip,"导出带版本清单的 ZIP，经微信或其他方式传给平板，再由 VaultBridge 保存进库。");TableLayoutPanel z=Section(zip,"导出当前选择的范围");Help(z,"沿用“分享与配对”中勾选的范围，保留文件夹、笔记和附件。隐藏配置、历史备份和链接目录不会导出。");InitButton(export,"选择位置并导出 ZIP",true);Buttons(z,export);Notice(z,"使用库外的新文件名保存，不覆盖已有 ZIP。与联网同步接续时，平板用相同库、合并模式、子目录留空。");Help(z,"导出不要求正在分享，适合离线传输。平板有本地修改时仍会保留冲突版本。");Button go=ActionButton("返回选择分享范围",delegate{SelectPage(0);},false);Buttons(z,go);Help(z,"到分享页面调整勾选；文件夹勾选会包含当前子目录中的文件。");
            TableLayoutPanel devices=contents[2];Add(devices,Caption("已配对设备",16,true));Help(devices,"配对允许读取已选内容；写入电脑仍需逐批确认。撤销后设备不能收发新内容，已有文件保留。");TableLayoutPanel deviceCard=Section(devices,"设备授权");Button manage=ActionButton("查看设备 / 撤销授权",ManageDevices,false);Buttons(deviceCard,manage);Help(deviceCard,"显示配对设备；确认后撤销全部设备授权，重新连接需要扫码。当前二维码也会刷新。");Notice(deviceCard,"换电脑主库后必须重新配对。配对文字包含临时授权，仅提供给自己的平板。");
            TableLayoutPanel settings=contents[3];Add(settings,Caption("设置",16,true));BuildAppearancePreferences(settings);TableLayoutPanel styles=Section(settings,"当前外观结果");appearanceInfo=Caption("等待检测",11,false);Add(styles,appearanceInfo);Buttons(styles,ActionButton("重新检测外观",delegate{RefreshAppearance(true);},false));Help(styles,"只读取当前库基础深浅色与强调色，不执行主题 CSS 或插件，也不修改或传输 Obsidian 配置。");
            TableLayoutPanel help=Section(settings,"操作说明");showHelp=new CheckBox{Text="显示每个选项的说明",Checked=state.showHelp,AutoSize=true};Add(help,showHelp);showHelp.CheckedChanged+=delegate{state.showHelp=showHelp.Checked;UpdateHelp();Save();};Help(help,"熟悉后可以收起常规说明；目录、授权、冲突等重要影响提示继续显示。");Notice(help,"关闭主窗口后程序保留在托盘，正在分享会继续。右键托盘可停止分享或完全退出；电脑关机后无法获取更新。");
            Help(help,"Android 回传选择页可设置已传标记或隐藏已核验相同项。Windows 使用系统文件选择器，不会仅凭历史记录隐藏来源或自动跳过新内容。");BuildUpdateSection(settings);BuildFeedbackSection(settings);BuildTransferPage(contents[4]);TableLayoutPanel logPanel=Stack();logPanel.Dock=DockStyle.Fill;logPanel.Padding=new Padding(0,8,8,0);pageHost.Controls.Add(logPanel,0,1);Add(logPanel,Caption("最近任务",10,true));activity.Multiline=true;activity.ReadOnly=true;activity.ScrollBars=ScrollBars.Vertical;activity.BorderStyle=BorderStyle.None;activity.Height=56;activity.Font=new Font(Font.FontFamily,9F);Input(logPanel,activity,68);
            Label footer=Caption("笔记与文件本地直传 · 备份先于更新 · 建议通过 GitHub Issue 公开提交",9,false);footer.Tag="muted";shell.Controls.Add(footer,0,2);SelectPage(0);
        }
        void RefreshAppearance(bool force) {
            bool systemDark=previewMode?false:ObsidianAppearance.SystemDark();ObsidianAppearance next=DesktopPreferences.Resolve(ObsidianAppearance.Read(state.vault,systemDark),state,systemDark);
            if(force||detectedAppearance==null||next.Key!=detectedAppearance.Key){detectedAppearance=next;ApplyAppearance();}
        }
        void SelectPage(int index) { selectedPage=index;for(int i=0;i<pages.Count;i++)pages[i].Parent.Visible=i==index;pages[index].Parent.BringToFront();if(palette!=null)StyleNavigation(); }
        void UpdateHelp() { foreach(Label label in descriptions)label.Visible=state.showHelp; }
        void ApplyAppearance() {
            if(detectedAppearance==null)detectedAppearance=ObsidianAppearance.Fallback(previewMode?false:ObsidianAppearance.SystemDark(),"尚未选择 Obsidian 库");
            SuspendLayout();palette=Palette.For(detectedAppearance);ink=palette.Ink;accent=palette.Accent;BackColor=palette.Canvas;ForeColor=ink;
            navigation.Dock=DockStyle.Left;navigation.Width=Px(174);pageHost.Padding=new Padding(Px(16),0,0,0);for(int i=0;i<navButtons.Count;i++)navButtons[i].SetBounds(Px(9),Px(12+i*56),navigation.Width-Px(18),Px(44));
            Colorize(this);navigation.BackColor=palette.Nav;foreach(SurfacePanel card in cards){card.Radius=Px(10);card.Line=palette.Line;card.Padding=new Padding(Px(16));card.Invalidate();}foreach(SurfacePanel frame in inputFrames){frame.Radius=Px(6);frame.Line=palette.Line;frame.Padding=new Padding(Px(10),Px(7),Px(10),Px(7));frame.Invalidate();}appearanceInfo.Text=detectedAppearance.Summary;StyleNavigation();UpdateHelp();ResumeLayout(true);
        }
        void Colorize(Control parent) {
            foreach(Control c in parent.Controls){c.ForeColor=palette.Ink;c.BackColor=parent.BackColor;
                if(c is SurfacePanel)c.BackColor=Equals(c.Tag,"input")?palette.Wash:palette.Surface;
                else if(c is RoundedButton){RoundedButton b=(RoundedButton)c;bool primary=Equals(b.Tag,"primary");b.Fill=primary?palette.Accent:palette.Wash;b.HoverFill=primary?palette.Accent:palette.Hover;b.PressedFill=primary?palette.Accent:palette.Line;b.Stroke=palette.Line;b.DrawBorder=!primary;b.DisabledInk=palette.Muted;b.Radius=Px(6);b.BackColor=b.Fill;b.ForeColor=primary?palette.PrimaryInk:palette.Ink;b.MinimumSize=new Size(b.AutoSize?Px(100):0,Px(ReferenceEquals(b,updateBadge)?24:36));b.Invalidate();}
                else if(c is TextBox || c is CheckedListBox || c is ComboBox){c.BackColor=palette.Wash;c.ForeColor=palette.Ink;ThemedComboBox combo=c as ThemedComboBox;if(combo!=null){combo.SelectionFill=palette.Hover;combo.Line=palette.Line;combo.Muted=palette.Muted;}}
                else if(Equals(c.Tag,"muted"))c.ForeColor=palette.Muted;
                else if(Equals(c.Tag,"notice")){c.BackColor=palette.Surface;c.ForeColor=palette.Muted;}
                else if(Equals(c.Tag,"qr"))c.BackColor=qr.Image==null?palette.Surface:Color.White;
                Colorize(c);
                NativeAppearance.Apply(c,detectedAppearance.Dark);
            }
            status.ForeColor=server==null?palette.Muted:palette.AccentInk;
        }
        void StyleNavigation() { for(int i=0;i<navButtons.Count;i++){RoundedButton b=(RoundedButton)navButtons[i];b.Fill=i==selectedPage?palette.Wash:palette.Nav;b.HoverFill=palette.Hover;b.PressedFill=palette.Wash;b.BackColor=b.Fill;b.ForeColor=i==selectedPage?palette.Ink:palette.Muted;b.DrawBorder=false;b.Invalidate();} }
    }
}
