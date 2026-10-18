package com.shengying.beantest;
import android.app.*;import android.content.*;import android.os.*;import android.widget.*;import org.json.JSONObject;
/** Public UI/cloud bootstrap; a separately licensed implementation may supply RecorderAdapter. */
public class MainActivity extends Activity {
 final Handler ui=new Handler(Looper.getMainLooper());
 ProductUi productUi;AnalysisPanel analysisPanel;DailyAlertPanel dailyAlerts;ReplyPanel replyPanel;
 AudioSink audioSink;RemoteCaptionFeed remoteCaptions;RecorderAdapter adapter;
 TextView status;EditText targetInput;LinearLayout deviceList;volatile boolean connected,recording,destroyed;
 @Override public void onCreate(Bundle state){super.onCreate(state);
  if(CloudSetupActivity.ready(this))try{CloudSetupActivity.apply(this);}catch(Exception ignored){}
  productUi=new ProductUi(this,state);status=new TextView(this);targetInput=new EditText(this);targetInput.setHint("可选设备标识");deviceList=new LinearLayout(this);deviceList.setOrientation(1);
  LinearLayout host=new LinearLayout(this);host.setOrientation(1);
  analysisPanel=new AnalysisPanel(this,host,(n,d)->{});dailyAlerts=new DailyAlertPanel(this,host,(n,d)->{},state);replyPanel=new ReplyPanel(this,host,(n,d)->{});
  productUi.bind(analysisPanel,dailyAlerts,replyPanel,new LinearLayout(this));setContentView(productUi.root);
  audioSink=new AudioSink(this,s->ui.post(()->productUi.captionStatus.setText(s)));
  if(audioSink.enabled)remoteCaptions=new RemoteCaptionFeed(this,productUi.captions,(n,d)->{},e->{dailyAlerts.onTranscript(e);replyPanel.onTranscript(e);productUi.onTranscript(e);},s->productUi.captionStatus.setText(s));
  else productUi.captionStatus.setText("公开构建 · 云端服务尚未配置");
  try{if(!BuildConfig.RECORDER_ADAPTER_CLASS.isEmpty()){
   adapter=(RecorderAdapter)Class.forName(BuildConfig.RECORDER_ADAPTER_CLASS).getDeclaredConstructor().newInstance();
   adapter.initialize(this,CloudSetupActivity.deviceAuthorization(this),new RecorderAdapter.Listener(){
    public void onState(boolean c,boolean r,String text){connected=c;recording=r;ui.post(()->{status.setText(text);productUi.state();});}
    public void onPcmStart(String id,int rate,int channels){audioSink.begin(id,"bean_live","private-adapter",rate,channels);}
    public void onPcm(byte[] pcm,long time){audioSink.pcm(pcm,time);}public void onPcmEnd(){audioSink.end();}
   });
  }}catch(Exception unavailable){adapter=null;}
  if(adapter==null)status.setText("硬件适配器未安装 · 界面与标记可使用");
 }
 void permissions(){if(adapter!=null)adapter.requestPermissions();else new AlertDialog.Builder(this).setMessage("公开源码仅保留设备接口；请自行接入获授权的适配器。云端首次配置无需输入 App 激活码。").setPositiveButton("配置云端",(d,w)->startActivity(new Intent(this,CloudSetupActivity.class))).setNegativeButton("关闭",null).show();}
 void scan(){if(adapter!=null)adapter.scan("");else permissions();}
 void query(){if(adapter!=null)adapter.refreshState();else status.setText("硬件适配器未安装");}
 @Override public void onRequestPermissionsResult(int r,String[] p,int[] results){super.onRequestPermissionsResult(r,p,results);if(r==83)productUi.microphonePermission(results.length>0&&results[0]==0);}
 @Override public void onBackPressed(){productUi.goBack();}
 @Override protected void onStart(){super.onStart();if(analysisPanel!=null)analysisPanel.setForeground(true);if(dailyAlerts!=null)dailyAlerts.setForeground(true);if(replyPanel!=null)replyPanel.setForeground(true);if(productUi!=null)productUi.resume();}
 @Override protected void onStop(){if(analysisPanel!=null)analysisPanel.setForeground(false);if(dailyAlerts!=null)dailyAlerts.setForeground(false);if(replyPanel!=null)replyPanel.setForeground(false);if(productUi!=null)productUi.pause();super.onStop();}
 @Override protected void onSaveInstanceState(Bundle out){if(dailyAlerts!=null)dailyAlerts.saveState(out);if(productUi!=null)productUi.save(out);super.onSaveInstanceState(out);}
 @Override protected void onDestroy(){destroyed=true;if(adapter!=null)adapter.close();if(remoteCaptions!=null)remoteCaptions.close();if(audioSink!=null)audioSink.close();if(analysisPanel!=null)analysisPanel.close();if(dailyAlerts!=null)dailyAlerts.close();if(replyPanel!=null)replyPanel.close();if(productUi!=null)productUi.close();super.onDestroy();}
}
