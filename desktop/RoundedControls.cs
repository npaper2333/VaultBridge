using System;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Windows.Forms;

namespace VaultBridge {
    static class RoundShape {
        public static GraphicsPath Path(RectangleF bounds,int radius){
            GraphicsPath path=new GraphicsPath();float d=Math.Min(radius*2,Math.Min(bounds.Width,bounds.Height));
            if(d<2){path.AddRectangle(bounds);return path;}
            path.AddArc(bounds.Left,bounds.Top,d,d,180,90);path.AddArc(bounds.Right-d,bounds.Top,d,d,270,90);path.AddArc(bounds.Right-d,bounds.Bottom-d,d,d,0,90);path.AddArc(bounds.Left,bounds.Bottom-d,d,d,90,90);path.CloseFigure();return path;
        }
    }
    sealed class RoundedButton : Button {
        public int Radius=6;
        public Color Fill,HoverFill,PressedFill,Stroke,DisabledInk;
        public bool DrawBorder=true;
        bool hovering,pressing;
        public RoundedButton(){SetStyle(ControlStyles.UserPaint|ControlStyles.AllPaintingInWmPaint|ControlStyles.OptimizedDoubleBuffer|ControlStyles.ResizeRedraw,true);FlatStyle=FlatStyle.Flat;FlatAppearance.BorderSize=0;UseVisualStyleBackColor=false;}
        protected override void OnPaintBackground(PaintEventArgs e){e.Graphics.Clear(Parent==null?SystemColors.Control:Parent.BackColor);}
        protected override void OnPaint(PaintEventArgs e){
            // Flat Button is opaque and can skip OnPaintBackground during WM_PAINT.
            // Fill the complete buffer here so rounded corners never retain black pixels.
            e.Graphics.Clear(Parent==null?SystemColors.Control:Parent.BackColor);
            e.Graphics.SmoothingMode=SmoothingMode.AntiAlias;
            RectangleF box=new RectangleF(0.5F,0.5F,Math.Max(1,Width-1),Math.Max(1,Height-1));Color fill=!Enabled?Fill:pressing?PressedFill:hovering?HoverFill:Fill;
            using(GraphicsPath shape=RoundShape.Path(box,Radius)){using(Brush brush=new SolidBrush(fill))e.Graphics.FillPath(brush,shape);if(DrawBorder)using(Pen pen=new Pen(Stroke))e.Graphics.DrawPath(pen,shape);}
            Rectangle text=Rectangle.Inflate(ClientRectangle,-Padding.Left,-Padding.Top);TextFormatFlags flags=TextFormatFlags.HorizontalCenter|TextFormatFlags.VerticalCenter|TextFormatFlags.SingleLine|TextFormatFlags.EndEllipsis;
            if(!ShowKeyboardCues)flags|=TextFormatFlags.HidePrefix;
            TextRenderer.DrawText(e.Graphics,Text,Font,text,Enabled?ForeColor:DisabledInk,flags);
            if(Focused&&ShowFocusCues){Rectangle focus=Rectangle.Inflate(ClientRectangle,-4,-4);ControlPaint.DrawFocusRectangle(e.Graphics,focus,Enabled?ForeColor:DisabledInk,fill);}
        }
        public override Size GetPreferredSize(Size proposed){Size text=TextRenderer.MeasureText(Text,Font);return new Size(Math.Max(MinimumSize.Width,text.Width+Padding.Horizontal+12),Math.Max(MinimumSize.Height,text.Height+Padding.Vertical));}
        protected override void OnMouseEnter(EventArgs e){hovering=true;Invalidate();base.OnMouseEnter(e);}
        protected override void OnMouseLeave(EventArgs e){hovering=false;pressing=false;Invalidate();base.OnMouseLeave(e);}
        protected override void OnMouseDown(MouseEventArgs e){if(e.Button==MouseButtons.Left)pressing=true;Invalidate();base.OnMouseDown(e);}
        protected override void OnMouseUp(MouseEventArgs e){pressing=false;Invalidate();base.OnMouseUp(e);}
        protected override void OnKeyDown(KeyEventArgs e){if(e.KeyCode==Keys.Space)pressing=true;Invalidate();base.OnKeyDown(e);}
        protected override void OnKeyUp(KeyEventArgs e){pressing=false;Invalidate();base.OnKeyUp(e);}
        protected override void OnEnabledChanged(EventArgs e){pressing=false;Invalidate();base.OnEnabledChanged(e);}
        protected override void OnGotFocus(EventArgs e){Invalidate();base.OnGotFocus(e);}
        protected override void OnLostFocus(EventArgs e){pressing=false;Invalidate();base.OnLostFocus(e);}
    }
}
