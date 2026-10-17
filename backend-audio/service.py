"""Shared PCM and transcript feeds. No danger, keyword or emotion judgments."""
import argparse
import base64
import binascii
from collections import OrderedDict
import hashlib
import json
from pathlib import Path
import queue
import sqlite3
import sys
import threading
import time
from urllib.parse import parse_qs, urlsplit
import wave
import io

import numpy as np
from scipy.signal import resample_poly

sys.path.insert(0, str(Path(__file__).resolve().parents[1]/'transcript-gateway'))
from gateway import Store, Handler, GatewayServer, ContractError, load_tokens


def now():
    return int(time.time()*1000)


class AudioStore(Store):
    """Original immutable text store plus a bounded, private PCM retention window."""
    def __init__(self, path, retention_seconds=600, max_audio_bytes=64*1024*1024):
        super().__init__(path)
        self.retention_ms = retention_seconds*1000
        self.max_audio_bytes = max_audio_bytes
        self.db.executescript('''
            CREATE TABLE IF NOT EXISTS audio_chunks (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL, sequence INTEGER NOT NULL,
                source TEXT NOT NULL, received_at_ms INTEGER NOT NULL,
                start_sample INTEGER NOT NULL, sample_rate INTEGER NOT NULL,
                fingerprint TEXT NOT NULL, payload TEXT NOT NULL, pcm BLOB NOT NULL,
                UNIQUE(session_id,sequence));
            CREATE INDEX IF NOT EXISTS audio_source ON audio_chunks(source,id);
            CREATE INDEX IF NOT EXISTS audio_session ON audio_chunks(session_id,start_sample);
            CREATE INDEX IF NOT EXISTS audio_age ON audio_chunks(received_at_ms);
            CREATE TABLE IF NOT EXISTS audio_utterances (
                session_id TEXT NOT NULL, utterance_id TEXT NOT NULL,
                start_16k INTEGER NOT NULL, end_16k INTEGER NOT NULL,
                PRIMARY KEY(session_id,utterance_id));
            CREATE TABLE IF NOT EXISTS capture_status (
                id INTEGER PRIMARY KEY AUTOINCREMENT, source TEXT NOT NULL, payload TEXT NOT NULL);
        ''')
        self.db.commit()

    def _audio_high(self):
        row = self.db.execute("SELECT seq FROM sqlite_sequence WHERE name='audio_chunks'").fetchone()
        return row[0] if row else 0

    def _purge_audio(self):
        self.db.execute('DELETE FROM audio_chunks WHERE received_at_ms<?', (now()-self.retention_ms,))
        total = self.db.execute('SELECT COALESCE(SUM(length(pcm)),0) FROM audio_chunks').fetchone()[0]
        if total > self.max_audio_bytes:
            for identity, length in self.db.execute('SELECT id,length(pcm) FROM audio_chunks ORDER BY id').fetchall():
                self.db.execute('DELETE FROM audio_chunks WHERE id=?', (identity,))
                total -= length
                if total <= self.max_audio_bytes:
                    break

    def existing_audio(self, session, sequence):
        with self.condition:
            return self.db.execute('SELECT id,fingerprint FROM audio_chunks WHERE session_id=? AND sequence=?',
                                   (session, sequence)).fetchone()

    def audio_publish(self, value, raw, fingerprint):
        payload = {k: v for k, v in value.items() if k != 'audio_base64'}
        payload.update(type='audio.chunk', end=value.get('end', False),
                       end_sample=value['start_sample']+len(raw)//(2*value['channels']),
                       audio_start_ms=value['start_sample']*1000/value['sample_rate'],
                       audio_end_ms=(value['start_sample']+len(raw)//(2*value['channels']))*1000/value['sample_rate'],
                       received_at_ms=now())
        with self.condition, self.db:
            self._purge_audio()
            row = self.db.execute('INSERT INTO audio_chunks(session_id,sequence,source,received_at_ms,start_sample,sample_rate,fingerprint,payload,pcm) '
                                  'VALUES(?,?,?,?,?,?,?,?,?)', (value['session_id'], value['sequence'], value['source'],
                                  now(), value['start_sample'], value['sample_rate'], fingerprint,
                                  json.dumps(payload, ensure_ascii=False), raw))
            self._purge_audio()
            self.condition.notify_all()
            return str(row.lastrowid)

    @staticmethod
    def _audio_event(row):
        event = json.loads(row[1])
        event.update(event_id=str(row[0]), audio_base64=base64.b64encode(row[2]).decode(),
                     audio_url=f'/v1/audio/chunks/{row[0]}')
        return event

    def audio_poll(self, cursor='latest', limit=4, wait_ms=0, source='bean_live'):
        deadline = time.monotonic()+wait_ms/1000
        with self.condition:
            position = self._audio_high() if cursor == 'latest' else int(cursor)
            if position > self._audio_high():
                raise ContractError('cursor_ahead', 'Cursor exceeds this audio database', 409)
            while True:
                with self.db:
                    self._purge_audio()
                high = self._audio_high()
                minimum = self.db.execute('SELECT MIN(id) FROM audio_chunks').fetchone()[0]
                if position > 0 and position < (minimum-1 if minimum else high):
                    raise ContractError('audio_cursor_expired', 'Audio retention passed this cursor; explicitly use 0 for retained data or latest', 410)
                rows = self.db.execute('SELECT id,payload,pcm FROM audio_chunks WHERE id>? AND source=? ORDER BY id LIMIT ?',
                                       (position, source, limit+1)).fetchall()
                if rows:
                    more = len(rows) > limit
                    return dict(schema_version='1.0', events=[self._audio_event(r) for r in rows[:limit]],
                                next_cursor=str(rows[limit-1][0] if more else high), has_more=more,
                                server_time_ms=now(), retention_seconds=self.retention_ms//1000)
                remaining = deadline-time.monotonic()
                if remaining <= 0:
                    return dict(schema_version='1.0', events=[], next_cursor=str(high), has_more=False,
                                server_time_ms=now(), retention_seconds=self.retention_ms//1000)
                position = high
                self.condition.wait(remaining)

    def audio_chunk(self, identity):
        with self.condition, self.db:
            self._purge_audio()
            row = self.db.execute('SELECT id,payload,pcm FROM audio_chunks WHERE id=?', (identity,)).fetchone()
            if not row:
                raise ContractError('audio_unavailable', 'Audio chunk missing or expired', 410)
            return json.loads(row[1]), row[2]

    def link_utterance(self, session, utterance, start, end):
        with self.condition, self.db:
            self.db.execute('INSERT INTO audio_utterances VALUES(?,?,?,?)', (session, utterance, start, end))

    def utterance_audio(self, session, utterance):
        with self.condition, self.db:
            self._purge_audio()
            span = self.db.execute('SELECT start_16k,end_16k FROM audio_utterances WHERE session_id=? AND utterance_id=?',
                                   (session, utterance)).fetchone()
            if not span:
                raise ContractError('not_found', 'Unknown sentence', 404)
            rows = self.db.execute('SELECT start_sample,sample_rate,payload,pcm FROM audio_chunks WHERE session_id=? ORDER BY start_sample',
                                   (session,)).fetchall()
            if not rows:
                raise ContractError('audio_expired', 'Sentence audio expired; text remains available', 410)
            rate = rows[0][1]
            channels = json.loads(rows[0][2])['channels']
            frame_bytes = channels*2
            left, right = span[0]*(rate//16000), span[1]*(rate//16000)
            position, blocks = left, []
            for start, sample_rate, payload, raw in rows:
                end = start+len(raw)//frame_bytes
                if end <= position or start >= right:
                    continue
                if start > position or sample_rate != rate:
                    raise ContractError('audio_gap', 'Sentence audio no longer complete', 410)
                take = min(end, right)
                blocks.append(raw[(position-start)*frame_bytes:(take-start)*frame_bytes])
                position = take
            if position < right:
                raise ContractError('audio_gap', 'Sentence tail unavailable', 410)
            return dict(sample_rate=rate, channels=channels, encoding='pcm_s16le', session_id=session,
                        utterance_id=utterance, start_sample=left, end_sample=right), b''.join(blocks)

    def failure(self, value, error):
        payload = dict(schema_version='1.0', type='audio.status', session_id=value['session_id'],
                       source=value['source'], sequence=value['sequence'], status='failed',
                       error=type(error).__name__, emitted_at_ms=now())
        with self.condition, self.db:
            self.db.execute('INSERT INTO capture_status(source,payload) VALUES(?,?)',
                            (value['source'], json.dumps(payload)))

    def statuses(self, source):
        with self.condition:
            rows = self.db.execute('SELECT id,payload FROM capture_status WHERE source=? ORDER BY id DESC LIMIT 100', (source,)).fetchall()
            return dict(schema_version='1.0', events=[dict(json.loads(p), event_id=str(i)) for i, p in reversed(rows)])


class Speakers:
    """Session-only tentative voice labels. Never an actual person identity."""
    def __init__(self):
        self.profiles = []

    def identify(self, vector):
        if vector is None or not np.isfinite(vector).all() or np.linalg.norm(vector) < 1e-8:
            return None, 'unknown'
        v = vector/np.linalg.norm(vector)
        scores = [float(np.dot(v, p)) for p in self.profiles]
        order = np.argsort(scores)[::-1]
        if scores and scores[order[0]] >= .65:
            if len(order) > 1 and scores[order[0]]-scores[order[1]] < .1:
                return None, 'unknown'
            return f'speaker_{int(order[0])+1}', 'tentative_match'
        if (not scores or max(scores) < .35) and len(self.profiles) < 8:
            self.profiles.append(v)
            return f'speaker_{len(self.profiles)}', 'tentative_new'
        return None, 'unknown'


class Engine:
    def __init__(self, store, models):
        self.store, self.models = store, models
        self.lock = threading.Lock()
        self.sessions = OrderedDict()
        self.speech_queue = queue.Queue(32)
        self.states = {}
        self.errors = 0
        self.stopping = threading.Event()
        self.worker = threading.Thread(target=self._run, daemon=True)
        self.worker.start()

    @staticmethod
    def validate(value):
        if not isinstance(value, dict) or value.get('schema_version') != '1.0':
            raise ContractError('invalid_audio', 'Expected version 1.0 audio object')
        for key in ('session_id', 'device_sn'):
            if not isinstance(value.get(key), str) or not 1 <= len(value[key]) <= 160:
                raise ContractError('invalid_audio', f'{key} required')
        if value.get('source') not in ('bean_live', 'replay'):
            raise ContractError('invalid_source', 'bean_live or replay required')
        if value.get('encoding') != 'pcm_s16le' or type(value.get('channels')) is not int or value['channels'] not in (1, 2):
            raise ContractError('invalid_format', 'pcm_s16le with 1 or 2 channels required')
        if type(value.get('sample_rate')) is not int or value['sample_rate'] not in (16000, 48000):
            raise ContractError('invalid_format', '16000 or 48000 Hz required')
        for key in ('sequence', 'start_sample', 'captured_at_ms'):
            if type(value.get(key)) is not int or not 0 <= value[key] < 2**53:
                raise ContractError('invalid_audio', f'{key} must be a nonnegative integer')
        if type(value.get('end', False)) is not bool:
            raise ContractError('invalid_audio', 'end must be boolean')
        try:
            raw = base64.b64decode(value['audio_base64'], validate=True)
        except (KeyError, TypeError, ValueError, binascii.Error):
            raise ContractError('invalid_audio', 'Invalid base64 PCM')
        frame_bytes = 2*value['channels']
        if len(raw) % frame_bytes or len(raw) > value['sample_rate']*frame_bytes or (not raw and not value.get('end')):
            raise ContractError('invalid_audio', 'Expected up to one second of aligned PCM; empty only for end')
        if len(raw)//frame_bytes % (value['sample_rate']//16000):
            raise ContractError('invalid_audio', 'PCM must align to 16 kHz boundaries')
        return raw

    def submit(self, value):
        raw = self.validate(value)
        sid, seq = value['session_id'], value['sequence']
        fingerprint = hashlib.sha256(json.dumps(value, sort_keys=True).encode()).hexdigest()
        with self.lock:
            existing = self.store.existing_audio(sid, seq)
            if existing:
                if existing[1] != fingerprint:
                    raise ContractError('audio_conflict', 'Different content at same sequence', 409)
                return dict(accepted=True, duplicate=True, sequence=seq, audio_event_id=str(existing[0]))
            for key, state in list(self.sessions.items()):
                if time.monotonic()-state['seen'] > 600:
                    self.sessions.pop(key)
            state = self.sessions.get(sid)
            if state is None:
                if seq != 0 or value['start_sample'] != 0:
                    raise ContractError('audio_gap', 'New session must start at sequence/sample 0', 409)
                if len(self.sessions) >= 16:
                    raise ContractError('session_limit', 'Too many recent sessions', 503)
                state = dict(next=0, sample=0, seen=time.monotonic(), closed=False,
                             format=(value['sample_rate'], value['channels'], value['device_sn'], value['source']))
            if state['closed'] or time.monotonic()-state['seen'] > 120 or seq != state['next'] or value['start_sample'] != state['sample']:
                raise ContractError('audio_gap', 'Out of order, missing or closed stream; start a new session', 409)
            if state['format'] != (value['sample_rate'], value['channels'], value['device_sn'], value['source']):
                raise ContractError('audio_conflict', 'Session format/source is immutable', 409)
            if self.speech_queue.full():
                raise ContractError('overloaded', 'Processing queue full; retry same sequence', 503)
            identity = self.store.audio_publish(value, raw, fingerprint)
            self.speech_queue.put_nowait((dict(value), raw, time.monotonic()))
            state.update(next=seq+1, sample=state['sample']+len(raw)//(2*value['channels']),
                         closed=value.get('end', False), seen=time.monotonic())
            self.sessions[sid] = state
            return dict(accepted=True, duplicate=False, sequence=seq, audio_event_id=identity)

    def _run(self):
        while not self.stopping.is_set():
            try:
                value, raw, queued = self.speech_queue.get(timeout=.2)
            except queue.Empty:
                continue
            try:
                if time.monotonic()-queued > 10:
                    raise RuntimeError('processing_backlog_expired')
                samples = np.frombuffer(raw, dtype='<i2').astype(np.float32)/32768
                if value['channels'] == 2:
                    samples = samples.reshape(-1, 2).mean(axis=1)
                if value['sample_rate'] != 16000 and len(samples):
                    samples = resample_poly(samples, 1, 3).astype(np.float32)
                self._speech(value, samples)
            except Exception as error:
                self.errors += 1
                self.store.failure(value, error)
                self.states[value['session_id']] = dict(failed=True, seen=time.monotonic())
            finally:
                self.speech_queue.task_done()

    def _speech(self, value, samples):
        sid = value['session_id']
        for key, old in list(self.states.items()):
            if time.monotonic()-old['seen'] > 120:
                self.states.pop(key)
        if sid not in self.states:
            self.states[sid] = dict(vad=self.models.vad(), speakers=Speakers(), total=0,
                                    pending=np.empty(0, np.float32), count=0, seen=time.monotonic())
        state = self.states[sid]
        state['seen'] = time.monotonic()
        if state.get('failed'):
            return
        state['total'] += len(samples)
        vad = state['vad']
        state['pending'] = np.concatenate((state['pending'], samples))
        while len(state['pending']) >= 512:
            vad.accept_waveform(state['pending'][:512])
            state['pending'] = state['pending'][512:]
            self._segments(value, state)
        if value.get('end'):
            if len(state['pending']):
                vad.accept_waveform(np.pad(state['pending'], (0, 512-len(state['pending']))))
            vad.flush()
            self._segments(value, state)
            self.states.pop(sid, None)

    def _segments(self, value, state):
        vad = state['vad']
        while not vad.empty():
            segment = vad.front
            samples = np.array(segment.samples, dtype=np.float32)
            start = segment.start
            vad.pop()
            samples = samples[:max(0, state['total']-start)]  # Exclude VAD flush padding from delivered audio.
            if not len(samples):
                continue
            for left, right, ambiguous in self.models.turns(samples):
                self._sentence(value, state, samples[left:right], start+left, ambiguous)

    def _sentence(self, value, state, samples, start, ambiguous):
        text, vector, language = self.models.speech(samples)
        if not text:
            return
        speaker, status = state['speakers'].identify(None if ambiguous else vector)
        state['count'] += 1
        sid, end = value['session_id'], start+len(samples)
        utterance = f"{sid}-audio-{state['count']}"
        self.store.link_utterance(sid, utterance, start, end)
        self.store.publish(dict(schema_version='1.0', type='transcript.final', revision=1,
            session_id=sid, utterance_id=utterance, source=value['source'], text=text,
            speaker_id=speaker, speaker_status=status, is_final=True, device_sn=value['device_sn'],
            language=language, emitted_at_ms=now(), audio_start_ms=round(start/16), audio_end_ms=round(end/16),
            engine='sensevoice-int8-2025-09-09', final_reason='vad_endpoint',
            latest_audio_age_ms=max(0, now()-value['captured_at_ms'])))

    def close(self):
        self.speech_queue.join()
        self.stopping.set()
        self.worker.join(2)


class AudioHandler(Handler):
    def send_audio(self, metadata, raw, format):
        if format == 'wav':
            out = io.BytesIO()
            with wave.open(out, 'wb') as wav:
                wav.setnchannels(metadata['channels']); wav.setsampwidth(2); wav.setframerate(metadata['sample_rate']); wav.writeframes(raw)
            raw = out.getvalue()
        self.send_response(200)
        self.send_header('Content-Type', 'audio/wav' if format == 'wav' else 'application/octet-stream')
        self.send_header('Content-Length', str(len(raw)))
        self.send_header('Cache-Control', 'no-store')
        self.send_header('X-Audio-Encoding', 'pcm_s16le')
        self.send_header('X-Audio-Sample-Rate', str(metadata['sample_rate']))
        self.send_header('X-Audio-Channels', str(metadata['channels']))
        self.end_headers()
        try:
            self.wfile.write(raw)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def do_GET(self):
        path = urlsplit(self.path)
        if path.path not in ('/v1/audio', '/v1/audio/cursor', '/v1/audio/utterance', '/v1/audio-health', '/v1/audio-status') and not path.path.startswith('/v1/audio/chunks/'):
            return super().do_GET()
        try:
            self.authenticate('read')
            q = parse_qs(path.query, keep_blank_values=True)
            if any(len(v) != 1 for v in q.values()):
                raise ContractError('invalid_query', 'Repeated query parameter')
            def arg(key, default=''): return q.get(key, [default])[0]
            if path.path == '/v1/audio-health':
                if q:
                    raise ContractError('invalid_query', 'No query parameters allowed')
                self.send_json(200, dict(status='degraded' if self.server.engine.errors else 'ready',
                    models_loaded=True, queued_speech=self.server.engine.speech_queue.qsize(),
                    processing_errors=self.server.engine.errors, audio_retention_seconds=self.server.store.retention_ms//1000))
                return
            if path.path == '/v1/audio-status':
                if set(q)-{'source'} or arg('source', 'bean_live') not in ('bean_live', 'replay'):
                    raise ContractError('invalid_query', 'Expected source=bean_live or replay')
                self.send_json(200, self.server.store.statuses(arg('source', 'bean_live')))
                return
            if path.path == '/v1/audio/cursor':
                if q:
                    raise ContractError('invalid_query', 'No query parameters allowed')
                with self.server.store.condition:
                    self.send_json(200, dict(schema_version='1.0', cursor=str(self.server.store._audio_high())))
                return
            if path.path == '/v1/audio/utterance' or path.path.startswith('/v1/audio/chunks/'):
                format = arg('format', 'pcm')
                if format not in ('pcm', 'wav'):
                    raise ContractError('invalid_format', 'format must be pcm or wav')
                if path.path == '/v1/audio/utterance':
                    if set(q)-{'session_id', 'utterance_id', 'format'} or not arg('session_id') or not arg('utterance_id'):
                        raise ContractError('invalid_query', 'session_id and utterance_id required')
                    metadata, raw = self.server.store.utterance_audio(arg('session_id'), arg('utterance_id'))
                else:
                    identity = path.path[len('/v1/audio/chunks/'):]
                    if set(q)-{'format'} or not identity.isascii() or not identity.isdigit() or len(identity)>18:
                        raise ContractError('invalid_query', 'Invalid chunk ID')
                    metadata, raw = self.server.store.audio_chunk(int(identity))
                self.send_audio(metadata, raw, format)
                return
            if set(q)-{'cursor', 'limit', 'wait_ms', 'source'}:
                raise ContractError('invalid_query', 'Unknown audio query parameter')
            cursor = arg('cursor', 'latest')
            if cursor != 'latest' and (not cursor.isascii() or not cursor.isdigit() or len(cursor)>18):
                raise ContractError('invalid_cursor', 'Use latest or a returned audio cursor')
            limit, wait, source = int(arg('limit', '4')), int(arg('wait_ms', '0')), arg('source', 'bean_live')
            if not 1 <= limit <= 20 or not 0 <= wait <= 25000 or source not in ('bean_live', 'replay'):
                raise ContractError('invalid_query', 'Invalid limit, wait_ms or source')
            self.send_json(200, self.server.store.audio_poll(cursor, limit, wait, source))
        except ContractError as e:
            self.send_json(e.status, {'error': {'code': e.code, 'message': e.message}})
        except ValueError:
            self.send_json(400, {'error': {'code': 'invalid_query', 'message': 'Invalid numeric query'}})

    def do_POST(self):
        if urlsplit(self.path).path != '/v1/audio/chunks':
            return super().do_POST()
        try:
            self.authenticate('write')
            if urlsplit(self.path).query or self.headers.get('Transfer-Encoding'):
                raise ContractError('invalid_body', 'No query or chunked encoding allowed')
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= 300000:
                raise ContractError('invalid_length', 'Body must be 1..300000 bytes', 413)
            if self.headers.get('Content-Type', '').split(';')[0].strip() != 'application/json':
                raise ContractError('invalid_content_type', 'application/json required', 415)
            body = self.rfile.read(length)
            if len(body) != length:
                raise ContractError('invalid_body', 'Incomplete upload')
            result = self.server.engine.submit(json.loads(body))
            self.send_json(200 if result['duplicate'] else 202, result)
        except ContractError as e:
            self.send_json(e.status, {'error': {'code': e.code, 'message': e.message}})
        except (ValueError, TypeError, UnicodeError):
            self.send_json(400, {'error': {'code': 'invalid_json', 'message': 'Invalid audio JSON'}})


class AudioServer(GatewayServer):
    def __init__(self, address, store, read, write, engine, legacy=False):
        super().__init__(address, store, read, write, legacy)
        self.RequestHandlerClass = AudioHandler
        self.engine = engine


def main():
    p = argparse.ArgumentParser(description=__doc__)
    p.add_argument('--host', default='127.0.0.1')
    p.add_argument('--port', type=int, default=8766)
    p.add_argument('--models-dir', type=Path, default=Path(__file__).parent/'models')
    p.add_argument('--data-dir', type=Path, default=Path(__file__).parent/'data')
    p.add_argument('--audio-retention-seconds', type=int, default=600)
    p.add_argument('--max-audio-mib', type=int, default=64)
    a = p.parse_args()
    if not 30 <= a.audio_retention_seconds <= 86400 or not 4 <= a.max_audio_mib <= 4096:
        p.error('Audio retention must be 30..86400 seconds; capacity 4..4096 MiB')
    from models import Models
    models = Models(a.models_dir)
    read, write = load_tokens(a.data_dir)
    store = AudioStore(a.data_dir/'transcripts.sqlite3', a.audio_retention_seconds, a.max_audio_mib*1024*1024)
    engine = Engine(store, models)
    server = AudioServer((a.host, a.port), store, read, write, engine)
    print(f'Audio/text backend ready on {a.host}:{a.port}; no downstream judgments enabled', flush=True)
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        pass
    finally:
        server.server_close(); engine.close(); store.close()


if __name__ == '__main__':
    main()
