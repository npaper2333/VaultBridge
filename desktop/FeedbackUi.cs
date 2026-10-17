using System;
using System.Diagnostics;
using System.Windows.Forms;

namespace VaultBridge {
    partial class MainForm {
        internal const string FeedbackIssueUrl="https://github.com/npaper2333/VaultBridge/issues/new";
        internal const string FeedbackIssueDisclosure="提交建议需要登录 GitHub；Issue 内容公开，其他人可以查看。请勿填写密码、配对文字或其他隐私。";
        Label feedbackServiceStatus,feedbackPrivacyNotice;
        Button feedbackIssue,feedbackCopyLink,feedbackWeChat;

        void BuildFeedbackSection(TableLayoutPanel settings){
            TableLayoutPanel section=Section(settings,"联系与建议");
            Add(section,Caption("作者微信 · nitro1447",11,true));
            feedbackPrivacyNotice=Caption(FeedbackIssueDisclosure,10,false);
            feedbackPrivacyNotice.Tag="notice";Add(section,feedbackPrivacyNotice);
            feedbackIssue=ActionButton("到 GitHub 提交建议",delegate{
                string result;TryOpenFeedbackIssue(previewMode,info=>Process.Start(info),out result);feedbackServiceStatus.Text=result;
            },false);
            feedbackCopyLink=ActionButton("复制 Issue 链接",delegate{CopyFeedbackValue(FeedbackIssueUrl,"Issue 链接");},false);
            feedbackWeChat=ActionButton("复制微信号",delegate{CopyFeedbackValue("nitro1447","微信号");},false);
            Buttons(section,feedbackIssue,feedbackCopyLink,feedbackWeChat);
            feedbackServiceStatus=Caption("点击后使用系统默认浏览器打开；应用不会附加旧草稿、笔记、目录或设备信息。",10,false);
            Add(section,feedbackServiceStatus);
        }

        internal static bool TryOpenFeedbackIssue(bool preview,Action<ProcessStartInfo> launch,out string result){
            if(preview){result="预览模式：未启动浏览器。正式使用时会打开 GitHub 新 Issue 页。";return false;}
            try{
                launch(new ProcessStartInfo(FeedbackIssueUrl){UseShellExecute=true});
                result="已请求默认浏览器打开 GitHub；登录后可填写公开 Issue。";return true;
            }catch{
                result="无法启动浏览器，请点击“复制 Issue 链接”后自行打开。";return false;
            }
        }

        void CopyFeedbackValue(string value,string label){
            if(previewMode){feedbackServiceStatus.Text="预览模式：未改动剪贴板。";return;}
            try{Clipboard.SetText(value);feedbackServiceStatus.Text="已复制"+label+(label=="微信号"?"，可备注“VaultBridge 反馈”。":"，请在浏览器中打开。Issue 内容公开。");}
            catch{feedbackServiceStatus.Text="复制未完成，"+label+"："+value;}
        }
    }
}