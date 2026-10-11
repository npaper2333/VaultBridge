using System;
using System.Collections.Generic;
using System.Drawing;
using System.Drawing.Drawing2D;
using System.Drawing.Imaging;
using System.IO;
using System.Reflection;

namespace VaultBridge {
    // The existing Android note/transfer mark, drawn as a vector at each Windows icon size.
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
                    using(Brush fill=new SolidBrush(ColorTranslator.FromHtml("#0f766e")))g.FillPath(fill,background);
                }
                using(Brush white=new SolidBrush(Color.White)) {
                    g.FillPolygon(white,new[]{new PointF(10,11),new PointF(21,11),new PointF(21,15),new PointF(14,15),new PointF(14,33),new PointF(21,33),new PointF(21,37),new PointF(10,37)});
                    g.FillPolygon(white,new[]{new PointF(27,11),new PointF(38,11),new PointF(38,37),new PointF(27,37),new PointF(27,33),new PointF(34,33),new PointF(34,15),new PointF(27,15)});
                    g.FillPolygon(white,new[]{new PointF(20,22),new PointF(27,22),new PointF(27,18),new PointF(34,24),new PointF(27,30),new PointF(27,26),new PointF(20,26)});
                }
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
