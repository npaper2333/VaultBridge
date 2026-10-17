package io.vaultbridge.app;

/** Regression: incomplete edits are preview-only and cannot be applied. */
public final class AccentDraftTest {
    static int count;
    static void check(boolean yes,String message){count++;if(!yes)throw new AssertionError(message);}
    public static void main(String[] args){
        AccentDraft draft=new AccentDraft("#d9b55c",0xff7f6df2);
        check(draft.applied().equals("#d9b55c"),"saved color retained");
        check(draft.edit("#3Af"),"short hex accepted");check(draft.applied().equals("#33aaff"),"short hex normalized");
        check(!draft.edit("#33"),"partial edit invalid");check(draft.preview()==0xff33aaff,"invalid edit retains last valid preview");
        boolean blocked=false;try{draft.applied();}catch(IllegalStateException expected){blocked=true;}check(blocked,"invalid edit never applies");
        draft.select(0xff15816b);check(draft.valid()&&draft.applied().equals("#15816b"),"swatch recovers invalid input");
        draft.reset();check(draft.applied().equals("#d9b55c"),"reset returns original, not prior slider color");
        check(!draft.edit("#ff000000"),"alpha input rejected");check(!draft.edit("rgb(256,0,0)"),"out of range rejected");
        check(draft.edit(" rgb(255, 0, 128) "),"existing RGB notation retained");check(draft.applied().equals("#ff0080"),"RGB canonicalized");
        for(String color:new String[]{"#000000","#ffffff","#777777","#d9b55c","#7f6df2","#397dba","#15816b","#bf5e47"}){
            draft.edit(color);for(boolean dark:new boolean[]{false,true}){UiTheme theme=UiTheme.from(new AppearanceConfig(dark,draft.preview(),""));check(theme.accent==draft.preview(),"preview retains exact chosen color");check(UiTheme.contrast(theme.accent,theme.primaryInk)>=4.5,"sample button readable "+color);}
        }
        check(new AccentDraft("broken",0xff397dba).applied().equals("#397dba"),"invalid saved value safely uses active accent");
        System.out.println("Accent draft checks passed: "+count);
    }
}
