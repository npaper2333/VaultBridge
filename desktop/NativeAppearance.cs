using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Runtime.CompilerServices;
using System.Runtime.InteropServices;
using System.Windows.Forms;

namespace VaultBridge {
    static class NativeAppearance {
        public static void Apply(Control control,bool dark){ScrollFrame frame=control as ScrollFrame;if(frame!=null)frame.Theme(dark);}
    }
    sealed class ThemedComboBox : ComboBox {
        public Color SelectionFill=SystemColors.ControlDark;
        public Color Line=SystemColors.ControlDark,Muted=SystemColors.GrayText;
        [DllImport("user32.dll")] static extern IntPtr GetWindowDC(IntPtr window);
        [DllImport("user32.dll")] static extern int ReleaseDC(IntPtr window,IntPtr dc);
        public ThemedComboBox(){FlatStyle=FlatStyle.Flat;DrawMode=DrawMode.OwnerDrawFixed;}
        protected override void WndProc(ref Message message){
            base.WndProc(ref message);
            if(message.Msg==0x317||message.Msg==0x318){if(message.WParam!=IntPtr.Zero)using(Graphics g=Graphics.FromHdc(message.WParam))PaintFace(g);}
            else if(message.Msg==0xF){IntPtr dc=GetWindowDC(Handle);if(dc!=IntPtr.Zero)try{using(Graphics g=Graphics.FromHdc(dc))PaintFace(g);}finally{ReleaseDC(Handle,dc);}}
        }
        void PaintFace(Graphics graphics){
            using(Brush parent=new SolidBrush(Parent==null?BackColor:Parent.BackColor))graphics.FillRectangle(parent,new Rectangle(0,0,Width,Height));graphics.SmoothingMode=SmoothingMode.AntiAlias;int padding=Math.Max(4,8*DeviceDpi/96);
            using(GraphicsPath shape=RoundShape.Path(new RectangleF(0.5F,0.5F,Math.Max(1,Width-1),Math.Max(1,Height-1)),Math.Max(3,6*DeviceDpi/96)))using(Brush fill=new SolidBrush(BackColor))using(Pen outline=new Pen(Line)){graphics.FillPath(fill,shape);graphics.DrawPath(outline,shape);}
            TextRenderer.DrawText(graphics,Text,Font,new Rectangle(padding,0,Math.Max(1,Width-padding*4),Height),Enabled?ForeColor:Muted,TextFormatFlags.Left|TextFormatFlags.VerticalCenter|TextFormatFlags.SingleLine|TextFormatFlags.EndEllipsis);
            float x=Width-padding*1.5F,y=Height/2F;float d=Math.Max(2,3*DeviceDpi/96F);using(Pen arrow=new Pen(Muted,Math.Max(1,DeviceDpi/96F)))graphics.DrawLines(arrow,new[]{new PointF(x-d,y-d/2),new PointF(x,y+d/2),new PointF(x+d,y-d/2)});
            if(Focused&&ShowFocusCues)ControlPaint.DrawFocusRectangle(graphics,new Rectangle(padding/2,3,Math.Max(1,Width-padding),Math.Max(1,Height-6)),ForeColor,BackColor);
        }
        protected override void OnDrawItem(DrawItemEventArgs e){
            Color background=(e.State&DrawItemState.Selected)!=0&&(e.State&DrawItemState.ComboBoxEdit)==0?SelectionFill:BackColor;
            using(System.Drawing.Brush brush=new System.Drawing.SolidBrush(background))e.Graphics.FillRectangle(brush,e.Bounds);
            string value=e.Index>=0&&e.Index<Items.Count?GetItemText(Items[e.Index]):Text;
            System.Drawing.Rectangle text=e.Bounds;text.Inflate(-4,0);
            TextRenderer.DrawText(e.Graphics,value,Font,text,Enabled?ForeColor:System.Drawing.SystemColors.GrayText,TextFormatFlags.Left|TextFormatFlags.VerticalCenter|TextFormatFlags.SingleLine);
            if((e.State&DrawItemState.Focus)!=0)e.DrawFocusRectangle();
        }
    }
}
