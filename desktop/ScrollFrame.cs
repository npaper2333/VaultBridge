using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace VaultBridge {
    // The native content control still handles wheel, keyboard and text selection.
    // Its nonclient scrollbar is never visible; a separate buffered control owns the rail.
    sealed class ScrollFrame : Panel {
        [DllImport("user32.dll")] static extern int GetWindowLong(IntPtr window,int index);
        [DllImport("user32.dll")] static extern int SetWindowLong(IntPtr window,int index,int value);
        [DllImport("user32.dll")] static extern bool SetWindowPos(IntPtr window,IntPtr after,int x,int y,int width,int height,uint flags);
        [DllImport("user32.dll")] static extern IntPtr SendMessage(IntPtr window,int message,IntPtr first,IntPtr second);
        [StructLayout(LayoutKind.Sequential)] struct Styles { public int Old,New; }
        const int VerticalStyle=0x00200000;
        readonly Control content;
        readonly ContentWindow window;
        internal readonly ScrollRail Rail;
        bool updating;
        int listWheelRemainder;
        internal int NativePaintWithScrollbar;
        internal int NativeVerticalStyle { get { return content.IsHandleCreated?GetWindowLong(content.Handle,-16)&VerticalStyle:0; } }
        sealed class ContentWindow : NativeWindow {
            readonly ScrollFrame owner;
            public ContentWindow(ScrollFrame frame){owner=frame;}
            protected override void WndProc(ref Message message){
                // ShowScrollBar can change styles without WM_STYLECHANGING. Remove the
                // style before Windows calculates or paints any nonclient pixels.
                if(message.Msg==0x83||message.Msg==0x85)owner.HideNativeStyle();
                if((message.Msg==0xF||message.Msg==0x85)&&owner.NativeVerticalStyle!=0)owner.NativePaintWithScrollbar++;
                ListBox list=owner.content as ListBox;int before=message.Msg==0x20A&&list!=null?list.TopIndex:0;
                base.WndProc(ref message);
                // The native listbox ignores wheel input without WS_VSCROLL. Keep its
                // normal input/events, then supply scrolling only if it did not move.
                if(message.Msg==0x20A&&list!=null){if(list.TopIndex==before)owner.ListWheel((short)((message.WParam.ToInt64()>>16)&0xffff));else owner.listWheelRemainder=0;}
                if(message.Msg==0x7C&&message.WParam.ToInt64()==-16){Styles styles=(Styles)Marshal.PtrToStructure(message.LParam,typeof(Styles));styles.New&=~VerticalStyle;Marshal.StructureToPtr(styles,message.LParam,false);}
                if(message.Msg==0xF||message.Msg==0x115||message.Msg==0x20A||message.Msg==0x100||message.Msg==0x101||message.Msg==0x5||message.Msg==0xC||message.Msg==0xB6||message.Msg==0xB7||message.Msg==0xBA||message.Msg==0x202||message.Msg==0x200)owner.SyncRange();
            }
        }
        public ScrollFrame(Control view){
            content=view;DoubleBuffered=true;Margin=Padding.Empty;Padding=Padding.Empty;
            Rail=new ScrollRail{Dock=DockStyle.Right,AccessibleName="垂直滚动条",ScrollTo=ScrollTo};window=new ContentWindow(this);
            Controls.Add(content);content.Dock=DockStyle.Fill;Controls.Add(Rail);content.BringToFront();
            content.HandleCreated+=delegate{window.AssignHandle(content.Handle);HideNativeBar();SyncRange();};
            content.SizeChanged+=delegate{SyncRange();};content.TextChanged+=delegate{SyncRange();};content.FontChanged+=delegate{SyncRange();};
            content.EnabledChanged+=delegate{SyncRange();};
            ScrollableControl panel=content as ScrollableControl;if(panel!=null)panel.Scroll+=delegate{SyncRange();};
            if(content.IsHandleCreated){window.AssignHandle(content.Handle);HideNativeBar();}
        }
        void HideNativeStyle(){int style=GetWindowLong(content.Handle,-16);if((style&VerticalStyle)!=0)SetWindowLong(content.Handle,-16,style&~VerticalStyle);}
        void HideNativeBar(){int style=GetWindowLong(content.Handle,-16);if((style&VerticalStyle)==0)return;HideNativeStyle();SetWindowPos(content.Handle,IntPtr.Zero,0,0,0,0,0x37);}
        internal void Theme(bool dark){BackColor=content.BackColor;Rail.BackColor=content.BackColor;Rail.Thumb=ColorTranslator.FromHtml(dark?"#656565":"#a6a6a6");Rail.Ink=ColorTranslator.FromHtml(dark?"#a8a8a8":"#6b6b6b");Rail.Invalidate();SyncRange();}
        internal void SyncRange(){
            if(updating||IsDisposed||!content.IsHandleCreated)return;updating=true;
            try{
                Rail.Enabled=content.Enabled;
                int maximum=0,position=0,page=1,step=Math.Max(1,content.Font.Height);
                ScrollableControl panel=content as ScrollableControl;TextBox text=content as TextBox;ListBox list=content as ListBox;
                if(panel!=null){page=Math.Max(1,panel.ClientSize.Height);maximum=Math.Max(0,panel.DisplayRectangle.Height-page);position=-panel.AutoScrollPosition.Y;}
                else if(text!=null){page=Math.Max(1,text.ClientSize.Height/Math.Max(1,text.Font.Height));maximum=Math.Max(0,SendMessage(text.Handle,0xBA,IntPtr.Zero,IntPtr.Zero).ToInt32()-page);position=SendMessage(text.Handle,0xCE,IntPtr.Zero,IntPtr.Zero).ToInt32();step=1;}
                else if(list!=null){page=Math.Max(1,list.ClientSize.Height/Math.Max(1,list.ItemHeight));maximum=Math.Max(0,list.Items.Count-page);position=list.TopIndex;step=1;}
                Rail.SetRange(maximum,page,position,step);bool visible=maximum>0;if(Rail.Visible!=visible)Rail.Visible=visible;
            }finally{updating=false;}
        }
        internal void ScrollTo(int position){
            position=Math.Max(0,Math.Min(Rail.Maximum,position));ScrollableControl panel=content as ScrollableControl;TextBox text=content as TextBox;ListBox list=content as ListBox;
            if(panel!=null)panel.AutoScrollPosition=new Point(-panel.AutoScrollPosition.X,position);
            else if(text!=null)SendMessage(text.Handle,0xB6,IntPtr.Zero,new IntPtr(position-SendMessage(text.Handle,0xCE,IntPtr.Zero,IntPtr.Zero).ToInt32()));
            else if(list!=null&&list.Items.Count>0)list.TopIndex=position;
            SyncRange();
        }
        void ListWheel(int delta){if(!content.Enabled)return;SyncRange();listWheelRemainder+=delta;int ticks=listWheelRemainder/120;listWheelRemainder%=120;int lines=SystemInformation.MouseWheelScrollLines;ScrollTo(Rail.Position-ticks*(lines<0?Rail.Page:Math.Max(0,lines)));}
        protected override void OnLayout(LayoutEventArgs e){base.OnLayout(e);SyncRange();}
        protected override void Dispose(bool disposing){if(disposing)window.ReleaseHandle();base.Dispose(disposing);}
    }
    sealed class ScrollPage : Panel {
        public ScrollPage(){DoubleBuffered=true;}
        protected override CreateParams CreateParams { get { CreateParams value=base.CreateParams;value.Style&=~0x00200000;return value; } }
    }
    sealed class ScrollLog : TextBox {
        protected override CreateParams CreateParams { get { CreateParams value=base.CreateParams;value.Style&=~0x00200000;return value; } }
    }
    sealed class ScrollScopeList : CheckedListBox {
        protected override CreateParams CreateParams { get { CreateParams value=base.CreateParams;value.Style&=~0x00200000;return value; } }
    }
    sealed class ScrollTaskList : ListBox {
        protected override CreateParams CreateParams { get { CreateParams value=base.CreateParams;value.Style&=~0x00200000;return value; } }
    }
    sealed class ScrollRail : Control {
        internal Color Thumb=Color.Gray,Ink=Color.Gray;
        internal Action<int> ScrollTo;
        internal int Maximum,Position,Page=1,Step=1;
        readonly Timer repeat=new Timer();
        bool dragging,hover;
        int dragOffset,wheelRemainder,repeatPoint;
        public ScrollRail(){SetStyle(ControlStyles.UserPaint|ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer|ControlStyles.Opaque|ControlStyles.Selectable,true);TabStop=true;AccessibleRole=AccessibleRole.ScrollBar;Width=16;repeat.Tick+=delegate{repeat.Interval=55;StepAt(repeatPoint);};}
        protected override void OnHandleCreated(EventArgs e){base.OnHandleCreated(e);Width=Math.Max(12,16*DeviceDpi/96);}
        protected override void OnDpiChangedAfterParent(EventArgs e){base.OnDpiChangedAfterParent(e);Width=Math.Max(12,16*DeviceDpi/96);}
        internal void SetRange(int maximum,int page,int position,int step){maximum=Math.Max(0,maximum);page=Math.Max(1,page);position=Math.Max(0,Math.Min(maximum,position));if(Maximum==maximum&&Page==page&&Position==position&&Step==step)return;Maximum=maximum;Page=page;Position=position;Step=step;Invalidate();}
        internal Rectangle ThumbBounds {
            get{int arrow=Math.Min(Width,Height/3),track=Math.Max(1,Height-2*arrow);int length=Math.Min(track,Math.Max(Math.Min(track,Math.Max(8,12*DeviceDpi/96)),(int)((long)track*Page/(Maximum+(long)Page))));int top=arrow+(Maximum==0?0:(int)((long)(track-length)*Position/Maximum));return new Rectangle(Math.Max(2,Width/4),top,Math.Max(1,Width-2*Math.Max(2,Width/4)),length);}
        }
        protected override void OnPaintBackground(PaintEventArgs e){}
        protected override void OnPaint(PaintEventArgs e){
            e.Graphics.Clear(BackColor);e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;Rectangle thumb=ThumbBounds;
            using(GraphicsPath shape=RoundShape.Path(thumb,Math.Max(2,Width/4)))using(Brush fill=new SolidBrush(hover||dragging?ControlPaint.Light(Thumb,0.12F):Thumb))e.Graphics.FillPath(fill,shape);
            int arrow=Math.Min(Width,Height/3);using(Pen pen=new Pen(Ink,Math.Max(1,DeviceDpi/96F)))for(int end=0;end<2;end++){float x=Width/2F,y=end==0?arrow/2F:Height-arrow/2F,d=Math.Max(2,Width/7F),direction=end==0?-1:1;e.Graphics.DrawLines(pen,new[]{new PointF(x-d,y-direction*d/2),new PointF(x,y+direction*d/2),new PointF(x+d,y-direction*d/2)});}
            if(Focused&&ShowFocusCues)ControlPaint.DrawFocusRectangle(e.Graphics,Rectangle.Inflate(ClientRectangle,-1,-1),Ink,BackColor);
        }
        void MoveTo(int value){if(ScrollTo!=null)ScrollTo(Math.Max(0,Math.Min(Maximum,value)));}
        void StepAt(int y){int arrow=Math.Min(Width,Height/3);Rectangle thumb=ThumbBounds;if(y<arrow)MoveTo(Position-Step);else if(y>=Height-arrow)MoveTo(Position+Step);else if(y<thumb.Top)MoveTo(Position-Page);else if(y>=thumb.Bottom)MoveTo(Position+Page);}
        protected override void OnMouseDown(MouseEventArgs e){base.OnMouseDown(e);if(e.Button!=MouseButtons.Left)return;Focus();Capture=true;Rectangle thumb=ThumbBounds;dragging=thumb.Contains(e.Location);if(dragging)dragOffset=e.Y-thumb.Top;else{repeatPoint=e.Y;StepAt(e.Y);repeat.Interval=350;repeat.Start();}Invalidate();}
        protected override void OnMouseMove(MouseEventArgs e){base.OnMouseMove(e);if(!dragging)return;int arrow=Math.Min(Width,Height/3),travel=Height-2*arrow-ThumbBounds.Height;if(travel>0)MoveTo((int)Math.Min(Maximum,Math.Max(0,(long)(e.Y-dragOffset-arrow)*Maximum/travel)));}
        protected override void OnMouseUp(MouseEventArgs e){base.OnMouseUp(e);if(e.Button==MouseButtons.Left){repeat.Stop();dragging=false;Capture=false;Invalidate();}}
        protected override void OnMouseCaptureChanged(EventArgs e){base.OnMouseCaptureChanged(e);if(!Capture){repeat.Stop();dragging=false;Invalidate();}}
        protected override void OnMouseEnter(EventArgs e){base.OnMouseEnter(e);hover=true;Invalidate();}
        protected override void OnMouseLeave(EventArgs e){base.OnMouseLeave(e);hover=false;Invalidate();}
        protected override void OnMouseWheel(MouseEventArgs e){base.OnMouseWheel(e);wheelRemainder+=e.Delta;int ticks=wheelRemainder/120;wheelRemainder%=120;int lines=SystemInformation.MouseWheelScrollLines;MoveTo(Position-ticks*(lines<0?Page:Math.Max(0,lines)*Step));}
        protected override bool IsInputKey(Keys keyData){Keys key=keyData&Keys.KeyCode;return key==Keys.Up||key==Keys.Down||key==Keys.PageUp||key==Keys.PageDown||key==Keys.Home||key==Keys.End||base.IsInputKey(keyData);}
        protected override void OnKeyDown(KeyEventArgs e){base.OnKeyDown(e);switch(e.KeyCode){case Keys.Up:MoveTo(Position-Step);break;case Keys.Down:MoveTo(Position+Step);break;case Keys.PageUp:MoveTo(Position-Page);break;case Keys.PageDown:MoveTo(Position+Page);break;case Keys.Home:MoveTo(0);break;case Keys.End:MoveTo(Maximum);break;default:return;}e.Handled=true;}
        protected override void Dispose(bool disposing){if(disposing)repeat.Dispose();base.Dispose(disposing);}
    }
}
