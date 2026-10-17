package com.shengying.beantest;

import org.json.JSONObject;
import org.json.JSONArray;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.*;
import java.util.function.Consumer;

/** JSONL evidence for every update; only final live/replay sentences are sent over HTTP. */
final class TranscriptSink implements AutoCloseable {
    private final BufferedWriter writer;private final Consumer<String> log;private final Consumer<JSONObject> replies;
    private final ThreadPoolExecutor sender=new ThreadPoolExecutor(1,1,0,TimeUnit.SECONDS,new ArrayBlockingQueue<>(16));
    volatile String endpoint="http://127.0.0.1:8765/transcripts",latestUtterance="";volatile boolean enabled=true,textOnly,remoteProducer;
    TranscriptSink(File files,Consumer<String> log,Consumer<JSONObject> replies)throws Exception{this.log=log;this.replies=replies;writer=new BufferedWriter(new OutputStreamWriter(new FileOutputStream(new File(files,"transcripts.jsonl"),true),StandardCharsets.UTF_8));}
    synchronized void accept(JSONObject event)throws Exception{
        latestUtterance=event.optString("utterance_id");String body=event.toString();writer.write(body+"\n");writer.flush();if(remoteProducer||!enabled||!event.optBoolean("is_final"))return;
        boolean compatibility=textOnly;if(compatibility)body=new JSONObject().put("text",event.getString("text")).toString();final String payload=body;
        String destination=endpoint;long queued=System.nanoTime();
        try{sender.execute(()->post(destination,payload,event.optString("utterance_id"),queued));}catch(RejectedExecutionException full){log.accept("HTTP queue full; final not sent, retained in transcripts.jsonl");}
    }
    private void post(String destination,String body,String id,long queued){
        HttpURLConnection connection=null;
        try{
            if(System.nanoTime()-queued>TimeUnit.SECONDS.toNanos(10)){log.accept("HTTP expired "+id);return;}
            URL url=new URL(destination);if(!url.getProtocol().equals("https")&&!(url.getProtocol().equals("http")&&(url.getHost().equals("127.0.0.1")||url.getHost().equals("localhost"))))throw new IOException("Use HTTPS except for localhost test receiver");
            connection=(HttpURLConnection)url.openConnection();connection.setInstanceFollowRedirects(false);connection.setRequestMethod("POST");connection.setConnectTimeout(2500);connection.setReadTimeout(3000);connection.setDoOutput(true);connection.setRequestProperty("Content-Type","application/json; charset=utf-8");connection.setRequestProperty("Idempotency-Key",id);
            byte[] bytes=body.getBytes(StandardCharsets.UTF_8);connection.setFixedLengthStreamingMode(bytes.length);try(OutputStream out=connection.getOutputStream()){out.write(bytes);}int code=connection.getResponseCode();
            log.accept("HTTP status="+code+" utterance="+id+" elapsedMs="+TimeUnit.NANOSECONDS.toMillis(System.nanoTime()-queued));
            if(code>=200&&code<300){try(InputStream in=connection.getInputStream();ByteArrayOutputStream buffer=new ByteArrayOutputStream()){byte[] chunk=new byte[4096];int n;while((n=in.read(chunk))!=-1){if(buffer.size()+n>65536)throw new IOException("Reply too large");buffer.write(chunk,0,n);}if(buffer.size()>0){JSONObject response=new JSONObject(buffer.toString("UTF-8"));JSONArray options=response.optJSONArray("options");if(options!=null&&options.length()>0){if(!id.equals(latestUtterance)){log.accept("HTTP stale reply discarded "+id);return;}if(options.length()>6)throw new IOException("Too many options");for(int i=0;i<options.length();i++)if(!(options.get(i) instanceof String)||options.getString(i).length()>200)throw new IOException("Invalid reply option");response.put("utterance_id",id);replies.accept(response);}}}}
        }catch(Exception e){log.accept("HTTP failed "+e.getClass().getSimpleName()+": "+e.getMessage()+"; final retained locally");}finally{if(connection!=null)connection.disconnect();}
    }
    @Override public synchronized void close()throws Exception{sender.shutdown();writer.close();}
}
