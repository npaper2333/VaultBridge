using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Reflection;

namespace VaultBridge {
    // User-selected VB monogram, rendered independently at each Windows icon size.
    public static class AppIcon {
        public static readonly int[] Sizes={16,20,24,32,48,64,128,256};
        public static Icon Load(Size size) {
            using(Stream stream=Assembly.GetExecutingAssembly().GetManifestResourceStream("VaultBridge.ico")) {
                if(stream==null)throw new InvalidOperationException("Embedded VaultBridge icon is missing.");
                using(Icon source=new Icon(stream,size))return (Icon)source.Clone();
            }
        }
        public static Bitmap Render(int size) {
            Bitmap image=new Bitmap(size,size,PixelFormat.Format32bppArgb);
            using(Graphics g=Graphics.FromImage(image)) {
                g.Clear(Color.Transparent);g.SmoothingMode=SmoothingMode.AntiAlias;g.PixelOffsetMode=PixelOffsetMode.HighQuality;g.ScaleTransform(size/48F,size/48F);
                using(GraphicsPath background=new GraphicsPath()) {
                    background.AddArc(1,1,18,18,180,90);background.AddArc(29,1,18,18,270,90);background.AddArc(29,29,18,18,0,90);background.AddArc(1,29,18,18,90,90);background.CloseFigure();
                    using(Brush fill=new SolidBrush(ColorTranslator.FromHtml("#303234")))g.FillPath(fill,background);
                }
                using(Pen white=new Pen(Color.White,3F)){white.StartCap=white.EndCap=LineCap.Round;white.LineJoin=LineJoin.Round;g.DrawLines(white,new[]{new PointF(9,15),new PointF(15.5F,33),new PointF(22,15)});using(GraphicsPath b=new GraphicsPath()){b.AddLine(27,33,27,15);b.AddLine(27,15,31.5F,15);b.AddBezier(31.5F,15,39,15,40,24,32,24);b.AddLine(32,24,27,24);b.AddLine(27,24,32,24);b.AddBezier(32,24,41,24,40.5F,33,31.5F,33);b.AddLine(31.5F,33,27,33);g.DrawPath(white,b);}}
                using(Pen gold=new Pen(ColorTranslator.FromHtml("#edbf70"),3F)){gold.StartCap=gold.EndCap=LineCap.Round;gold.LineJoin=LineJoin.Round;g.DrawLines(gold,new[]{new PointF(13.5F,27.5F),new PointF(15.5F,33),new PointF(17.5F,27.5F)});}
            }
            return image;
        }
        public static void WriteIco(string path) {
            List<byte[]> frames=new List<byte[]>();foreach(int size in Sizes)using(Bitmap frame=Render(size))using(MemoryStream memory=new MemoryStream()){frame.Save(memory,ImageFormat.Png);frames.Add(memory.ToArray());}
            using(BinaryWriter writer=new BinaryWriter(File.Create(path))) {
                writer.Write((ushort)0);writer.Write((ushort)1);writer.Write((ushort)Sizes.Length);int offset=6+16*Sizes.Length;
                for(int i=0;i<Sizes.Length;i++){writer.Write((byte)(Sizes[i]==256?0:Sizes[i]));writer.Write((byte)(Sizes[i]==256?0:Sizes[i]));writer.Write((byte)0);writer.Write((byte)0);writer.Write((ushort)1);writer.Write((ushort)32);writer.Write(frames[i].Length);writer.Write(offset);offset+=frames[i].Length;}
                foreach(byte[] frame in frames)writer.Write(frame);
            }
        }
        public static void WritePreview(string path) {
            using(Bitmap sheet=new Bitmap(560,190))using(Graphics g=Graphics.FromImage(sheet))using(Font font=new Font("Segoe UI",9)) {
                g.Clear(Color.FromArgb(245,245,245));using(Brush dark=new SolidBrush(Color.FromArgb(30,30,30)))g.FillRectangle(dark,0,95,560,95);
                int x=24;foreach(int size in new[]{16,20,24,32,48,64}) {
                    using(Bitmap icon=Render(size)){g.DrawImageUnscaled(icon,x,12);g.DrawImageUnscaled(icon,x,107);}
                    g.DrawString(size+" px",font,Brushes.Black,x,78);g.DrawString(size+" px",font,Brushes.White,x,173);x+=size+30;
                }
                sheet.Save(path,ImageFormat.Png);
            }
        }
    }
}
