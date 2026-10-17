package io.vaultbridge.app;

import java.util.Locale;

/** An uncommitted color edit: invalid input cannot replace the last valid preview. */
final class AccentDraft {
    final int original;
    private int preview;
    private boolean valid=true;
    AccentDraft(String saved,int fallback){Integer parsed=AppearanceConfig.color(saved);original=parsed==null?fallback:parsed;preview=original;}
    boolean edit(String text){Integer parsed=AppearanceConfig.color(text);valid=parsed!=null;if(valid)preview=parsed;return valid;}
    void select(int color){preview=0xff000000|(color&0xffffff);valid=true;}
    void reset(){select(original);}
    int preview(){return preview;}
    boolean valid(){return valid;}
    String hex(){return hex(preview);}
    String applied(){if(!valid)throw new IllegalStateException("颜色尚未填写完整");return hex();}
    static String hex(int color){return String.format(Locale.ROOT,"#%06x",color&0xffffff);}
}
