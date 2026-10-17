package io.vaultbridge.app;

import java.io.*;
import java.util.*;

public final class SourceSessionModelTest {
    static int passed;static void check(boolean v,String label){if(!v)throw new AssertionError(label);passed++;System.out.println("PASS: "+label);}
    interface Work {void run()throws Exception;}static void reject(Work action,String label)throws Exception{try{action.run();throw new AssertionError(label);}catch(IOException expected){check(true,label);}}
    public static void main(String[] args)throws Exception {
        SourceSessionModel model=new SourceSessionModel();List<SourceSessionModel.Item> selected=new ArrayList<>();selected.add(new SourceSessionModel.Item("照片/山.jpg","content://documents/tree/root/document/one"));String token=model.create(selected);
        check(token.matches("[0-9a-f]{32}"),"source session uses random opaque token");check(model.resolve(token,0).path.equals("照片/山.jpg"),"selected source path retained");check(model.resolve(token,0).uri.endsWith("/one"),"selected source URI retained");selected.clear();check(model.resolve(token,0)!=null,"allowlist snapshot cannot be changed by caller list");
        reject(()->model.resolve("content://documents/one",0),"URI is not a session token");reject(()->model.resolve(new String(new char[64]).replace('\0','a'),0),"catalog ID cannot open a send source");reject(()->model.resolve(token,-1),"negative index rejected");reject(()->model.resolve(token,1),"unselected index rejected");reject(()->model.resolve(null,0),"missing session rejected");
        reject(()->new SourceSessionModel.Item("a.jpg","file:///storage/a.jpg"),"arbitrary file path rejected");reject(()->new SourceSessionModel.Item("a.jpg","https://example.org/a.jpg"),"network source URI rejected");reject(()->new SourceSessionModel.Item("a.jpg","content://io.vaultbridge.app.updates/apk"),"internal installer provider excluded");reject(()->new SourceSessionModel.Item("../a.jpg","content://documents/a"),"unsafe relative source path rejected");reject(()->new SourceSessionModel.Item("a.jpg","content://user@documents/a"),"URI user information rejected");
        model.close(token);reject(()->model.resolve(token,0),"closed source session cannot be reopened");reject(()->new SourceSessionModel().resolve(token,0),"process restart does not revive source authority");reject(()->model.create(Collections.emptyList()),"empty allowlist rejected");
        reject(()->new SourceSessionModel.Item("a.jpg","content://IO.VAULTBRIDGE.APP.UPDATES/apk"),"internal provider excluded regardless of authority case");
        List<SourceSessionModel.Item> one=Collections.singletonList(new SourceSessionModel.Item("a.txt","content://documents/a"));String[] keys=new String[4];for(int i=0;i<4;i++)keys[i]=model.create(one);check(new HashSet<>(Arrays.asList(keys)).size()==4,"parallel sessions use distinct identities");reject(()->model.create(one),"session count bounded");model.close(keys[0]);check(!model.create(one).equals(keys[0]),"capacity can be reused without reviving old token");
        System.out.println("Source session model checks passed: "+passed);
    }
}
