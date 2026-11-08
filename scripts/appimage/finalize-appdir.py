#!/usr/bin/env python3
"""Attach runtime licenses and exact source/build inventories before packing."""
import hashlib
import json
import os
from pathlib import Path
import shutil
import tarfile
root=Path(__file__).resolve().parents[2]
work=Path(os.environ.get('WORK',root/'.release-work')).resolve()
app=work/'AppDir'
inputs=[('appimage-runtime','appimage-runtime-fedfad51.tar.gz',{'LICENSE'}),
 ('libfuse','fuse-3.15.0.tar.xz',{'LGPL2.txt','GPL2.txt'}),
 ('squashfuse','squashfuse-0.5.2.tar.gz',{'LICENSE'}),
 ('musl','alpine/musl-1.2.5.tar.gz',{'COPYRIGHT'}),
 ('mimalloc','alpine/mimalloc-2.1.7.tar.gz',{'LICENSE'}),
 ('zstd','alpine/zstd-1.5.6.tar.gz',{'LICENSE','COPYING'}),
 ('zlib','alpine/zlib-1.4.tar.gz',{'README','zlib.h'})]
for component,filename,names in inputs:
    with tarfile.open(work/'sources'/filename) as tf:
        found=[]
        for member in tf.getmembers():
            if member.isfile() and len(Path(member.name).parts)==2 and Path(member.name).name in names:
                target=app/'usr/share/licenses'/component/Path(member.name).name
                target.parent.mkdir(parents=True,exist_ok=True)
                target.write_bytes(tf.extractfile(member).read())
                found.append(member.name)
        if not found: raise RuntimeError(f'Missing license text: {component}')
for name in ['LICENSE','THIRD-PARTY-NOTICES.md']:
    shutil.copyfile(root/name,app/name)
metadata=app/'usr/share/metuxa'
for filename in ['sources.lock.json','ubuntu-build-packages.lock','alpine-build-packages.lock']:
    shutil.copyfile(root/'scripts/appimage'/filename,metadata/filename)
for filename in ['ubuntu-packages.tsv','runtime-packages.txt']:
    shutil.copyfile(work/filename,metadata/filename)
packages=json.loads((metadata/'ubuntu-packages.json').read_text())
provided=json.loads((work/'sources/ubuntu/source-packages.json').read_text())
assert {(p['source'],p['source_version']) for p in packages} <= {(p['source'],p['version']) for p in provided}
index=[]
for p in sorted((work/'sources').rglob('*')):
    if p.is_file() and p.name!='SOURCE-INDEX.json':
        index.append({'file':str(p.relative_to(work/'sources')),'sha256':hashlib.sha256(p.read_bytes()).hexdigest(),'bytes':p.stat().st_size})
(work/'sources/SOURCE-INDEX.json').write_text(json.dumps(index,indent=2)+'\n')
shutil.copyfile(work/'sources/SOURCE-INDEX.json',metadata/'SOURCE-INDEX.json')
# Record exact source inputs used by the two application executables.
source_files=[]
for name in ['src','client/gtk','client/desktop_test','tests','data/branding']:
    for p in sorted((root/name).rglob('*')):
        if p.is_file() and p.suffix in {'.cpp','.hpp','.h','.build','.png','.svg','.xml'}:
            source_files.append((str(p.relative_to(root)),hashlib.sha256(p.read_bytes()).hexdigest()))
for name in ['meson.build','meson_options.txt']:
    source_files.append((name,hashlib.sha256((root/name).read_bytes()).hexdigest()))
(metadata/'application-source.sha256').write_text(''.join(f'{h}  {name}\n' for name,h in sorted(source_files)))
(metadata/'BUILD-INFO.txt').write_text('metuxa 0.4.2 x86_64 AppImage\nUbuntu 24.04 baseline, host glibc >= 2.39\nFFmpeg GPLv3 configuration; nonfree disabled\nRuntime rebuilt locally from pinned official AppImage source, not an official signed AppImage binary\nCorresponding source: metuxa-0.4.2-source.tar.gz AND metuxa-0.4.2-dependency-sources.tar.gz in the same release\n')
files=[]
for p in sorted(app.rglob('*')):
    if p.is_file() and p.name!='FILES.sha256':
        files.append(f'{hashlib.sha256(p.read_bytes()).hexdigest()}  {p.relative_to(app)}\n')
(metadata/'FILES.sha256').write_text(''.join(files))
print(f'Licenses and source correspondence verified: {len(packages)} binary packages, {len(provided)} Ubuntu source packages.')
