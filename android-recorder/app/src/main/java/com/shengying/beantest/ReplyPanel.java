package com.shengying.beantest;

import android.app.Activity;
import android.media.AudioAttributes;
import android.os.Handler;
import android.os.Looper;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import com.example.replyfeature.TranscriptReplyFeature;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.function.BiConsumer;

/** Opt-in transcript consumer. Recording and the frozen transcript HTTP contract stay independent. */
final class ReplyPanel implements AutoCloseable {
    private final Activity activity;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final BiConsumer<String,String> log;
    private final Switch toggle;
    private final TextView state, heard;
    private final Button[] options = new Button[4];
    private final String[] replyValues=new String[4];private UiKit kit;private int selectedChoice=-1;private TextView choiceHeading;
    private final Button stop;
    private final Button toneChoices;
    private JSONObject pendingTone;
    private boolean showingTone;
    private TranscriptReplyFeature feature;
    private CombinedReplyClient combined;
    private long latestInputTime;
    private TextToSpeech speech;
    private boolean voiceReady, closed, adjusting;
    final LinearLayout panel;
    private boolean foreground=true;
    private long generation, voiceSequence;
    private String latestSpeechId = "", utterance = "", session = "";

    ReplyPanel(Activity activity, LinearLayout parent, BiConsumer<String,String> log) {
        this.activity=activity; this.log=log;
        panel=new LinearLayout(activity); panel.setOrientation(LinearLayout.VERTICAL);
        int space=dp(12); panel.setPadding(0,space,0,space); parent.addView(panel);
        toggle=new Switch(activity); toggle.setText("开启候选回应"); toggle.setTextSize(20);
        toggle.setMinHeight(dp(48)); toggle.setContentDescription("及时回应开关"); panel.addView(toggle);
        state=new TextView(activity); state.setTextSize(16); state.setText("已关闭 · 开启后生成回复，点选后播放");
        state.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE); panel.addView(state);
        heard=new TextView(activity); heard.setTextSize(17); heard.setTextIsSelectable(true); panel.addView(heard);
        for(int i=0;i<4;i++) {
            Button button=new Button(activity); button.setAllCaps(false); button.setTextSize(18);
            button.setMinHeight(dp(52)); button.setVisibility(android.view.View.GONE);
            final int index=i;button.setOnClickListener(v -> {selectedChoice=index;decorateChoices();speak(replyValues[index]==null?button.getText().toString():replyValues[index]);}); options[i]=button; panel.addView(button);
        }
        toneChoices=new Button(activity);toneChoices.setText("查看结合语气的候选");toneChoices.setAllCaps(false);toneChoices.setMinHeight(dp(48));toneChoices.setVisibility(android.view.View.GONE);
        toneChoices.setOnClickListener(v->{if(pendingTone!=null)showToneChoices(pendingTone);});panel.addView(toneChoices);
        stop=new Button(activity); stop.setText("停止播放"); stop.setVisibility(android.view.View.GONE);
        stop.setOnClickListener(v -> stopSpeech()); panel.addView(stop);
        toggle.setOnCheckedChangeListener((button,checked)->{ if(!adjusting) setEnabled(checked); });
        speech=new TextToSpeech(activity, result -> ui.post(() -> initVoice(result)));
    }

    private int dp(int value) { return Math.round(value*activity.getResources().getDisplayMetrics().density); }
    void styleCandidates(UiKit k){kit=k;toggle.setText("开启候选回应");toggle.setTextSize(14);state.setTextSize(12);state.setPadding(0,dp(6),0,dp(10));heard.setVisibility(android.view.View.GONE);choiceHeading=k.heading("选一句，轻松回应",18);choiceHeading.setPadding(0,dp(12),0,dp(10));panel.addView(choiceHeading,panel.indexOfChild(options[0]));for(Button option:options){option.setTextColor(k.ink);option.setTextSize(16);option.setElevation(0);option.setStateListAnimator(null);option.setGravity(android.view.Gravity.START|android.view.Gravity.CENTER_VERTICAL);option.setMinHeight(dp(83));option.setPadding(dp(17),dp(13),dp(17),dp(13));option.setCompoundDrawablePadding(dp(10));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,dp(5),0,dp(5));option.setLayoutParams(lp);k.press(option);}toneChoices.setTextSize(14);decorateChoices();}
    private void setChoice(int index,String value){replyValues[index]=value;String tone="回应 "+(index+1);java.util.regex.Matcher match=java.util.regex.Pattern.compile("(?:（([^（）]{1,16})）|\\(([^()]{1,16})\\))$").matcher(value);String text=value;if(match.find()){tone=match.group(1)!=null?match.group(1):match.group(2);text=value.substring(0,match.start()).trim();}android.text.SpannableString label=new android.text.SpannableString(tone+"\n"+text);label.setSpan(new android.text.style.AbsoluteSizeSpan(12,true),0,tone.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);label.setSpan(new android.text.style.StyleSpan(android.graphics.Typeface.BOLD),tone.length()+1,label.length(),android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);options[index].setText(label);options[index].setContentDescription(tone+"，"+text+"，点击朗读");options[index].setVisibility(android.view.View.VISIBLE);decorateChoices();}
    private void decorateChoices(){if(kit==null)return;int[] colors={kit.mint,kit.butter,kit.blue,kit.lilac};for(int i=0;i<options.length;i++){Button b=options[i];android.graphics.drawable.GradientDrawable bg=kit.shape(colors[i],21);if(i==selectedChoice)bg.setStroke(dp(2),kit.ink);b.setBackground(bg);b.setSelected(i==selectedChoice);b.setCompoundDrawablesRelativeWithIntrinsicBounds(null,null,kit.iconDrawable(i==selectedChoice?"check":"arrow",false,kit.ink),null);if(android.os.Build.VERSION.SDK_INT>=30)b.setStateDescription(i==selectedChoice?"已选择":"未选择");}if(choiceHeading!=null){int count=0;for(Button b:options)if(b.getVisibility()==android.view.View.VISIBLE)count++;choiceHeading.setText("选一句，轻松回应"+(count>0?"  · "+count: ""));}}


    private void initVoice(int result) {
        if(closed || speech==null) return;
        if(result!=TextToSpeech.SUCCESS) { log.accept("reply_tts_unavailable","引擎初始化失败"); return; }
        int language=speech.setLanguage(Locale.SIMPLIFIED_CHINESE);
        voiceReady=language!=TextToSpeech.LANG_MISSING_DATA && language!=TextToSpeech.LANG_NOT_SUPPORTED;
        speech.setAudioAttributes(new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build());
        speech.setOnUtteranceProgressListener(new UtteranceProgressListener() {
            public void onStart(String id) { ui.post(()->{if(currentVoice(id)){ state.setText("正在播放所选回复"); log.accept("reply_tts_start","id="+id); }}); }
            public void onDone(String id) { ui.post(()->{if(currentVoice(id)){ state.setText("播放完成 · 可选择其他回复"); stop.setVisibility(android.view.View.GONE); log.accept("reply_tts_done","id="+id); }}); }
            public void onError(String id) { ui.post(()->{if(currentVoice(id)){ state.setText("播放失败，请检查系统语音引擎和中文语音包"); stop.setVisibility(android.view.View.GONE); log.accept("reply_tts_error","播放失败"); }}); }
        });
        log.accept("reply_tts_ready", "chinese="+voiceReady);
    }

    private boolean currentVoice(String id) { return !closed && toggle.isChecked() && id.equals(latestSpeechId); }

    private void setEnabled(boolean enabled) {
        final long epoch=++generation;
        if(feature!=null) { feature.stop(); feature=null; }
        if(combined!=null) { combined.close(); combined=null; }
        stopSpeech(); clearChoices(); heard.setText("");
        if(!enabled) { state.setText("已关闭 · 不生成新回复"); log.accept("reply_disabled", "用户关闭"); return; }
        try {
            File file=new File(activity.getFilesDir(),"reply-config.json");
            JSONObject config=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
            if(config.optBoolean("combined_replies",false)){
                combined=new CombinedReplyClient(activity,event->ui.post(()->{
                    if(!closed&&toggle.isChecked()&&generation==epoch)onCombined(event);
                }),message->ui.post(()->{
                    if(closed||!toggle.isChecked()||generation!=epoch)return;
                    if(message.equals("ready")){
                        if(utterance.isEmpty())state.setText("已开启 · 根据对方语气生成候选");
                        log.accept("reply_feed_ready","emotion and replies share one audio request");
                    }else{if(options[0].getVisibility()!=android.view.View.VISIBLE)state.setText(message);log.accept("reply_context_error",message);}
                }));
                latestInputTime=0;utterance="";session="";
                state.setText("正在连接联合回应…");log.accept("reply_context_enabled","parallel audio analysis; future-only");
            }
            feature=new TranscriptReplyFeature(activity,config.getString("gateway_url"),config.getString("read_token"),
                    config.getString("api_key"),config.optString("model","deepseek-flash"),config.optString("reply_endpoint",""),new TranscriptReplyFeature.Listener() {
                private boolean active() { return !closed && toggle.isChecked() && generation==epoch; }
                public void onReady() { if(active()) { state.setText("已开启 · 等待新的对话"); log.accept("reply_feed_ready", "固定文字接口已连接"); } }
                public void onInput(String sessionId,String utteranceId,String text) {
                    if(!active()) return;
                    if(!sessionId.equals(session)||!utteranceId.equals(utterance))clearChoices(); session=sessionId; utterance=utteranceId;
                    heard.setText("对方说："+text); state.setText("正在生成回复…");
                    log.accept("reply_input", "session="+sessionId+" utterance="+utteranceId);
                }
                public void onOptions(String sessionId,String utteranceId,String text,JSONObject json) {
                    if(!active()) return;
                    displayFastOptions(sessionId,utteranceId,text,json);
                }
                public void onError(String message) { if(active()) { if(pendingTone!=null)showToneChoices(pendingTone);else{clearChoices();state.setText(message+"；可关闭后重开重试");} log.accept("reply_error", message); } }
            });
            state.setText("正在连接文字流…"); feature.startFromNow(); log.accept("reply_enabled", "仅处理开启后到达的稳定句子");
        } catch(Exception unavailable) {
            if(feature!=null) { feature.stop(); feature=null; }
            if(combined!=null) { combined.close(); combined=null; }
            adjusting=true; toggle.setChecked(false); adjusting=false;
            state.setText("回应服务尚未连接，请在我的页面完成服务激活");
            log.accept("reply_config_error", unavailable.getClass().getSimpleName());
        }
    }

    private void clearChoices() { for(Button button:options) button.setVisibility(android.view.View.GONE);selectedChoice=-1;decorateChoices();pendingTone=null;showingTone=false;toneChoices.setVisibility(android.view.View.GONE); }

    void displayFastOptions(String sid,String uid,String text,JSONObject json){
        if(closed||!toggle.isChecked())return;
        try{
            JSONArray values=json.getJSONArray("options");if(values.length()!=4)throw new IllegalArgumentException("Four replies required");
            session=sid;utterance=uid;heard.setText("对方说："+text);if(showingTone)return;
            for(int i=0;i<4;i++)setChoice(i,values.getString(i));
            state.setText(pendingTone==null?"请选择一句回复，点击后手机播放":"快速候选已就绪 · 也可查看结合语气的候选");
            log.accept("reply_options","session="+sid+" utterance="+uid+" count=4");
        }catch(Exception invalid){state.setText("回复格式异常，请等待下一句话");}
    }

    void onTranscript(JSONObject event){
        ui.post(()->{
            if(closed||!toggle.isChecked()||combined==null||!event.optBoolean("is_final")||!"bean_live".equals(event.optString("source")))return;
            long time=event.optLong("received_at_ms",0);if(time<latestInputTime)return;
            String id=event.optString("utterance_id"),sid=event.optString("session_id");
            if(id.equals(utterance)&&sid.equals(session)){latestInputTime=time;heard.setText("对方说："+event.optString("text"));return;}
            latestInputTime=time;session=sid;utterance=id;clearChoices();
            heard.setText("对方说："+event.optString("text"));state.setText("正在结合语气生成回复…");
            log.accept("reply_input","session="+sid+" utterance="+id);
        });
    }

    void onCombined(JSONObject event){
        if(closed||!foreground||!toggle.isChecked()||combined==null)return;
        if(!utterance.isEmpty()&&(!utterance.equals(event.optString("utterance_id"))||!session.equals(event.optString("session_id"))))return;
        String type=event.optString("type");
        if(!type.equals("reply.options")&&!type.equals("reply.unavailable"))return;
        if(event.optLong("utterance_time_ms",0)<latestInputTime)return;
        if(type.equals("reply.unavailable")){state.setText(options[0].getVisibility()==android.view.View.VISIBLE?"快速候选可用 · 本句语气建议暂不可用":"本句语气建议暂不可用，等待下一句");return;}
        try{
            JSONArray values=event.getJSONArray("options");if(values.length()!=4)throw new IllegalArgumentException("Four replies required");
            pendingTone=event;
            if(options[0].getVisibility()==android.view.View.VISIBLE){
                toneChoices.setVisibility(android.view.View.VISIBLE);
                if(latestSpeechId.isEmpty())state.setText("快速候选已就绪 · 也可查看结合语气的候选");
                log.accept("reply_context_ready","utterance="+event.optString("utterance_id"));
            }else showToneChoices(event);
        }catch(Exception error){state.setText("回复格式暂不可用，等待下一句");}
    }

    private void showToneChoices(JSONObject event){
        if(closed||!toggle.isChecked())return;
        try{
            JSONArray values=event.getJSONArray("options");if(values.length()!=4)return;
            latestInputTime=event.optLong("utterance_time_ms",0);session=event.getString("session_id");utterance=event.getString("utterance_id");
            showingTone=true;toneChoices.setVisibility(android.view.View.GONE);heard.setText("对方说："+event.optString("text"));
            for(int i=0;i<4;i++)setChoice(i,values.getString(i));
            state.setText("已参考语气估计："+event.optString("emotion")+" / "+event.optString("tone")+"\n请选择一句回复，点击后播放");
            log.accept("reply_options","session="+session+" utterance="+utterance+" count=4 combined=true");
        }catch(Exception error){state.setText("语气候选暂不可用");}
    }

    private void speak(String option) {
        if(closed || !toggle.isChecked()) return;
        if(!voiceReady) { state.setText("中文语音尚未就绪；仍可阅读或复制回复");new android.app.AlertDialog.Builder(activity).setTitle("需要中文语音包").setMessage("请在手机系统中安装或启用中文文字转语音。字幕和文字候选可以继续使用。").setPositiveButton("打开语音设置",(d,w)->{try{activity.startActivity(new android.content.Intent("com.android.settings.TTS_SETTINGS"));}catch(Exception missing){try{activity.startActivity(new android.content.Intent(TextToSpeech.Engine.ACTION_INSTALL_TTS_DATA));}catch(Exception absent){state.setText("请在系统设置中搜索“文字转语音”，安装中文语音包");}}}).setNegativeButton("稍后",null).show();return; }
        String spoken=option.replaceFirst("(?:（[^（）()]{1,8}）|\\([^（）()]{1,8}\\))$", "").trim();
        latestSpeechId="reply-"+(++voiceSequence);
        int result=speech.speak(spoken,TextToSpeech.QUEUE_FLUSH,null,latestSpeechId);
        if(result==TextToSpeech.SUCCESS) { stop.setVisibility(android.view.View.VISIBLE); state.setText("准备播放所选回复…");
            log.accept("reply_selected", "session="+session+" utterance="+utterance+" id="+latestSpeechId); }
        else { state.setText("无法播放，请检查系统语音设置"); log.accept("reply_tts_error", "speak返回错误"); }
    }

    private void stopSpeech() {
        latestSpeechId=""; if(speech!=null) speech.stop(); stop.setVisibility(android.view.View.GONE);
        if(!closed && toggle.isChecked()) state.setText("播放已停止");
    }

    void setForeground(boolean value){
        if(foreground==value)return;foreground=value;
        if(!value){generation++;if(feature!=null){feature.stop();feature=null;}if(combined!=null){combined.close();combined=null;}stopSpeech();clearChoices();if(toggle.isChecked())state.setText("已暂停 · 返回 App 后接收新的对话");}
        else if(toggle.isChecked())setEnabled(true);
    }

    public void close() {
        closed=true; generation++; if(feature!=null) { feature.stop(); feature=null; }
        if(combined!=null){combined.close();combined=null;}
        if(speech!=null) { speech.stop(); speech.shutdown(); }
    }
}
