"""Opt-in test backend: shared PCM -> Silero VAD -> organizer HTTP ASR -> v1.

Only this experimental entry point selects cloud ASR. Existing backend defaults
and the frozen transcript/audio contracts are untouched. No synthetic speaker IDs.
"""
import argparse
import json
from pathlib import Path
import queue
import sys
import threading
import time

import numpy as np
import sherpa_onnx as so

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT/'backend-audio'))
from service import AudioHandler, AudioServer, AudioStore, Engine, now
from gateway import load_tokens
from client import ASRError, Client, load_config, transcript_event, wav_bytes


class VADOnly:
    def __init__(self, model):
        config = so.VadModelConfig()
        config.silero_vad.model = str(model)
        config.silero_vad.min_silence_duration = .25
        config.silero_vad.min_speech_duration = .2
        config.silero_vad.max_speech_duration = 6
        config.sample_rate = 16000
        self.config = config

    def vad(self):
        return so.VoiceActivityDetector(self.config, buffer_size_in_seconds=30)

    def turns(self, samples):
        # This API does not establish reliable speakers; preserve a complete segment.
        return [(0, len(samples), True)]


class CloudEngine(Engine):
    def __init__(self, store, vad, client, log_path):
        self.client, self.log_path = client, log_path
        self.cloud_queue = queue.Queue(8)
        self.cloud_stopping = threading.Event()
        self.cloud_log_lock = threading.Lock()
        self.cloud_thread = threading.Thread(target=self._cloud_run, daemon=True)
        self.cloud_thread.start()
        super().__init__(store, vad)

    def _log(self, value):
        with self.cloud_log_lock:
            with self.log_path.open('a', encoding='utf-8') as out:
                out.write(json.dumps(value, ensure_ascii=False)+'\n')

    def _sentence(self, value, state, samples, start, ambiguous):
        state['count'] += 1
        utterance = f"{value['session_id']}-audio-{state['count']}"
        try:
            self.cloud_queue.put_nowait((dict(value), np.array(samples, copy=True),
                                        start, utterance, time.monotonic(), now()))
        except queue.Full:
            raise ASRError('cloud_queue_full') from None

    def _cloud_run(self):
        while not self.cloud_stopping.is_set():
            try:
                value, samples, start, utterance, queued, queued_wall = self.cloud_queue.get(timeout=.2)
            except queue.Empty:
                continue
            row = dict(session_id=value['session_id'], utterance_id=utterance, source=value['source'],
                       segment_audio_start_ms=round(start/16), segment_audio_end_ms=round((start+len(samples))/16),
                       queued_at_server_ms=queued_wall)
            try:
                queue_ms = round((time.monotonic()-queued)*1000)
                if queue_ms > 8000:
                    raise ASRError('cloud_backlog_expired')
                pcm = (np.clip(samples, -1, 32767/32768)*32768).round().astype('<i2').tobytes()
                result = self.client.transcribe(wav_bytes(pcm))
                row.update(queue_ms=queue_ms, request_ms=result.elapsed_ms, usage=result.usage,
                           text=result.text, response_mode=result.response_mode,
                           returned_at_server_ms=now(), status='recognized' if result.text else 'empty')
                if result.text:
                    event = transcript_event(result, value['session_id'], utterance,
                        row['segment_audio_start_ms'], row['segment_audio_end_ms'], value['source'])
                    event['device_sn'] = value['device_sn']
                    self.store.link_utterance(value['session_id'], utterance, start, start+len(samples))
                    saved, _ = self.store.publish(event)
                    row.update(event_id=saved['event_id'], published_at_server_ms=now())
            except Exception as error:
                self.errors += 1
                self.store.failure(value, error)
                row.update(status='failed', error_code=error.code if isinstance(error, ASRError) else type(error).__name__,
                           http_status=getattr(error, 'status', None), returned_at_server_ms=now())
            finally:
                self._log(row)
                self.cloud_queue.task_done()

    def close(self):
        # Drain audio/VAD before waiting for pending cloud recognition.
        super().close()
        self.cloud_queue.join()
        self.cloud_stopping.set(); self.cloud_thread.join(2)


class CloudHandler(AudioHandler):
    def send_json(self, status, value):
        if isinstance(value, dict) and 'models_loaded' in value:
            value = dict(value, asr_engine='organizer_http_asr', streaming_audio_input=False,
                pending_audio_chunks=self.server.engine.speech_queue.unfinished_tasks,
                pending_cloud_requests=self.server.engine.cloud_queue.unfinished_tasks)
        return super().send_json(status, value)


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--port', type=int, default=8767)
    p.add_argument('--config', type=Path, default=Path(__file__).parent/'local/config.json')
    p.add_argument('--data-dir', type=Path, default=Path(__file__).parent/'local/data')
    a = p.parse_args()
    config = load_config(a.config)
    if not config.get('api_key'):
        p.error('Organizer API key is missing in local config')
    config['timeout_seconds'] = min(config.get('timeout_seconds', 5), 5)
    if config.get('transport') == 'windows_curl':
        from curl_transport import CurlClient
        client = CurlClient(config)
    else:
        client = Client(config)
    read, write = load_tokens(a.data_dir)
    store = AudioStore(a.data_dir/'transcripts.sqlite3')
    vad = VADOnly(ROOT/'backend-audio/models/silero_vad.onnx')
    engine = CloudEngine(store, vad, client, a.data_dir/'cloud-events.jsonl')
    server = AudioServer(('127.0.0.1', a.port), store, read, write, engine)
    server.RequestHandlerClass = CloudHandler
    print(f'Organizer HTTP ASR test backend ready on 127.0.0.1:{a.port}; credentials omitted', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close(); engine.close()


if __name__ == '__main__': main()
