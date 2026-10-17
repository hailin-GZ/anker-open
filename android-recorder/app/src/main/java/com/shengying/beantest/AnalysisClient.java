package com.shengying.beantest;

import android.content.Context;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.function.Consumer;

/** Reads only the project's analysis results; the Alibaba Cloud key remains on the server. */
final class AnalysisClient implements AutoCloseable {
    private final Context context;
    private final Consumer<JSONObject> onEvent;
    private final Consumer<String> onStatus;
    private final String endpoint, token;
    private final Thread thread;
    private volatile boolean closed;
    private volatile HttpURLConnection active;
    final boolean enabled;

    AnalysisClient(Context context, Consumer<JSONObject> onEvent, Consumer<String> onStatus) {
        this.context = context.getApplicationContext();
        this.onEvent = onEvent;
        this.onStatus = onStatus;
        String configuredEndpoint = "", configuredToken = "";
        boolean configured = false;
        try (InputStream input = new FileInputStream(new File(context.getFilesDir(), "analysis-config.json"));
             ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
            byte[] chunk = new byte[4096]; int count;
            while ((count = input.read(chunk)) != -1) {
                if (bytes.size() + count > 8192) throw new IllegalArgumentException("Config too large");
                bytes.write(chunk, 0, count);
            }
            JSONObject config = new JSONObject(bytes.toString("UTF-8"));
            configured = config.optBoolean("enabled", false);
            if (configured) {
                configuredEndpoint = config.getString("analysis_endpoint");
                configuredToken = config.getString("read_token");
                URL url = new URL(configuredEndpoint);
                if (!(url.getProtocol().equals("https") ||
                    (url.getProtocol().equals("http") && (url.getHost().equals("127.0.0.1") || url.getHost().equals("localhost")))))
                    throw new IllegalArgumentException("HTTPS required except local loopback");
                if (!url.getPath().equals("/v1/analysis") || url.getQuery() != null || url.getUserInfo() != null || url.getRef() != null || configuredToken.length() < 24)
                    throw new IllegalArgumentException("Invalid analysis configuration");
            }
        } catch (java.io.FileNotFoundException absent) {
            configured = false;
        } catch (Exception invalid) {
            configured = false;
            onStatus.accept("分析配置无效：" + invalid.getClass().getSimpleName());
        }
        endpoint = configuredEndpoint; token = configuredToken; enabled = configured;
        thread = enabled ? new Thread(this::loop, "analysis-poll") : null;
        if (enabled) { thread.setDaemon(true); thread.start(); }
        else onStatus.accept("危险音与情绪分析未配置");
    }

    private void loop() {
        String cursor = "latest"; // Foreground subscription: never vibrate for historical results.
        while (!closed) {
            HttpURLConnection connection = null;
            try {
                URL url = new URL(endpoint + "?cursor=" + java.net.URLEncoder.encode(cursor, "UTF-8") + "&wait_ms=15000&limit=20");
                connection = (HttpURLConnection) url.openConnection();
                active = connection;
                connection.setRequestProperty("Authorization", "Bearer " + token);
                connection.setConnectTimeout(5000); connection.setReadTimeout(22000);
                connection.setInstanceFollowRedirects(false);
                int status = connection.getResponseCode();
                if (status == 410 || status == 409) {
                    onStatus.accept("分析结果游标过期，已跳过过期结果并继续接收新结果");
                    cursor = "latest";
                    context.getSharedPreferences("analysis", Context.MODE_PRIVATE).edit().putString("cursor", cursor).apply();
                    Thread.sleep(1000);
                    continue;
                }
                if (status != 200) throw new java.io.IOException("HTTP " + status);
                JSONObject page;
                try (InputStream input = connection.getInputStream(); ByteArrayOutputStream bytes = new ByteArrayOutputStream()) {
                    byte[] chunk = new byte[4096]; int count;
                    while ((count = input.read(chunk)) != -1) {
                        if (bytes.size() + count > 1024*1024) throw new java.io.IOException("Result page too large");
                        bytes.write(chunk, 0, count);
                    }
                    page = new JSONObject(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
                }
                JSONArray events = page.getJSONArray("events");
                for (int i=0; i<events.length(); i++) {
                    JSONObject event = events.getJSONObject(i);
                    if(page.has("server_time_ms"))event.put("age_at_receive_ms",Math.max(0,page.getLong("server_time_ms")-event.optLong("created_at_ms",0)));
                    onEvent.accept(event);
                    cursor = event.getString("event_id");
                    context.getSharedPreferences("analysis", Context.MODE_PRIVATE).edit().putString("cursor", cursor).commit();
                }
                cursor = page.getString("next_cursor");
                context.getSharedPreferences("analysis", Context.MODE_PRIVATE).edit().putString("cursor", cursor).apply();
                if (events.length() == 0 && !page.optBoolean("has_more")) onStatus.accept("分析服务已连接，等待音频结果");
            } catch (InterruptedException stop) {
                Thread.currentThread().interrupt(); return;
            } catch (Exception error) {
                if (!closed) {
                    onStatus.accept("分析服务暂不可用：" + error.getClass().getSimpleName() + "，稍后重试");
                    try { Thread.sleep(3000); } catch (InterruptedException stop) { Thread.currentThread().interrupt(); return; }
                }
            } finally {
                if (connection != null) connection.disconnect();
                active = null;
            }
        }
    }

    @Override public void close() {
        closed = true;
        if (active != null) active.disconnect();
        if (thread != null) thread.interrupt();
    }
}

