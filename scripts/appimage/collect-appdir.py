#!/usr/bin/env python3
"""Collect a closed Ubuntu runtime, copyright files and exact source package IDs.

Run only on the private Ubuntu 24.04 release builder, after Meson install.
Do not run this against a live desktop's distribution libraries.
"""
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess

work = Path(os.environ.get('WORK', '/work'))
source = Path(os.environ.get('SOURCE', '/source'))
app = work / 'AppDir'
lib = app / 'usr/lib'
lib.mkdir(parents=True, exist_ok=True)
packages = {}
files = []
# These interfaces are deliberately supplied by the target OS. We never bundle
# glibc, its loader, NSS or GPU vendor drivers into this application image.
external = re.compile(r'^(?:ld-linux.*|libc\.so\..*|libm\.so\..*|libdl\.so\..*|libpthread\.so\..*|librt\.so\..*|libresolv\.so\..*|libutil\.so\..*)$')
external_seen = set()

def run(*args):
    return subprocess.check_output(args, text=True).strip()

def owner(path):
    choices = [str(path), str(path.resolve())]
    for p in list(choices):
        if p.startswith('/usr/lib/'):
            choices.append(p.removeprefix('/usr'))
    for p in choices:
        r = subprocess.run(['dpkg-query', '-S', p], text=True, capture_output=True)
        if r.returncode == 0:
            return r.stdout.split(': ', 1)[0].splitlines()[0]
    raise RuntimeError(f'No package owner: {path}')

def record_package(package):
    if package not in packages:
        fields = run('dpkg-query', '-W', '-f=${binary:Package}\t${Version}\t${source:Package}\t${source:Version}', package).split('\t')
        packages[package] = dict(zip(['binary', 'binary_version', 'source', 'source_version'], fields))
        copyright = Path('/usr/share/doc') / package.split(':')[0] / 'copyright'
        if not copyright.exists():
            raise RuntimeError(f'Missing copyright: {package}')
        dest = app / 'usr/share/licenses/ubuntu' / package.split(':')[0]
        dest.mkdir(parents=True, exist_ok=True)
        shutil.copyfile(copyright.resolve(), dest / 'copyright')

def copyfile(path, dest):
    path = Path(path)
    dest = Path(dest)
    dest.parent.mkdir(parents=True, exist_ok=True)
    if str(path).startswith('/work/prefix/'):
        origin = 'source-built'
    else:
        origin = owner(path)
        record_package(origin)
    shutil.copy2(path.resolve(), dest)
    files.append({'path': str(dest.relative_to(app)), 'origin': origin,
                  'sha256_before_rpath': hashlib.sha256(dest.read_bytes()).hexdigest()})

queue = list((app / 'usr/bin').glob('metuxa-*'))
# Meson installs only the two application executables, never test programs.
assert sorted(p.name for p in queue) == ['metuxa-control', 'metuxa-server']

plugins = []
for name in ['rt', 'protocol-native', 'client-node', 'client-device', 'adapter', 'metadata', 'session-manager']:
    p = Path('/usr/lib/x86_64-linux-gnu/pipewire-0.3') / f'libpipewire-module-{name}.so'
    if not p.exists():
        raise RuntimeError(f'Missing required PipeWire module: {p}')
    plugins.append((p, lib / 'pipewire-0.3' / p.name))
for name in ['support', 'audioconvert', 'videoconvert']:
    for p in (Path('/usr/lib/x86_64-linux-gnu/spa-0.2') / name).glob('*.so'):
        plugins.append((p, lib / 'spa-0.2' / name / p.name))
for p in Path('/usr/lib/x86_64-linux-gnu/gdk-pixbuf-2.0').glob('*/loaders/libpixbufloader-svg.so'):
    plugins.append((p, lib / 'gdk-pixbuf-loaders' / p.name))
assert any('pixbufloader-svg' in p.name for p, _ in plugins)
for original, dest in plugins:
    copyfile(original, dest)
    queue.append(dest)

seen = set()
env = os.environ | {'LD_LIBRARY_PATH': f'{work}/prefix/lib:{lib}'}
while queue:
    p = queue.pop(0)
    if p in seen:
        continue
    seen.add(p)
    output = subprocess.check_output(['ldd', str(p)], text=True, env=env)
    if 'not found' in output:
        raise RuntimeError(f'Missing dependency for {p}:\n{output}')
    for line in output.splitlines():
        match = re.search(r'^\s*(\S+) => (/\S+)', line)
        if not match:
            continue
        soname, resolved = match.groups()
        if external.match(soname):
            external_seen.add(soname)
            continue
        dest = lib / soname
        if not dest.exists():
            copyfile(Path(resolved), dest)
            queue.append(dest)

# Data selected explicitly to avoid importing a desktop's personal settings.
for package, directory in [('adwaita-icon-theme','icons/Adwaita'), ('hicolor-icon-theme','icons/hicolor'), ('gsettings-desktop-schemas','glib-2.0/schemas')]:
    record_package(package)
    src = Path('/usr/share') / directory
    shutil.copytree(src, app / 'usr/share' / directory, dirs_exist_ok=True, symlinks=False)
# The schema directory also contains GLib and GTK package schemas.
for p in Path('/usr/share/glib-2.0/schemas').glob('*'):
    if p.is_file() and p.name != 'gschemas.compiled':
        record_package(owner(p))
subprocess.run(['glib-compile-schemas', str(app / 'usr/share/glib-2.0/schemas')], check=True)
copyfile(Path('/usr/share/pipewire/client.conf'), app / 'usr/share/pipewire/client.conf')
# Use matching Fontconfig rules rather than parsing a newer host's syntax.
record_package('fontconfig-config')
shutil.copytree('/etc/fonts', app / 'usr/etc/fonts', dirs_exist_ok=True, symlinks=False)
shutil.copytree('/usr/share/fontconfig/conf.avail', app / 'usr/etc/fonts/conf.avail', dirs_exist_ok=True, symlinks=False)
for base, _, names in os.walk('/etc/fonts', followlinks=True):
    for name in names:
        record_package(owner(Path(base) / name))
record_package('base-files')
shutil.copytree('/usr/share/common-licenses', app / 'usr/share/common-licenses', dirs_exist_ok=True)
for component in ['ffmpeg', 'x264', 'sdbus-cpp', 'fontconfig']:
    dest = app / 'usr/share/licenses' / component
    dest.mkdir(parents=True, exist_ok=True)
    for license_file in (work / 'build-deps' / component).iterdir():
        if license_file.is_file() and license_file.name.startswith(('COPYING', 'LICENSE')):
            shutil.copyfile(license_file, dest / license_file.name)
dest = app / 'usr/share/licenses/nv-codec-headers'
dest.mkdir(parents=True, exist_ok=True)
header = (work / 'build-deps/nv-codec-headers/include/ffnvcodec/nvEncodeAPI.h').read_text()
(dest / 'LICENSE').write_text(header.split('*/',1)[0]+'*/\n')
shutil.copyfile(work / 'build-deps/ffmpeg/COPYING.GPLv3', app / 'COPYING.GPLv3')
shutil.copyfile(source / 'scripts/appimage/AppRun', app / 'AppRun')
(app / 'AppRun').chmod(0o755)
# Root icon is copied byte-for-byte, never re-rendered or transformed.
shutil.copyfile(source / 'data/branding/metuxa-tux.png', app / 'dev.metuxa.Control.png')
shutil.copyfile(source / 'data/branding/metuxa-tux.png', app / '.DirIcon')
shutil.copyfile(source / 'client/gtk/dev.metuxa.Control.desktop', app / 'dev.metuxa.Control.desktop')
for name in ['LICENSE', 'THIRD-PARTY-NOTICES.md']:
    shutil.copyfile(source / name, app / name)
for p in seen:
    subprocess.run(['patchelf','--remove-rpath',str(p)],check=True)
# A complete cache is generated from precisely the loader(s) bundled above.
query = next(Path('/usr/lib/x86_64-linux-gnu/gdk-pixbuf-2.0').glob('gdk-pixbuf-query-loaders'))
loaders = sorted((lib / 'gdk-pixbuf-loaders').glob('*.so'))
cache = subprocess.check_output([str(query), *map(str, loaders)], text=True, env=env)
(lib / 'gdk-pixbuf-loaders.cache.in').write_text(cache.replace(str(app), '@APPDIR@'))
(lib / 'gio/modules').mkdir(parents=True, exist_ok=True)
metadata = app / 'usr/share/metuxa'
metadata.mkdir(parents=True, exist_ok=True)
(metadata / 'ubuntu-packages.json').write_text(json.dumps(sorted(packages.values(), key=lambda x:x['binary']), indent=2)+'\n')
(metadata / 'bundled-files.json').write_text(json.dumps(files, indent=2)+'\n')
(metadata / 'host-interfaces.txt').write_text('\n'.join(sorted(external_seen))+'\n')
shutil.copyfile(source / 'scripts/appimage/sources.lock.json', metadata / 'sources.lock.json')
print(f'Collected {len(seen)} ELF objects, {len(packages)} Ubuntu binary packages.')
