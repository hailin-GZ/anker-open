"""Versioned transcript feed. Standard-library only; no audio processing or AI calls."""
import argparse
import hashlib
import hmac
import json
import os
from pathlib import Path
import secrets
import sqlite3
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.parse import parse_qs, urlsplit

API_VERSION = '1.0'
MAX_BODY = 131072


class ContractError(Exception):
    def __init__(self, code, message, status=400):
        self.code, self.message, self.status = code, message, status


def normalize(value):
    if not isinstance(value, dict):
        raise ContractError('invalid_event', 'JSON object required')
    if value.get('schema_version', API_VERSION) != API_VERSION:
        raise ContractError('unsupported_version', 'Expected schema_version 1.0')
    kind = value.get('type')
    if kind not in ('transcript.partial', 'transcript.final'):
        raise ContractError('invalid_type', 'Expected transcript.partial or transcript.final')
    final = kind == 'transcript.final'
    if value.get('is_final') is not final:
        raise ContractError('invalid_final_flag', 'type and is_final must agree')
    result = {'schema_version': API_VERSION, 'type': kind, 'is_final': final}
    for key in ('session_id', 'utterance_id'):
        text = value.get(key)
        if not isinstance(text, str) or not 1 <= len(text) <= 160:
            raise ContractError('invalid_identity', f'{key} must be a nonempty string, at most 160 characters')
        result[key] = text
    revision = value.get('revision')
    if type(revision) is not int or not 1 <= revision <= 2147483647:
        raise ContractError('invalid_revision', 'revision must be a positive integer')
    result['revision'] = revision
    text = value.get('text')
    if not isinstance(text, str) or not text.strip() or len(text) > 16000:
        raise ContractError('invalid_text', 'Nonempty text required, at most 16000 characters')
    result['text'] = text
    source = value.get('source')
    if source not in ('bean_live', 'replay'):
        raise ContractError('invalid_source', 'source must be bean_live or replay')
    result['source'] = source
    emitted = value.get('emitted_at_ms')
    if type(emitted) is not int or not 0 <= emitted <= 9223372036854775807:
        raise ContractError('invalid_timestamp', 'emitted_at_ms must be a nonnegative Unix millisecond integer')
    result['emitted_at_ms'] = emitted
    for key, default in (('speaker_id', None), ('device_sn', None), ('language', 'zh-CN'), ('speaker_status', 'unknown')):
        item = value.get(key, default)
        if item is not None and (not isinstance(item, str) or not 1 <= len(item) <= 160):
            raise ContractError('invalid_field', f'{key} must be a nonempty string or null')
        if key in ('language', 'speaker_status') and item is None:
            raise ContractError('invalid_field', f'{key} cannot be null')
        result[key] = item
    if result['speaker_status'] == 'unknown' and result['speaker_id'] is not None:
        raise ContractError('invalid_speaker', 'unknown speaker must have speaker_id=null')
    metadata = {}
    allowed = ('engine', 'confidence', 'audio_start_ms', 'audio_end_ms', 'final_reason', 'latest_audio_age_ms', 'speech_end_to_emit_ms', 'speaker_frames', 'speaker_cosine_distance', 'speaker_profile_count')
    incoming = value.get('metadata', {})
    if not isinstance(incoming, dict):
        raise ContractError('invalid_metadata', 'metadata must be an object')
    for key in allowed:
        if key in value:
            metadata[key] = value[key]
        elif key in incoming:
            metadata[key] = incoming[key]
    result['metadata'] = metadata
    return result


class Store:
    def __init__(self, path):
        self.path = Path(path)
        self.path.parent.mkdir(parents=True, exist_ok=True)
        self.condition = threading.Condition()
        self.db = sqlite3.connect(str(self.path), check_same_thread=False, timeout=10)
        self.db.execute('PRAGMA journal_mode=WAL')
        self.db.executescript('''
            CREATE TABLE IF NOT EXISTS events (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id TEXT NOT NULL,
                utterance_id TEXT NOT NULL,
                revision INTEGER NOT NULL,
                type TEXT NOT NULL,
                source TEXT NOT NULL,
                received_at_ms INTEGER NOT NULL,
                fingerprint TEXT NOT NULL,
                payload TEXT NOT NULL,
                UNIQUE(session_id, utterance_id, revision)
            );
            CREATE INDEX IF NOT EXISTS event_filter ON events(source, type, id);
        ''')
        self.db.commit()

    def _cursor(self):
        return self.db.execute('SELECT COALESCE(MAX(id), 0) FROM events').fetchone()[0]

    def cursor(self):
        with self.condition:
            return str(self._cursor())

    @staticmethod
    def _event(row):
        value = json.loads(row[1])
        value['event_id'] = str(row[0])
        value['received_at_ms'] = row[2]
        return value

    def publish(self, value):
        event = normalize(value)
        body = json.dumps(event, ensure_ascii=False, sort_keys=True, separators=(',', ':'), allow_nan=False)
        digest = hashlib.sha256(body.encode('utf-8')).hexdigest()
        with self.condition:
            same = self.db.execute('SELECT id,payload,received_at_ms,fingerprint FROM events WHERE session_id=? AND utterance_id=? AND revision=?', (event['session_id'], event['utterance_id'], event['revision'])).fetchone()
            if same:
                if same[3] != digest:
                    raise ContractError('revision_conflict', 'Same sentence revision has different content', 409)
                return self._event(same), True
            previous = self.db.execute('SELECT revision,type FROM events WHERE session_id=? AND utterance_id=? ORDER BY revision DESC LIMIT 1', (event['session_id'], event['utterance_id'])).fetchone()
            if previous:
                if previous[1] == 'transcript.final':
                    raise ContractError('utterance_finalized', 'A final sentence is immutable in v1', 409)
                if event['revision'] <= previous[0]:
                    raise ContractError('outdated_revision', 'revision must increase', 409)
            received = int(time.time()*1000)
            with self.db:
                inserted = self.db.execute('INSERT INTO events(session_id,utterance_id,revision,type,source,received_at_ms,fingerprint,payload) VALUES(?,?,?,?,?,?,?,?)', (event['session_id'], event['utterance_id'], event['revision'], event['type'], event['source'], received, digest, body))
            row = (inserted.lastrowid, body, received)
            self.condition.notify_all()
            return self._event(row), False

    def poll(self, cursor='latest', limit=20, wait_ms=0, include_partial=False, source='bean_live'):
        deadline = time.monotonic()+wait_ms/1000
        with self.condition:
            position = self._cursor() if cursor == 'latest' else int(cursor)
            if position > self._cursor():
                raise ContractError('cursor_ahead', 'Cursor exceeds this database; check that the service/database has not changed', 409)
            while True:
                high = self._cursor()
                clause, params = ['id>?', 'id<=?'], [position, high]
                if not include_partial:
                    clause.append('type=?'); params.append('transcript.final')
                if source != 'all':
                    clause.append('source=?'); params.append(source)
                rows = self.db.execute('SELECT id,payload,received_at_ms FROM events WHERE '+' AND '.join(clause)+' ORDER BY id LIMIT ?', params+[limit+1]).fetchall()
                if rows:
                    more = len(rows) > limit
                    selected = rows[:limit]
                    next_cursor = selected[-1][0] if more else high
                    return {'schema_version': API_VERSION, 'events': [self._event(row) for row in selected], 'next_cursor': str(next_cursor), 'has_more': more, 'server_time_ms': int(time.time()*1000)}
                position = high  # Advance past filtered partials/replays without resending old data.
                remaining = deadline-time.monotonic()
                if remaining <= 0:
                    return {'schema_version': API_VERSION, 'events': [], 'next_cursor': str(position), 'has_more': False, 'server_time_ms': int(time.time()*1000)}
                self.condition.wait(remaining)

    def close(self):
        with self.condition:
            self.db.close()


class GatewayServer(ThreadingHTTPServer):
    daemon_threads = True

    def __init__(self, address, store, read_token, write_token, allow_local_legacy=False):
        super().__init__(address, Handler)
        self.store, self.read_token, self.write_token = store, read_token, write_token
        self.allow_local_legacy = allow_local_legacy


class Handler(BaseHTTPRequestHandler):
    def setup(self):
        super().setup()
        self.connection.settimeout(35)

    def send_json(self, status, data):
        payload = json.dumps(data, ensure_ascii=False, allow_nan=False).encode('utf-8')
        self.send_response(status)
        self.send_header('Content-Type', 'application/json; charset=utf-8')
        self.send_header('Content-Length', str(len(payload)))
        self.send_header('Cache-Control', 'no-store')
        self.end_headers()
        try:
            self.wfile.write(payload)
        except (BrokenPipeError, ConnectionResetError):
            pass

    def authenticate(self, role, legacy=False):
        if legacy and self.server.allow_local_legacy and self.client_address[0] in ('127.0.0.1', '::1'):
            return
        expected = self.server.read_token if role == 'read' else self.server.write_token
        header = self.headers.get('Authorization', '')
        token = header[7:] if header.startswith('Bearer ') else ''
        if not token or not hmac.compare_digest(token.encode(), expected.encode()):
            raise ContractError('unauthorized', f'A valid {role} bearer token is required', 401)

    def do_GET(self):
        try:
            path = urlsplit(self.path)
            if path.path == '/health':
                self.send_json(200, {'status': 'ready', 'service': 'transcript-gateway', 'schema_version': API_VERSION})
                return
            self.authenticate('read')
            if path.path == '/v1/transcripts/cursor':
                self.send_json(200, {'schema_version': API_VERSION, 'cursor': self.server.store.cursor()})
                return
            if path.path != '/v1/transcripts':
                raise ContractError('not_found', 'Unknown endpoint', 404)
            query = parse_qs(path.query, keep_blank_values=True)
            allowed = {'cursor', 'limit', 'wait_ms', 'include_partial', 'source'}
            if set(query)-allowed or any(len(v) != 1 for v in query.values()):
                raise ContractError('invalid_query', 'Unknown or repeated query parameter')
            def arg(key, default): return query.get(key, [default])[0]
            cursor = arg('cursor', 'latest')
            if cursor != 'latest' and (not cursor.isascii() or not cursor.isdigit() or len(cursor)>18):
                raise ContractError('invalid_cursor', 'Use a returned cursor string, latest, or 0')
            limit, wait_ms = int(arg('limit', '20')), int(arg('wait_ms', '0'))
            if not 1 <= limit <= 100 or not 0 <= wait_ms <= 25000:
                raise ContractError('invalid_query', 'limit must be 1..100; wait_ms must be 0..25000')
            partial, source = arg('include_partial', 'false'), arg('source', 'bean_live')
            if partial not in ('true', 'false') or source not in ('bean_live', 'replay', 'all'):
                raise ContractError('invalid_query', 'Invalid include_partial or source')
            self.send_json(200, self.server.store.poll(cursor, limit, wait_ms, partial == 'true', source))
        except ContractError as error:
            self.send_json(error.status, {'error': {'code': error.code, 'message': error.message}})
        except ValueError:
            self.send_json(400, {'error': {'code': 'invalid_query', 'message': 'Invalid numeric value'}})

    def do_POST(self):
        try:
            path = urlsplit(self.path)
            if path.path not in ('/v1/transcripts', '/transcripts') or path.query:
                raise ContractError('not_found', 'Unknown endpoint', 404)
            self.authenticate('write', legacy=path.path == '/transcripts')
            if self.headers.get('Transfer-Encoding'):
                raise ContractError('invalid_body', 'Content-Length required; chunked upload not supported')
            length = int(self.headers.get('Content-Length', '0'))
            if not 0 < length <= MAX_BODY:
                raise ContractError('invalid_length', 'Body must be 1..131072 bytes', 413)
            if self.headers.get('Content-Type', '').split(';')[0].strip().lower() != 'application/json':
                raise ContractError('invalid_content_type', 'Expected application/json', 415)
            raw = self.rfile.read(length)
            if len(raw) != length:
                raise ContractError('invalid_body', 'Incomplete body')
            value = json.loads(raw.decode('utf-8'))
            event, duplicate = self.server.store.publish(value)
            self.send_json(200 if duplicate else 201, {'accepted': True, 'duplicate': duplicate, 'event_id': event['event_id'], 'utterance_id': event['utterance_id'], 'received_at_ms': event['received_at_ms']})
        except ContractError as error:
            self.send_json(error.status, {'error': {'code': error.code, 'message': error.message}})
        except (ValueError, UnicodeError, TypeError):
            self.send_json(400, {'error': {'code': 'invalid_json', 'message': 'Invalid JSON event'}})

    def log_message(self, format, *args):
        pass  # No transcript text, credentials or request headers in access logs.


def load_tokens(data_dir):
    data_dir.mkdir(parents=True, exist_ok=True)
    path = data_dir/'access-keys.json'
    if not path.exists():
        with path.open('x', encoding='utf-8') as out:
            json.dump({'read_token': secrets.token_urlsafe(32), 'write_token': secrets.token_urlsafe(32)}, out, indent=2)
        try: path.chmod(0o600)
        except OSError: pass
    keys = json.loads(path.read_text(encoding='utf-8'))
    read = os.environ.get('TRANSCRIPT_READ_TOKEN', keys['read_token'])
    write = os.environ.get('TRANSCRIPT_WRITE_TOKEN', keys['write_token'])
    if len(read)<24 or len(write)<24 or hmac.compare_digest(read, write):
        raise ValueError('Use distinct read/write tokens, each at least 24 characters')
    return read, write


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--host', default='127.0.0.1')
    parser.add_argument('--port', type=int, default=8765)
    parser.add_argument('--data-dir', type=Path, default=Path(__file__).resolve().parent/'data')
    parser.add_argument('--allow-local-legacy', action='store_true', help='Allow existing Android app unauthenticated POST /transcripts on a loopback-only server')
    args = parser.parse_args(argv)
    if args.allow_local_legacy and args.host not in ('127.0.0.1', '::1'):
        parser.error('--allow-local-legacy requires a loopback bind address')
    read, write = load_tokens(args.data_dir)
    store = Store(args.data_dir/'transcripts.sqlite3')
    server = GatewayServer((args.host, args.port), store, read, write, args.allow_local_legacy)
    print(f'Transcript gateway listening on {args.host}:{args.port}; API /v1/transcripts; data={args.data_dir}', flush=True)
    print('Read/write keys loaded; keys are not printed. /health is public; transcript endpoints require authorization.', flush=True)
    try: server.serve_forever()
    except KeyboardInterrupt: pass
    finally:
        server.server_close()
        # Handler threads are daemonized; process exit closes SQLite after any completed transactions.


if __name__ == '__main__':
    main()
