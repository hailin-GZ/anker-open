package com.shengying.beantest;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONObject;
import java.util.HashMap;
import java.util.Map;
import java.util.function.BiConsumer;

/** Foreground audio insights; output remains separate from immutable captions. */
final class AnalysisPanel implements AutoCloseable {
    private final Activity activity;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final BiConsumer<String,String> log;
    private final PhoneHaptics haptics;
    private final Switch danger,emotion;
    final TextView connection;private final TextView dangerResult,emotionResult;
    final LinearLayout dangerSection,emotionSection;
    java.util.function.Consumer<JSONObject> observer=e->{};
    private final Map<String,Long> lastAlert=new HashMap<>();
    private AnalysisClient client;
    private boolean foreground=true,closed;
    private int generation;
    private long latestEmotionTime;
    AnalysisPanel(Activity activity,LinearLayout parent,BiConsumer<String,String> log){
        this.activity=activity;this.log=log;haptics=new PhoneHaptics(activity);
        LinearLayout panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(0,dp(18),0,dp(16));parent.addView(panel);
        dangerSection=new LinearLayout(activity);dangerSection.setOrientation(LinearLayout.VERTICAL);panel.addView(dangerSection);
        emotionSection=new LinearLayout(activity);emotionSection.setOrientation(LinearLayout.VERTICAL);panel.addView(emotionSection);
        danger=toggle("危险音提醒","危险音提醒开关");dangerSection.addView(danger);
        dangerSection.addView(text("喇叭、警笛、呼救喊声，以及“小心、让开”等警示出现时，提醒并请求手机震动。",14));
        dangerResult=text("等待声音分析 · 请保持 App 在前台",16);dangerResult.setAccessibilityLiveRegion(1);dangerSection.addView(dangerResult);
        emotion=toggle("情绪感知","情绪感知开关");emotionSection.addView(emotion);
        emotionSection.addView(text("根据每句录音的语气估计情绪；无法分清说话人时会保留未知。",14));
        emotionResult=text("等待下一句录音",16);emotionResult.setAccessibilityLiveRegion(1);emotionSection.addView(emotionResult);
        connection=text("正在连接分析服务…",14);panel.addView(connection);
        danger.setChecked(true);emotion.setChecked(true);
        danger.setOnCheckedChangeListener((b,on)->{if(!on){haptics.cancel();dangerResult.setText("危险音提醒已关闭");}else dangerResult.setText("等待新的危险声音线索");refresh();});
        emotion.setOnCheckedChangeListener((b,on)->{emotionResult.setText(on?"等待下一句录音":"情绪感知已关闭");refresh();});
        refresh();
    }
    /** Settings anatomy mirrors the approved proposal without inventing per-class switches. */
    void styleNative(UiKit k){
        dangerSection.removeAllViews();danger.setText("");danger.setMinHeight(dp(48));danger.setMinimumWidth(dp(48));dangerSection.addView(nativeRow(k,"danger","危险音提醒","统一控制下方警示声音提醒",danger));
        dangerSection.addView(nativeRow(k,"compass","车辆鸣笛","留意靠近道路的车辆 · 由总开关控制",null));dangerSection.addView(nativeRow(k,"shield","警报与警笛","留意周围的警示信号 · 由总开关控制",null));dangerSection.addView(nativeRow(k,"speaker","呼救与口头警示","呼救、小心、让开等 · 由总开关控制",null));dangerResult.setTextSize(12);dangerSection.addView(dangerResult);
        emotionSection.removeAllViews();emotion.setText("");emotion.setMinHeight(dp(48));emotion.setMinimumWidth(dp(48));emotionSection.addView(nativeRow(k,"heart","情绪感知","根据每句录音语气估计，无法确定时保留未知",emotion));emotionResult.setTextSize(12);emotionSection.addView(emotionResult);connection.setTextSize(12);connection.setPadding(0,dp(4),0,dp(10));
    }
    private LinearLayout nativeRow(UiKit k,String icon,String title,String help,Switch control){LinearLayout row=k.row();row.setMinimumHeight(dp(70));row.setPadding(0,dp(10),0,dp(10));row.addView(new UiKit.Icon(activity,icon,k.ink),new LinearLayout.LayoutParams(dp(24),dp(24)));LinearLayout words=k.col();words.addView(k.heading(title,16));TextView description=k.text(help,12,k.muted);description.setPadding(0,dp(5),0,0);words.addView(description);LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.leftMargin=dp(12);wp.rightMargin=dp(8);row.addView(words,wp);if(control!=null)row.addView(control,new LinearLayout.LayoutParams(dp(52),dp(48)));return row;}
    private int dp(int n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size){TextView t=new TextView(activity);t.setText(value);t.setTextSize(size);t.setTextColor(Color.rgb(55,74,77));t.setPadding(0,dp(4),0,dp(8));return t;}
    private Switch toggle(String title,String description){Switch s=new Switch(activity);s.setText(title);s.setContentDescription(description);s.setTextSize(21);s.setTypeface(null,Typeface.BOLD);s.setTextColor(Color.rgb(25,39,42));s.setMinHeight(dp(52));return s;}
    private void refresh(){
        boolean wanted=!closed&&foreground&&(danger.isChecked()||emotion.isChecked());
        if(!wanted){generation++;if(client!=null)client.close();client=null;connection.setText(foreground?"分析提醒已关闭":"离开 App，提醒已暂停");return;}
        if(client!=null)return;
        final int run=++generation;
        client=new AnalysisClient(activity,e->ui.post(()->{if(!closed&&foreground&&run==generation)accept(e);}),
            status->ui.post(()->{if(!closed&&foreground&&run==generation)connection.setText(status);}));
    }
    void accept(JSONObject event){
        if(closed||!foreground)return;
        if(!"bean_live".equals(event.optString("source","bean_live")))return;
        long age=event.has("age_at_receive_ms")?event.optLong("age_at_receive_ms"):System.currentTimeMillis()-event.optLong("created_at_ms",0);
        if(age>30000){log.accept("analysis_stale_skipped",event.optString("event_id"));return;}
        String type=event.optString("type");
        if((type.equals("danger.sound")||type.equals("danger.speech"))&&danger.isChecked()){
            String code=type.equals("danger.speech")?event.optString("phrase"):event.optString("sound");
            String label=code;
            switch(code){case "car_horn":label="疑似汽车喇叭";break;case "siren":label="疑似警笛";break;case "scream":label="疑似呼救或尖叫";break;case "alarm":label="疑似紧急警报";break;default:label="听到警示：“"+code+"”";}
            String detail=type.equals("danger.speech")?event.optString("text"):event.optString("evidence");
            long time=SystemClock.elapsedRealtime(),last=lastAlert.getOrDefault(code,-10000L);
            if(time-last<4000)return;
            lastAlert.put(code,time);haptics.alert();observer.accept(event);
            dangerResult.setText(label+"\n"+detail+"\n"+haptics.explanation());dangerResult.setTextColor(new UiKit(activity).warn);
            log.accept("danger_alert","type="+type+" category="+code+" haptic="+haptics.status()+" event="+event.optString("event_id"));
        }else if(type.equals("emotion.estimate")&&emotion.isChecked()){
            observer.accept(event);long sentenceTime=event.optLong("utterance_time_ms",0);
            if(sentenceTime<latestEmotionTime)return;
            latestEmotionTime=sentenceTime;
            String value=event.optString("emotion"),label=value.isEmpty()?"无法确定":value;
            switch(value){case "neutral":label="平静 / 中性";break;case "happy":label="开心";break;case "angry":label="生气 / 急躁";break;case "sad":label="悲伤 / 低落";break;case "unknown":label="无法确定";break;}
            String speaker=event.isNull("speaker_id")?"说话人未确定":"会话内说话人："+event.optString("speaker_id");
            String tone=event.optString("tone","");
            emotionResult.setText("情绪估计："+label+(tone.isEmpty()?"":"\n表达语气："+tone)+"\n“"+event.optString("text")+"”\n"+speaker+"\n"+event.optString("evidence"));
            log.accept("emotion_displayed","emotion="+value+" tone="+tone+" utterance="+event.optString("utterance_id"));
        }else if(type.equals("emotion.unavailable")&&emotion.isChecked()){
            observer.accept(event);emotionResult.setText("这一句情绪暂不可用 · 等待下一句");log.accept("emotion_unavailable",event.optString("reason"));
        }else if(type.equals("danger.unavailable")||type.equals("analysis.gap")){
            connection.setText("部分声音未能分析，请检查服务连接");log.accept("analysis_unavailable",type);
        }else if(type.equals("analysis.checked")){
            connection.setText("声音分析运行中 · 最近一段模型耗时 "+(event.optInt("request_ms")/1000.0)+" 秒");
            log.accept("analysis_checked","event="+event.optString("event_id"));
        }
    }
    boolean isEmotionEnabled(){return emotion.isChecked();}
    void setForeground(boolean value){foreground=value;if(!value)haptics.cancel();refresh();}
    public void close(){closed=true;refresh();haptics.cancel();}
}
