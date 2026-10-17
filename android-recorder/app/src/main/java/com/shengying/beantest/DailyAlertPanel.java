package com.shengying.beantest;

import android.app.Activity;
import android.content.SharedPreferences;
import android.content.Intent;
import android.provider.Settings;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.text.Editable;
import android.text.InputFilter;
import android.text.TextWatcher;
import android.view.View;
import android.view.inputmethod.InputMethodManager;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.Switch;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.text.DateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.function.BiConsumer;

/** Foreground daily-name/number alerts; settings never change the frozen feed. */
final class DailyAlertPanel implements AutoCloseable {
    private final int INK,MUTED,ACCENT;
    private static final String STATE="daily_alert_temporary_v1";
    private final Activity activity;
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final BiConsumer<String,String> log;
    private final SharedPreferences prefs;
    private final KeywordMonitor monitor=new KeywordMonitor();
    private final PhoneHaptics haptics;
    private final Switch toggle,homophones;
    private final TextView state,lastMatch,vibrationStatus;
    private final EditText name,nickname,testText;
    private final LinearLayout temporaryContainer;
    private final Button add;
    private final List<TemporaryRow> temporary=new ArrayList<>();
    private boolean initializing=true,adjusting,closed,foreground=true;
    private int received,alerts;
    final LinearLayout panel;
    java.util.function.Consumer<String> notice=text->{};

    private final class TemporaryRow {
        final LinearLayout row;
        final EditText input;
        final Button remove;
        TemporaryRow(String value){
            row=new LinearLayout(activity);row.setGravity(android.view.Gravity.CENTER_VERTICAL);
            input=input("例如：三十号");input.setText(value);
            row.addView(input,new LinearLayout.LayoutParams(0,-2,1));
            remove=button("移除",false);remove.setMinWidth(dp(64));
            remove.setOnClickListener(v->{temporary.remove(this);temporaryContainer.removeView(row);renumber();sync();});
            row.addView(remove,new LinearLayout.LayoutParams(dp(72),dp(52)));
            input.addTextChangedListener(watcher());
        }
    }

    DailyAlertPanel(Activity activity,LinearLayout parent,BiConsumer<String,String> log,Bundle restored){
        this.activity=activity;this.log=log;UiKit colors=new UiKit(activity);INK=colors.ink;MUTED=colors.muted;ACCENT=colors.primary;
        prefs=activity.getSharedPreferences("daily_alert_profile_v1",0);haptics=new PhoneHaptics(activity);
        panel=new LinearLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);panel.setPadding(0,dp(20),0,dp(20));parent.addView(panel);
        toggle=new Switch(activity);toggle.setText("日常音提醒");toggle.setTextColor(INK);toggle.setTextSize(21);
        toggle.setTypeface(null,Typeface.BOLD);toggle.setMinHeight(dp(52));toggle.setContentDescription("日常提醒开关");panel.addView(toggle);
        panel.addView(text("听到你的名字、绰号或号码时，用手机震动提醒。",15,MUTED));
        state=text("未开启 · 填写关键词后开启",15,MUTED);state.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);panel.addView(state);
        panel.addView(label("常用关键词"));
        panel.addView(text("姓名",15,INK));name=input("输入你的名字");name.setContentDescription("姓名关键词");name.setText(prefs.getString("name",""));panel.addView(name);
        panel.addView(text("绰号",15,INK));nickname=input("输入你的绰号或代号");nickname.setContentDescription("绰号关键词");nickname.setText(prefs.getString("nickname",""));panel.addView(nickname);
        homophones=new Switch(activity);homophones.setText("姓名 / 绰号同音匹配");homophones.setTextColor(INK);homophones.setTextSize(16);homophones.setMinHeight(dp(48));homophones.setContentDescription("姓名绰号同音匹配");homophones.setChecked(prefs.getBoolean("homophones",true));panel.addView(homophones);
        panel.addView(text("两个字起的中文姓名、绰号按完整拼音匹配，不区分声调；可能误报，可关闭。",14,MUTED));
        panel.addView(label("临时关键词"));panel.addView(text("用于叫号等临时场景；重新打开 App 后清空。",14,MUTED));
        temporaryContainer=new LinearLayout(activity);temporaryContainer.setOrientation(LinearLayout.VERTICAL);panel.addView(temporaryContainer);
        add=button("添加一行临时关键词",false);add.setContentDescription("添加临时关键词");
        add.setOnClickListener(v->{if(temporary.size()<8){addTemporary("");temporary.get(temporary.size()-1).input.requestFocus();sync();}});panel.addView(add);
        ArrayList<String> restoredValues=restored==null?null:restored.getStringArrayList(STATE);
        if(restoredValues==null||restoredValues.isEmpty())addTemporary("");
        else for(String value:restoredValues)if(temporary.size()<8)addTemporary(value);
        panel.addView(text("提醒方式：手机震动",15,INK));
        panel.addView(text("录音豆震动暂不可用，当前使用手机提醒。",14,MUTED));
        vibrationStatus=text("",14,MUTED);panel.addView(vibrationStatus);updateVibrationStatus();
        Button vibration=button("测试手机震动",false);vibration.setOnClickListener(v->{
            boolean submitted=haptics.alert();state.setText(haptics.explanation());updateVibrationStatus();
            log.accept("daily_alert_haptic_test",haptics.status());});panel.addView(vibration);
        Button settings=button("打开声音与振动设置",false);settings.setOnClickListener(v->{try{activity.startActivity(new Intent(Settings.ACTION_SOUND_SETTINGS));}catch(RuntimeException error){state.setText("无法打开设置，请手动进入手机的声音与振动设置");}});panel.addView(settings);
        lastMatch=text("还没有提醒记录",16,MUTED);lastMatch.setPadding(0,dp(12),0,dp(12));lastMatch.setTextIsSelectable(true);
        lastMatch.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);panel.addView(lastMatch);
        Button showTest=button("没有录音豆？用文字测试",false);panel.addView(showTest);
        LinearLayout test=new LinearLayout(activity);test.setOrientation(LinearLayout.VERTICAL);test.setVisibility(View.GONE);panel.addView(test);
        test.addView(text("这里只验证关键词和震动，不代表语音识别成功。",14,MUTED));
        testText=input("例如：请三十号到窗口办理");testText.setContentDescription("测试识别文字");testText.setFilters(new InputFilter[]{new InputFilter.LengthFilter(200)});testText.setSingleLine(false);testText.setMaxLines(3);test.addView(testText);
        Button run=button("测试这句话",true);run.setOnClickListener(v->{
            hideKeyboard();sync();
            if(!toggle.isChecked()){state.setText("先开启日常提醒，再测试这句话");return;}
            if(testText.getText().toString().trim().isEmpty()){testText.setError("输入一句包含关键词的话");return;}
            List<KeywordMonitor.Rule> hits=monitor.test(testText.getText().toString());
            if(hits.isEmpty()){lastMatch.setText("文字测试：未命中关键词");lastMatch.setTextColor(MUTED);log.accept("daily_alert_test_no_match","no_match");}
            else showMatch(hits,testText.getText().toString(),true,null);
        });test.addView(run);
        showTest.setOnClickListener(v->{boolean visible=test.getVisibility()!=View.VISIBLE;test.setVisibility(visible?View.VISIBLE:View.GONE);showTest.setText(visible?"收起文字测试":"没有录音豆？用文字测试");});
        panel.addView(text("保持 App 在前台并开始录音后监测；文字识别有误时可能漏报。",14,MUTED));
        name.addTextChangedListener(watcher());nickname.addTextChangedListener(watcher());homophones.setOnCheckedChangeListener((button,enabled)->sync());
        toggle.setOnCheckedChangeListener((button,enabled)->{
            if(adjusting)return;sync();
            if(enabled&&monitor.ruleCount()==0){adjusting=true;toggle.setChecked(false);adjusting=false;monitor.setEnabled(false);
                name.setError("至少填写一个关键词");name.requestFocus();state.setText("先填写姓名、绰号或临时关键词");return;}
            received=0;monitor.setEnabled(enabled);if(!enabled)haptics.cancel();updateState();
            log.accept(enabled?"daily_alert_enabled":"daily_alert_disabled","keywords="+monitor.ruleCount());
        });
        initializing=false;sync();
        if(restored!=null&&restored.getBoolean("daily_alert_enabled",false)&&monitor.ruleCount()>0)toggle.setChecked(true);
    }

    /** Recompose the same controls; listeners, monitor and stored rules remain unchanged. */
    void styleNative(UiKit k){
        java.util.ArrayList<android.view.View> old=new java.util.ArrayList<>();for(int i=0;i<panel.getChildCount();i++)old.add(panel.getChildAt(i));panel.removeAllViews();panel.setPadding(0,0,0,dp(12));
        LinearLayout names=k.col();names.setVisibility(android.view.View.GONE);names.addView(k.copy("姓名"));moveNative(name,names);names.addView(k.copy("绰号"));moveNative(nickname,names);k.gap(names,12);
        toggle.setText("");toggle.setMinHeight(dp(48));toggle.setMinimumWidth(dp(48));
        panel.addView(nativeRow(k,"user","姓名与绰号","匹配字幕里的名字、绰号或号码",toggle,()->names.setVisibility(names.getVisibility()==android.view.View.VISIBLE?android.view.View.GONE:android.view.View.VISIBLE)));panel.addView(names);
        homophones.setText("");homophones.setMinHeight(dp(48));homophones.setMinimumWidth(dp(48));panel.addView(nativeRow(k,"wave","姓名同音匹配","两个字起，按完整拼音匹配",homophones,null));
        LinearLayout temporaryBox=k.col();temporaryBox.setVisibility(android.view.View.GONE);moveNative(temporaryContainer,temporaryBox);moveNative(add,temporaryBox);panel.addView(nativeRow(k,"list","叫号与临时关键词","点击填写临时要留意的号码或词语",null,()->temporaryBox.setVisibility(temporaryBox.getVisibility()==android.view.View.VISIBLE?android.view.View.GONE:android.view.View.VISIBLE)));panel.addView(temporaryBox);
        state.setTextSize(12);state.setPadding(0,dp(9),0,dp(9));moveNative(state,panel);
        TextView available=k.text("门铃、来电铃声和家人声纹识别尚未接通。当前日常提醒使用字幕关键词。",12,k.muted);panel.addView(available);k.gap(panel,10);
        LinearLayout tools=k.col();tools.setVisibility(android.view.View.GONE);
        for(android.view.View v:old){if(v==toggle||v==homophones||v==name||v==nickname||v==state||v==temporaryContainer||v==add)continue;if(v instanceof Button||v==vibrationStatus||v==lastMatch||v instanceof LinearLayout)moveNative(v,tools);}
        Button preview=k.button("体验手机震动",false);preview.setCompoundDrawablesRelativeWithIntrinsicBounds(k.iconDrawable("bell",false,k.primary),null,null,null);preview.setOnClickListener(v->{haptics.alert();state.setText(haptics.explanation());updateVibrationStatus();log.accept("daily_alert_haptic_test",haptics.status());});panel.addView(preview);
        Button more=k.button("更多设置与文字测试",false);more.setOnClickListener(v->tools.setVisibility(tools.getVisibility()==android.view.View.VISIBLE?android.view.View.GONE:android.view.View.VISIBLE));panel.addView(more);panel.addView(tools);k.style(names);k.style(temporaryBox);
    }
    private void moveNative(android.view.View view,LinearLayout into){if(view.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)view.getParent()).removeView(view);into.addView(view);}
    private LinearLayout nativeRow(UiKit k,String icon,String title,String help,Switch control,Runnable action){LinearLayout row=k.row();row.setPadding(0,dp(12),0,dp(12));row.setMinimumHeight(dp(76));row.addView(new UiKit.Icon(activity,icon,k.ink),new LinearLayout.LayoutParams(dp(25),dp(25)));LinearLayout words=k.col();words.addView(k.heading(title,16));TextView copy=k.text(help,12,k.muted);copy.setPadding(0,dp(5),0,0);words.addView(copy);LinearLayout.LayoutParams wp=new LinearLayout.LayoutParams(0,-2,1);wp.leftMargin=dp(12);wp.rightMargin=dp(8);row.addView(words,wp);if(control!=null){if(control.getParent() instanceof android.view.ViewGroup)((android.view.ViewGroup)control.getParent()).removeView(control);row.addView(control,new LinearLayout.LayoutParams(dp(52),dp(48)));}else row.addView(new UiKit.Icon(activity,"next",k.muted),new LinearLayout.LayoutParams(dp(20),dp(20)));if(action!=null){row.setOnClickListener(v->action.run());row.setFocusable(true);row.setContentDescription(title+"，"+help);}return row;}
    private int dp(int value){return Math.round(value*activity.getResources().getDisplayMetrics().density);}
    private TextView text(String value,int size,int color){TextView v=new TextView(activity);v.setText(value);v.setTextSize(size);v.setTextColor(color);v.setPadding(0,dp(3),0,dp(5));return v;}
    private TextView label(String value){TextView v=text(value,16,INK);v.setTypeface(null,Typeface.BOLD);v.setPadding(0,dp(16),0,dp(4));return v;}
    private EditText input(String hint){EditText v=new EditText(activity);v.setHint(hint);v.setHintTextColor(MUTED);v.setTextColor(INK);v.setTextSize(18);v.setSingleLine(true);v.setMinHeight(dp(52));v.setBackgroundTintList(ColorStateList.valueOf(ACCENT));v.setFilters(new InputFilter[]{new InputFilter.LengthFilter(32)});return v;}
    private Button button(String value,boolean primary){Button b=new Button(activity);b.setText(value);b.setAllCaps(false);b.setTextSize(16);b.setMinHeight(dp(48));b.setTextColor(primary?Color.WHITE:ACCENT);if(primary)b.setBackgroundTintList(ColorStateList.valueOf(ACCENT));return b;}
    private TextWatcher watcher(){return new TextWatcher(){public void beforeTextChanged(CharSequence s,int st,int c,int a){}public void onTextChanged(CharSequence s,int st,int before,int count){if(!initializing)sync();}public void afterTextChanged(Editable e){}};}
    private void addTemporary(String value){TemporaryRow row=new TemporaryRow(value);temporary.add(row);temporaryContainer.addView(row.row);renumber();}
    private void renumber(){for(int i=0;i<temporary.size();i++){temporary.get(i).input.setContentDescription("临时关键词"+(i+1));temporary.get(i).remove.setContentDescription("移除临时关键词"+(i+1));}add.setEnabled(temporary.size()<8);}
    private void sync(){
        if(initializing)return;
        List<KeywordMonitor.Rule> rules=new ArrayList<>();rules.add(new KeywordMonitor.Rule("姓名",name.getText().toString(),homophones.isChecked()));rules.add(new KeywordMonitor.Rule("绰号",nickname.getText().toString(),homophones.isChecked()));
        for(int i=0;i<temporary.size();i++)rules.add(new KeywordMonitor.Rule("临时关键词"+(i+1),temporary.get(i).input.getText().toString()));monitor.setRules(rules);
        if(monitor.ruleCount()>0)name.setError(null);
        prefs.edit().putString("name",name.getText().toString()).putString("nickname",nickname.getText().toString()).putBoolean("homophones",homophones.isChecked()).apply();
        if(toggle.isChecked()&&monitor.ruleCount()==0){adjusting=true;toggle.setChecked(false);adjusting=false;monitor.setEnabled(false);haptics.cancel();}
        updateState();
    }
    private void updateState(){
        if(!foreground&&toggle.isChecked()){state.setText("已暂停 · 返回 App 后继续");state.setTextColor(MUTED);return;}
        state.setText(toggle.isChecked()?"已开启 · "+monitor.ruleCount()+" 个关键词 · 等待新的转写":"未开启 · 填写关键词后开启");state.setTextColor(toggle.isChecked()?ACCENT:MUTED);
    }
    private void hideKeyboard(){InputMethodManager keyboard=(InputMethodManager)activity.getSystemService(Activity.INPUT_METHOD_SERVICE);if(keyboard!=null)keyboard.hideSoftInputFromWindow(testText.getWindowToken(),0);testText.clearFocus();}

    void onTranscript(JSONObject event){
        final String source=event.optString("source"),session=event.optString("session_id"),utterance=event.optString("utterance_id"),text=event.optString("text");final boolean stable=event.optBoolean("is_final",false);
        ui.post(()->{
            if(closed)return;
            List<KeywordMonitor.Rule> hits=monitor.accept(source,session,utterance,text,stable,SystemClock.elapsedRealtime());
            if(!hits.isEmpty())showMatch(hits,text,false,utterance);
            else if(toggle.isChecked()&&foreground&&stable&&"bean_live".equals(source)){received++;state.setText("监测中 · "+monitor.ruleCount()+" 个关键词 · 已收到转写");}
        });
    }
    private void showMatch(List<KeywordMonitor.Rule> hits,String text,boolean test,String utterance){
        boolean submitted=haptics.alert();StringBuilder words=new StringBuilder();JSONArray values=new JSONArray(),kinds=new JSONArray();
        for(KeywordMonitor.Rule rule:hits){if(words.length()>0)words.append("、");String kind=KeywordMonitor.matchKind(rule,text);words.append("「").append(rule.value).append("」");if("homophone".equals(kind))words.append("（同音）");values.put(rule.value);kinds.put(kind);}
        if(!test){alerts++;notice.accept("日常提醒 · 听到了 "+words);}
        lastMatch.setText((test?"文字测试命中 ":"听到了 ")+words+"\n"+text+"\n"+DateFormat.getTimeInstance(DateFormat.SHORT).format(new Date())+" · "+haptics.explanation());lastMatch.setTextColor(INK);updateVibrationStatus();
        state.setText(test?"文字测试完成":"已提醒 "+alerts+" 次 · 继续监测");
        try{JSONObject detail=new JSONObject().put("keywords",values).put("match_kinds",kinds).put("phone_vibration_submitted",submitted).put("phone_vibration_status",haptics.status()).put("bean_vibration","not_exposed_by_sdk");if(utterance!=null)detail.put("utterance_id",utterance);log.accept(test?"daily_alert_test_match":"daily_alert_match",detail.toString());}catch(Exception ignored){}
    }
    private void updateVibrationStatus(){String reason=haptics.blockedReason();vibrationStatus.setText(reason.isEmpty()?"系统未发现已知的震动拦截；请先测试震感。":reason);vibrationStatus.setTextColor(reason.isEmpty()?MUTED:Color.rgb(160,65,30));}
    void saveState(Bundle out){ArrayList<String> values=new ArrayList<>();for(TemporaryRow row:temporary)values.add(row.input.getText().toString());out.putStringArrayList(STATE,values);out.putBoolean("daily_alert_enabled",toggle.isChecked());}
    void setForeground(boolean value){foreground=value;monitor.setForeground(value);if(!value)haptics.cancel();if(!closed){updateState();if(value)updateVibrationStatus();}}
    public void close(){closed=true;monitor.setEnabled(false);haptics.cancel();}
}
