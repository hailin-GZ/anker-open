"""Download only the pinned VAD dependency required by the cloud backend."""
import hashlib,json
from pathlib import Path
import urllib.request
ROOT=Path(__file__).resolve().parents[1]
asset=next(x for x in json.loads((ROOT/'backend-audio/models.lock.json').read_text())['assets'] if x['name']=='silero_vad.onnx')
target=ROOT/'backend-audio/models'/asset['name'];target.parent.mkdir(exist_ok=True)
if not target.is_file() or hashlib.sha256(target.read_bytes()).hexdigest()!=asset['sha256']:
    with urllib.request.urlopen(asset['url'],timeout=60) as response:data=response.read(2_000_000)
    if len(data)!=asset['bytes'] or hashlib.sha256(data).hexdigest()!=asset['sha256']:raise RuntimeError('VAD dependency verification failed')
    target.write_bytes(data)
print('Pinned Silero VAD dependency verified')
