using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Runtime.InteropServices;
using System.Reflection;
using System.Windows.Forms;

namespace VaultBridge {
    public class AppearanceCase { public string name,json,accent;public bool systemDark,dark; }
    partial class MainForm {
        [DllImport("user32.dll")] static extern IntPtr GetWindowDpiAwarenessContext(IntPtr hwnd);
        [DllImport("user32.dll")] static extern bool AreDpiAwarenessContextsEqual(IntPtr first,IntPtr second);
        [DllImport("user32.dll")] static extern uint GetDpiForWindow(IntPtr hwnd);
        [DllImport("user32.dll")] static extern IntPtr SendMessage(IntPtr hwnd,int message,IntPtr wParam,IntPtr lParam);
        static IEnumerable<Control> ControlsOf(Control parent){yield return parent;foreach(Control child in parent.Controls)foreach(Control c in ControlsOf(child))yield return c;}
        static void Shot(MainForm form,string path){Application.DoEvents();using(Bitmap image=new Bitmap(form.Width,form.Height)){form.DrawToBitmap(image,new Rectangle(Point.Empty,image.Size));image.Save(path);}}
        static bool RepaintCorners(RoundedButton button) {
            bool enabled=button.Enabled;bool valid=true;MethodInfo paint=typeof(RoundedButton).GetMethod("OnPaint",BindingFlags.Instance|BindingFlags.NonPublic);
            MethodInfo enter=typeof(RoundedButton).GetMethod("OnMouseEnter",BindingFlags.Instance|BindingFlags.NonPublic),leave=typeof(RoundedButton).GetMethod("OnMouseLeave",BindingFlags.Instance|BindingFlags.NonPublic);
            try{for(int state=0;state<3;state++){button.Enabled=state!=2;leave.Invoke(button,new object[]{EventArgs.Empty});if(state==1)enter.Invoke(button,new object[]{EventArgs.Empty});
                using(Bitmap buffer=new Bitmap(button.Width,button.Height))using(Graphics graphics=Graphics.FromImage(buffer))using(PaintEventArgs args=new PaintEventArgs(graphics,new Rectangle(Point.Empty,buffer.Size))){
                    graphics.Clear(Color.Black);paint.Invoke(button,new object[]{args});int expected=button.Parent.BackColor.ToArgb();foreach(Point point in new[]{new Point(0,0),new Point(buffer.Width-1,0),new Point(0,buffer.Height-1),new Point(buffer.Width-1,buffer.Height-1)})valid&=buffer.GetPixel(point.X,point.Y).ToArgb()==expected;
                }
            }}finally{button.Enabled=enabled;leave.Invoke(button,new object[]{EventArgs.Empty});}return valid;
        }
        static bool DarkRightEdge(Control view) {
            using(Bitmap buffer=new Bitmap(view.Width,view.Height)){view.DrawToBitmap(buffer,new Rectangle(Point.Empty,buffer.Size));
                for(int x=Math.Max(0,buffer.Width-7);x<buffer.Width-1;x++)for(int y=2;y<buffer.Height-2;y++){Color pixel=buffer.GetPixel(x,y);if(pixel.R>185&&pixel.G>185&&pixel.B>185)return false;}
            }return true;
        }
        internal static int CheckAppearance(string output) {
            Directory.CreateDirectory(output);List<string> results=new List<string>();int count=0;Exception inputFailure=null;
            System.Threading.ThreadExceptionEventHandler handler=delegate(object sender,System.Threading.ThreadExceptionEventArgs e){inputFailure=e.Exception;};Application.ThreadException+=handler;
            Action<bool,string> check=(ok,name)=>{if(!ok)throw new Exception(name);count++;results.Add("PASS: "+name);File.WriteAllLines(Path.Combine(output,"appearance-progress.txt"),results);};
            try {
                string vectors=Path.GetFullPath(Path.Combine(AppDomain.CurrentDomain.BaseDirectory,"..","tests","appearance-cases.json"));
                foreach(AppearanceCase c in Json.Decode<List<AppearanceCase>>(File.ReadAllText(vectors))){ObsidianAppearance v=ObsidianAppearance.Parse(c.json,c.systemDark);check(v.Dark==c.dark&&v.Hex.Equals(c.accent,StringComparison.OrdinalIgnoreCase),c.name);}
                check(ObsidianAppearance.Parse(new string('x',65537),false).Detail.Contains("64 KiB"),"oversized parsed configuration");
                string notes=Path.Combine(output,"appearance-vault"),other=Path.Combine(output,"appearance-other-vault");Directory.CreateDirectory(notes);Directory.CreateDirectory(other);string config=Path.Combine(notes,".obsidian","appearance.json");Directory.CreateDirectory(Path.GetDirectoryName(config));File.WriteAllText(Path.Combine(notes,"sample.md"),"# Sample\n");
                check(ObsidianAppearance.Read(other,true).Dark&&ObsidianAppearance.Read(other,true).Detail.Contains("未找到"),"missing config follows supplied system mode");
                File.WriteAllBytes(config,new byte[]{0xc3,0x28});check(ObsidianAppearance.Read(notes,false).Detail.Contains("格式无效"),"invalid UTF8 fallback");
                File.WriteAllBytes(config,new byte[65537]);check(ObsidianAppearance.Read(notes,false).Detail.Contains("64 KiB"),"bounded physical config read");
                File.WriteAllText(config,"{\"theme\":\"moonstone\",\"accentColor\":\"#c2410c\"}");
                using(MainForm form=new MainForm(true)) {
                    form.ShowInTaskbar=false;form.StartPosition=FormStartPosition.Manual;form.Location=new Point(-32000,-32000);form.Opacity=0;form.Show();Application.DoEvents();
                    check(AreDpiAwarenessContextsEqual(GetWindowDpiAwarenessContext(form.Handle),new IntPtr(-4))&&form.DeviceDpi==(int)GetDpiForWindow(form.Handle),"native PerMonitorV2 awareness");results.Add("Native DeviceDpi="+form.DeviceDpi+"; Win32Dpi="+GetDpiForWindow(form.Handle));
                    using(Stream stream=Assembly.GetExecutingAssembly().GetManifestResourceStream("VaultBridge.ico"))using(BinaryReader reader=new BinaryReader(stream)){check(reader.ReadUInt16()==0&&reader.ReadUInt16()==1&&reader.ReadUInt16()==8,"embedded icon has eight Windows sizes");}
                    check(ReferenceEquals(form.tray.Icon,form.trayIcon)&&ReferenceEquals(form.Icon,form.applicationIcon),"tray and window use dedicated app artwork");
                    using(Icon extracted=Icon.ExtractAssociatedIcon(Assembly.GetExecutingAssembly().Location))using(Bitmap art=extracted.ToBitmap()){Color pixel=art.GetPixel(art.Width/2,Math.Max(1,art.Height/6));check(pixel.G>pixel.R+20&&pixel.G>pixel.B,"EXE includes the dedicated native icon");}
                    results.Add("Share status bounds="+form.status.Bounds+"; visible="+form.status.Visible+"; text="+form.status.Text);
                    check(form.status.Visible&&form.status.Height>=form.status.Font.Height&&form.status.Bottom<=form.status.Parent.ClientSize.Height,"share status label remains visible within header");
                    form.state.vault=notes;form.wholeVault.Checked=false;form.SelectPage(1);form.activity.AppendText("state sentinel");string[] scopes=form.SelectedScopes();form.RefreshAppearance(true);
                    check(!form.detectedAppearance.Dark&&form.palette.Accent.ToArgb()==ColorTranslator.FromHtml("#c2410c").ToArgb(),"selected vault colors detected");
                    check(!ControlsOf(form).OfType<Button>().Any(b=>b.Text.Contains("外观风格")||b.Text.Contains("雾白")||b.Text.Contains("午夜")),"manual style controls removed");
                    form.state.style="night";form.RefreshAppearance(true);check(form.navigation.Dock==DockStyle.Left&&form.navigation.Width==form.Px(174),"old style preference ignored; lake sidebar fixed");
                    File.WriteAllText(config,"{\"theme\":\"obsidian\",\"accentColor\":\"#a78bfa\"}");Stopwatch wait=Stopwatch.StartNew();while(!form.detectedAppearance.Dark&&wait.ElapsedMilliseconds<4000){Application.DoEvents();System.Threading.Thread.Sleep(20);}
                    check(form.detectedAppearance.Dark&&form.palette.Accent.ToArgb()==ColorTranslator.FromHtml("#a78bfa").ToArgb(),"timer detects edited config without a click");
                    check(form.SelectedScopes().SequenceEqual(scopes)&&form.selectedPage==1&&form.activity.Text.Contains("state sentinel"),"automatic appearance preserves operational state");form.timer.Stop();
                    form.state.vault=other;form.RefreshAppearance(true);check(!form.detectedAppearance.Dark&&form.palette.Accent.ToArgb()==ColorTranslator.FromHtml("#7f6df2").ToArgb(),"switch to unconfigured vault discards previous colors");
                    form.state.vault=notes;form.RefreshAppearance(true);check(form.detectedAppearance.Dark,"switch back rereads that vault");
                    string[] themes={"{\"theme\":\"moonstone\",\"accentColor\":\"#7f6df2\"}","{\"theme\":\"obsidian\",\"accentColor\":\"#a78bfa\"}","{\"theme\":\"moonstone\",\"accentColor\":\"#c2410c\"}","{\"theme\":\"obsidian\",\"accentColor\":\"#0f766e\"}","{\"theme\":\"obsidian\",\"accentColor\":\"#ffff00\"}"};string[] names={"light-purple","dark-purple","light-orange","dark-teal","dark-yellow"};
                    for(int i=0;i<themes.Length;i++){File.WriteAllText(config,themes[i]);form.RefreshAppearance(true);check(form.navigation.Dock==DockStyle.Left&&form.navigation.Width==form.Px(174),"fixed layout "+names[i]);check(RepaintCorners((RoundedButton)form.browse)&&RepaintCorners((RoundedButton)form.navButtons[3]),"fresh-buffer normal/hover/disabled corners "+names[i]);for(int page=0;page<4;page++){form.SelectPage(page);Shot(form,Path.Combine(output,"desktop-"+names[i]+"-page"+page+".png"));}}
                    form.SelectPage(0);RoundedButton main=(RoundedButton)form.start;
                    check(DarkRightEdge(form.pages[0])&&DarkRightEdge(form.activity),"dark page and log scrollbars have no bright strip");
                    check(DarkRightEdge(form.addresses),"dark address selector and arrow have no white background");
                    ScrollFrame pageFrame=(ScrollFrame)form.pages[0].Parent,logFrame=(ScrollFrame)form.activity.Parent,scopeFrame=(ScrollFrame)form.scopes.Parent;
                    check(pageFrame.NativeVerticalStyle==0&&logFrame.NativeVerticalStyle==0&&scopeFrame.NativeVerticalStyle==0,"visible native scrollbar styles are removed at creation");
                    check(pageFrame.Rail.Visible&&form.pages[0].Right<=pageFrame.Rail.Left,"page content and buffered rail do not overlap");
                    Panel scrollPage=form.pages[0];SendMessage(scrollPage.Handle,0x115,new IntPtr(7),IntPtr.Zero);Application.DoEvents();check(scrollPage.AutoScrollPosition.Y<0,"native page scroll to bottom works");SendMessage(scrollPage.Handle,0x115,new IntPtr(6),IntPtr.Zero);Application.DoEvents();check(scrollPage.AutoScrollPosition.Y==0,"native page scroll to top works");
                    SendMessage(scrollPage.Handle,0x20A,new IntPtr(-120<<16),IntPtr.Zero);Application.DoEvents();check(scrollPage.AutoScrollPosition.Y<0,"native mouse wheel still scrolls page");scrollPage.AutoScrollPosition=Point.Empty;
                    string logBefore=form.activity.Text;form.activity.Text=String.Join(Environment.NewLine,Enumerable.Range(0,100).Select(n=>"Temporary log line "+n));SendMessage(form.activity.Handle,0x115,new IntPtr(7),IntPtr.Zero);Application.DoEvents();check(SendMessage(form.activity.Handle,0xCE,IntPtr.Zero,IntPtr.Zero).ToInt32()>0,"native log scroll to bottom works");check(DarkRightEdge(logFrame.Rail),"long log scrollbar remains dark");
                    pageFrame.NativePaintWithScrollbar=logFrame.NativePaintWithScrollbar=0;bool burst=true;for(int i=0;i<400;i++){int delta=i%20<10?-120:120;SendMessage(scrollPage.Handle,0x20A,new IntPtr(delta<<16),IntPtr.Zero);SendMessage(form.activity.Handle,0x20A,new IntPtr(delta<<16),IntPtr.Zero);burst&=pageFrame.NativeVerticalStyle==0&&logFrame.NativeVerticalStyle==0;if(i%20==0){Application.DoEvents();burst&=DarkRightEdge(pageFrame.Rail)&&DarkRightEdge(logFrame.Rail);}}
                    check(burst&&pageFrame.NativePaintWithScrollbar==0&&logFrame.NativePaintWithScrollbar==0,"800 rapid wheel messages never enable native white scrollbar painting");
                    MethodInfo railDown=typeof(ScrollRail).GetMethod("OnMouseDown",BindingFlags.Instance|BindingFlags.NonPublic),railMove=typeof(ScrollRail).GetMethod("OnMouseMove",BindingFlags.Instance|BindingFlags.NonPublic),railUp=typeof(ScrollRail).GetMethod("OnMouseUp",BindingFlags.Instance|BindingFlags.NonPublic);
                    pageFrame.ScrollTo(0);Rectangle thumb=pageFrame.Rail.ThumbBounds;int railX=pageFrame.Rail.Width/2,grabY=thumb.Top+thumb.Height/2;railDown.Invoke(pageFrame.Rail,new object[]{new MouseEventArgs(MouseButtons.Left,1,railX,grabY,0)});railMove.Invoke(pageFrame.Rail,new object[]{new MouseEventArgs(MouseButtons.Left,0,railX,pageFrame.Rail.Height-pageFrame.Rail.Width,0)});railUp.Invoke(pageFrame.Rail,new object[]{new MouseEventArgs(MouseButtons.Left,1,railX,pageFrame.Rail.Height-pageFrame.Rail.Width,0)});
                    check(scrollPage.AutoScrollPosition.Y<0&&pageFrame.Rail.Position>0&&!pageFrame.Rail.Capture,"buffered page thumb drag scrolls and releases capture");
                    pageFrame.Rail.Focus();SendMessage(pageFrame.Rail.Handle,0x100,new IntPtr((int)Keys.Home),IntPtr.Zero);SendMessage(pageFrame.Rail.Handle,0x101,new IntPtr((int)Keys.Home),IntPtr.Zero);check(scrollPage.AutoScrollPosition.Y==0,"buffered rail Home key returns to top");SendMessage(pageFrame.Rail.Handle,0x100,new IntPtr((int)Keys.End),IntPtr.Zero);SendMessage(pageFrame.Rail.Handle,0x101,new IntPtr((int)Keys.End),IntPtr.Zero);check(pageFrame.Rail.Position==pageFrame.Rail.Maximum,"buffered rail End key reaches bottom");scrollPage.AutoScrollPosition=Point.Empty;
                    logFrame.ScrollTo(0);logFrame.ScrollTo(logFrame.Rail.Maximum);check(SendMessage(form.activity.Handle,0xCE,IntPtr.Zero,IntPtr.Zero).ToInt32()>0,"buffered log rail controls native text scrolling");form.activity.Select(0,8);check(form.activity.SelectedText=="Temporar","native log selection remains intact");form.activity.Text=logBefore;
                    object[] scopeItems=form.scopes.Items.Cast<object>().ToArray();bool[] scopeChecks=Enumerable.Range(0,form.scopes.Items.Count).Select(i=>form.scopes.GetItemChecked(i)).ToArray();for(int i=0;i<80;i++)form.scopes.Items.Add(new ScopeChoice{Path="temporary-"+i,Label="Temporary scope "+i});Application.DoEvents();scopeFrame.SyncRange();SendMessage(form.scopes.Handle,0x20A,new IntPtr(-480<<16),IntPtr.Zero);Application.DoEvents();
                    results.Add("Scope wheel: enabled="+form.scopes.Enabled+", top="+form.scopes.TopIndex+", maximum="+scopeFrame.Rail.Maximum+", visible="+scopeFrame.Rail.Visible+", nativeStyle="+scopeFrame.NativeVerticalStyle);check(scopeFrame.Rail.Visible&&form.scopes.TopIndex>0&&scopeFrame.NativeVerticalStyle==0&&DarkRightEdge(scopeFrame.Rail),"long scope list scrolls without enabling a native white rail");scopeFrame.ScrollTo(scopeFrame.Rail.Maximum);check(form.scopes.TopIndex>0&&scopeFrame.Rail.Position==scopeFrame.Rail.Maximum,"buffered scope rail reaches last items");form.scopes.Items.Clear();for(int i=0;i<scopeItems.Length;i++)form.scopes.Items.Add(scopeItems[i],scopeChecks[i]);scopeFrame.SyncRange();
                    int addressBefore=form.addresses.SelectedIndex;form.addresses.Items.Add("192.0.2.1");form.addresses.Enabled=true;form.addresses.SelectedIndex=0;form.addresses.Focus();SendMessage(form.addresses.Handle,0x100,new IntPtr((int)Keys.Down),new IntPtr(1));SendMessage(form.addresses.Handle,0x101,new IntPtr((int)Keys.Down),new IntPtr(1));Application.DoEvents();check(form.addresses.SelectedIndex==1,"native keyboard address selection works");form.addresses.Items.RemoveAt(form.addresses.Items.Count-1);form.addresses.SelectedIndex=addressBefore;form.addresses.Enabled=false;
                    typeof(Control).GetMethod("RecreateHandle",BindingFlags.Instance|BindingFlags.NonPublic).Invoke(form.activity,null);typeof(Control).GetMethod("RecreateHandle",BindingFlags.Instance|BindingFlags.NonPublic).Invoke(form.addresses,null);Application.DoEvents();check(DarkRightEdge(form.activity)&&DarkRightEdge(form.addresses)&&logFrame.NativeVerticalStyle==0,"theme survives log and address handle recreation");
                    Color primaryFill=main.Fill;using(Bitmap button=new Bitmap(main.Width,main.Height)){main.DrawToBitmap(button,new Rectangle(Point.Empty,button.Size));check(button.GetPixel(0,0).ToArgb()!=primaryFill.ToArgb()&&button.GetPixel(main.Width/2,2).ToArgb()==primaryFill.ToArgb(),"primary button has actual rounded fill");}
                    using(Bitmap card=new Bitmap(form.cards[0].Width,form.cards[0].Height)){form.cards[0].DrawToBitmap(card,new Rectangle(Point.Empty,card.Size));check(card.GetPixel(0,0).ToArgb()!=form.cards[0].BackColor.ToArgb(),"card corners reveal the surrounding canvas");}
                    using(Bitmap screen=new Bitmap(Path.Combine(output,"desktop-dark-yellow-page0.png"))){int colorful=0,total=0;for(int y=0;y<screen.Height;y+=2)for(int x=0;x<screen.Width;x+=2){Color pixel=screen.GetPixel(x,y);if(Math.Max(pixel.R,Math.Max(pixel.G,pixel.B))-Math.Min(pixel.R,Math.Min(pixel.G,pixel.B))>50)colorful++;total++;}double ratio=colorful/(double)total;results.Add("Colored pixel ratio, dark yellow page: "+ratio.ToString("P2"));check(ratio<0.03,"accent color covers less than three percent of the screen");}
                    check(form.browse.Width<form.browse.Parent.ClientSize.Width*0.8&&form.refreshScopes.Width<form.refreshScopes.Parent.ClientSize.Width*0.8,"secondary actions use compact widths");
                    using(Button baseline=new Button{Bounds=new Rectangle(4,48,140,40)})using(RoundedButton input=new RoundedButton{Text="Input probe",Bounds=new Rectangle(4,4,140,40),Fill=Color.Gray,HoverFill=Color.DarkGray,PressedFill=Color.Gray,Stroke=Color.Gray,ForeColor=Color.White,DisabledInk=Color.Silver}){
                        int clicked=0,baselineClicked=0,down=0,up=0;input.Click+=delegate{clicked++;};baseline.Click+=delegate{baselineClicked++;};input.MouseDown+=delegate{down++;};input.MouseUp+=delegate{up++;};form.Controls.Add(baseline);form.Controls.Add(input);input.BringToFront();baseline.BringToFront();Application.DoEvents();IntPtr point=new IntPtr((input.Height/2<<16)|(input.Width/2));
                        // Native mouse activation also checks the cursor's on-screen window. The test form is deliberately offscreen.
                        SendMessage(baseline.Handle,0x201,new IntPtr(1),point);SendMessage(baseline.Handle,0x202,IntPtr.Zero,point);SendMessage(input.Handle,0x201,new IntPtr(1),point);SendMessage(input.Handle,0x202,IntPtr.Zero,point);Application.DoEvents();results.Add("Offscreen mouse clicks, standard="+baselineClicked+", rounded="+clicked);check(down==1&&up==1&&clicked==baselineClicked,"rounded button preserves mouse messages and standard offscreen behavior");
                        int before=clicked;SendMessage(form.Handle,0x111,IntPtr.Zero,input.Handle);Application.DoEvents();check(clicked==before+1,"rounded button native click command");
                        input.Focus();before=clicked;SendMessage(input.Handle,0x100,new IntPtr((int)Keys.Space),new IntPtr(1));SendMessage(input.Handle,0x101,new IntPtr((int)Keys.Space),new IntPtr(1));Application.DoEvents();check(clicked==before+1,"rounded button keyboard Space click");
                        before=clicked;input.Enabled=false;input.PerformClick();check(clicked==before,"disabled rounded button cannot activate");form.Controls.Remove(input);form.Controls.Remove(baseline);
                    }
                    form.state.showHelp=false;form.UpdateHelp();check(!form.descriptions.Any(x=>x.Visible),"help visibility still works");form.state.showHelp=true;form.UpdateHelp();
                    SettingsStore temp=new SettingsStore(Path.Combine(output,"appearance-settings"));temp.Save(form.state);Settings saved=temp.Load();check(saved.vault==notes&&saved.style=="night"&&saved.showHelp,"existing local settings retained without migration");
                    form.state.port=0;form.state.scopes=new List<string>{""};form.server=new VaultServer(form.state,delegate{},delegate{});form.server.Start(true);VaultServer running=form.server;int port=running.Port;
                    File.WriteAllText(config,themes[0]);form.RefreshAppearance(true);File.WriteAllText(config,themes[1]);form.RefreshAppearance(true);check(ReferenceEquals(running,form.server)&&running.Port==port,"appearance refresh preserves running share");form.StopServer();
                    VaultIndex index=new VaultIndex(notes,"appearance-scope",new[]{""});check(!index.Scan(new[]{""}).files.Any(x=>x.path.StartsWith(".obsidian")),"read exception does not expose hidden configuration to sync");
                    foreach(int dpi in new[]{96,120,144,168,192})using(MainForm probe=new MainForm(true)) {
                        probe.ShowInTaskbar=false;probe.StartPosition=FormStartPosition.Manual;probe.Location=new Point(-32000,-32000);probe.Opacity=0;probe.Show();Application.DoEvents();probe.timer.Stop();
                        float ratio=dpi/(float)probe.DeviceDpi;List<Control> controls=ControlsOf(probe).ToList();List<Font> fonts=controls.Select(c=>new Font(c.Font.FontFamily,c.Font.SizeInPoints*ratio,c.Font.Style)).ToList();
                        try{probe.AutoScaleMode=AutoScaleMode.None;probe.Scale(new SizeF(ratio,ratio));probe.layoutDpi=dpi;probe.ClientSize=new Size(probe.Px(1120),probe.Px(850));for(int i=0;i<controls.Count;i++)controls[i].Font=fonts[i];
                            for(int i=0;i<2;i++){probe.detectedAppearance=ObsidianAppearance.Parse(themes[i],false);probe.ApplyAppearance();check(probe.navigation.Width==probe.Px(174)&&probe.navButtons.All(b=>b.Bounds.Right<=probe.navigation.Width&&b.Bounds.Bottom<=probe.navigation.Height)&&probe.start.Height>=probe.Px(32),"DPI layout "+dpi+" "+names[i]);check(RepaintCorners((RoundedButton)probe.browse)&&RepaintCorners((RoundedButton)probe.navButtons[3]),"fresh-buffer corners at DPI "+dpi+" "+names[i]);ScrollFrame scaledFrame=(ScrollFrame)probe.pages[0].Parent;check(scaledFrame.NativeVerticalStyle==0&&(!scaledFrame.Rail.Visible||probe.pages[0].Right<=scaledFrame.Rail.Left),"buffered rail layout and native suppression at DPI "+dpi+" "+names[i]);Shot(probe,Path.Combine(output,"layout-"+dpi+"-"+names[i]+".png"));}
                        }finally{foreach(Font font in fonts)font.Dispose();}
                    }
                    check(inputFailure==null,"appearance input and paint did not raise a UI exception");
                }
                results.Add("Desktop appearance checks passed: "+count);File.WriteAllLines(Path.Combine(output,"appearance-results.txt"),results);return 0;
            }catch(Exception ex){results.Add(ex.ToString());File.WriteAllLines(Path.Combine(output,"appearance-results.txt"),results);return 1;}
            finally{Application.ThreadException-=handler;}
        }
    }
}
