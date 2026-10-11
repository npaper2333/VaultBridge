using System;
using System.Collections.Generic;
using System.Drawing;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using Microsoft.Win32;

namespace VaultBridge {
    sealed class ObsidianAppearance {
        public const int MaxBytes=65536;
        public bool Dark;
        public Color Accent;
        public string Detail;
        public string Key { get { return Dark+"|"+Accent.ToArgb()+"|"+Detail; } }
        public string Hex { get { return String.Format("#{0:x2}{1:x2}{2:x2}",Accent.R,Accent.G,Accent.B); } }
        public string Summary { get { return (Dark?"深色背景":"浅色背景")+" · "+Hex+"\n"+Detail; } }
        public static bool SystemDark() { try { using(RegistryKey key=Registry.CurrentUser.OpenSubKey(@"Software\Microsoft\Windows\CurrentVersion\Themes\Personalize"))return key!=null&&Convert.ToInt32(key.GetValue("AppsUseLightTheme",1))==0; } catch { return false; } }
        public static ObsidianAppearance Fallback(bool dark,string why) { return new ObsidianAppearance {Dark=dark,Accent=ColorTranslator.FromHtml("#7f6df2"),Detail=why+"；使用系统深浅色和默认紫色"}; }
        public static Color? AccentOf(string value) {
            if(value==null)return null;value=value.Trim();
            if(Regex.IsMatch(value,@"^#[0-9a-fA-F]{3}$"))value="#"+value[1]+value[1]+value[2]+value[2]+value[3]+value[3];
            if(Regex.IsMatch(value,@"^#[0-9a-fA-F]{6}$"))return ColorTranslator.FromHtml(value);
            Match rgb=Regex.Match(value,@"^rgb\(\s*(\d{1,3})\s*,\s*(\d{1,3})\s*,\s*(\d{1,3})\s*\)$",RegexOptions.IgnoreCase);
            if(rgb.Success){int r=int.Parse(rgb.Groups[1].Value),g=int.Parse(rgb.Groups[2].Value),b=int.Parse(rgb.Groups[3].Value);if(r<256&&g<256&&b<256)return Color.FromArgb(r,g,b);}return null;
        }
        public static ObsidianAppearance Parse(string json,bool systemDark) {
            try {
                if(json==null||Encoding.UTF8.GetByteCount(json)>MaxBytes)return Fallback(systemDark,"外观配置超过 64 KiB 或为空");
                Dictionary<string,object> data=Json.Decode<Dictionary<string,object>>(json.TrimStart('\ufeff'));
                if(data==null)return Fallback(systemDark,"外观配置格式无效");
                object raw;string mode=data.TryGetValue("theme",out raw)?raw as string:null;
                if(data.ContainsKey("theme")&&mode==null)return Fallback(systemDark,"外观模式格式无效");
                bool dark=systemDark;string detail="已读取 .obsidian/appearance.json";
                if(mode=="obsidian"||mode=="dark")dark=true;else if(mode=="moonstone"||mode=="light")dark=false;else if(String.IsNullOrEmpty(mode)||mode=="system")detail+="，深浅色跟随系统";else return Fallback(systemDark,"外观模式无法识别");
                string color=data.TryGetValue("accentColor",out raw)?raw as string:null;Color? accent=AccentOf(color);
                if(!accent.HasValue)detail+=String.IsNullOrWhiteSpace(color)?"，使用默认紫色":"，强调色无效，使用默认紫色";
                return new ObsidianAppearance {Dark=dark,Accent=accent??ColorTranslator.FromHtml("#7f6df2"),Detail=detail};
            } catch { return Fallback(systemDark,"外观配置格式无效"); }
        }
        public static ObsidianAppearance Read(string vault,bool systemDark) {
            if(String.IsNullOrWhiteSpace(vault))return Fallback(systemDark,"尚未选择 Obsidian 库");
            try {
                string root=Path.GetFullPath(vault),folder=Path.Combine(root,".obsidian"),file=Path.Combine(folder,"appearance.json");
                foreach(string part in new[]{root,folder,file})if((File.Exists(part)||Directory.Exists(part))&&(File.GetAttributes(part)&FileAttributes.ReparsePoint)!=0)return Fallback(systemDark,"外观配置位于链接路径，未读取");
                if(!File.Exists(file))return Fallback(systemDark,"当前库未找到 .obsidian/appearance.json");
                using(FileStream input=new FileStream(file,FileMode.Open,FileAccess.Read,FileShare.ReadWrite|FileShare.Delete)) {
                    using(MemoryStream bytes=new MemoryStream()){byte[] buffer=new byte[4096];int n;while((n=input.Read(buffer,0,buffer.Length))>0){if(bytes.Length+n>MaxBytes)return Fallback(systemDark,"外观配置超过 64 KiB");bytes.Write(buffer,0,n);}return Parse(new UTF8Encoding(false,true).GetString(bytes.ToArray()),systemDark);}
                }
            } catch(DecoderFallbackException) { return Fallback(systemDark,"外观配置格式无效"); }
            catch { return Fallback(systemDark,"无法读取当前库外观配置"); }
        }
    }
}
