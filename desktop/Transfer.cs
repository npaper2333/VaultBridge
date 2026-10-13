using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;

namespace VaultBridge {
    public class TransferOffer {
        public string id = Guid.NewGuid().ToString("N");
        public List<FileItem> files = new List<FileItem>();
        public List<string> warnings = new List<string>();
        public Dictionary<string,string> sources = new Dictionary<string,string>();
    }
    public class TransferJob {
        public string id, owner, mode, prefix, root, state = "pending";
        [System.Web.Script.Serialization.ScriptIgnore] public volatile bool revokeRequested;
        public List<FileItem> files = new List<FileItem>();
        public Dictionary<string,string> saved = new Dictionary<string,string>();
    }
    public static class TransferRules {
        public const int Chunk = 4 * 1024 * 1024;
        public const long MaxFile = 8L * 1024 * 1024 * 1024;
        public const long MaxBatch = 64L * 1024 * 1024 * 1024;
        public static bool Hash(string hash) { return hash != null && Regex.IsMatch(hash,"\\A[0-9a-f]{64}\\z"); }
        public static int Count(long size) { if(size<0||size>MaxFile)throw new IOException("单文件上限为 8 GiB");return (int)((size+Chunk-1)/Chunk); }
        public static int Length(long size,int index) { if(index<0||index>=Count(size))throw new IOException("分块编号错误");return (int)Math.Min(Chunk,size-(long)index*Chunk); }
        public static void Validate(List<FileItem> files) {
            if(files==null||files.Count==0||files.Count>Paths.MaxFiles)throw new IOException("选择 1–20,000 个文件");
            HashSet<string> names=new HashSet<string>(StringComparer.OrdinalIgnoreCase);long total=0;
            foreach(FileItem f in files){f.path=Paths.Normalize(f.path);Count(f.size);if(!Hash(f.sha256)||!names.Add(f.path))throw new IOException("清单哈希无效或文件重名");total=checked(total+f.size);if(total>MaxBatch)throw new IOException("一批最多 64 GiB，请分批");}
        }
        public static string Owner(string token){return Crypto.Hash(new MemoryStream(Encoding.UTF8.GetBytes(token)));}
        public static void Source(string file){string current=Path.GetFullPath(file);while(!String.IsNullOrEmpty(current)){if((File.GetAttributes(current)&FileAttributes.ReparsePoint)!=0)throw new IOException("来源路径包含链接，请重新选择真实目录");string parent=Path.GetDirectoryName(current);if(parent==current)break;current=parent;}}
        public static void Space(string root,long bytes){string volume=Path.GetPathRoot(Path.GetFullPath(root));if(new DriveInfo(volume).AvailableFreeSpace<bytes+64L*1024*1024)throw new IOException("空间不足：需要为分块和最终文件预留空间");}
        public static string Alternate(string relative,int number) {
            string folder=Path.GetDirectoryName(relative.Replace('/',Path.DirectorySeparatorChar));string ext=Path.GetExtension(relative),stem=Path.GetFileNameWithoutExtension(relative);
            if(stem.Length>150)stem=stem.Substring(0,150);if(ext.Length>60)ext=ext.Substring(0,60);
            return Paths.Normalize((String.IsNullOrEmpty(folder)?"":folder.Replace('\\','/')+"/")+stem+" (VaultBridge "+number+")"+ext);
        }
    }
    // All incoming writes are explicitly approved; chunk files are immutable and retained.
    public sealed class TransferHub {
        readonly string directory;
        readonly Settings settings;
        readonly Action<TransferJob> confirm;
        readonly Action<string> log;
        readonly object gate=new object();
        TransferOffer offer;
        readonly Dictionary<string,TransferJob> jobs=new Dictionary<string,TransferJob>();
        readonly Dictionary<string,string> views=new Dictionary<string,string>();
        readonly HashSet<string> prompted=new HashSet<string>();
        readonly Dictionary<string,FileItem> notes=new Dictionary<string,FileItem>();
        public TransferHub(string folder,Settings state,Action<TransferJob> approval,Action<string> logger){
            directory=folder;settings=state;confirm=approval;log=logger;Directory.CreateDirectory(directory);
            if((File.GetAttributes(directory)&FileAttributes.ReparsePoint)!=0)throw new IOException("传输暂存目录不能是链接");
            string outFile=Path.Combine(directory,"outgoing.json");if(File.Exists(outFile))try{offer=Json.Decode<TransferOffer>(File.ReadAllText(outFile));TransferRules.Validate(offer.files);}catch(Exception){log("上次发送清单无法恢复，请重新选文件");}
            foreach(string sub in Directory.EnumerateDirectories(directory)){
                if(!Regex.IsMatch(Path.GetFileName(sub),"\\A[0-9a-f]{32}\\z")||(File.GetAttributes(sub)&FileAttributes.ReparsePoint)!=0)continue;
                try{TransferJob job=Json.Decode<TransferJob>(File.ReadAllText(Path.Combine(sub,"job.json")));TransferRules.Validate(job.files);if(job.id!=Path.GetFileName(sub))continue;jobs[job.id]=job;views[job.id]=Json.Encode(job);}catch(Exception){log("一份传输记录无法读取，保留暂存供检查");}
            }
        }
        static void Save(string path,object value){
            string temp=path+".writing-"+Guid.NewGuid().ToString("N");using(FileStream output=new FileStream(temp,FileMode.CreateNew,FileAccess.Write)){byte[] data=Encoding.UTF8.GetBytes(Json.Encode(value));output.Write(data,0,data.Length);output.Flush(true);}
            if(File.Exists(path))File.Replace(temp,path,path+".previous-"+Guid.NewGuid().ToString("N"),true);else File.Move(temp,path);
        }
        string Folder(TransferJob job){return Path.Combine(directory,job.id);}
        void SaveJob(TransferJob job){Save(Path.Combine(Folder(job),"job.json"),job);string snapshot=Json.Encode(job);lock(gate)views[job.id]=snapshot;}
        public void Publish(IEnumerable<string> selected){
            TransferOffer next=new TransferOffer();
            foreach(string raw in selected){string absolute=Path.GetFullPath(raw);if(Directory.Exists(absolute))Walk(next,absolute,new DirectoryInfo(absolute).Name);else Add(next,absolute,Path.GetFileName(absolute));}
            TransferRules.Validate(next.files);lock(gate){Save(Path.Combine(directory,"outgoing.json"),next);offer=next;}
        }
        void Walk(TransferOffer next,string folder,string prefix){
            Paths.Normalize(prefix);if((File.GetAttributes(folder)&FileAttributes.ReparsePoint)!=0)throw new IOException("不发送链接目录");
            foreach(string entry in Directory.EnumerateFileSystemEntries(folder)){string relative=prefix+"/"+Path.GetFileName(entry);try{Paths.Normalize(relative);if((File.GetAttributes(entry)&FileAttributes.ReparsePoint)!=0)throw new IOException("链接被排除");if(Directory.Exists(entry))Walk(next,entry,relative);else Add(next,entry,relative);}catch(IOException ex){next.warnings.Add(relative+"："+ex.Message);}}
        }
        void Add(TransferOffer next,string file,string relative){
            relative=Paths.Normalize(relative);TransferRules.Source(file);FileInfo info=new FileInfo(file);TransferRules.Count(info.Length);
            if(next.files.Count>=Paths.MaxFiles||next.sources.ContainsKey(relative))throw new IOException("文件过多或同名，请分批");
            using(FileStream input=File.Open(file,FileMode.Open,FileAccess.Read,FileShare.Read))next.files.Add(new FileItem{path=relative,size=input.Length,sha256=Crypto.Hash(input),modified=info.LastWriteTimeUtc.Ticks});next.sources.Add(relative,file);
        }
        public object Listing(){lock(gate){if(offer==null)throw new IOException("电脑尚未选择普通文件");return new {ok=true,id=offer.id,files=offer.files,warnings=offer.warnings,chunk=TransferRules.Chunk,maxFile=TransferRules.MaxFile,vaultName=Directory.Exists(settings.vault)?new DirectoryInfo(settings.vault).Name:"未选择主库"};}}
        public List<TransferJob> Snapshot(){List<string> snapshots;lock(gate)snapshots=views.Values.ToList();return snapshots.Select(Json.Decode<TransferJob>).ToList();}
        public void Revoke(string id){TransferJob job;lock(gate)job=jobs[id];job.revokeRequested=true;lock(job){job.state="rejected";SaveJob(job);}}
        public void RememberNotes(VaultManifest manifest){Dictionary<string,FileItem> prepared=new Dictionary<string,FileItem>();VaultIndex index=new VaultIndex(settings.vault,settings.vaultId,settings.scopes);foreach(FileItem f in manifest.files){string source=index.FilePath(f.path);prepared[f.path]=new FileItem{path=f.path,size=f.size,sha256=f.sha256,modified=new FileInfo(source).LastWriteTimeUtc.Ticks};}lock(gate){notes.Clear();foreach(var entry in prepared)notes.Add(entry.Key,entry.Value);}}
        void Prompt(TransferJob job){bool prompt=false;lock(gate)if(job.state=="pending"&&confirm!=null)prompt=prompted.Add(job.id);if(prompt)confirm(Json.Decode<TransferJob>(Json.Encode(job)));}
        TransferJob Job(string id,string token){TransferJob job;lock(gate)if(!jobs.TryGetValue(id,out job)||!Crypto.Equal(job.owner,TransferRules.Owner(token)))throw new IOException("任务不存在或属于另一设备");return job;}
        string Destination(TransferJob job,string root,string relative){
            string full=Path.GetFullPath(root);if(!Directory.Exists(full))throw new IOException("接收目录不存在");TransferRules.Source(full);
            string target=Paths.Resolve(full,relative),vaultRoot=String.IsNullOrEmpty(settings.vault)?null:Path.GetFullPath(settings.vault).TrimEnd(Path.DirectorySeparatorChar);
            if(job.mode=="notes"){if(vaultRoot==null||!full.TrimEnd(Path.DirectorySeparatorChar).Equals(vaultRoot,StringComparison.OrdinalIgnoreCase))throw new IOException("笔记回传仅允许当前主库，请重新建立任务");}
            else if(vaultRoot!=null&&(target.Equals(vaultRoot,StringComparison.OrdinalIgnoreCase)||target.StartsWith(vaultRoot+Path.DirectorySeparatorChar,StringComparison.OrdinalIgnoreCase)))throw new IOException("普通文件不能写入笔记库，请使用笔记回传入口");
            return target;
        }
        static void Approved(TransferJob job){if(job.state!="approved"||job.revokeRequested)throw new IOException(job.state=="rejected"||job.revokeRequested?"电脑拒绝或撤销了本批接收":"等待电脑确认接收");}
        public void Approve(string id,string root,bool accepted){TransferJob job;lock(gate)job=jobs[id];lock(job){if(job.state!="pending"||job.revokeRequested)return;if(!accepted){job.state="rejected";SaveJob(job);return;}
            string full=Path.GetFullPath(root);foreach(FileItem f in job.files)Destination(job,full,Join(job.prefix,f.path));
            long total=job.files.Sum(f=>f.size);bool sameVolume=Path.GetPathRoot(Path.GetFullPath(directory)).Equals(Path.GetPathRoot(full),StringComparison.OrdinalIgnoreCase);TransferRules.Space(directory,total*(sameVolume?3:2));TransferRules.Space(full,total);job.root=full;job.state="approved";SaveJob(job);log("已允许一批"+(job.mode=="notes"?"笔记回传":"普通文件接收")+"，同名内容变化将另存");}}
        static string Join(string prefix,string path){return String.IsNullOrEmpty(prefix)?path:prefix+"/"+path;}
        public bool Handle(string action,Dictionary<string,object> request,Stream stream,string token){
            if(!action.StartsWith("x-",StringComparison.Ordinal))return false;
                if(action=="x-list"){Wire.Write(stream,Listing());return true;}
                if(action=="x-note-get"){
                    string relative=Paths.Normalize(Wire.Text(request,"path"));FileItem item;lock(gate)if(!notes.TryGetValue(relative,out item)||!Crypto.Equal(item.sha256,Wire.Text(request,"sha256")))throw new IOException("笔记清单变化，请重新获取");
                    VaultIndex index=new VaultIndex(settings.vault,settings.vaultId,settings.scopes);string source=index.FilePath(relative);FileInfo info=new FileInfo(source);if(!info.Exists||info.Length!=item.size||info.LastWriteTimeUtc.Ticks!=item.modified)throw new IOException("电脑笔记变化，请刷新清单");
                    int length=TransferRules.Length(item.size,Number(request,"block"));using(FileStream input=File.Open(source,FileMode.Open,FileAccess.Read,FileShare.Read)){input.Position=(long)Number(request,"block")*TransferRules.Chunk;byte[] data=Wire.ReadBytes(input,length);Wire.Write(stream,new{ok=true,size=length,sha256=Crypto.Hash(new MemoryStream(data))});stream.Write(data,0,data.Length);stream.Flush();}return true;
                }
                if(action=="x-get"){
                    TransferOffer outgoing;lock(gate)outgoing=offer;if(outgoing==null||outgoing.id!=Wire.Text(request,"id"))throw new IOException("发送清单已变化，请重新选择任务");
                    string relative=Paths.Normalize(Wire.Text(request,"path"));FileItem item=outgoing.files.FirstOrDefault(f=>f.path==relative);if(item==null||!Crypto.Equal(item.sha256,Wire.Text(request,"sha256")))throw new IOException("文件未在本次授权清单内");
                    string source=outgoing.sources[relative];TransferRules.Source(source);FileInfo info=new FileInfo(source);if(!info.Exists||info.Length!=item.size||info.LastWriteTimeUtc.Ticks!=item.modified)throw new IOException("来源文件变化，请电脑重新选择文件");
                    int block=Number(request,"block"),length=TransferRules.Length(item.size,block);using(FileStream input=File.Open(source,FileMode.Open,FileAccess.Read,FileShare.Read)){input.Position=(long)block*TransferRules.Chunk;byte[] data=Wire.ReadBytes(input,length);Wire.Write(stream,new {ok=true,size=length,sha256=Crypto.Hash(new MemoryStream(data))});stream.Write(data,0,data.Length);stream.Flush();}return true;
                }
                if(action=="x-request"){
                    string id=Wire.Text(request,"id");if(!Regex.IsMatch(id,"\\A[0-9a-f]{32}\\z"))throw new IOException("无效任务编号");
                    object rawFiles;if(!request.TryGetValue("files",out rawFiles))throw new IOException("缺少文件清单");List<FileItem> files=Json.Decode<List<FileItem>>(Json.Encode(rawFiles));TransferRules.Validate(files);string mode=Wire.Text(request,"mode"),prefix=Wire.Text(request,"prefix");if(mode!="files"&&mode!="notes")throw new IOException("无效工作模式");if(prefix!="")prefix=Paths.Normalize(prefix);
                    TransferJob job;lock(gate){if(!jobs.TryGetValue(id,out job)){if(jobs.Values.Count(j=>j.state=="pending")>=50)throw new IOException("待确认任务过多，请先处理已有任务");job=new TransferJob{id=id,owner=TransferRules.Owner(token),files=files,mode=mode,prefix=prefix};Directory.CreateDirectory(Folder(job));SaveJob(job);jobs.Add(id,job);}}
                    lock(job){Job(id,token);if(job.mode!=mode||job.prefix!=prefix||Json.Encode(job.files)!=Json.Encode(files))throw new IOException("任务内容变化，请创建新任务");Wire.Write(stream,new {ok=true,state=job.state});Prompt(job);}return true;
                }
                TransferJob current=Job(Wire.Text(request,"id"),token);lock(current){int fileIndex=Number(request,"file");if(fileIndex<0||fileIndex>=current.files.Count)throw new IOException("文件编号错误");FileItem file=current.files[fileIndex];
                if(action=="x-status"){Wire.Write(stream,new {ok=true,state=current.state,done=current.saved.ContainsKey(fileIndex.ToString()),saved=current.saved.ContainsKey(fileIndex.ToString())?current.saved[fileIndex.ToString()]:"",chunks=Blocks(current,fileIndex,true)});return true;}
                Approved(current);
                if(action=="x-has"){int block=Number(request,"block");int length=TransferRules.Length(file.size,block);string hash=Wire.Text(request,"sha256");if(!TransferRules.Hash(hash))throw new IOException("无效分块哈希");string local=BlockPath(current,fileIndex,block,hash);Wire.Write(stream,new {ok=true,present=File.Exists(local)&&new FileInfo(local).Length==length&&Crypto.Equal(Crypto.HashFile(local),hash)});return true;}
                if(action=="x-put"){
                    if(current.saved.ContainsKey(fileIndex.ToString()))throw new IOException("该文件已保存，请刷新状态");int block=Number(request,"block"),length=TransferRules.Length(file.size,block);string hash=Wire.Text(request,"sha256");if(!TransferRules.Hash(hash))throw new IOException("分块哈希错误");TransferRules.Space(directory,length);
                    string pending=Path.Combine(Folder(current),fileIndex+"-"+block+"-"+Guid.NewGuid().ToString("N")+".partial");Wire.Write(stream,new {ok=true,ready=true});
                    using(FileStream output=new FileStream(pending,FileMode.CreateNew,FileAccess.Write)){byte[] data=Wire.ReadBytes(stream,length);Approved(current);if(!Crypto.Equal(Crypto.Hash(new MemoryStream(data)),hash))throw new IOException("分块校验失败");output.Write(data,0,data.Length);output.Flush(true);}
                    string ready=BlockPath(current,fileIndex,block,hash);foreach(string previous in Directory.EnumerateFiles(Folder(current),fileIndex+"-"+block+"-*.block"))if(previous!=ready)File.Move(previous,Path.Combine(Folder(current),"replaced-"+Guid.NewGuid().ToString("N")+".partial"));if(File.Exists(ready)){if(Crypto.HashFile(ready)!=hash)File.Move(ready,Path.Combine(Folder(current),"corrupt-"+Guid.NewGuid().ToString("N")+".partial"));else{Wire.Write(stream,new {ok=true});return true;}}File.Move(pending,ready);Wire.Write(stream,new {ok=true});return true;
                }
                if(action=="x-commit"){
                    string saved;if(current.saved.TryGetValue(fileIndex.ToString(),out saved)){Wire.Write(stream,new {ok=true,saved=saved});return true;}
                    List<BlockRecord> blocks=Blocks(current,fileIndex,true);if(blocks.Count!=TransferRules.Count(file.size))throw new IOException("仍有缺失或损坏分块");TransferRules.Space(directory,file.size);TransferRules.Space(current.root,file.size);
                    string assembled=Path.Combine(Folder(current),"assembled-"+fileIndex+"-"+Guid.NewGuid().ToString("N")+".bin");using(FileStream output=new FileStream(assembled,FileMode.CreateNew,FileAccess.Write)){foreach(BlockRecord b in blocks.OrderBy(b=>b.index))using(FileStream input=File.OpenRead(BlockPath(current,fileIndex,b.index,b.sha256)))input.CopyTo(output);output.Flush(true);}
                    if(new FileInfo(assembled).Length!=file.size||!Crypto.Equal(Crypto.HashFile(assembled),file.sha256))throw new IOException("全文件校验失败，未写入目标");
                    Approved(current);string desired=Join(current.prefix,file.path),chosen=desired;int n=0;
                    while(File.Exists(Destination(current,current.root,chosen))||Directory.Exists(Destination(current,current.root,chosen))){string existing=Destination(current,current.root,chosen);if(File.Exists(existing)&&new FileInfo(existing).Length==file.size&&Crypto.Equal(Crypto.HashFile(existing),file.sha256))break;chosen=TransferRules.Alternate(desired,++n);if(n>10000)throw new IOException("重名文件过多");}
                    string target=Destination(current,current.root,chosen);Directory.CreateDirectory(Path.GetDirectoryName(target));target=Destination(current,current.root,chosen);
                    if(!File.Exists(target)){string landing=Path.Combine(Path.GetDirectoryName(target),".VaultBridge-"+Guid.NewGuid().ToString("N")+".partial");using(FileStream input=File.OpenRead(assembled))using(FileStream output=new FileStream(landing,FileMode.CreateNew,FileAccess.Write)){input.CopyTo(output);output.Flush(true);}if(!Crypto.Equal(Crypto.HashFile(landing),file.sha256))throw new IOException("目标暂存校验失败");Approved(current);Destination(current,current.root,chosen);File.Move(landing,target);}
                    if(!Crypto.Equal(Crypto.HashFile(target),file.sha256))throw new IOException("目标校验失败；暂存保留，可继续");current.saved[fileIndex.ToString()]=chosen;SaveJob(current);Wire.Write(stream,new {ok=true,saved=chosen});log("接收完成："+chosen);return true;
                }
                throw new IOException("不支持的传输操作");
            }
        }
        public class BlockRecord { public int index;public string sha256; }
        string BlockPath(TransferJob job,int file,int block,string hash){return Path.Combine(Folder(job),file+"-"+block+"-"+hash+".block");}
        List<BlockRecord> Blocks(TransferJob job,int index,bool verify){
            List<BlockRecord> blocks=new List<BlockRecord>();HashSet<int> seen=new HashSet<int>();
            foreach(string path in Directory.EnumerateFiles(Folder(job),index+"-*.block")){Match m=Regex.Match(Path.GetFileName(path),"\\A[0-9]+-([0-9]+)-([0-9a-f]{64})\\.block\\z");if(!m.Success)continue;int b=Int32.Parse(m.Groups[1].Value);if(b>=TransferRules.Count(job.files[index].size)||new FileInfo(path).Length!=TransferRules.Length(job.files[index].size,b))continue;string hash=m.Groups[2].Value;if(verify&&!Crypto.Equal(Crypto.HashFile(path),hash))continue;if(seen.Add(b))blocks.Add(new BlockRecord{index=b,sha256=hash});}
            return blocks;
        }
        static int Number(Dictionary<string,object> request,string key){int number;string text=Wire.Text(request,key);if(!Regex.IsMatch(text,"\\A[0-9]+\\z")||!Int32.TryParse(text,out number))throw new IOException("无效编号");return number;}
    }
}
