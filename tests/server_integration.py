#!/usr/bin/env python3
"""Synthetic end-to-end test. Run ONLY in a private network namespace, no user bus/display."""
import hashlib,json,os,queue,signal,socket,ssl,subprocess,sys,threading,time
from cryptography.hazmat.primitives.ciphers.aead import AESGCM
SERVER=os.path.abspath(sys.argv[1])
assert not os.environ.get('DISPLAY') and not os.environ.get('WAYLAND_DISPLAY') and not os.environ.get('DBUS_SESSION_BUS_ADDRESS')

def start(monitors=1,audio='none'):
    read,write=os.pipe();events=queue.Queue();errors=[]
    p=subprocess.Popen([SERVER,'--source','test','--width','320','--height','240','--fps','15','--bitrate','1000','--no-hw','--codec','h264','--audio',audio,'--monitors',str(monitors),'--port','27878','--no-discovery','--status-fd',str(write)],pass_fds=(write,),stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,text=True)
    os.close(write)
    def status():
        with os.fdopen(read) as f:
            for line in f:
                try: events.put(json.loads(line))
                except json.JSONDecodeError: pass
    def logs():
        for line in p.stderr:
            errors.append(line)
            if len(errors)>200:del errors[0]
    threading.Thread(target=status,daemon=True).start();threading.Thread(target=logs,daemon=True).start()
    credentials=None;deadline=time.monotonic()+12
    while time.monotonic()<deadline:
        if p.poll() is not None:raise AssertionError('synthetic server exited: '+''.join(errors[-8:]))
        try:e=events.get(timeout=.2)
        except queue.Empty:continue
        if e.get('type')=='security':credentials=e
        if e.get('state')=='listening':assert credentials;return p,credentials,events,errors
    p.kill();p.wait();raise AssertionError('synthetic startup timeout')

def connect(credentials,index=0):
    ctx=ssl.SSLContext(ssl.PROTOCOL_TLS_CLIENT);ctx.check_hostname=False;ctx.verify_mode=ssl.CERT_NONE;ctx.minimum_version=ssl.TLSVersion.TLSv1_2
    s=ctx.wrap_socket(socket.socket(),server_hostname='metuxa-test');s.settimeout(4);s.connect(('127.0.0.1',27878+index))
    assert hashlib.sha256(s.getpeercert(binary_form=True)).hexdigest()==credentials['fingerprint']
    s.sendall(('AUTH '+credentials['pairing_code']+'\n').encode());f=s.makefile('rb');line=f.readline();assert line.startswith(b'HELLO ');h=json.loads(line[6:]);assert h['version']==5 and not h['input']
    udp=socket.socket(socket.AF_INET,socket.SOCK_DGRAM);udp.bind(('127.0.0.1',0));udp.settimeout(4)
    s.sendall(('READY {"port":'+str(udp.getsockname()[1])+'}\n').encode());assert f.readline()==b'START\n'
    return s,f,udp,h

def close_client(client):
    s,f,u,h=client
    try:s.sendall(b'INPUT r\nBYE\n')
    except OSError:pass
    f.close();s.close();u.close();time.sleep(.08)

def receive(client,seconds=1):
    s,f,u,h=client;sec=h['security'];cipher=AESGCM(bytes.fromhex(sec['s2c_key']));prefix=bytes.fromhex(sec['s2c_prefix']);end=time.monotonic()+seconds
    videos=audios=0;seen=set();annex=bytearray();frag=None
    while time.monotonic()<end:
        packet,addr=u.recvfrom(2048);assert packet[:5]==b'MXG1\x01' and packet[5:8]==b'\0\0\0';counter=packet[8:16];assert counter not in seen;seen.add(counter)
        rtp=cipher.decrypt(prefix+counter,packet[16:],packet[:16]);assert rtp[0]>>6==2
        pt=rtp[1]&127
        if pt==h['video']['pt']:
            videos+=1;payload=rtp[12:];kind=payload[0]&31
            if kind==28:
                if payload[1]&128:frag=bytearray(b'\0\0\0\1'+bytes([(payload[0]&0xe0)|(payload[1]&31)]))
                if frag is not None:frag.extend(payload[2:])
                if payload[1]&64 and frag is not None:annex.extend(frag);frag=None
            elif kind in range(1,24):annex.extend(b'\0\0\0\1'+payload)
        elif 'audio'in h and pt==h['audio']['pt']:audios+=1
    assert videos>5
    if annex:
        decoded=subprocess.run(['ffmpeg','-v','error','-f','h264','-i','pipe:0','-frames:v','1','-f','null','-'],input=annex,stdout=subprocess.DEVNULL,stderr=subprocess.PIPE,timeout=5)
        assert decoded.returncode==0,'decrypted video failed to decode: '+decoded.stderr.decode()[:200]
    else:raise AssertionError('No H264 access units reconstructed')
    return videos,audios

def stop(p,events):
    p.send_signal(signal.SIGTERM);assert p.wait(timeout=5)==0
    time.sleep(.05);states=[]
    while not events.empty():states.append(events.get().get('state'))
    assert 'stopped'in states

p=None
try:
    p,first,events,errors=start(3,'test')
    clients=[connect(first,i)for i in range(3)]
    assert all(c[3]['monitor_count']==3 for c in clients)
    assert 'audio'in clients[0][3] and all('audio'not in c[3]for c in clients[1:])
    counts=[receive(c,.8)for c in clients];assert counts[0][1]>5 and counts[1][1]==counts[2][1]==0
    for c in clients:close_client(c)
    for i in range(20):
        c=connect(first);close_client(c)
    p.send_signal(signal.SIGUSR1);time.sleep(.15);p.send_signal(signal.SIGUSR2)
    stop(p,events);p=None
    p,second,events,errors=start(1,'none');assert first['fingerprint']!=second['fingerprint'] and first['pairing_code']!=second['pairing_code']
    c=connect(second);assert 'audio'not in c[3];receive(c,.5);close_client(c);stop(p,events);p=None
    print('PASS: isolated full server startup, TLS/authentication, 3 encrypted software-video streams decoded, primary-only synthetic audio, 20 reconnects, gate signals, orderly stop, fresh run identity, audio-off.')
finally:
    if p is not None and p.poll() is None:p.kill();p.wait()
