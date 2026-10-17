package io.vaultbridge.app;

import java.io.*;
import java.util.*;
import org.json.*;

/** Synthetic manifests and receipts; never opens a device, network, or a user vault. */
public final class SendPickerModelTest {
    static int passed;
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);passed++;System.out.println("PASS: "+label);}
    interface Work {void run()throws Exception;}
    static void reject(Work work,String label)throws Exception {try{work.run();throw new AssertionError(label);}catch(IOException expected){check(true,label);}}
    static String hash(String value)throws Exception {return TransferEngine.hash(value.getBytes("UTF-8"));}
    static JSONObject file(String path,String body)throws Exception{return new JSONObject().put("path",path).put("size",body.getBytes("UTF-8").length).put("sha256",hash(body));}
    static JSONObject listing(boolean complete,JSONArray files)throws Exception {return new JSONObject().put("mode","notes").put("prefix","").put("vaultId","vault").put("targetId","root-target").put("checkedAt",1234).put("complete",complete).put("scopes",complete?new JSONArray().put(""):new JSONArray().put("same.md").put("changed.md").put("archive")).put("warnings",new JSONArray()).put("files",files);}
    static SendPickerModel model()throws Exception {List<SendPickerModel.Item> items=new ArrayList<>();String[] paths={"same.md","changed.md","different-name.jpg","missing.pdf","unknown.bin"};for(int i=0;i<paths.length;i++){SendPickerModel.Item item=new SendPickerModel.Item(""+i,paths[i],i==0||i==1);items.add(item);}SendPickerModel model=new SendPickerModel(items,"identity");for(int i=0;i<4;i++)model.fingerprint(items.get(i),1,hash("A"));return model;}
    static JSONObject receipt(String saved)throws Exception{return new JSONObject().put("targetId","files-target").put("vaultId","vault").put("sha256",hash("A")).put("size",1).put("saved",saved);}
    static JSONObject verified(String result,String saved)throws Exception {return new JSONObject(receipt(saved).toString()).put("mode","files").put("prefix","").put("result",result).put("checkedAt",2345);}
    public static void main(String[] args)throws Exception {
        SendPickerModel model=model();List<SendPickerModel.Item> items=model.items;
        check(model.visible("","all").size()==5,"all initial unknown items visible");
        check(model.visible("SAME","markdown").size()==1,"case insensitive path search and type filter");
        check(model.visible("","image").size()==1,"image type does not depend on URI");
        check(model.selected().size()==2,"explicit initial selections retained");
        check(model.selectMissing()==0,"unknown never selected as missing");
        model.hide(true);check(model.visible("","all").size()==5,"hide cannot remove unknown items");
        JSONArray remote=new JSONArray().put(file("same.md","A")).put(file("changed.md","B")).put(file("archive/photo.jpg","A"));
        model.compare(listing(false,remote),"vault");
        check(items.get(0).state.equals("same"),"same path plus size plus hash proves same");
        check(!items.get(0).selected,"new same result cancels existing hidden selection");
        check(items.get(1).state.equals("changed")&&items.get(1).selected,"same path different content remains selected and visible");
        check(items.get(2).state.equals("elsewhere"),"B index finds content under a different path");
        check(items.get(3).state.equals("elsewhere"),"content match has priority over incomplete absence");
        check(items.get(4).state.equals("unknown"),"unhashed source cannot receive an existence proof");
        check(model.visible("","all").size()==4,"only exact target matches hidden");
        check(model.visible("","image").size()==1,"different path match never hidden");
        model.hide(false);check(model.visible("","all").size()==5&&!items.get(0).selected,"show does not restore a cancelled selection");
        model.fingerprint(items.get(3),1,hash("C"));model.compare(listing(false,remote),"vault");check(items.get(3).state.equals("unknown"),"partial listing cannot prove missing");
        model.compare(listing(true,remote),"vault");check(items.get(3).state.equals("missing"),"complete whole scope can prove missing");
        check(model.selectMissing()==1&&items.get(3).selected,"select new chooses only confirmed missing");
        JSONObject warning=listing(true,remote);warning.getJSONArray("warnings").put("excluded-unreadable");model.compare(warning,"vault");check(items.get(3).state.equals("unknown"),"scan warning prevents global absence claim");
        JSONObject limited=listing(true,remote).put("scopes",new JSONArray().put("same.md").put("changed.md").put("archive"));model.compare(limited,"vault");check(items.get(3).state.equals("unknown"),"complete flag without whole scope cannot prove absence");
        reject(()->model.compare(listing(false,new JSONArray().put(file("unapproved/secret.md","A"))),"vault"),"reply entries outside reported authorization scope rejected");
        model.target("来自手机","identity");check(model.checkedAt==0&&model.targetId.isEmpty(),"prefix change invalidates proof binding");check(model.visible("","all").size()==5,"target change reveals old same items");
        reject(()->model.compare(listing(true,remote),"vault"),"reply for old prefix rejected");
        check(model.sameCount()==0,"old target response leaves no same proof");
        JSONObject nested=listing(true,new JSONArray().put(file("来自手机/same.md","A"))).put("prefix","来自手机");model.compare(nested,"vault");check(items.get(0).state.equals("same"),"prefix participates before path matching");
        model.target("来自手机","different-computer");check(items.get(0).state.equals("unknown"),"computer or source identity change invalidates old proof");
        reject(()->model.target("../escape","identity"),"relative path traversal rejected");
        reject(()->model.target("/absolute","identity"),"absolute target prefix rejected");
        model.target("","identity");reject(()->model.compare(listing(true,remote),"other-vault"),"wrong paired vault rejected");
        reject(()->model.compare(listing(true,remote).put("targetId",""),"vault"),"unbound target cannot prove existence");
        reject(()->model.compare(listing(true,remote).put("checkedAt",0),"vault"),"missing check time cannot prove freshness");
        reject(()->model.compare(new JSONObject(),"vault"),"old server metadata safely rejected");
        reject(()->model.compare(listing(true,new JSONArray().put(file("a.md","A")).put(file("A.md","B"))),"vault"),"case ambiguous remote paths rejected");
        items.get(0).history=true;model.invalidate();check(items.get(0).state.equals("history"),"successful history preserved only as pending");model.hide(true);check(model.visible("","all").size()==5,"offline invalidation unhides historical matches");
        SendPickerModel ordinary=model();SendPickerModel.Item one=ordinary.items.get(0);ordinary.hide(true);ordinary.verify(one,verified("same","same.md"),receipt("same.md"));check(one.state.equals("same")&&!one.selected,"exact approved target receipt can hide and deselect");
        ordinary.invalidate();ordinary.verify(one,verified("same","renamed.md"),receipt("renamed.md"));check(one.state.equals("elsewhere"),"numbered saved name is separate other-path evidence");check(ordinary.visible("","all").size()==5,"renamed saved item remains visible");
        ordinary.invalidate();ordinary.verify(one,verified("missing","renamed.md"),receipt("renamed.md"));check(one.state.equals("unknown"),"missing renamed historical file cannot prove desired path missing");
        ordinary.verify(one,verified("same","same.md").put("prefix","other"),receipt("same.md"));check(one.state.equals("unknown"),"verify prefix mismatch rejected");
        ordinary.verify(one,verified("same","same.md").put("targetId","different"),receipt("same.md"));check(one.state.equals("unknown"),"verify target ID mismatch rejected");
        ordinary.verify(one,verified("same","same.md").put("sha256",hash("B")),receipt("same.md"));check(one.state.equals("unknown"),"old receipt content cannot verify changed source");
        ordinary.verify(one,verified("same","same.md").put("size",2),receipt("same.md"));check(one.state.equals("unknown"),"verify size mismatch rejected");
        ordinary.verify(one,verified("same","same.md"),receipt("same.md").put("saved","different.md"));check(one.state.equals("unknown"),"actual saved path must match approved receipt");
        reject(()->ordinary.verify(one,verified("same","same.md"),new JSONObject()),"legacy receipt missing target remains unconfirmed");
        ordinary.verify(one,verified("same","same.md"),receipt("same.md"));SendPickerModel.Item two=ordinary.items.get(1);JSONObject different=receipt("changed.md").put("targetId","second-root");ordinary.verify(two,verified("same","changed.md").put("targetId","second-root"),different);check(!two.state.equals("same"),"one page cannot combine confirmations from different target roots");
        check(!SendPickerModel.label("history").equals(SendPickerModel.label("same")),"history and fresh confirmation remain distinct labels");
        ordinary.fingerprint(one,1,hash("B"));check(!one.state.equals("same"),"changed source fingerprint invalidates exact confirmation");check(ordinary.visible("","all").contains(one),"changed source becomes visible while hide enabled");
        JSONObject local=file("one.md","A");TransferClient.receipt(local,new JSONObject().put("saved","one.md"));check(!local.has("targetId"),"legacy success receipt does not invent a target identity");
        JSONObject success=receipt("one.md").put("completedAt",4567);TransferClient.receipt(local,success);check(local.getString("targetId").equals("files-target")&&local.getLong("completedAt")==4567,"success receipt records exact bound target and completion time");
        reject(()->TransferClient.receipt(local,new JSONObject(success.toString()).put("sha256",hash("B"))),"mismatched success hash cannot become a target receipt");
        reject(()->TransferClient.receipt(local,new JSONObject(success.toString()).put("size",2)),"mismatched success size cannot become a target receipt");
        reject(()->TransferClient.receipt(local,new JSONObject(success.toString()).put("completedAt",0)),"untimed success receipt remains unconfirmed");
        System.out.println("Send picker model checks passed: "+passed);
    }
}
