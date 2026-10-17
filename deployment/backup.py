"""Private SQLite backup, excluding short-lived raw audio; keep seven dated snapshots."""
from datetime import datetime,timezone
from pathlib import Path
import os
import sqlite3
import tarfile
import tempfile
ROOT=Path('/var/lib/shengying')
os.umask(0o077)
out=ROOT/'backups';out.mkdir(exist_ok=True)
with tempfile.TemporaryDirectory(dir=out) as tmp:
    tmp=Path(tmp)
    sources=[ROOT/'installations.sqlite3',*list((ROOT/'installations').glob('*/*.sqlite3'))]
    for source in sources:
        if not source.is_file():continue
        target=tmp/source.relative_to(ROOT);target.parent.mkdir(parents=True,exist_ok=True)
        with sqlite3.connect(source) as live,sqlite3.connect(target) as backup:
            live.backup(backup)
            tables={row[0] for row in backup.execute("SELECT name FROM sqlite_master WHERE type='table'")}
            for table in ('audio_chunks','audio_buffer'):
                if table in tables:backup.execute('DELETE FROM '+table)
            backup.commit();backup.execute('VACUUM')
            if backup.execute('PRAGMA integrity_check').fetchone()[0]!='ok':raise RuntimeError('Backup integrity failure')
    archive=out/('metadata-'+datetime.now(timezone.utc).strftime('%Y%m%dT%H%M%SZ')+'.tar.gz')
    with tarfile.open(archive,'w:gz') as file:
        for source in tmp.rglob('*.sqlite3'):file.add(source,arcname=source.relative_to(tmp).as_posix())
for old in sorted(out.glob('metadata-*.tar.gz'),reverse=True)[7:]:old.unlink()
print('Private metadata backup verified; short-lived raw audio excluded')
