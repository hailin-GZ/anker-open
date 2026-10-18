package com.shengying.beantest;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.AtomicFile;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;

/** First-install provisioning without provider keys or engineering settings. */
public final class CloudSetupActivity extends Activity {
    static final String ORIGIN=BuildConfig.SERVICE_ORIGIN;
    private boolean busy;
    static boolean ready(Activity a){
        try { JSONObject c=new JSONObject(new String(Files.readAllBytes(new File(a.getFilesDir(),"cloud-bundle.json").toPath()),StandardCharsets.UTF_8));return ORIGIN.equals(c.optString("origin")); }
        catch(Exception absent){return false;}
    }
    static void apply(Activity a)throws Exception{
        JSONObject c=new JSONObject(new String(Files.readAllBytes(new File(a.getFilesDir(),"cloud-bundle.json").toPath()),StandardCharsets.UTF_8));
        for(String pair:new String[]{"backend:backend-config.json","reply:reply-config.json","analysis:analysis-config.json"}){
            String[] p=pair.split(":");JSONObject value=new JSONObject(c.getJSONObject(p[0]).toString());
            File local=new File(a.getFilesDir(),"local-audio-test.json");
            if(BuildConfig.DEBUG&&local.exists()){
                String origin=new JSONObject(new String(Files.readAllBytes(local.toPath()),StandardCharsets.UTF_8)).getString("origin");
                if(!origin.matches("http://127\\.0\\.0\\.1:[0-9]+"))throw new IOException("Local test bridge must be loopback");
                if(p[0].equals("backend"))value.put("audio_endpoint",origin+"/v1/audio/chunks");
                if(p[0].equals("reply"))value.put("gateway_url",origin).put("reply_endpoint",origin+"/v1/replies");
                if(p[0].equals("analysis"))value.put("analysis_endpoint",origin+"/v1/analysis");
            }
            atomic(new File(a.getFilesDir(),p[1]),value.toString());
        }
    }
    static void atomic(File file,String value)throws IOException{
        AtomicFile target=new AtomicFile(file);FileOutputStream out=null;
        try{out=target.startWrite();out.write(value.getBytes(StandardCharsets.UTF_8));target.finishWrite(out);}
        catch(IOException error){if(out!=null)target.failWrite(out);throw error;}
    }
    static JSONObject deviceAuthorization(Activity a){try{return new JSONObject(new String(Files.readAllBytes(new File(a.getFilesDir(),"cloud-bundle.json").toPath()),StandardCharsets.UTF_8)).optJSONObject("sdk_auth");}catch(Exception absent){return new JSONObject();}}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);UiKit k=new UiKit(this);LinearLayout content=k.col();content.setPadding(k.dp(24),k.dp(38),k.dp(24),k.dp(24));content.setBackgroundColor(k.bg);content.setFitsSystemWindows(true);
        content.addView(k.heading("欢迎使用声应",30));content.addView(k.copy("让声音被看见，让回应被听见。"));k.gap(content,22);
        content.addView(k.heading("连接你的云端服务",22));content.addView(k.copy("无需输入 App 激活码。这部手机将拥有独立的对话空间；无需电脑、USB 或模型密钥。"));
        TextView status=k.copy("需要联网、兼容的录音豆和有效的 SDK 授权。");status.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(status);
        CheckBox consent=new CheckBox(this);consent.setText("我已阅读下方说明，同意将开始录音后的声音发送到云端处理。");consent.setTextColor(k.ink);consent.setMinHeight(k.dp(56));content.addView(consent);
        Button activate=k.button("开始使用",true);content.addView(activate);
        content.addView(k.copy("按录音豆实体键后，音频传到香港服务器，由云端服务生成字幕、声音提醒、情绪估计与可选回复。对话档案保存在本机；服务端短期缓存原音频。只有点选回复才朗读。离开 App 后暂停提醒，当前不提供后台守护。"));
        content.addView(k.copy("支持 Android 8.0 及以上。蓝牙、通知和语音样本麦克风权限在需要时分别申请。此版为已授权设备试用版，声纹克隆与家人身份识别尚未提供。"));
        Button privacy=k.button("查看隐私与使用说明",false);privacy.setOnClickListener(v->startActivity(new Intent(Intent.ACTION_VIEW,android.net.Uri.parse(ORIGIN+"/privacy.html"))));content.addView(privacy);
        activate.setOnClickListener(v->{if(busy)return;if(!consent.isChecked()){status.setText("请先阅读云端音频处理说明并确认。");return;}busy=true;activate.setEnabled(false);status.setText("正在连接云端，请稍候…");
            new Thread(()->{try{
                android.content.SharedPreferences prefs=getSharedPreferences("cloud-installation",0);
                String identity=prefs.getString("id","");String proof=prefs.getString("proof","");
                if(identity.isEmpty()||proof.isEmpty()){identity=UUID.randomUUID().toString();proof=UUID.randomUUID().toString()+UUID.randomUUID();if(!prefs.edit().putString("id",identity).putString("proof",proof).commit())throw new IOException();}
                JSONObject body=new JSONObject().put("installation_id",identity).put("installation_secret",proof);
                HttpURLConnection c=(HttpURLConnection)new URL(ORIGIN+"/v1/enroll").openConnection();JSONObject result;
                try{c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setConnectTimeout(10000);c.setReadTimeout(20000);c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");byte[] data=body.toString().getBytes(StandardCharsets.UTF_8);c.setFixedLengthStreamingMode(data.length);try(OutputStream out=c.getOutputStream()){out.write(data);}int http=c.getResponseCode();if(http!=200)throw new IOException(http==403?"此安装已停用，请联系管理员":http==429?"操作较频繁，请稍后再试":http==503?"服务暂时已满，请联系管理员":"暂时无法连接，请检查网络后重试");
                    try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[4096];int n;while((n=in.read(b))!=-1){if(out.size()+n>32768)throw new IOException();out.write(b,0,n);}result=new JSONObject(out.toString("UTF-8"));}
                }finally{c.disconnect();}
                result.getJSONObject("backend").getString("write_token");result.getJSONObject("reply").getString("read_token");result.getJSONObject("analysis").getString("control_token");result.put("origin",ORIGIN);
                File old=new File(getFilesDir(),"cloud-bundle.json");if(old.exists())Files.copy(old.toPath(),new File(getFilesDir(),"cloud-bundle.previous.json").toPath(),java.nio.file.StandardCopyOption.REPLACE_EXISTING);
                atomic(old,result.toString());apply(this);
                runOnUiThread(()->{if(isFinishing()||isDestroyed())return;startActivity(new Intent(this,MainActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));finish();});
            }catch(Exception error){runOnUiThread(()->{busy=false;activate.setEnabled(true);status.setText(error instanceof IOException&&error.getMessage()!=null?error.getMessage():"连接未完成，请检查网络后重试");});}},"cloud-activation").start();});
        ScrollView scroll=new ScrollView(this);scroll.addView(content);setContentView(scroll);
    }
}
