package io.vaultbridge.app;

final class UiTheme {
    final String id="lake",name="晴湖书架";final boolean dark;final int canvas,surface,ink,muted,accent,accentInk,primaryInk,wash,line,nav,thumb;final int radius=12,padding=20;
    private UiTheme(AppearanceConfig config){dark=config.dark;canvas=c(dark?"#1e1e1e":"#f6f6f6");surface=c(dark?"#252525":"#ffffff");ink=c(dark?"#e5e5e5":"#252525");muted=c(dark?"#a8a8a8":"#6b6b6b");accent=config.accent;wash=c(dark?"#2c2c2c":"#f1f1f1");nav=c(dark?"#191919":"#ededed");line=c(dark?"#383838":"#e5e5e5");thumb=c(dark?"#757575":"#8a8a8a");accentInk=contrast(accent,wash)>=4.5?accent:ink;primaryInk=contrast(accent,0xff000000)>contrast(accent,0xffffffff)?0xff000000:0xffffffff;}
    static int c(String value){return 0xff000000|Integer.parseInt(value.substring(1),16);}
    static UiTheme from(AppearanceConfig config){return new UiTheme(config);}
    static int red(int c){return (c>>>16)&255;}static int green(int c){return (c>>>8)&255;}static int blue(int c){return c&255;}
    static int mix(int a,int b,double w){return 0xff000000|((int)Math.round(red(a)*(1-w)+red(b)*w)<<16)|((int)Math.round(green(a)*(1-w)+green(b)*w)<<8)|(int)Math.round(blue(a)*(1-w)+blue(b)*w);}
    static double channel(int v){double x=v/255.0;return x<=0.04045?x/12.92:Math.pow((x+0.055)/1.055,2.4);}
    static double luminance(int c){return 0.2126*channel(red(c))+0.7152*channel(green(c))+0.0722*channel(blue(c));}
    static double contrast(int a,int b){double x=luminance(a),y=luminance(b);return (Math.max(x,y)+0.05)/(Math.min(x,y)+0.05);}
}
