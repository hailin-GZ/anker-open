package com.shengying.beantest;

import android.content.Context;
import android.media.AudioAttributes;
import android.media.AudioManager;
import android.app.NotificationManager;
import android.provider.Settings;
import android.os.Build;
import android.os.VibrationAttributes;
import android.os.VibrationEffect;
import android.os.Vibrator;
import android.os.VibratorManager;

/** Notification haptics; respects system settings. No undocumented bean commands. */
final class PhoneHaptics {
    private final Vibrator vibrator;
    private final Context context;
    private String status="not_requested";
    PhoneHaptics(Context context){
        this.context=context;
        if(Build.VERSION.SDK_INT>=31){VibratorManager manager=context.getSystemService(VibratorManager.class);vibrator=manager==null?null:manager.getDefaultVibrator();}
        else vibrator=(Vibrator)context.getSystemService(Context.VIBRATOR_SERVICE);
    }
    boolean available(){return vibrator!=null&&vibrator.hasVibrator();}
    String blockedReason(){
        if(!available())return "手机没有可用的震动器";
        NotificationManager notifications=context.getSystemService(NotificationManager.class);
        if(notifications!=null&&notifications.getCurrentInterruptionFilter()!=NotificationManager.INTERRUPTION_FILTER_ALL
                &&notifications.getCurrentInterruptionFilter()!=NotificationManager.INTERRUPTION_FILTER_UNKNOWN)
            return "勿扰模式可能拦截提醒，请在系统设置中检查";
        AudioManager audio=context.getSystemService(AudioManager.class);
        if(audio!=null){
            String setting=audio.getRingerMode()==AudioManager.RINGER_MODE_NORMAL?"vibrate_in_normal":"vibrate_in_silent";
            if(Settings.System.getInt(context.getContentResolver(),setting,-1)==0)
                return "vibrate_in_silent".equals(setting)?"系统已关闭静音时振动，请在声音与振动设置中开启":"系统已关闭响铃时振动，请在声音与振动设置中开启";
        }
        if(Settings.System.getInt(context.getContentResolver(),"notification_vibration_intensity",-1)==0)
            return "系统通知振动强度为零，请在声音与振动设置中调整";
        return "";
    }
    String status(){return status;}
    String explanation(){String blocked=blockedReason();return !blocked.isEmpty()?blocked:"error".equals(status)?"震动调用失败，请检查系统设置":"已请求手机震动；是否有震感请实际确认";}
    boolean alert(){
        if(!blockedReason().isEmpty()){status="blocked_by_system_settings";return false;}
        try{
            VibrationEffect effect=VibrationEffect.createWaveform(new long[]{0,180,100,180,100,300},-1);
            if(Build.VERSION.SDK_INT>=33)vibrator.vibrate(effect,new VibrationAttributes.Builder().setUsage(VibrationAttributes.USAGE_NOTIFICATION).build());
            else vibrator.vibrate(effect,new AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_NOTIFICATION).build());
            status="request_submitted";return true;
        }catch(RuntimeException error){status="error";return false;}
    }
    void cancel(){if(vibrator!=null)try{vibrator.cancel();}catch(RuntimeException ignored){}}
}
