using System;
using System.Drawing;

namespace VaultBridge {
    internal static class DesktopPreferences {
        internal static string Theme(string mode){return mode=="light"||mode=="dark"||mode=="system"?mode:"obsidian";}
        internal static string Accent(string mode){return mode=="custom"?"custom":"obsidian";}
        internal static string Sent(string mode){return mode=="hidden"?"hidden":"marked";}
        internal static ObsidianAppearance Resolve(ObsidianAppearance source,Settings settings,bool systemDark){
            string mode=Theme(settings.themeMode),accent=Accent(settings.accentMode);bool dark=mode=="dark"||(mode=="system"?systemDark:mode=="obsidian"&&source.Dark);
            Color? custom=ObsidianAppearance.AccentOf(settings.customAccent);Color color=accent=="custom"?(custom??ColorTranslator.FromHtml("#7f6df2")):source.Accent;
            return new ObsidianAppearance{Dark=dark,Accent=color,Detail=source.Detail+"\n背景："+(mode=="light"?"浅色":mode=="dark"?"深色":mode=="system"?"跟随系统":"跟随 Obsidian")+"；强调色："+(accent=="custom"?(custom.HasValue?"自定义":"自定义值无效，使用默认紫色"):"跟随 Obsidian")};
        }
    }
    partial class MainForm {
        System.Windows.Forms.ComboBox backgroundPreference,accentPreference;
        System.Windows.Forms.TextBox customAccentPreference;
        bool changingPreferences;
        void BuildAppearancePreferences(System.Windows.Forms.TableLayoutPanel settings){
            var section=Section(settings,"外观设置");
            Help(section,"背景和强调色分别设置；固定晴湖书架布局，保留中性背景与圆角。所有设置只影响 VaultBridge。");
            backgroundPreference=new ThemedComboBox{DropDownStyle=System.Windows.Forms.ComboBoxStyle.DropDownList};backgroundPreference.Items.AddRange(new object[]{"背景 · 跟随 Obsidian","背景 · 跟随系统","背景 · 浅色","背景 · 深色"});string mode=DesktopPreferences.Theme(state.themeMode);backgroundPreference.SelectedIndex=mode=="system"?1:mode=="light"?2:mode=="dark"?3:0;Add(section,backgroundPreference);
            accentPreference=new ThemedComboBox{DropDownStyle=System.Windows.Forms.ComboBoxStyle.DropDownList};accentPreference.Items.AddRange(new object[]{"强调色 · 跟随 Obsidian","强调色 · 自定义"});accentPreference.SelectedIndex=DesktopPreferences.Accent(state.accentMode)=="custom"?1:0;Add(section,accentPreference);
            customAccentPreference=new System.Windows.Forms.TextBox{Text=state.customAccent??"#7f6df2",BorderStyle=System.Windows.Forms.BorderStyle.None,MaxLength=32};Input(section,customAccentPreference,36);
            Buttons(section,ActionButton("应用自定义颜色",delegate{Color? color=ObsidianAppearance.AccentOf(customAccentPreference.Text);if(!color.HasValue){System.Windows.Forms.MessageBox.Show(this,"填写 #RRGGBB、#RGB 或 rgb(r,g,b) 颜色。","颜色无效");return;}state.customAccent=String.Format("#{0:x2}{1:x2}{2:x2}",color.Value.R,color.Value.G,color.Value.B);state.accentMode="custom";changingPreferences=true;customAccentPreference.Text=state.customAccent;accentPreference.SelectedIndex=1;changingPreferences=false;SavePreferences();},false),ActionButton("选择颜色",delegate{using(var picker=new System.Windows.Forms.ColorDialog{Color=ObsidianAppearance.AccentOf(customAccentPreference.Text)??System.Drawing.ColorTranslator.FromHtml("#7f6df2"),FullOpen=true})if(picker.ShowDialog(this)==System.Windows.Forms.DialogResult.OK){state.customAccent=String.Format("#{0:x2}{1:x2}{2:x2}",picker.Color.R,picker.Color.G,picker.Color.B);state.accentMode="custom";changingPreferences=true;customAccentPreference.Text=state.customAccent;accentPreference.SelectedIndex=1;changingPreferences=false;SavePreferences();}},false));
            Help(section,"自定义颜色只用于少量主要按钮；普通卡片、文字和输入仍使用中性色。跟随 Obsidian 时，每两秒只读当前库的 appearance.json；配置无效会显示回退原因。");
            backgroundPreference.SelectedIndexChanged+=delegate{if(changingPreferences)return;state.themeMode=new[]{"obsidian","system","light","dark"}[backgroundPreference.SelectedIndex];SavePreferences();};accentPreference.SelectedIndexChanged+=delegate{if(changingPreferences)return;state.accentMode=accentPreference.SelectedIndex==1?"custom":"obsidian";SavePreferences();};
        }
        void SavePreferences(){try{Save();RefreshAppearance(true);}catch(Exception){System.Windows.Forms.MessageBox.Show(this,"设置未能保存，请检查本地应用数据目录；当前内容仍保留。","设置未保存");}}
    }
}
