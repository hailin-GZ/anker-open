package com.shengying.beantest;

import android.app.*;
import android.content.*;
import android.os.Build;
import org.json.JSONObject;

/** Inexact system reminders; independent from foreground environmental listening. */
public class ReminderReceiver extends BroadcastReceiver {
    static final String CHANNEL="important-marks";
    static PendingIntent pending(Context c,long id){return PendingIntent.getBroadcast(c,(int)id,new Intent(c,ReminderReceiver.class).putExtra("note",id),PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);}
    static void schedule(Context c,long id,long time){if(time>0)((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,time,pending(c,id));}
    static void cancel(Context c,long id){((AlarmManager)c.getSystemService(Context.ALARM_SERVICE)).cancel(pending(c,id));c.getSystemService(NotificationManager.class).cancel((int)id);}
    static void channel(Context c){NotificationManager n=c.getSystemService(NotificationManager.class);n.createNotificationChannel(new NotificationChannel(CHANNEL,"重要标记提醒",NotificationManager.IMPORTANCE_HIGH));}
    @Override public void onReceive(Context c,Intent intent){
        try(UiStore store=new UiStore(c)){
            if(Intent.ACTION_BOOT_COMPLETED.equals(intent.getAction())){for(JSONObject note:store.rows("SELECT * FROM notes WHERE deleted=0 AND done=0 AND due>0 AND notified=0"))schedule(c,note.optLong("id"),Math.max(System.currentTimeMillis()+1000,note.optLong("due")));return;}
            long id=intent.getLongExtra("note",-1);
            for(JSONObject note:store.rows("SELECT * FROM notes WHERE id=? AND deleted=0 AND done=0 AND notified=0",""+id)){
                store.notified(id);channel(c);
                Intent open=new Intent(c,MainActivity.class).putExtra("open_notifications",true).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP|Intent.FLAG_ACTIVITY_SINGLE_TOP);
                PendingIntent target=PendingIntent.getActivity(c,(int)id,open,PendingIntent.FLAG_UPDATE_CURRENT|PendingIntent.FLAG_IMMUTABLE);
                Notification.Builder b=new Notification.Builder(c,CHANNEL).setSmallIcon(android.R.drawable.ic_lock_idle_alarm).setContentTitle("声应 · 重要标记提醒").setContentText(note.optString("text")).setStyle(new Notification.BigTextStyle().bigText(note.optString("text"))).setContentIntent(target).setAutoCancel(true);
                try{c.getSystemService(NotificationManager.class).notify((int)id,b.build());}catch(SecurityException denied){/* The in-app notification remains available. */}
            }
        }
    }
}
