package com.shengying.beantest;
import android.app.Activity;
import org.json.JSONObject;
/** Project-owned extension point. Implement hardware acquisition and any licensed decoding privately. */
public interface RecorderAdapter extends AutoCloseable {
 interface Listener {
  void onState(boolean connected, boolean recording, String message);
  void onPcmStart(String sessionId, int sampleRate, int channels);
  void onPcm(byte[] signedPcm16LittleEndian, long capturedAtMs);
  void onPcmEnd();
 }
 void initialize(Activity activity, JSONObject opaqueAuthorization, Listener listener) throws Exception;
 void requestPermissions(); void scan(String optionalDeviceId); void refreshState();
 void close();
}
