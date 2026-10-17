package com.shengying.beantest;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.widget.TextView;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.function.BiConsumer;
import java.util.function.Consumer;

/** Cloud captions keep working while the independent timely-reply switch is off. */
final class RemoteCaptionFeed implements AutoCloseable {
    private final Handler ui=new Handler(Looper.getMainLooper());
    private final TextView captions;
    private final BiConsumer<String,String> log;
    private final Thread worker;
    private final Consumer<JSONObject> transcripts;
    private final Consumer<String> status;
    private volatile boolean closed;
    private volatile HttpURLConnection active;

    RemoteCaptionFeed(Context context,TextView captions,BiConsumer<String,String> log){
        this(context,captions,log,event->{});
    }
    RemoteCaptionFeed(Context context,TextView captions,BiConsumer<String,String> log,Consumer<JSONObject> transcripts){
        this(context,captions,log,transcripts,captions::setText);
    }
    RemoteCaptionFeed(Context context,TextView captions,BiConsumer<String,String> log,Consumer<JSONObject> transcripts,Consumer<String> status){
        this.captions=captions;this.log=log;this.transcripts=transcripts;this.status=status;
        File config=new File(context.getFilesDir(),"reply-config.json");
        worker=new Thread(()->run(config),"BeanRemoteCaptions");worker.start();
    }

    private JSONObject get(String endpoint,String token)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(endpoint).openConnection();active=c;
        try{
            c.setInstanceFollowRedirects(false);c.setConnectTimeout(2000);c.setReadTimeout(6000);
            c.setRequestProperty("Authorization","Bearer "+token);
            if(c.getResponseCode()!=200)throw new java.io.IOException("Caption feed unavailable");
            try(InputStream in=c.getInputStream();ByteArrayOutputStream out=new ByteArrayOutputStream()){
                byte[] b=new byte[4096];int n;
                while((n=in.read(b))!=-1){if(out.size()+n>1048576)throw new java.io.IOException("Feed too large");out.write(b,0,n);}
                return new JSONObject(out.toString("UTF-8"));
            }
        }finally{c.disconnect();active=null;}
    }

    private void run(File file){
        try{
            JSONObject config=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
            String base=config.getString("gateway_url"),token=config.getString("read_token");
            URL url=new URL(base);
            if(!(url.getProtocol().equals("https")||(url.getProtocol().equals("http")&&(url.getHost().equals("127.0.0.1")||url.getHost().equals("localhost")))))throw new java.io.IOException("HTTPS required");
            if(url.getUserInfo()!=null||url.getQuery()!=null||url.getRef()!=null)throw new java.io.IOException("Invalid caption URL");
            String cursor=null;boolean warned=false;
            while(!closed){
                try{
                    if(cursor==null){cursor=get(base+"/v1/transcripts/cursor",token).getString("cursor");log.accept("remote_caption_ready","cloud text feed connected");ui.post(()->{if(!closed)status.accept("字幕已就绪 · 按录音豆实体键开始录音");});}
                    JSONObject response=get(base+"/v1/transcripts?cursor="+cursor+"&source=bean_live&limit=20&wait_ms=4000",token);
                    JSONArray events=response.getJSONArray("events");
                    for(int i=0;i<events.length();i++){
                        JSONObject e=events.getJSONObject(i);if(!e.optBoolean("is_final"))continue;
                        String text=e.getString("text"),id=e.getString("utterance_id");
                        ui.post(()->{if(!closed){captions.setText(text);status.accept("已接收原话 · 说话人未确定");}});
                        log.accept("remote_caption", "utterance="+id);
                        transcripts.accept(e);
                    }
                    cursor=response.getString("next_cursor");warned=false;
                }catch(Exception error){
                    if(!closed&&!warned){warned=true;log.accept("remote_caption_unavailable",error.getClass().getSimpleName());
                        ui.post(()->{if(!closed)status.accept("字幕连接暂不可用，正在重连…");});}
                    if(!closed)Thread.sleep(1000);
                }
            }
        }catch(Exception error){if(!closed){log.accept("remote_caption_config_error",error.getClass().getSimpleName());
            ui.post(()->{if(!closed)status.accept("字幕暂不可用，请检查设备与服务连接。");});}}
    }

    public void close(){closed=true;HttpURLConnection c=active;if(c!=null)c.disconnect();worker.interrupt();}
}
