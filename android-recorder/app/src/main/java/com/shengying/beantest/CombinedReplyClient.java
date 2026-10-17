package com.shengying.beantest;

import android.content.Context;
import org.json.JSONObject;
import java.io.File;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Opt-in lease: emotion and four replies share one server-side audio call. */
final class CombinedReplyClient implements AutoCloseable {
    final String epoch=UUID.randomUUID().toString();
    private final ScheduledExecutorService control=Executors.newSingleThreadScheduledExecutor();
    private final String base,token;
    private final Consumer<String> status;
    private final AnalysisClient feed;
    private volatile boolean closed;
    CombinedReplyClient(Context context,Consumer<JSONObject> events,Consumer<String> status)throws Exception{
        this.status=status;
        JSONObject config=new JSONObject(new String(Files.readAllBytes(new File(context.getFilesDir(),"analysis-config.json").toPath()),StandardCharsets.UTF_8));
        String endpoint=config.getString("analysis_endpoint");URL url=new URL(endpoint);
        boolean local=url.getHost().equals("127.0.0.1")||url.getHost().equals("localhost");
        if(!(url.getProtocol().equals("https")||(url.getProtocol().equals("http")&&local)))throw new IllegalArgumentException("HTTPS required");
        if(!url.getPath().equals("/v1/analysis")||url.getQuery()!=null||url.getUserInfo()!=null||url.getRef()!=null)throw new IllegalArgumentException("Invalid analysis origin");
        base=endpoint.substring(0,endpoint.length()-"/v1/analysis".length());token=config.getString("control_token");
        if(token.length()<24)throw new IllegalArgumentException("Reply control is not configured");
        feed=new AnalysisClient(context,e->{
            if(!closed&&epoch.equals(e.optString("reply_epoch"))&&"bean_live".equals(e.optString("source")))events.accept(e);
        },s->{if(!closed&&s.contains("不可用"))status.accept("联合回应连接暂不可用");});
        control.scheduleAtFixedRate(()->{
            if(closed)return;
            try{send(true);if(!closed)status.accept("ready");}
            catch(Exception error){if(!closed)status.accept("联合回应暂不可用，请检查电脑服务");}
        },0,20,TimeUnit.SECONDS);
    }
    private void send(boolean enabled)throws Exception{
        HttpURLConnection c=(HttpURLConnection)new URL(base+"/v1/reply-interest").openConnection();
        try{
            c.setRequestMethod("POST");c.setInstanceFollowRedirects(false);c.setConnectTimeout(1500);c.setReadTimeout(2000);c.setDoOutput(true);
            c.setRequestProperty("Authorization","Bearer "+token);c.setRequestProperty("Content-Type","application/json");
            byte[] body=new JSONObject().put("enabled",enabled).put("epoch",epoch).toString().getBytes(StandardCharsets.UTF_8);
            c.setFixedLengthStreamingMode(body.length);
            try(java.io.OutputStream out=c.getOutputStream()){out.write(body);}
            if(c.getResponseCode()!=200)throw new java.io.IOException("Reply subscription unavailable");
        }finally{c.disconnect();}
    }
    public void close(){
        if(closed)return;closed=true;feed.close();
        control.execute(()->{try{send(false);}catch(Exception ignored){}});control.shutdown();
    }
}
