package com.shengying.beantest;

import android.content.Context;
import android.util.Base64;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Bounded, adaptive original-PCM delivery. Networking never blocks the decoder/SDK. */
final class AudioSink implements AutoCloseable {
    private static final class Session {
        String id; final String source,device; final int rate,channels;
        long sequence,sample; boolean reset;
        Session(String id,String source,String device,int rate,int channels){this.id=id;this.source=source;this.device=device;this.rate=rate;this.channels=channels;}
        int bytesPerSecond(){return rate*channels*2;}
    }
    private static final class Chunk {
        final Session session; final byte[] pcm; final long wall,queued; final boolean end;
        Chunk(Session session,byte[] pcm,long wall,boolean end){this.session=session;this.pcm=pcm;this.wall=wall;this.end=end;queued=System.nanoTime();}
    }
    private final Consumer<String> log;
    private final ArrayDeque<Chunk> queue=new ArrayDeque<>();
    private final ByteArrayOutputStream pending=new ByteArrayOutputStream();
    private final Thread worker;
    private String endpoint="",token="";
    private Session current;
    private long capturedAt,queuedBytes,inputBytes,sentBytes,requests,recoveries,droppedBytes,maxQueuedBytes;
    private boolean busy;
    private volatile boolean closed;
    private volatile HttpURLConnection active;
    private volatile String lastFailure="";
    final boolean enabled;
    AudioSink(Context context,Consumer<String> log){
        this.log=log;boolean configured=false;
        try(InputStream in=new File(context.getFilesDir(),"backend-config.json").exists()
                ?new FileInputStream(new File(context.getFilesDir(),"backend-config.json")):context.getAssets().open("backend-config.json");
            ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] bytes=new byte[4096];int n;
            while((n=in.read(bytes))!=-1){if(out.size()+n>8192)throw new IOException("Config too large");out.write(bytes,0,n);}
            JSONObject c=new JSONObject(out.toString("UTF-8"));configured=c.optBoolean("enabled",false);
            if(configured){endpoint=c.getString("audio_endpoint");token=c.getString("write_token");URL url=new URL(endpoint);
                if(!(url.getProtocol().equals("https")||(url.getProtocol().equals("http")&&(url.getHost().equals("127.0.0.1")||url.getHost().equals("localhost")))))throw new IOException("HTTPS required except loopback");
                if(!url.getPath().equals("/v1/audio/chunks")||url.getQuery()!=null||url.getUserInfo()!=null||token.length()<24)throw new IOException("Invalid backend configuration");}
        }catch(FileNotFoundException absent){configured=false;}
        catch(Exception invalid){configured=false;log.accept("Audio backend config unavailable: "+invalid.getClass().getSimpleName());}
        enabled=configured;
        worker=new Thread(this::run,"BeanPcmUpload");worker.start();
        log.accept(enabled?"Audio backend enabled; adaptive PCM delivery; local recognition disabled":"Cloud audio upload unavailable");
    }
    /** Local instrumentation transport; it cannot target any external host. */
    AudioSink(String endpoint,Consumer<String> log){
        if(!endpoint.matches("http://127\\.0\\.0\\.1:[0-9]+/v1/audio/chunks"))throw new IllegalArgumentException("Loopback test endpoint required");
        this.endpoint=endpoint;this.token="explicit-local-instrumentation-token";this.log=log;enabled=true;
        worker=new Thread(this::run,"BeanPcmUploadTest");worker.start();
    }
    synchronized void begin(String id,String source,String device,int rate,int channels){
        if(!enabled||closed)return;
        end();
        if((rate!=16000&&rate!=48000)||(channels!=1&&channels!=2)){log.accept("Audio backend requires 16/48 kHz PCM, 1 or 2 channels");return;}
        current=new Session(id,source,device,rate,channels);pending.reset();
    }
    synchronized void pcm(byte[] bytes,long wall){
        if(!enabled||closed||current==null)return;
        if(bytes.length%(current.channels*2)!=0)throw new IllegalArgumentException("Unaligned PCM");
        capturedAt=wall;inputBytes+=bytes.length;pending.write(bytes,0,bytes.length);
        int size=current.bytesPerSecond()/5;
        if(pending.size()<size)return;
        byte[] all=pending.toByteArray();pending.reset();int offset=0;
        while(all.length-offset>=size){enqueue(new Chunk(current,Arrays.copyOfRange(all,offset,offset+size),capturedAt,false));offset+=size;}
        pending.write(all,offset,all.length-offset);
    }
    synchronized void end(){
        if(!enabled||closed||current==null)return;
        enqueue(new Chunk(current,pending.toByteArray(),capturedAt,true));pending.reset();current=null;
    }
    private void enqueue(Chunk chunk){
        // Bound memory by audio duration. A prolonged outage discards old audio explicitly,
        // then rebases the transport session; it must not silently disable future speech.
        int limit=chunk.session.bytesPerSecond()*12;
        boolean dropped=false;
        while(!queue.isEmpty()&&(queuedBytes+chunk.pcm.length>limit||queue.size()>=128)){
            Chunk old=queue.removeFirst();queuedBytes-=old.pcm.length;droppedBytes+=old.pcm.length;old.session.reset=true;dropped=true;
        }
        if(dropped)log.accept("Audio upload recovering: old queued audio expired; new speech continues in a new session");
        queue.addLast(chunk);queuedBytes+=chunk.pcm.length;maxQueuedBytes=Math.max(maxQueuedBytes,queuedBytes);notifyAll();
    }
    private void run(){
        while(true){
            Chunk first;byte[] pcm;boolean end;long wall;
            synchronized(this){
                while(queue.isEmpty()&&!closed)try{wait();}catch(InterruptedException stop){if(closed)return;}
                if(closed)return;
                first=queue.removeFirst();queuedBytes-=first.pcm.length;
                ByteArrayOutputStream batch=new ByteArrayOutputStream();batch.write(first.pcm,0,first.pcm.length);end=first.end;wall=first.wall;
                // Coalesce only already-waiting adjacent chunks. Each request remains <=1s,
                // as required by the existing server contract, without delaying fresh audio.
                while(!end&&!queue.isEmpty()){
                    Chunk next=queue.peekFirst();
                    if(next.session!=first.session||batch.size()+next.pcm.length>first.session.bytesPerSecond())break;
                    queue.removeFirst();queuedBytes-=next.pcm.length;batch.write(next.pcm,0,next.pcm.length);end=next.end;wall=next.wall;
                }
                pcm=batch.toByteArray();busy=true;
                if(first.session.reset){rebase(first.session);first.session.reset=false;}
            }
            boolean delivered=post(first.session,pcm,wall,end);
            synchronized(this){
                if(!delivered&&!closed){droppedBytes+=pcm.length;rebase(first.session);log.accept("Audio upload recovering after request failure; following audio resumes automatically");}
                busy=false;notifyAll();
            }
        }
    }
    private void rebase(Session session){session.id=UUID.randomUUID().toString();session.sequence=session.sample=0;recoveries++;}
    private boolean post(Session session,byte[] pcm,long wall,boolean end){
        final byte[] payload;
        try{
            JSONObject e=new JSONObject();e.put("schema_version","1.0");e.put("session_id",session.id);e.put("source",session.source);e.put("device_sn",session.device);
            e.put("encoding","pcm_s16le");e.put("sample_rate",session.rate);e.put("channels",session.channels);e.put("sequence",session.sequence);e.put("start_sample",session.sample);
            e.put("captured_at_ms",wall);e.put("end",end);e.put("audio_base64",Base64.encodeToString(pcm,Base64.NO_WRAP));
            // Android JSONObject escapes '/' in base64. PCM near negative zero contains
            // many '/' characters: a nominal 256KB string can become >300KB on the wire.
            // JSON permits literal '/', so keep base64 compact under the existing HTTP cap.
            payload=e.toString().replace("\\/","/").getBytes(StandardCharsets.UTF_8);
            if(payload.length>300000)throw new IOException("Audio request exceeds existing backend limit");
        }catch(Exception invalid){return false;}
        for(int attempt=0;attempt<3&&!closed;attempt++){
            HttpURLConnection c=null;
            try{
                c=(HttpURLConnection)new URL(endpoint).openConnection();active=c;c.setInstanceFollowRedirects(false);c.setRequestMethod("POST");c.setConnectTimeout(2000);c.setReadTimeout(3000);c.setDoOutput(true);
                c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Authorization","Bearer "+token);c.setFixedLengthStreamingMode(payload.length);
                try(OutputStream out=c.getOutputStream()){out.write(payload);}int status=c.getResponseCode();
                // Consume response so Android can reuse the HTTPS connection for the next batch.
                InputStream response=status<400?c.getInputStream():c.getErrorStream();
                if(response!=null)try(InputStream in=response){byte[] bytes=new byte[1024];int count,total=0;while((count=in.read(bytes))!=-1){total+=count;if(total>65536)throw new IOException("Response too large");}}
                synchronized(this){requests++;}
                if(status==200||status==202){session.sequence++;session.sample+=pcm.length/(session.channels*2);synchronized(this){sentBytes+=pcm.length;}return true;}
                lastFailure="HTTP "+status;
                if(status!=503&&status!=429&&status<500)return false;
            }catch(Exception temporary){lastFailure=temporary.getClass().getSimpleName();/* Retry identical sequence. */}
            finally{if(c!=null)c.disconnect();active=null;}
            try{Thread.sleep(100L*(attempt+1));}catch(InterruptedException stop){Thread.currentThread().interrupt();return false;}
        }
        return false;
    }
    synchronized JSONObject diagnostics(){
        JSONObject value=new JSONObject();try{value.put("input_bytes",inputBytes);value.put("sent_bytes",sentBytes);value.put("http_responses",requests);value.put("queued_bytes",queuedBytes);value.put("max_queued_bytes",maxQueuedBytes);value.put("recoveries",recoveries);value.put("dropped_bytes",droppedBytes);value.put("busy",busy);value.put("last_failure",lastFailure);}catch(Exception ignored){}return value;
    }
    synchronized boolean awaitDrained(long timeout)throws InterruptedException{
        long until=System.currentTimeMillis()+timeout;
        while((busy||!queue.isEmpty())&&!closed){long left=until-System.currentTimeMillis();if(left<=0)return false;wait(left);}return !busy&&queue.isEmpty();
    }
    @Override public void close(){
        synchronized(this){closed=true;pending.reset();queue.clear();queuedBytes=0;notifyAll();}
        HttpURLConnection c=active;if(c!=null)c.disconnect();worker.interrupt();
    }
}
