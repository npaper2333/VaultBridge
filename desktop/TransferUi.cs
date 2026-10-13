using System;
using System.Drawing;
using System.IO;
using System.Linq;
using System.Threading.Tasks;
using System.Windows.Forms;

namespace VaultBridge {
    partial class MainForm {
        TransferHub transfers;
        Label outgoingInfo, inboxInfo;
        bool publishing;
        void InitializeTransfers(){if(!previewMode)transfers=new TransferHub(Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData),"VaultBridge","Transfers"),state,ConfirmIncoming,Log);}
        void BuildTransferPage(TableLayoutPanel page){
            Add(page,Caption("普通文件互传",16,true));Help(page,"电脑和平板双向手动传输。4 MiB 分块，单文件最多 8 GiB，一批最多 64 GiB。ZIP 原样保存，Markdown 不改写链接。");
            TableLayoutPanel send=Section(page,"电脑 → 平板");outgoingInfo=Caption("选择文件或文件夹后，在平板“普通文件”页获取清单并接收。",10,false);Add(send,outgoingInfo);
            Buttons(send,ActionButton("选择发送文件",ChooseOutgoingFiles,true),ActionButton("选择发送文件夹",ChooseOutgoingFolder,false));Help(send,"文件夹保留相对目录，隐藏配置和链接被排除。选择后先计算内容校验；重新选择会替换发送清单，原有文件保留。");
            TableLayoutPanel receive=Section(page,"平板 → 电脑");inboxInfo=Caption(String.IsNullOrEmpty(state.inbox)?"接收位置：尚未选择":state.inbox,10,false);Add(receive,inboxInfo);Buttons(receive,ActionButton("选择普通文件收件箱",ChooseInbox,false),ActionButton("接收任务 / 撤销本批",ManageTransfers,false));Help(receive,"接收位置独立于笔记库。平板发送一批文件时，电脑显示接收确认；已配对不代表可以直接写入。");
            TableLayoutPanel notes=Section(page,"平板笔记回传");Help(notes,"平板在“电脑同步”页选笔记传回当前电脑 Obsidian 主库。默认根目录，可填库内子目录；电脑显示位置与文件列表，再决定是否接收。");Notice(notes,"同名内容相同跳过，不同内容编号另存。不会自动合并或覆盖电脑笔记；普通文件收件箱与笔记回传分开授权。");
            Notice(page,"右上角开始分享后两端才能互传。暂停、续传和持久任务队列在平板管理；断网仅补缺失块，已完成文件不重传。接收结果显示在最近任务。");
        }
        async void PublishFiles(string[] files){
            if(publishing||previewMode)return;publishing=true;outgoingInfo.Text="正在计算文件校验与建立发送清单…";
            try{await Task.Run(()=>transfers.Publish(files));outgoingInfo.Text="发送清单已就绪；在平板获取清单、选目标目录后接收。";Log("普通文件发送清单已更新");}
            catch(Exception ex){outgoingInfo.Text="清单未更新："+ex.Message;Log(outgoingInfo.Text);}
            finally{publishing=false;}
        }
        void ChooseOutgoingFiles(object sender,EventArgs e){using(OpenFileDialog dialog=new OpenFileDialog{Title="选择普通文件",Multiselect=true,Filter="所有文件|*.*"})if(dialog.ShowDialog(this)==DialogResult.OK)PublishFiles(dialog.FileNames);}
        void ChooseOutgoingFolder(object sender,EventArgs e){using(FolderBrowserDialog dialog=new FolderBrowserDialog{Description="选择发送文件夹",ShowNewFolderButton=false})if(dialog.ShowDialog(this)==DialogResult.OK)PublishFiles(new[]{dialog.SelectedPath});}
        void ChooseInbox(object sender,EventArgs e){using(FolderBrowserDialog dialog=new FolderBrowserDialog{Description="普通文件收件箱（选择笔记库以外的目录）",SelectedPath=state.inbox})if(dialog.ShowDialog(this)==DialogResult.OK){state.inbox=dialog.SelectedPath;Save();inboxInfo.Text=state.inbox;}}
        void ManageTransfers(object sender,EventArgs e){
            if(transfers==null)return;var jobs=transfers.Snapshot();if(jobs.Count==0){MessageBox.Show(this,"还没有接收任务。");return;}
            using(Form dialog=new Form{Text="接收任务",Size=new Size(600,420),StartPosition=FormStartPosition.CenterParent,Font=Font,BackColor=palette.Canvas,ForeColor=palette.Ink}){
                ListBox list=new ScrollTaskList{Dock=DockStyle.Fill,BackColor=palette.Wash,ForeColor=palette.Ink,BorderStyle=BorderStyle.None};foreach(TransferJob job in jobs)list.Items.Add((job.mode=="notes"?"笔记":"普通文件")+" · "+(job.state=="pending"?"待确认":job.state=="rejected"?"已拒绝 / 撤销":"已允许")+" · "+job.saved.Count+"/"+job.files.Count+" 完成 · "+job.id.Substring(0,8));Button review=ActionButton("查看结果 / 撤销后续写入",async delegate{if(list.SelectedIndex<0)return;int selected=list.SelectedIndex;TransferJob job=jobs[selected];string saved=String.Join("\n",job.saved.Values.Take(12));if(MessageBox.Show(dialog,"位置："+job.root+"\n已保存：\n"+saved+"\n\n撤销本批后续写入？已保存文件保留。","接收任务",MessageBoxButtons.YesNo,MessageBoxIcon.Question,MessageBoxDefaultButton.Button2)==DialogResult.Yes){list.Items[selected]="正在撤销 · "+job.id.Substring(0,8);try{await Task.Run(()=>transfers.Revoke(job.id));if(!dialog.IsDisposed)list.Items[selected]="已撤销 · "+job.id.Substring(0,8);}catch(Exception ex){Log("撤销未完成："+ex.Message);if(!dialog.IsDisposed)MessageBox.Show(dialog,ex.Message,"撤销未完成");}}},false);review.Dock=DockStyle.Bottom;dialog.Controls.Add(new ScrollFrame(list){Dock=DockStyle.Fill});dialog.Controls.Add(review);Colorize(dialog);dialog.ShowDialog(this);
            }
        }
        void ConfirmIncoming(TransferJob job){
            if(IsDisposed||!IsHandleCreated)return;
            BeginInvoke(new Action(async delegate{
                if(IsDisposed)return;Show();if(WindowState==FormWindowState.Minimized)WindowState=FormWindowState.Normal;Activate();
                Exception failure=null;
                try{
                    string root=job.mode=="notes"?state.vault:state.inbox;if(!Directory.Exists(root)){if(job.mode=="notes")throw new IOException("尚未选择电脑 Obsidian 库，请拒绝后先选择主库");ChooseInbox(null,EventArgs.Empty);root=state.inbox;}
                    string destination=String.IsNullOrEmpty(job.prefix)?root:Paths.Resolve(root,job.prefix);
                    string list=String.Join("\n",job.files.Take(8).Select(f=>f.path+"  ("+(f.size/1048576.0).ToString("0.##")+" MiB)"));if(job.files.Count>8)list+="\n…另外 "+(job.files.Count-8)+" 个文件";
                    string detail=(job.mode=="notes"?"平板请求将所选笔记传回电脑主库。":"平板请求发送普通文件。")+"\n\n保存到："+destination+"\n共 "+job.files.Count+" 个文件 · "+(job.files.Sum(f=>f.size)/1048576.0).ToString("0.##")+" MiB\n\n"+list+"\n\n相同内容跳过；同名不同内容编号另存。ZIP 原样保存。\n允许本批接收？";
                    bool accepted=MessageBox.Show(this,detail,"确认接收",MessageBoxButtons.YesNo,MessageBoxIcon.Question,MessageBoxDefaultButton.Button2)==DialogResult.Yes;
                    await Task.Run(()=>transfers.Approve(job.id,root,accepted));Log(accepted?"本批接收已批准":"已拒绝本批接收");
                }catch(Exception ex){failure=ex;}
                if(failure!=null){try{await Task.Run(()=>transfers.Approve(job.id,"",false));}catch(Exception){}if(!IsDisposed)MessageBox.Show(this,failure.Message,"接收未批准");}
            }));
        }
    }
}
