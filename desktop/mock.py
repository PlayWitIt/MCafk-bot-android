# Mock Minecraft 1.21.11 (protocol 774) server for testing the AFK bot.
import socket, struct, zlib, json, os, threading, time, sys
from cryptography.hazmat.primitives.asymmetric import rsa, padding
from cryptography.hazmat.primitives import serialization
from cryptography.hazmat.primitives.ciphers import Cipher, algorithms, modes

PROTO=int(sys.argv[4]) if len(sys.argv)>4 else 774
# ids (1.21.11)
L_C_ENC, L_C_SUCCESS, L_C_COMPRESS = 0x01, 0x02, 0x03
C_C_FINISH, C_C_KEEPALIVE, C_C_KNOWN, C_C_RP = None, None, None, None
md=json.load(open(sys.argv[1]))
def m(st,d):
    if st not in md: return {}
    return {n:int(k,16) for k,n in md[st][d]['types']['packet'][1][0]['type'][1]['mappings'].items()}
LC,LS,CC,CS,PC,PS=m('login','toClient'),m('login','toServer'),m('configuration','toClient'),m('configuration','toServer'),m('play','toClient'),m('play','toServer')
PSI={v:k for k,v in PS.items()}; CSI={v:k for k,v in CS.items()}

def varint(v):
    out=b''
    v&=0xffffffff
    while True:
        b=v&0x7f; v>>=7
        if v: out+=bytes([b|0x80])
        else: return out+bytes([b])
def mstr(s): b=s.encode(); return varint(len(b))+b
def nbt_str(s): b=s.encode(); return b'\x08'+struct.pack('>H',len(b))+b
def nbt_translate(key):
    k=key.encode()
    return b'\x0a'+b'\x08'+struct.pack('>H',9)+b'translate'+struct.pack('>H',len(k))+k+b'\x00'

class Conn:
    def __init__(s,sock): s.sock=sock; s.th=-1; s.enc=None; s.dec=None; s.buf=b''
    def recvn(s,n):
        while len(s.buf)<n:
            d=s.sock.recv(65536)
            if not d: raise EOFError
            if s.dec: d=s.dec.update(d)
            s.buf+=d
        r=s.buf[:n]; s.buf=s.buf[n:]; return r
    def rvar(s):
        v=0;sh=0
        while True:
            b=s.recvn(1)[0]; v|=(b&0x7f)<<sh; sh+=7
            if not b&0x80: return v
    def read(s):
        n=s.rvar(); d=s.recvn(n)
        if s.th>=0:
            dl,i=pvar(d,0); d=d[i:]
            if dl: d=zlib.decompress(d)
        pid,i=pvar(d,0); return pid,d[i:]
    def send(s,pid,payload=b''):
        body=varint(pid)+payload
        if s.th>=0:
            if len(body)>=s.th: body=varint(len(body))+zlib.compress(body)
            else: body=varint(0)+body
        f=varint(len(body))+body
        if s.enc: f=s.enc.update(f)
        s.sock.sendall(f)
def pvar(d,i):
    v=0;sh=0
    while True:
        b=d[i];i+=1;v|=(b&0x7f)<<sh;sh+=7
        if not b&0x80: return v,i

key=rsa.generate_private_key(public_exponent=65537,key_size=1024)
pub=key.public_key().public_bytes(serialization.Encoding.DER,serialization.PublicFormat.SubjectPublicKeyInfo)

def log(*a): print('[MOCK]',*a,flush=True)

def handle(sock):
    c=Conn(sock)
    pid,d=c.read(); proto,i=pvar(d,0); l,i=pvar(d,i); host=d[i:i+l]; i+=l; port=struct.unpack('>H',d[i:i+2])[0]; nxt=d[i+2]
    log('handshake',proto,host,port,nxt)
    if nxt==1:
        c.read()
        st={"version":{"name":"1.12.2" if PROTO==340 else "Folia 1.21.11","protocol":(-1 if os.environ.get("NOPROTO") else PROTO)},"players":{"max":20,"online":0},"description":{"text":"Mock §aserver"}}
        c.send(0,mstr(json.dumps(st))); return
    pid,d=c.read(); l,i=pvar(d,0); name=d[i:i+l].decode(); log('login_start',hex(pid),name,'uuid',d[i+l:].hex())
    if PROTO!=340:
        c.send(LC['encryption_begin'], mstr('')+varint(len(pub))+pub+varint(4)+b'abcd'+(b'' if PROTO==340 else b'\x00'))
        pid,d=c.read(); l,i=pvar(d,0); es=d[i:i+l]; i+=l; l2,i=pvar(d,i); et=d[i:i+l2]
        secret=key.decrypt(es,padding.PKCS1v15()); tok=key.decrypt(et,padding.PKCS1v15()); assert tok==b'abcd', tok
        ci=Cipher(algorithms.AES(secret),modes.CFB8(secret)); c.enc=ci.encryptor(); c.dec=ci.decryptor()
        if c.buf: c.buf=c.dec.update(c.buf)
        log('encryption OK')
    c.send(LC['compress'],varint(64)); c.th=64
    if PROTO==340:
        c.send(LC['success'], mstr('6a8a020e-9c7c-3983-b7af-5cad599e62e7')+mstr(name))
        return play(c,sock,name)
    c.send(LC['success'], os.urandom(16)+mstr(name)+varint(0))
    pid,d=c.read(); assert pid==LS['login_acknowledged'], pid; log('login ack')
    pid,d=c.read(); log('config got',CSI.get(pid),d.hex()[:60])
    c.send(CC['keep_alive'],struct.pack('>q',12345))
    pid,d=c.read(); assert CSI[pid]=='keep_alive' and struct.unpack('>q',d)[0]==12345; log('config keepalive OK')
    c.send(CC['select_known_packs'],varint(1)+mstr('minecraft')+mstr('core')+mstr('1.21.11'))
    pid,d=c.read(); log('known packs reply',CSI.get(pid),d)
    u=os.urandom(16); c.send(CC['add_resource_pack'],u+mstr('http://x/pack.zip')+mstr('')+b'\x01'+b'\x00')
    for _ in range(3): pid,d=c.read(); log('rp reply',CSI.get(pid),d[16:].hex(), d[:16]==u)
    if 'code_of_conduct' in CC:
        c.send(CC['code_of_conduct'],mstr('Be nice'))
        pid,d=c.read(); log('coc reply',CSI.get(pid))
    c.send(CC['finish_configuration'])
    pid,d=c.read(); assert CSI[pid]=='finish_configuration'; log('config finished -> play')
    return play(c,sock,name)

def pack_longs(vals,bits):
    per=64//bits; out=[]
    for i in range(0,len(vals),per):
        v=0
        for k,x in enumerate(vals[i:i+per]): v|=x<<(k*bits)
        out.append(v)
    return out
def chunk_packet(cx,cz):
    newfmt = PROTO>=770
    GRASS,STONE,WATER=9,1,86
    secs=b''
    for si in range(24):
        y0=-64+si*16
        idx=[]
        for i in range(4096):
            y=y0+i//256; z=(i//16)%16; x=i%16
            if x<8: v = 1 if y==64 else (2 if y<64 else 0)
            else: v = 3 if 60<=y<=62 else (2 if y<60 else 0)
            idx.append(v)
        if all(v==0 for v in idx):
            blk=b'\x00'+varint(0)+(b'' if newfmt else varint(0))
            cnt=0
        else:
            longs=pack_longs(idx,4)
            blk=b'\x04'+varint(4)+varint(0)+varint(GRASS)+varint(STONE)+varint(WATER)+(b'' if newfmt else varint(len(longs)))+b''.join(struct.pack('>q',l-(1<<64) if l>=1<<63 else l) for l in longs)
            cnt=sum(1 for v in idx if v)
        bio=b'\x00'+varint(0)+(b'' if newfmt else varint(0))
        secs+=struct.pack('>h',cnt)+blk+bio
    hv=[ (64+64+1) if (i%16)<8 else (62+64+1) for i in range(256)]
    hl=pack_longs(hv,9)
    hlb=b''.join(struct.pack('>q',l-(1<<64) if l>=1<<63 else l) for l in hl)
    if newfmt: hm=varint(1)+varint(4)+varint(len(hl))+hlb
    else:
        nm=b'MOTION_BLOCKING'
        hm=b'\x0a'+b'\x0c'+struct.pack('>H',len(nm))+nm+struct.pack('>i',len(hl))+hlb+b'\x00'
    return struct.pack('>ii',cx,cz)+hm+varint(len(secs))+secs+varint(0)+varint(0)*4+varint(0)*2

def play(c,sock,name):
    login=struct.pack('>i',42)+b'\x00'+varint(1)+mstr('minecraft:overworld')+varint(100)+varint(10)+varint(8)+b'\x00\x01\x00'
    if PROTO>=766: login+=varint(0)+mstr('minecraft:overworld')
    elif PROTO>=764: login+=mstr('minecraft:overworld')+mstr('minecraft:overworld')
    c.send(PC['login'],login+os.urandom(30))
    c.send(PC['map_chunk'] if 'map_chunk' in PC else 0x27, os.urandom(1000)+b'\x00'*100000)
    if PROTO==340:
        c.send(PC['position'], struct.pack('>dddffb',100.5,64.0,-20.5,90.0,0.0,0)+varint(7))
        c.send(PC['chat'], mstr(json.dumps({"text":"","extra":[{"text":"\u00a76Welcome "},{"text":"to 1.12 mock!"}]}))+b'\x00')
        c.send(PC['resource_pack_send'], mstr('http://x/p.zip')+mstr(''))
    else:
        c.send(PC['position'], varint(7)+struct.pack('>ddddddffi',100.5,64.0,-20.5,0,0,0,90.0,0.0,0))
        c.send(PC['system_chat'], nbt_str('Welcome to the mock server!')+b'\x00')
    scen=sys.argv[5] if len(sys.argv)>5 else ''
    if PROTO!=340:
        c.send(PC['login'] if False else PC['login'], struct.pack('>i',42)+os.urandom(50))
    stop=time.time()+float(sys.argv[2])
    t0=time.time(); stage=0; walkpos=[]; inputs=[]; ticks=[0]; door_close_at=[0]
    sock.settimeout(0.5); ka=time.time()
    seen={}
    ys=[]
    while time.time()<stop:
        el=time.time()-t0
        if door_close_at[0] and time.time()>door_close_at[0]:
            door_close_at[0]=0
            def pos4(x,y,z): return struct.pack('>q', ((x&0x3FFFFFF)<<38)|((z&0x3FFFFFF)<<12)|(y&0xFFF))
            c.send(PC['block_change'], pos4(102,65,-21)+varint(6639))
            c.send(PC['block_change'], pos4(102,66,-21)+varint(6631))
            log('door closed again; player x was', walkpos[-1][0] if walkpos else None)
        if scen=='walk' and stage==0 and el>0.5:
            stage=1
            for cx in (5,6):
                for cz in (-2,):
                    c.send(PC['map_chunk'], chunk_packet(cx,cz))
            c.send(PC['chunk_batch_finished'], varint(2))
            c.send(PC['position'], varint(8)+struct.pack('>ddddddffi',100.5,65.0,-20.5,0,0,0,-90.0,0.0,0))
            def pos(x,y,z): return struct.pack('>q', ((x&0x3FFFFFF)<<38)|((z&0x3FFFFFF)<<12)|(y&0xFFF))
            for yy in (65,66):
                c.send(PC['block_change'], pos(104,yy,-21)+varint(1))
            # IRON_DOOR facing west, closed, between player and pillar (x=102)
            c.send(PC['block_change'], pos(102,65,-21)+varint(6639))
            c.send(PC['block_change'], pos(102,66,-21)+varint(6631))
            # STONE_BUTTON on a wall next to the door
            c.send(PC['block_change'], pos(101,66,-22)+varint(6709))
            CHEST_STATE=3786
            c.send(PC['block_change'], pos(101,65,-23)+varint(CHEST_STATE))
            # player inventory with one good item and one corrupt item (bad component data)
            import gzip
            A=json.load(open('/home/user/mcafk-bot-android/app/src/main/assets/proto/774.json'))
            apple=A['items'].index('Apple')
            inv=b''.join([varint(0)]*36)+varint(5)+varint(apple)+varint(0)+varint(0)+varint(1)+varint(apple)+varint(1)+varint(0)+varint(9999)+b'\xff\xff'+b''.join([varint(0)]*8)
            c.send(PC['window_items'], varint(0)+varint(1)+varint(46)+inv+varint(0))
            log('placed chest at 101,65,-23 and sent inventory')
            log('walk world ready, pillar at x=104')
        if scen=='map' and stage==0 and el>1:
            stage=1
            for cx in (5,6):
                c.send(PC['map_chunk'], chunk_packet(cx,-2))
            c.send(PC['chunk_batch_finished'], varint(2))
            log('sent 2 chunks')
        if scen=='full' and stage==0 and el>1.5:
            stage=1
            pu=bytes(range(16))
            # player_info: add_player | update_listed, one entry
            c.send(PC['player_info'], b'\x09'+varint(1)+pu+mstr('Griefer')+varint(0)+varint(1))
            c.send(PC['spawn_entity'], varint(7)+os.urandom(16)+varint(150)+struct.pack('>ddd',105.5,64,-20.5)+b'\x00'*20)
            c.send(PC['spawn_entity'], varint(99)+pu+varint(155)+struct.pack('>ddd',160.5,70,-20.5)+b'\x00'*20)
            c.send(PC['player_chat'], varint(0)+pu+varint(0)+b'\x00'+mstr('hello afk bot')+b'\x00'*30)
            log('griefer spawned 60 blocks east, zombie 5 blocks')
        if scen=='full' and stage==1 and el>5:
            stage=2
            c.send(PC['sync_entity_position'], varint(99)+struct.pack('>ddd',115.5,64,-20.5)+b'\x00'*50)
            log('griefer now 15 blocks away')
        if scen=='player' and stage==0 and el>2:
            stage=1
            # spawn a zombie (ignored) and a player 60 blocks away
            c.send(PC['spawn_entity'], varint(7)+os.urandom(16)+varint(5)+struct.pack('>ddd',100.5,64,-20.5)+b'\x00'*20)
            c.send(PC['spawn_entity'], varint(99)+os.urandom(16)+varint(155)+struct.pack('>ddd',160.5,64,-20.5)+b'\x00'*20)
            log('spawned player 60 blocks away')
        if scen=='player' and stage==1 and el>4:
            stage=2
            c.send(PC['sync_entity_position'], varint(99)+struct.pack('>ddd',130.5,64,-20.5)+b'\x00'*50)
            log('player now 30 blocks away')
        if scen=='player' and stage==2 and el>6:
            stage=3
            c.send(PC['rel_entity_move'], varint(99)+struct.pack('>hhh',-8*4096,0,0)+b'\x01')
            log('player now 22 blocks away')
        if scen=='health' and stage==0 and el>2:
            stage=1; c.send(PC['update_health'], struct.pack('>f',14.0)+varint(20)+struct.pack('>f',5.0)); log('health 14')
        if scen=='health' and stage==1 and el>4:
            stage=2; c.send(PC['update_health'], struct.pack('>f',8.0)+varint(20)+struct.pack('>f',5.0)); log('health 8')
        if time.time()-ka>2:
            ka=time.time(); c.send(PC['keep_alive'],struct.pack('>q',int(ka)))
        try: pid,d=c.read()
        except socket.timeout: continue
        except EOFError: log('CLIENT DISCONNECTED at %.1fs'%(time.time()-t0)); return
        n=PSI.get(pid,hex(pid)); seen[n]=seen.get(n,0)+1
        if n=='teleport_confirm': log('teleport confirm id',pvar(d,0)[0])
        if n=='keep_alive': log('keepalive reply OK')
        if n in('position','position_look'):
            y=struct.unpack('>d',d[8:16])[0]; ys.append(round(y,3))
        if n=='player_loaded': log('player_loaded')
        if n in ('chat_command','chat_message'): log('GOT',n,d[:40])
        if n=='player_input': log('sneak input',d.hex())
        if n=='chunk_batch_received': log('chunk batch ack', struct.unpack('>f',d)[0])
        if n=='position_look' and scen=='walk':
            xx,yy,zz=struct.unpack('>ddd',d[:24]); walkpos.append((round(xx,2),round(yy,2),round(zz,2)))
        if n=='block_place' and struct.unpack('>q', d[1:9])[0] == ((101&0x3FFFFFF)<<38)|(((-22)&0x3FFFFFF)<<12)|66:
            log('BUTTON PRESSED -> door opens for 1 second')
            def pos3(x,y,z): return struct.pack('>q', ((x&0x3FFFFFF)<<38)|((z&0x3FFFFFF)<<12)|(y&0xFFF))
            c.send(PC['block_change'], pos3(102,65,-21)+varint(6637))
            c.send(PC['block_change'], pos3(102,66,-21)+varint(6629))
            door_close_at[0]=time.time()+1.0
        elif n=='block_place' and struct.unpack('>q', d[1:9])[0] == ((102&0x3FFFFFF)<<38)|(((-21)&0x3FFFFFF)<<12)|65:
            log('USE ON DOOR -> opening it')
            def pos2(x,y,z): return struct.pack('>q', ((x&0x3FFFFFF)<<38)|((z&0x3FFFFFF)<<12)|(y&0xFFF))
            c.send(PC['block_change'], pos2(102,65,-21)+varint(6637))
            c.send(PC['block_change'], pos2(102,66,-21)+varint(6629))
        elif n=='block_place':
            log('USE ITEM ON', d.hex())
            import gzip
            A=json.load(open('/home/user/mcafk-bot-android/app/src/main/assets/proto/774.json'))
            mp=A['types']['SlotComponentType'][1]['mappings']; cn=[int(k) for k,v in mp.items() if v=='custom_name'][0]
            dia=A['items'].index('Diamond'); sword=A['items'].index('Diamond Sword')
            c.send(PC['open_window'], varint(1)+varint(2)+nbt_str('Loot Chest'))
            slots=b''
            for i in range(63):
                if i==0: slots+=varint(64)+varint(dia)+varint(0)+varint(0)
                elif i==5:
                    nm=b'Excalibur'
                    slots+=varint(1)+varint(sword)+varint(1)+varint(0)+varint(cn)+b'\x08'+struct.pack('>H',len(nm))+nm
                else: slots+=varint(0)
            c.send(PC['window_items'], varint(1)+varint(3)+varint(63)+slots+varint(0))
            log('sent chest contents')
        if n=='window_click': log('WINDOW CLICK', d.hex())
        if n=='block_dig': log('BLOCK_DIG status=%d at %.2fs' % (d[0], time.time()-t0), d.hex())
        if n=='held_item_slot': log('HELD SLOT', d.hex())
        if n=='use_item': log('USE ITEM', d.hex())
        if n=='player_input': inputs.append(d.hex())
        if n=='tick_end': ticks[0]+=1
        if n=='close_window': log('CLOSE WINDOW', d.hex())
        if n=='settings': log('settings view distance', d[6])
        if n=='arm_animation': log('swing (jump start)')
        if n in ('settings','resource_pack_receive'): log(n, d.hex())
    log('packet counts',seen)
    if walkpos:
        log('walk positions first/last', walkpos[:2], walkpos[-3:])
        xs=[p[0] for p in walkpos]; steps=[round(b-a,4) for a,b in zip(xs,xs[1:]) if b-a>0.0001]
        log('per-tick x steps (blocks):', steps[:14])
    log('player_input packets:', inputs, 'tick_end count:', ticks[0])
    log('y values',ys[:40])
    c.send(PC['kick_disconnect'], mstr(json.dumps({"translate":"multiplayer.disconnect.idling"})) if PROTO==340 else nbt_translate('multiplayer.disconnect.idling'))
    time.sleep(0.5); sock.close()

srv=socket.socket(); srv.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1); srv.bind(('127.0.0.1',25599)); srv.listen()
log('listening')
for _ in range(int(sys.argv[3]) if len(sys.argv)>3 else 2):
    s,_=srv.accept()
    try: handle(s)
    except Exception as e: import traceback; traceback.print_exc()
