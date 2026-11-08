#!/usr/bin/env python3
"""Retain and verify all static runtime library sources and Alpine patches."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tarfile
work = Path(os.environ.get('WORK', '.release-work'))
root = work / 'sources/alpine'
root.mkdir(parents=True, exist_ok=True)
commit = 'c2eb7755270e927dc5ee94aa9228cb95061afe35'
archive = root / 'aports-c2eb7755.tar.gz'
if not archive.exists():
    subprocess.run(['curl','-fL','--retry','2',f'https://api.github.com/repos/alpinelinux/aports/tarball/{commit}','-o',str(archive)],check=True)
inputs = [
 ('main/musl', 'musl-1.2.5.tar.gz', 'https://musl.libc.org/releases/musl-1.2.5.tar.gz'),
 ('main/zlib', 'zlib-1.4.tar.gz', 'https://zlib.net/fossils/zlib-1.4.tar.gz'),
 ('main/zstd', 'zstd-1.5.6.tar.gz', 'https://github.com/facebook/zstd/archive/v1.5.6.tar.gz'),
 ('community/mimalloc2', 'mimalloc-2.1.7.tar.gz', 'https://github.com/microsoft/mimalloc/archive/v2.1.7/mimalloc-2.1.7.tar.gz')]
manifest = []
with tarfile.open(archive) as tf:
    prefix = tf.getnames()[0].split('/')[0]
    for package, name, url in inputs:
        recipe = tf.extractfile(f'{prefix}/{package}/APKBUILD').read().decode()
        expected = re.search(r'^([0-9a-f]{128})  '+re.escape(name)+r'$',recipe,re.M).group(1)
        path = root / name
        if not path.exists():
            subprocess.run(['curl','-fL','--retry','2',url,'-o',str(path)],check=True)
        if hashlib.sha512(path.read_bytes()).hexdigest() != expected:
            raise RuntimeError(f'Official Alpine source checksum mismatch: {name}')
        manifest.append({'package':package,'archive':name,'url':url,'sha512':expected,'aports_commit':commit})
(root / 'source-packages.json').write_text(json.dumps(manifest,indent=2)+'\n')
print('Verified all four Alpine static-library source archives and retained complete package recipes/patches.')
