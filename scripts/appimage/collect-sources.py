#!/usr/bin/env python3
"""Download exact Ubuntu source packages for the files actually bundled.

Run in the same authenticated APT environment used to build the AppDir. APT
checks each download against its signed repository metadata. Nothing executes
from these source archives. Refuse incomplete correspondence.
"""
import hashlib
import json
import os
from pathlib import Path
import subprocess

work = Path(os.environ.get('WORK', '/work'))
packages = json.loads((work / 'AppDir/usr/share/metuxa/ubuntu-packages.json').read_text())
sources = sorted({(p['source'], p['source_version']) for p in packages})
destination = work / 'sources/ubuntu'
destination.mkdir(parents=True, exist_ok=True)
manifest = []
previous = set()
if (destination / 'source-packages.json').exists() and (destination / 'SHA256SUMS').exists():
    subprocess.run(['sha256sum','--quiet','-c','SHA256SUMS'],cwd=destination,check=True)
    previous = {(p['source'],p['version']) for p in json.loads((destination / 'source-packages.json').read_text())}
for package, version in sources:
    print(f'Collecting {package}={version}', flush=True)
    if (package,version) not in previous:
        subprocess.run(['apt-get','source','--download-only','--yes',f'{package}={version}'], cwd=destination, check=True)
    manifest.append({'source':package, 'version':version})
(destination / 'source-packages.json').write_text(json.dumps(manifest, indent=2)+'\n')
checksums = []
for p in sorted(destination.iterdir()):
    if p.is_file() and p.name != 'SHA256SUMS':
        checksums.append(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.name}\n')
(destination / 'SHA256SUMS').write_text(''.join(checksums))
print(f'Collected {len(sources)} exact source packages; keep .dsc, orig archives and Debian patches together.')
