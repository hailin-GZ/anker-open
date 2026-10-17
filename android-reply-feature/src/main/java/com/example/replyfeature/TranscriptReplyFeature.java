package com.example.replyfeature;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashSet;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** Reads stable transcript events and delivers the newest four AI reply options. */
public final class TranscriptReplyFeature {
    public interface Listener {
        /** Called on the Android main thread. json has exactly one key: options (four strings). */
        void onOptions(String sessionId, String utteranceId, String recognizedText, JSONObject json);

        /** Called on the Android main thread; message contains no tokens or transcript text. */
        void onError(String message);
        default void onReady() {}
        default void onInput(String sessionId, String utteranceId, String recognizedText) {}
    }

    private static final int MAX_RECENT_IDS = 256;
    private final String baseUrl;
    private final String readToken;
    private final ReplyGenerator generator;
    private final Listener listener;
    private final SharedPreferences preferences;
    private final String cursorKey;
    private final String recentKey;
    private final Handler mainHandler = new Handler(Looper.getMainLooper());
    private final ExecutorService feedWorker = Executors.newSingleThreadExecutor();
    private final ThreadPoolExecutor modelWorkers = new ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(1));
    private final AtomicBoolean started = new AtomicBoolean(false);
    private final AtomicBoolean closed = new AtomicBoolean(false);
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicLong newestSequence = new AtomicLong(0);
    private final LinkedHashSet<String> recentIds = new LinkedHashSet<>();
    private volatile HttpURLConnection activeFeedRequest;
    private String lastFeedError;
    private volatile boolean startAtLatest;

    /** A user enable action processes future speech only, never speech heard while disabled. */
    public void startFromNow() {
        startAtLatest = true;
        start();
    }

    public TranscriptReplyFeature(Context context, String gatewayBaseUrl, String readToken,
                                  String deepseekApiKey, Listener listener) {
        this(context, gatewayBaseUrl, readToken, deepseekApiKey, ReplyGenerator.MODEL, listener);
    }

    public TranscriptReplyFeature(Context context, String gatewayBaseUrl, String readToken,
                                  String deepseekApiKey, String model, Listener listener) {
        this(context, gatewayBaseUrl, readToken, deepseekApiKey, model, "", listener);
    }

    public TranscriptReplyFeature(Context context, String gatewayBaseUrl, String readToken,
                                  String deepseekApiKey, String model, String replyEndpoint, Listener listener) {
        if (context == null || listener == null) {
            throw new IllegalArgumentException("Context and listener are required");
        }
        if (gatewayBaseUrl == null || gatewayBaseUrl.trim().isEmpty()
                || readToken == null || readToken.trim().isEmpty()) {
            throw new IllegalArgumentException("Gateway URL and READ_TOKEN are required");
        }
        String normalized = gatewayBaseUrl.trim().replaceAll("/+$", "");
        try {
            URL url = new URL(normalized);
            if (!("http".equals(url.getProtocol()) || "https".equals(url.getProtocol()))
                    || url.getHost().isEmpty() || url.getUserInfo() != null
                    || (url.getPath() != null && !url.getPath().isEmpty())
                    || url.getQuery() != null || url.getRef() != null) {
                throw new IllegalArgumentException("Gateway URL must be an HTTP(S) origin, without a path");
            }
        } catch (IOException error) {
            throw new IllegalArgumentException("Invalid gateway URL", error);
        }
        this.baseUrl = normalized;
        this.readToken = readToken.trim();
        this.generator = new ReplyGenerator(deepseekApiKey, model, replyEndpoint);
        this.listener = listener;
        this.preferences = context.getApplicationContext().getSharedPreferences(
                "transcript_reply_feature_v1", Context.MODE_PRIVATE);
        this.cursorKey = "cursor:" + this.baseUrl;
        this.recentKey = "recent:" + this.baseUrl;
        restoreRecent();
    }

    /** Begin listening for future final utterances. A stopped instance is not reusable. */
    public void start() {
        if (closed.get()) {
            return;
        }
        if (!started.compareAndSet(false, true)) {
            return;
        }
        running.set(true);
        try {
            feedWorker.execute(new Runnable() {
                @Override public void run() { pollLoop(); }
            });
        } catch (RejectedExecutionException stoppedDuringStart) {
            running.set(false);
        }
    }

    public void stop() {
        closed.set(true);
        running.set(false);
        newestSequence.incrementAndGet();
        HttpURLConnection request = activeFeedRequest;
        if (request != null) {
            request.disconnect();
        }
        feedWorker.shutdownNow();
        generator.close();
        modelWorkers.shutdownNow();
    }

    private void pollLoop() {
        String cursor = startAtLatest ? null : preferences.getString(cursorKey, null);
        boolean readyDelivered = false;
        while (running.get()) {
            try {
                if (cursor == null) {
                    // First start begins at the current position; old test transcripts are not replayed.
                    cursor = getJson("/v1/transcripts/cursor").getString("cursor");
                    saveState(cursor);
                }
                if (!readyDelivered) {
                    readyDelivered = true;
                    mainHandler.post(() -> { if (running.get()) listener.onReady(); });
                }
                String query = "/v1/transcripts?cursor=" + URLEncoder.encode(cursor, "UTF-8")
                        + "&wait_ms=15000&limit=20";
                JSONObject batch = getJson(query);
                JSONArray events = batch.getJSONArray("events");
                for (int i = 0; i < events.length() && running.get(); i++) {
                    JSONObject event = events.getJSONObject(i);
                    if (!"transcript.final".equals(event.optString("type"))
                            || !event.optBoolean("is_final")
                            || !"bean_live".equals(event.optString("source"))) {
                        continue;
                    }
                    String sessionId = event.getString("session_id");
                    String utteranceId = event.getString("utterance_id");
                    String text = event.getString("text").trim();
                    String identity = sessionId + "\u0000" + utteranceId;
                    if (!text.isEmpty() && !recentIds.contains(identity)) {
                        scheduleGeneration(sessionId, utteranceId, text);
                        remember(identity);
                    }
                }
                cursor = batch.getString("next_cursor");
                saveState(cursor);
                lastFeedError = null;
            } catch (GatewayHttpException error) {
                postFeedError("文字接口返回 HTTP " + error.status);
                if (error.status == 400 || error.status == 401 || error.status == 403
                        || error.status == 409) {
                    running.set(false); // Bad token/cursor/query requires a real fix.
                    return;
                }
                retryDelay();
            } catch (Exception error) {
                if (running.get()) {
                    postFeedError("读取文字接口失败：" + error.getClass().getSimpleName());
                    retryDelay();
                }
            }
        }
    }

    private void scheduleGeneration(final String sessionId, final String utteranceId,
                                    final String text) {
        final long sequence = newestSequence.incrementAndGet();
        mainHandler.post(() -> {
            if (running.get() && sequence == newestSequence.get()) {
                listener.onInput(sessionId, utteranceId, text);
            }
        });
        Runnable task = new Runnable() {
            @Override public void run() {
                if (!running.get() || sequence != newestSequence.get()) return;
                try {
                    final JSONObject json = generator.generate(text);
                    if (!running.get() || sequence != newestSequence.get()) return;
                    mainHandler.post(new Runnable() {
                        @Override public void run() {
                            if (running.get() && sequence == newestSequence.get()) {
                                listener.onOptions(sessionId, utteranceId, text, json);
                            }
                        }
                    });
                } catch (ReplyGenerator.ModelHttpException error) {
                    if (running.get() && sequence == newestSequence.get()) {
                        String message = error.statusCode == 401
                                ? "回应服务授权已失效，请重新激活云端服务"
                                : error.statusCode == 402
                                ? "回应服务暂不可用，请联系服务提供方"
                                : "回应服务暂不可用，请稍后重试（HTTP " + error.statusCode + "）";
                        postError(message);
                    }
                } catch (Exception error) {
                    if (running.get() && sequence == newestSequence.get()) {
                        postError("生成候选回答失败：" + error.getClass().getSimpleName()
                                + (error instanceof IOException ? "（请检查模型接口及网络）" : "（模型返回格式不正确）"));
                    }
                }
            }
        };
        try {
            modelWorkers.execute(task);
        } catch (RejectedExecutionException full) {
            // The newest utterance has priority over an older task still waiting in the queue.
            modelWorkers.getQueue().clear();
            modelWorkers.execute(task);
        }
    }

    private JSONObject getJson(String path) throws IOException, JSONException {
        HttpURLConnection connection = (HttpURLConnection) new URL(baseUrl + path).openConnection();
        activeFeedRequest = connection;
        try {
            connection.setRequestMethod("GET");
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(10000);
            connection.setReadTimeout(30000);
            connection.setRequestProperty("Authorization", "Bearer " + readToken);
            connection.setRequestProperty("Accept", "application/json");
            int status = connection.getResponseCode();
            if (status < 200 || status >= 300) {
                throw new GatewayHttpException(status);
            }
            try (InputStream in = connection.getInputStream();
                 ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[4096];
                int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 1024 * 1024) {
                        throw new IOException("Gateway response is too large");
                    }
                    out.write(buffer, 0, count);
                }
                return new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
        } finally {
            activeFeedRequest = null;
            connection.disconnect();
        }
    }

    private void restoreRecent() {
        String encoded = preferences.getString(recentKey, "[]");
        try {
            JSONArray array = new JSONArray(encoded);
            for (int i = Math.max(0, array.length() - MAX_RECENT_IDS); i < array.length(); i++) {
                recentIds.add(array.getString(i));
            }
        } catch (JSONException ignored) {
            recentIds.clear();
        }
    }

    private void remember(String identity) {
        recentIds.add(identity);
        while (recentIds.size() > MAX_RECENT_IDS) {
            String oldest = recentIds.iterator().next();
            recentIds.remove(oldest);
        }
    }

    private void saveState(String cursor) throws IOException {
        JSONArray ids = new JSONArray();
        for (String identity : recentIds) ids.put(identity);
        if (!preferences.edit().putString(cursorKey, cursor)
                .putString(recentKey, ids.toString()).commit()) {
            throw new IOException("Could not save transcript cursor");
        }
    }

    private void retryDelay() {
        try {
            Thread.sleep(1500);
        } catch (InterruptedException ignored) {
            Thread.currentThread().interrupt();
        }
    }

    private void postFeedError(String message) {
        if (!message.equals(lastFeedError)) {
            lastFeedError = message;
            postError(message);
        }
    }

    private void postError(final String message) {
        mainHandler.post(new Runnable() {
            @Override public void run() { if (running.get()) listener.onError(message); }
        });
    }

    private static final class GatewayHttpException extends IOException {
        final int status;
        GatewayHttpException(int status) {
            super("Gateway HTTP " + status);
            this.status = status;
        }
    }
}

