package com.shengying.beantest;

import android.content.*;
import android.database.Cursor;
import android.database.sqlite.*;
import org.json.JSONObject;
import java.util.*;

/** App-private UI history. It never changes or replays the audio/analysis feeds. */
final class UiStore extends SQLiteOpenHelper {
    UiStore(Context c){super(c,"shengying-ui-v1.db",null,3);}
    @Override public void onCreate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE sentences(sid TEXT NOT NULL,uid TEXT NOT NULL,text TEXT NOT NULL,stamp INTEGER NOT NULL,emotion TEXT DEFAULT '',tone TEXT DEFAULT '',evidence TEXT DEFAULT '',PRIMARY KEY(sid,uid))");
        db.execSQL("CREATE TABLE notes(id INTEGER PRIMARY KEY AUTOINCREMENT,text TEXT NOT NULL,stamp INTEGER NOT NULL,due INTEGER DEFAULT 0,done INTEGER DEFAULT 0,notified INTEGER DEFAULT 0,sid TEXT DEFAULT '',uid TEXT DEFAULT '')");
        db.execSQL("CREATE TABLE voices(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL,kind TEXT NOT NULL,path TEXT NOT NULL,stamp INTEGER NOT NULL)");
        migrate(db);
        db.execSQL("ALTER TABLE notes ADD COLUMN kind TEXT DEFAULT ''");
    }
    private void migrate(SQLiteDatabase db){
        db.execSQL("CREATE TABLE IF NOT EXISTS session_categories(sid TEXT PRIMARY KEY,category TEXT NOT NULL DEFAULT 'other')");
        db.execSQL("ALTER TABLE notes ADD COLUMN pinned INTEGER DEFAULT 0");
        db.execSQL("ALTER TABLE notes ADD COLUMN deleted INTEGER DEFAULT 0");
    }
    @Override public void onUpgrade(SQLiteDatabase db,int a,int b){if(a<2)migrate(db);if(a<3)db.execSQL("ALTER TABLE notes ADD COLUMN kind TEXT DEFAULT ''");}
    String noteKind(JSONObject n){String value=n.optString("kind");return value.isEmpty()?(n.optLong("due")>0?"reminder":!n.optString("sid").isEmpty()?"quote":"todo"):value;}
    void noteKind(long id,String value){if(!Arrays.asList("todo","quote","reminder").contains(value))throw new IllegalArgumentException();ContentValues v=new ContentValues();v.put("kind",value);getWritableDatabase().update("notes",v,"id=?",new String[]{""+id});}
    String category(String sid){List<JSONObject> r=rows("SELECT category FROM session_categories WHERE sid=?",sid);return r.isEmpty()?"other":r.get(0).optString("category","other");}
    void category(String sid,String category){ContentValues v=new ContentValues();v.put("sid",sid);v.put("category",category);getWritableDatabase().insertWithOnConflict("session_categories",null,v,SQLiteDatabase.CONFLICT_REPLACE);}
    void noteFlag(long id,String flag,int value){if(!flag.equals("pinned")&&!flag.equals("deleted"))throw new IllegalArgumentException();ContentValues v=new ContentValues();v.put(flag,value);getWritableDatabase().update("notes",v,"id=?",new String[]{""+id});}
    void editNote(long id,String text,long due){ContentValues v=new ContentValues();v.put("text",text);v.put("due",due);v.put("notified",0);getWritableDatabase().update("notes",v,"id=?",new String[]{""+id});}
    synchronized boolean transcript(JSONObject e){
        if(!e.optBoolean("is_final")||!"bean_live".equals(e.optString("source")))return false;
        String sid=e.optString("session_id"),uid=e.optString("utterance_id"),text=e.optString("text");
        if(sid.isEmpty()||uid.isEmpty()||text.trim().isEmpty())return false;
        SQLiteDatabase db=getWritableDatabase();ContentValues v=new ContentValues();v.put("sid",sid);v.put("uid",uid);v.put("text",text);
        v.put("stamp",e.optLong("received_at_ms",System.currentTimeMillis()));
        if(db.insertWithOnConflict("sentences",null,v,SQLiteDatabase.CONFLICT_IGNORE)==-1){v.remove("stamp");db.update("sentences",v,"sid=? AND uid=?",new String[]{sid,uid});}return true;
    }
    synchronized void emotion(JSONObject e){
        String sid=e.optString("session_id"),uid=e.optString("utterance_id");if(sid.isEmpty()||uid.isEmpty())return;
        ContentValues v=new ContentValues();v.put("emotion",e.optString("emotion"));v.put("tone",e.optString("tone"));v.put("evidence",e.optString("evidence"));
        getWritableDatabase().update("sentences",v,"sid=? AND uid=?",new String[]{sid,uid});
        // Analysis can arrive before the caption. Insert using its immutable sentence IDs.
        v.put("sid",sid);v.put("uid",uid);v.put("text",e.optString("text"));v.put("stamp",e.optLong("utterance_time_ms",System.currentTimeMillis()));
        getWritableDatabase().insertWithOnConflict("sentences",null,v,SQLiteDatabase.CONFLICT_IGNORE);
    }
    List<JSONObject> rows(String sql,String...args){
        List<JSONObject> result=new ArrayList<>();try(Cursor c=getReadableDatabase().rawQuery(sql,args)){
            while(c.moveToNext()){JSONObject o=new JSONObject();for(int i=0;i<c.getColumnCount();i++)try{if(c.getType(i)==Cursor.FIELD_TYPE_INTEGER)o.put(c.getColumnName(i),c.getLong(i));else o.put(c.getColumnName(i),c.getString(i));}catch(Exception ignored){}result.add(o);}
        }return result;
    }
    long addNote(String text,long due,String sid,String uid){ContentValues v=new ContentValues();v.put("text",text);v.put("stamp",System.currentTimeMillis());v.put("due",due);v.put("sid",sid);v.put("uid",uid);return getWritableDatabase().insertOrThrow("notes",null,v);}
    void done(long id,boolean done){ContentValues v=new ContentValues();v.put("done",done?1:0);if(!done)v.put("notified",0);getWritableDatabase().update("notes",v,"id=?",new String[]{""+id});}
    void notified(long id){ContentValues v=new ContentValues();v.put("notified",1);getWritableDatabase().update("notes",v,"id=?",new String[]{""+id});}
    void addVoice(String name,String kind,String path){ContentValues v=new ContentValues();v.put("name",name);v.put("kind",kind);v.put("path",path);v.put("stamp",System.currentTimeMillis());getWritableDatabase().insertOrThrow("voices",null,v);}
}
