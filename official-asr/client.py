"""Project-owned HTTPS speech-provider extension. No vendor endpoint or request contract."""
import io,json,os,time,urllib.request,urllib.error,urllib.parse,wave
from pathlib import Path
from types import SimpleNamespace
class ASRError(RuntimeError):
 def __init__(self,code,status=None):self.code=code;self.status=status;super().__init__(code)
def load_config(path):return json.loads(Path(path).read_text(encoding="utf-8"))
class Client:
 def __init__(self,config):
  self.config=config;self.url=config.get("adapter_url","")
  if urllib.parse.urlsplit(self.url).scheme!="https":raise ASRError("configure_https_asr_adapter")
 def transcribe(self,wav_bytes,**unused):
  started=time.monotonic();key=os.environ.get("SPEECH_ADAPTER_TOKEN","")
  class NoRedirect(urllib.request.HTTPRedirectHandler):
   def redirect_request(self,*args,**kwargs):return None
  request=urllib.request.Request(self.url,data=wav_bytes,headers={"Content-Type":"audio/wav",**({"Authorization":"Bearer "+key} if key else {})})
  try:
   with urllib.request.build_opener(NoRedirect()).open(request,timeout=30) as response:value=json.loads(response.read(1048576))
  except Exception:raise ASRError("speech_adapter_unavailable") from None
  if not isinstance(value.get("text"),str):raise ASRError("invalid_adapter_response")
  return SimpleNamespace(text=value["text"],sentences=[],elapsed_ms=round((time.monotonic()-started)*1000),usage={},response_mode="json")

def wav_bytes(pcm, rate=16000, channels=1):
    out = io.BytesIO()
    with wave.open(out, 'wb') as wav:
        wav.setnchannels(channels); wav.setsampwidth(2); wav.setframerate(rate)
        wav.writeframes(pcm)
    return out.getvalue()


def transcript_event(result, session_id, utterance_id, start_ms, end_ms, source='replay'):
    if not result.text:
        raise ASRError('empty_transcript_not_publishable')
    return dict(schema_version='1.0', type='transcript.final', is_final=True,
        session_id=session_id, utterance_id=utterance_id, revision=1, source=source,
        text=result.text, speaker_id=None, speaker_status='unknown', language='zh-CN',
        device_sn=None, emitted_at_ms=int(time.time()*1000), engine="configured-asr",
        audio_start_ms=start_ms, audio_end_ms=end_ms, final_reason='http_segment_complete')
