package com.example.replyfeature;

import android.os.Handler;
import android.os.Looper;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/** App entry point: submit final recognized text and receive four JSON options on the main thread. */
public final class ReplyClient implements AutoCloseable {
    public interface Callback {
        void onSuccess(JSONObject json);
        /** httpStatus is zero for input, network or response-format errors. */
        void onError(String message, int httpStatus);
    }

    private final ReplyGenerator generator;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(
            2, 2, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(1),
            new ThreadPoolExecutor.DiscardOldestPolicy());
    private final AtomicLong latest = new AtomicLong();
    private final AtomicBoolean closed = new AtomicBoolean();

    public ReplyClient(String deepseekApiKey) {
        this(deepseekApiKey, ReplyGenerator.MODEL);
    }

    public ReplyClient(String deepseekApiKey, String model) {
        generator = new ReplyGenerator(deepseekApiKey, model);
    }

    /** New text supersedes earlier text. This method may be called from the UI thread. */
    public void generate(final String text, final Callback callback) {
        if (callback == null) throw new IllegalArgumentException("Callback is required");
        if (closed.get()) throw new IllegalStateException("ReplyClient is closed");
        final long sequence = latest.incrementAndGet();
        try {
            worker.execute(new Runnable() {
                @Override public void run() {
                    if (!isLatest(sequence)) return;
                    try {
                        final JSONObject result = generator.generate(text);
                        main.post(new Runnable() {
                            @Override public void run() {
                                if (isLatest(sequence)) callback.onSuccess(result);
                            }
                        });
                    } catch (ReplyGenerator.ModelHttpException error) {
                        String message;
                        switch (error.statusCode) {
                            case 401: message = "DeepSeek 拒绝 API Key（HTTP 401）"; break;
                            case 402: message = "DeepSeek 账户余额不足（HTTP 402）"; break;
                            case 403: message = "DeepSeek API 访问权限不足（HTTP 403）"; break;
                            case 429: message = "模型请求过于频繁，请稍后重试（HTTP 429）"; break;
                            default: message = "DeepSeek API 请求失败（HTTP " + error.statusCode + "）";
                        }
                        deliverError(sequence, callback, message, error.statusCode);
                    } catch (IllegalArgumentException error) {
                        deliverError(sequence, callback, "请输入非空文字，长度不超过16000字符", 0);
                    } catch (JSONException error) {
                        deliverError(sequence, callback, "模型未返回四个符合格式的回答选项", 0);
                    } catch (IOException error) {
                        deliverError(sequence, callback, "模型连接失败，请检查网络后重试", 0);
                    }
                }
            });
        } catch (RejectedExecutionException stopped) {
            if (!closed.get()) deliverError(sequence, callback, "请求队列暂不可用", 0);
        }
    }

    private boolean isLatest(long sequence) {
        return !closed.get() && latest.get() == sequence;
    }

    private void deliverError(final long sequence, final Callback callback,
                              final String message, final int status) {
        main.post(new Runnable() {
            @Override public void run() {
                if (isLatest(sequence)) callback.onError(message, status);
            }
        });
    }

    @Override public void close() {
        if (closed.compareAndSet(false, true)) {
            latest.incrementAndGet();
            generator.close();
            worker.shutdownNow();
        }
    }
}

