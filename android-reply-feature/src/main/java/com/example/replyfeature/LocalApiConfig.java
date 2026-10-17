package com.example.replyfeature;

/** Compatibility template only. The integrated APK reads its private reply-config.json. */
public final class LocalApiConfig {
    private LocalApiConfig() {}
    public static final String API_KEY = "";
    public static final String MODEL = ReplyGenerator.MODEL;
    public static ReplyClient createClient() { return new ReplyClient(API_KEY, MODEL); }
}
