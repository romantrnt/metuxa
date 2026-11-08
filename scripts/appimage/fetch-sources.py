#!/usr/bin/env python3
"""Fetch official pinned inputs, verifying every retained archive before build."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[2]
work = Path(os.environ.get('WORK', root / '.release-work')).resolve()
dest = work / 'sources'
dest.mkdir(parents=True,exist_ok=True)
lock = json.loads((root / 'scripts/appimage/sources.lock.json').read_text())
def verify(path, expected):
    if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
        raise RuntimeError(f'Checksum mismatch: {path}')
for item in lock['git_sources']:
    path = dest / item['file']
    if not path.exists():
        with tempfile.TemporaryDirectory(prefix='metuxa-source-') as d:
            subprocess.run(['git','init','--bare',d],check=True)
            subprocess.run(['git','-C',d,'fetch','--depth=1',item['repository'],item['commit']],check=True)
            archive = subprocess.Popen(['git','-C',d,'archive','--format=tar',item['commit']],stdout=subprocess.PIPE)
            with path.open('wb') as output:
                subprocess.run(['gzip','-n'],stdin=archive.stdout,stdout=output,check=True)
            if archive.wait(): raise RuntimeError('git archive failed')
    verify(path,item['sha256'])
runtime = lock['appimage_runtime']
for name,url,sha in [(runtime['source_file'],runtime['source_url'],runtime['source_sha256']),
 ('fuse-3.15.0.tar.xz','https://github.com/libfuse/libfuse/releases/download/fuse-3.15.0/fuse-3.15.0.tar.xz','70589cfd5e1cff7ccd6ac91c86c01be340b227285c5e200baa284e401eea2ca0'),
 ('squashfuse-0.5.2.tar.gz','https://github.com/vasi/squashfuse/archive/0.5.2.tar.gz','db0238c5981dabbd80ee09ae15387f390091668ca060a7bc38047912491443d3')]:
    path=dest/name
    if not path.exists(): subprocess.run(['curl','-fL','--retry','2',url,'-o',str(path)],check=True)
    verify(path,sha)
print('Verified pinned FFmpeg, x264, sdbus-c++, NVIDIA headers and runtime/FUSE source inputs.')
