using System;
using System.Collections.Generic;
using System.IO;
using System.Net;
using System.Security.Cryptography;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

namespace VaultBridge {
    internal sealed class FeedbackContext {public string version,platform;}
    internal sealed class FeedbackDraft {
        public int schema=1;
        public string clientRequestId=Guid.NewGuid().ToString("N"),type="suggestion",title="功能建议",body="",contact="",fingerprint="",receipt="",id="";
        public FeedbackContext contextOptional;
        public long receivedAt;
        internal string Payload(){return Json.Encode(new{schema=schema,clientRequestId=clientRequestId,type=type,title=title,body=body,contact=contact??"",contextOptional=contextOptional});}
    }
    internal sealed class FeedbackReceipt {internal string id,receipt;internal long receivedAt;}
    internal sealed class FeedbackConflictException:IOException {internal FeedbackConflictException():base("该提交编号已有不同内容；原记录保留，需使用新草稿编号再提交。"){} }
    internal static class FeedbackRules {
        internal const int MaxRequest=16*1024,MaxResponse=64*1024;
        internal static Uri Endpoint(string text){
            Uri uri;if(String.IsNullOrWhiteSpace(text))throw new IOException("建议服务尚未配置，草稿已保留，未发送。");
            if(text!=text.Trim()||text.IndexOf('\\')>=0||!Uri.TryCreate(text,UriKind.Absolute,out uri)||uri.Scheme!="https"||!uri.IsDefaultPort||uri.UserInfo.Length!=0||uri.Query.Length!=0||uri.Fragment.Length!=0||!uri.AbsolutePath.EndsWith("/wp-json/vaultbridge-feedback/v1/submit",StringComparison.Ordinal)||uri.Host.Length==0)throw new IOException("使用有效的 HTTPS 反馈提交地址（以 /wp-json/vaultbridge-feedback/v1/submit 结尾），不能包含凭据、查询或片段。");
            return uri;
        }
        internal static byte[] Payload(FeedbackDraft draft){
            if(draft==null||draft.schema!=1||draft.clientRequestId==null||!Regex.IsMatch(draft.clientRequestId,"\\A[0-9a-f]{32}\\z")||!(draft.type=="suggestion"||draft.type=="bug"||draft.type=="other")||String.IsNullOrWhiteSpace(draft.title)||draft.title.Length>120||String.IsNullOrWhiteSpace(draft.body)||draft.body.Length>4000||(draft.contact??"").Length>200)throw new IOException("填写标题（最多120字）和正文（最多4000字），联系方式最多200字。");
            if(draft.contextOptional!=null&&(draft.contextOptional.platform!="windows"||String.IsNullOrEmpty(draft.contextOptional.version)||draft.contextOptional.version.Length>32))throw new IOException("反馈版本信息无效。");
            // The optional object is completely omitted when the user did not opt in.
            Dictionary<string,object> data=new Dictionary<string,object>{{"schema",1},{"clientRequestId",draft.clientRequestId},{"type",draft.type},{"title",draft.title},{"body",draft.body}};
            if(!String.IsNullOrEmpty(draft.contact))data.Add("contact",draft.contact);if(draft.contextOptional!=null)data.Add("contextOptional",draft.contextOptional);
            byte[] bytes;try{bytes=new UTF8Encoding(false,true).GetBytes(Json.Encode(data));}catch(EncoderFallbackException ex){throw new IOException("正文包含无效字符，请检查后再提交。",ex);}if(bytes.Length>MaxRequest)throw new IOException("反馈编码后超过16 KiB，请缩短正文；草稿保留。");return bytes;
        }
        internal static FeedbackReceipt Receipt(byte[] bytes,int status,string expectedId){
            if(status==409)throw new FeedbackConflictException();
            if(status!=200&&status!=201)throw new IOException(status==429?"提交过于频繁，请稍后重试。草稿保留。":status==503?"服务尚未确认保存，请稍后使用同一草稿重试。":"建议服务未确认保存（HTTP "+status+"），草稿保留。");
            if(bytes==null||bytes.Length>MaxResponse)throw new IOException("反馈回执超过限制，未确认送达。");
            try {Dictionary<string,object> data=Json.Decode<Dictionary<string,object>>(new UTF8Encoding(false,true).GetString(bytes));object ok,schema,request,id,receipt,received;
                if(data==null||!data.TryGetValue("ok",out ok)||!(ok is bool)||!(bool)ok||!data.TryGetValue("schema",out schema)||Integer(schema)!=1||!data.TryGetValue("clientRequestId",out request)||!String.Equals(request as string,expectedId,StringComparison.Ordinal)||!data.TryGetValue("id",out id)||!(id is string)||!Regex.IsMatch((string)id,"\\A[1-9][0-9]{0,18}\\z")||!data.TryGetValue("receipt",out receipt)||!String.Equals(receipt as string,"VB-"+(string)id,StringComparison.Ordinal)||!data.TryGetValue("receivedAt",out received)||Integer(received)<=0)throw new IOException("服务未返回匹配的完整收件回执，未确认送达。");
                long number;if(!Int64.TryParse((string)id,out number)||number<=0)throw new IOException("收件编号无效，未确认送达。");return new FeedbackReceipt{id=(string)id,receipt=(string)receipt,receivedAt=Integer(received)};
            }catch(IOException){throw;}catch(Exception ex){if(ex is OutOfMemoryException)throw;throw new IOException("反馈回执格式无效，未确认送达。",ex);}
        }
        static long Integer(object value){return value is int?(int)value:value is long?(long)value:-1;}
    }
    internal static class FeedbackClient {
        // The author supplies a verified production address in a later reviewed build.
        // Public users never need to configure a server or possess author credentials.
        internal const string DefaultEndpoint="";
        internal static FeedbackReceipt Send(string endpoint,FeedbackDraft draft,CancellationToken token){
            Uri target=FeedbackRules.Endpoint(endpoint);byte[] payload=FeedbackRules.Payload(draft);
            using(CancellationTokenSource budget=CancellationTokenSource.CreateLinkedTokenSource(token)){budget.CancelAfter(30000);CancellationToken active=budget.Token;
                HttpWebRequest request=(HttpWebRequest)WebRequest.Create(target);request.Method="POST";request.AllowAutoRedirect=false;request.Timeout=15000;request.ReadWriteTimeout=15000;request.ContentType="application/json; charset=utf-8";request.Accept="application/json";request.UserAgent="VaultBridge-Feedback";request.UseDefaultCredentials=false;request.Credentials=null;request.ContentLength=payload.Length;
                using(active.Register(request.Abort)){try {active.ThrowIfCancellationRequested();using(Stream output=request.GetRequestStream()){output.Write(payload,0,payload.Length);}
                    HttpWebResponse response;try{response=(HttpWebResponse)request.GetResponse();}catch(WebException ex){response=ex.Response as HttpWebResponse;if(response==null)throw;}
                    using(response){int status=(int)response.StatusCode;if(status!=200&&status!=201)return FeedbackRules.Receipt(null,status,draft.clientRequestId);if(response.ContentLength>FeedbackRules.MaxResponse)throw new IOException("反馈回执超过限制，未确认送达。");using(Stream input=response.GetResponseStream()){byte[] bytes=UpdateRules.ReadBounded(input,FeedbackRules.MaxResponse,active);active.ThrowIfCancellationRequested();return FeedbackRules.Receipt(bytes,status,draft.clientRequestId);}}
                }catch(WebException ex){token.ThrowIfCancellationRequested();throw new IOException(budget.IsCancellationRequested?"提交超时，送达状态未知。草稿保留，可用相同编号重试。":"无法连接建议服务，未确认送达。草稿保留，可稍后重试。",ex);}catch(OperationCanceledException){token.ThrowIfCancellationRequested();throw new IOException("提交超时，送达状态未知。草稿保留。");}}
            }
        }
    }
    internal sealed class FeedbackDraftStore {
        readonly string folder;readonly object gate=new object();
        internal FeedbackDraftStore(string path){folder=path;}
        internal void Save(FeedbackDraft draft){lock(gate){if(draft==null||!Regex.IsMatch(draft.clientRequestId??"","\\A[0-9a-f]{32}\\z"))throw new IOException("草稿编号无效。");Directory.CreateDirectory(folder);string name=Path.Combine(folder,draft.clientRequestId+".protected");byte[] data=ProtectedData.Protect(Encoding.UTF8.GetBytes(Json.Encode(draft)),null,DataProtectionScope.CurrentUser);Atomic(name,data);Atomic(Path.Combine(folder,"current.protected"),ProtectedData.Protect(Encoding.UTF8.GetBytes(draft.clientRequestId),null,DataProtectionScope.CurrentUser));}}
        void Atomic(string file,byte[] bytes){string temporary=file+".writing-"+Guid.NewGuid().ToString("N");using(FileStream output=new FileStream(temporary,FileMode.CreateNew,FileAccess.Write)){output.Write(bytes,0,bytes.Length);output.Flush(true);}if(File.Exists(file))File.Replace(temporary,file,file+".previous-"+Guid.NewGuid().ToString("N"),true);else File.Move(temporary,file);}
        internal FeedbackDraft Load(){lock(gate){string pointer=Path.Combine(folder,"current.protected");if(!File.Exists(pointer))return new FeedbackDraft();if(new FileInfo(pointer).Length>8192)throw new IOException("草稿索引过大，原文件保留。");string id=Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(pointer),null,DataProtectionScope.CurrentUser));if(!Regex.IsMatch(id,"\\A[0-9a-f]{32}\\z"))throw new IOException("草稿索引无效，原文件保留。");string path=Path.Combine(folder,id+".protected");if(new FileInfo(path).Length>128*1024)throw new IOException("草稿过大，原文件保留。");FeedbackDraft draft=Json.Decode<FeedbackDraft>(Encoding.UTF8.GetString(ProtectedData.Unprotect(File.ReadAllBytes(path),null,DataProtectionScope.CurrentUser)));if(draft==null||draft.clientRequestId!=id)throw new IOException("草稿内容与索引不一致，原文件保留。");return draft;}}
    }
}
