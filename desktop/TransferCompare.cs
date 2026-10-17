using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Text;

namespace VaultBridge {
    public sealed class TransferReceipt {
        public string targetId, vaultId, saved, sha256;
        public long size, completedAt;
    }
    public sealed partial class TransferHub {
        static long Now(){return DateTimeOffset.UtcNow.ToUnixTimeMilliseconds();}
        static string RootKey(string root){return Path.GetFullPath(root).TrimEnd(Path.DirectorySeparatorChar,Path.AltDirectorySeparatorChar).ToUpperInvariant();}
        internal static string TargetIdentity(string mode,string root,string prefix,string vaultId){
            string relative=String.IsNullOrEmpty(prefix)?"":Paths.Normalize(prefix);
            return Crypto.Hash(new MemoryStream(Encoding.UTF8.GetBytes((vaultId??"")+"|"+mode+"|"+RootKey(root)+"|"+relative.ToUpperInvariant())));
        }
        internal object Compare(Dictionary<string,object> request){
            if(Wire.Text(request,"mode")!="notes")throw new IOException("普通文件仅可核验本人已批准任务，不能扫描电脑收件箱");
            string prefix=Wire.Text(request,"prefix");if(prefix!="")prefix=Paths.Normalize(prefix);
            string root=Path.GetFullPath(settings.vault),vaultId=settings.vaultId;string[] scopes=settings.scopes.ToArray();
            TransferRules.Source(root);if(prefix!=""){string walk="";foreach(string part in prefix.Split('/')){walk=walk.Length==0?part:walk+"/"+part;if(File.Exists(Paths.Resolve(root,walk)))throw new IOException("回传子目录被同名文件占用，请更换位置");}}
            VaultManifest manifest;try{manifest=new VaultIndex(root,vaultId,scopes).Scan(new[]{""});}catch(UnauthorizedAccessException){throw new IOException("无法读取电脑授权范围，未完成对比");}
            if(RootKey(settings.vault)!=RootKey(root)||settings.vaultId!=vaultId||!settings.scopes.SequenceEqual(scopes))throw new IOException("电脑库或授权范围已变化，请重新对比");
            // Index warnings may contain an OS exception path or the name of a skipped,
            // unshared root entry. A comparison does not grant permission to reveal either.
            string[] warnings=manifest.warnings.Count==0?new string[0]:new[]{"扫描有条目未纳入对比（不可读取、链接或超过限制）；不能据此证明整个库缺少内容。"};
            return new{ok=true,vaultId=vaultId,targetId=TargetIdentity("notes",root,prefix,vaultId),mode="notes",prefix=prefix,scopes=scopes,complete=scopes.Contains("")&&warnings.Length==0,warnings=warnings,files=manifest.files,checkedAt=Now()};
        }
        TransferReceipt Receipt(TransferJob job,int file){TransferReceipt receipt;return job.receipts!=null&&job.receipts.TryGetValue(file.ToString(),out receipt)?receipt:null;}
        void RecordCompleted(TransferJob job,int file,string saved){
            if(job.receipts==null)job.receipts=new Dictionary<string,TransferReceipt>();string key=file.ToString();TransferReceipt previous=Receipt(job,file);FileItem content=job.files[file];
            job.saved[key]=saved;job.receipts[key]=new TransferReceipt{saved=saved,sha256=content.sha256,size=content.size,completedAt=Now(),targetId=job.targetId,vaultId=job.vaultId};
            try{SaveJob(job);}catch{job.saved.Remove(key);if(previous==null)job.receipts.Remove(key);else job.receipts[key]=previous;throw;}
        }
        object ReceiptReply(TransferJob job,int file,string saved){TransferReceipt receipt=Receipt(job,file);FileItem content=job.files[file];return new{ok=true,saved=saved,targetId=receipt==null?"":receipt.targetId??"",vaultId=receipt==null?"":receipt.vaultId??"",sha256=content.sha256,size=content.size,completedAt=receipt==null?0:receipt.completedAt};}
        object Status(TransferJob job,int file){
            string saved;bool done=job.saved.TryGetValue(file.ToString(),out saved);TransferReceipt receipt=done?Receipt(job,file):null;FileItem content=job.files[file];
            return new{ok=true,state=job.state,done=done,saved=done?saved:"",chunks=Blocks(job,file,true),targetId=receipt==null?(job.targetId??""):(receipt.targetId??""),vaultId=receipt==null?(job.vaultId??""):(receipt.vaultId??""),sha256=content.sha256,size=content.size,completedAt=receipt==null?0:receipt.completedAt};
        }
        internal object Verify(TransferJob job,int file,Dictionary<string,object> request){
            Approved(job);string saved;TransferReceipt receipt=Receipt(job,file);FileItem expected=job.files[file];
            if(!job.saved.TryGetValue(file.ToString(),out saved)||receipt==null||String.IsNullOrEmpty(job.targetId)||String.IsNullOrEmpty(receipt.targetId)||receipt.completedAt<=0)
                return new{ok=true,result="unknown",reason="legacy-receipt",targetId="",vaultId="",mode=job.mode,prefix=job.prefix,saved=saved??"",sha256=expected.sha256,size=expected.size,completedAt=0,checkedAt=Now()};
            string currentRoot=job.mode=="notes"?settings.vault:settings.inbox;
            if(String.IsNullOrWhiteSpace(currentRoot)||RootKey(currentRoot)!=RootKey(job.root)||settings.vaultId!=job.vaultId||job.targetId!=TargetIdentity(job.mode,currentRoot,job.prefix,settings.vaultId)||receipt.targetId!=job.targetId||receipt.vaultId!=job.vaultId)
                throw new IOException("电脑接收位置或笔记库已变化，旧回执不能确认当前目标");
            object supplied; if(request.TryGetValue("targetId",out supplied)&&!Crypto.Equal(supplied as string,job.targetId))throw new IOException("目标身份不匹配，请重新核验");
            if(receipt.saved!=saved||receipt.sha256!=expected.sha256||receipt.size!=expected.size)throw new IOException("完成回执与原任务不一致，无法核验");
            string target=Destination(job,job.root,saved);string result;
            try {FileAttributes attributes=File.GetAttributes(target);if((attributes&FileAttributes.Directory)!=0)result="changed";else{TransferRules.Source(target);FileInfo info=new FileInfo(target);result=info.Length==expected.size&&Crypto.Equal(Crypto.HashFile(target),expected.sha256)?"same":"changed";}}
            catch(FileNotFoundException){result="missing";}catch(DirectoryNotFoundException){result="missing";}catch(UnauthorizedAccessException){result="unknown";}catch(IOException){result="unknown";}
            Approved(job);if(RootKey(currentRoot)!=RootKey(job.root)||RootKey(job.mode=="notes"?settings.vault:settings.inbox)!=RootKey(job.root)||settings.vaultId!=job.vaultId)throw new IOException("核验期间接收位置变化，请重试");Destination(job,job.root,saved);
            return new{ok=true,result=result,targetId=job.targetId,vaultId=job.vaultId,mode=job.mode,prefix=job.prefix,saved=saved,sha256=expected.sha256,size=expected.size,completedAt=receipt.completedAt,checkedAt=Now()};
        }
    }
}
