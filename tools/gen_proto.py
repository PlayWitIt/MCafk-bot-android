# Generates src/app/afkbot/Proto.java from minecraft-data (run from minecraft-data package dir)
import json,sys
dp=json.load(open('data/dataPaths.json'))['pc']
pv={x['minecraftVersion']:x['version'] for x in json.load(open('data/pc/common/protocolVersions.json'))}
SPEC=[
 ('L_C_DISCONNECT','login','toClient',['disconnect']),
 ('L_C_ENCRYPT','login','toClient',['encryption_begin']),
 ('L_C_SUCCESS','login','toClient',['success']),
 ('L_C_COMPRESS','login','toClient',['compress']),
 ('L_C_PLUGIN_REQ','login','toClient',['login_plugin_request']),
 ('L_C_COOKIE_REQ','login','toClient',['cookie_request']),
 ('L_S_START','login','toServer',['login_start']),
 ('L_S_ENCRYPT','login','toServer',['encryption_begin']),
 ('L_S_PLUGIN_RESP','login','toServer',['login_plugin_response']),
 ('L_S_ACK','login','toServer',['login_acknowledged']),
 ('L_S_COOKIE_RESP','login','toServer',['cookie_response']),
 ('C_C_DISCONNECT','configuration','toClient',['disconnect']),
 ('C_C_FINISH','configuration','toClient',['finish_configuration']),
 ('C_C_KEEPALIVE','configuration','toClient',['keep_alive']),
 ('C_C_PING','configuration','toClient',['ping']),
 ('C_C_KNOWN_PACKS','configuration','toClient',['select_known_packs']),
 ('C_C_COOKIE_REQ','configuration','toClient',['cookie_request']),
 ('C_C_RESOURCE_PACK','configuration','toClient',['add_resource_pack','resource_pack_send']),
 ('C_C_CODE_OF_CONDUCT','configuration','toClient',['code_of_conduct']),
 ('C_S_FINISH','configuration','toServer',['finish_configuration']),
 ('C_S_KEEPALIVE','configuration','toServer',['keep_alive']),
 ('C_S_PONG','configuration','toServer',['pong']),
 ('C_S_KNOWN_PACKS','configuration','toServer',['select_known_packs']),
 ('C_S_COOKIE_RESP','configuration','toServer',['cookie_response']),
 ('C_S_RESOURCE_PACK','configuration','toServer',['resource_pack_receive']),
 ('C_S_ACCEPT_COC','configuration','toServer',['accept_code_of_conduct']),
 ('C_S_SETTINGS','configuration','toServer',['settings']),
 ('P_C_KEEPALIVE','play','toClient',['keep_alive']),
 ('P_C_PING','play','toClient',['ping']),
 ('P_C_DISCONNECT','play','toClient',['kick_disconnect']),
 ('P_C_POSITION','play','toClient',['position']),
 ('P_C_LOGIN','play','toClient',['login']),
 ('P_C_START_CONFIG','play','toClient',['start_configuration']),
 ('P_C_HEALTH','play','toClient',['update_health']),
 ('P_C_SYSTEM_CHAT','play','toClient',['system_chat','chat']),
 ('P_C_RESOURCE_PACK','play','toClient',['add_resource_pack','resource_pack_send']),
 ('P_C_COOKIE_REQ','play','toClient',['cookie_request']),
 ('P_S_KEEPALIVE','play','toServer',['keep_alive']),
 ('P_S_PONG','play','toServer',['pong']),
 ('P_S_TELEPORT_CONFIRM','play','toServer',['teleport_confirm']),
 ('P_S_POSITION','play','toServer',['position']),
 ('P_S_POSITION_LOOK','play','toServer',['position_look']),
 ('P_S_LOOK','play','toServer',['look']),
 ('P_S_CLIENT_COMMAND','play','toServer',['client_command']),
 ('P_S_SWING','play','toServer',['arm_animation']),
 ('P_S_CONFIG_ACK','play','toServer',['configuration_acknowledged']),
 ('P_S_PLAYER_LOADED','play','toServer',['player_loaded']),
 ('P_S_RESOURCE_PACK','play','toServer',['resource_pack_receive']),
 ('P_S_COOKIE_RESP','play','toServer',['cookie_response']),
 ('P_S_SETTINGS','play','toServer',['settings']),
 ('P_C_SPAWN_ENTITY','play','toClient',['spawn_entity']),
 ('P_C_SPAWN_PLAYER','play','toClient',['named_entity_spawn']),
 ('P_C_ENTITY_DESTROY','play','toClient',['entity_destroy']),
 ('P_C_REL_MOVE','play','toClient',['rel_entity_move']),
 ('P_C_MOVE_LOOK','play','toClient',['entity_move_look']),
 ('P_C_ENTITY_TELEPORT','play','toClient',['entity_teleport']),
 ('P_C_ENTITY_SYNC','play','toClient',['sync_entity_position']),
 ('P_C_RESPAWN','play','toClient',['respawn']),
 ('P_C_SPAWN_LIVING','play','toClient',['spawn_entity_living']),
 ('P_C_PLAYER_INFO','play','toClient',['player_info']),
 ('P_C_PLAYER_REMOVE','play','toClient',['player_remove']),
 ('P_C_PLAYER_CHAT','play','toClient',['player_chat']),
 ('P_S_CHAT_COMMAND','play','toServer',['chat_command']),
 ('P_S_CHAT_MESSAGE','play','toServer',['chat_message','chat']),
 ('P_S_ENTITY_ACTION','play','toServer',['entity_action']),
 ('P_S_PLAYER_INPUT','play','toServer',['player_input']),
 ('P_C_MAP_CHUNK','play','toClient',['map_chunk']),
 ('P_C_UNLOAD_CHUNK','play','toClient',['unload_chunk']),
 ('P_C_CHUNK_BATCH_FINISHED','play','toClient',['chunk_batch_finished']),
 ('P_S_CHUNK_BATCH_RECEIVED','play','toServer',['chunk_batch_received']),
 ('P_C_BLOCK_CHANGE','play','toClient',['block_change']),
 ('P_C_MULTI_BLOCK','play','toClient',['multi_block_change']),
 ('P_C_WINDOW_ITEMS','play','toClient',['window_items']),
 ('P_C_SET_SLOT','play','toClient',['set_slot']),
 ('P_C_OPEN_WINDOW','play','toClient',['open_window']),
 ('P_C_CLOSE_WINDOW','play','toClient',['close_window']),
 ('P_S_USE_ITEM_ON','play','toServer',['block_place']),
 ('P_S_WINDOW_CLICK','play','toServer',['window_click']),
 ('P_S_CLOSE_WINDOW','play','toServer',['close_window']),
 ('P_S_HELD_SLOT','play','toServer',['held_item_slot']),
 ('P_S_TICK_END','play','toServer',['tick_end']),
 ('P_S_BLOCK_DIG','play','toServer',['block_dig']),
 ('P_S_USE_ENTITY','play','toServer',['use_entity']),
 ('P_S_USE_ITEM','play','toServer',['use_item']),
 ('P_C_HELD_SLOT','play','toClient',['held_item_slot']),
]

# ---- map colours (approximate, like a vanilla map) ----
COLORS = {}
DYES = {'white':0xE9ECEC,'orange':0xF07613,'magenta':0xBD44B3,'light_blue':0x3AAFD9,'yellow':0xF8C627,'lime':0x70B919,
        'pink':0xED8DAC,'gray':0x3E4447,'light_gray':0x8E8E86,'cyan':0x158991,'purple':0x792AAC,'blue':0x35399D,
        'brown':0x724728,'green':0x546D1B,'red':0xA12722,'black':0x141519}
TRANSPARENT_WORDS = ('air','light','barrier','structure_void','torch','rail','sign','banner',
    'tripwire','redstone_wire','flower_pot','candle','vine','ladder','string','cobweb','fire','head','skull',
    'carpet','pane','bars','chain','lantern','rod','pointed_dripstone','amethyst_bud','cluster','sapling','frogspawn','moss_carpet')
PLANTS = ('short_grass','tall_grass','grass','fern','dandelion','poppy','orchid','allium','azure','tulip','daisy','cornflower',
    'lily_of_the_valley','wither_rose','sunflower','lilac','rose_bush','peony','bush','dead_bush','sweet_berry','torchflower','pitcher',
    'mushroom','roots','sprouts','fungus','petals','leaf_litter','firefly','cactus_flower','eyeblossom','wildflowers')
def color_for(n):
    if n in ('air','cave_air','void_air'): return 0
    if n=='water' or 'kelp' in n or 'seagrass' in n or n=='bubble_column': return 0x3F76E4
    if n=='lava': return 0xD96415
    if n=='grass_block': return 0x7CBD6B
    if n in ('iron_door','iron_trapdoor','heavy_weighted_pressure_plate'): return 0xC9C9C9
    if n=='light_weighted_pressure_plate': return 0xF8D33D
    if n=='lever': return 0x8A8A8A
    if n.endswith('_button') or n.endswith('_pressure_plate'):
        if any(w in n for w in ('stone','blackstone','deepslate')): return 0x9A9A9A
        return 0xA88654
    WOODS=('oak','spruce','birch','jungle','acacia','dark_oak','mangrove','cherry','pale_oak')
    if any(n.startswith(w+'_') for w in WOODS) and any(k in n for k in ('stairs','slab','fence','door','planks')):
        return 0xA88654
    for d,c in sorted(DYES.items(), key=lambda kv:-len(kv[0])):
        if n.startswith(d+'_') and any(w in n for w in ('wool','concrete','terracotta','glass','shulker','bed','candle')):
            return c
    if any(n==w or n.endswith('_'+w) or n.startswith(w+'_') or ('_'+w+'_') in n for w in TRANSPARENT_WORDS): return 0
    if any(w in n for w in PLANTS): return 0x5E9C40
    rules = [
        ('grass_block',0x7CBD6B),('snow',0xF0F8FF),('powder_snow',0xF0F8FF),('ice',0xA0C8FF),('red_sand',0xBE6621),
        ('sandstone',0xD8CB9A),('sand',0xDBD3A0),('gravel',0x8C8580),('podzol',0x7B5835),('mycelium',0x6F6265),
        ('dirt_path',0x947A4A),('farmland',0x6F4E2B),('mud_brick',0x8B6E5C),('mud',0x3C3A3D),('dirt',0x8B6A45),
        ('clay',0xA0A6B4),('moss',0x5A7A2E),('cherry_leaves',0xE8A6C4),('azalea',0x5D7A2E),('leaves',0x48782F),
        ('crimson',0x7B2E3A),('warped',0x2A7A72),('nether_wart',0x7B1E1E),('netherrack',0x6F3131),('nether_brick',0x2C1517),
        ('soul',0x524035),('basalt',0x4E4E54),('blackstone',0x2A2328),('glowstone',0xF5D26B),('shroomlight',0xF2A04B),
        ('end_stone',0xDBDE9E),('purpur',0xA77BA7),('obsidian',0x15101F),('bedrock',0x3A3A3A),('magma',0x8E3E1A),
        ('terracotta',0x9A5F45),('_log',0x6B5235),('_wood',0x6B5235),('_stem',0x6B5235),('hyphae',0x6B5235),
        ('bamboo',0x7DA33A),('planks',0xA88654),('_slab',0x9A9A9A),('_stairs',0x9A9A9A),('fence',0xA88654),('door',0xA88654),
        ('cactus',0x2F7A2F),('pumpkin',0xE38A1D),('melon',0x7DAA2B),('hay',0xC9A426),('lily_pad',0x208030),
        ('sugar_cane',0x8BC36A),('glass',0xC8E6EE),('diorite',0xBDBDBD),('granite',0x956755),('calcite',0xDFE0DC),
        ('dripstone',0x866B5C),('deepslate',0x505055),('tuff',0x6C6D66),('copper',0xC06C50),('iron_block',0xDCDCDC),
        ('gold_block',0xF8D33D),('diamond_block',0x62EDE4),('emerald_block',0x41F384),('lapis_block',0x1F43A6),
        ('redstone_block',0xAF1A05),('quartz',0xEAE5DD),('prismarine',0x5AA59A),('sponge',0xC3C14A),('slime',0x7CC86A),
        ('honey',0xF0A41E),('bookshelf',0xA88654),('chest',0xA4742E),('crafting',0xA88654),('furnace',0x707070),
        ('stone',0x7F7F7F),('cobble',0x7F7F7F),('andesite',0x8A8A8A),('ore',0x7F7F7F),('brick',0x965F4E),
    ]
    for w,c in rules:
        if w in n: return c
    return 0x8A8A8A

rows=[];seen=set();names={};player_types={};entity_kinds={};entity_names={};block_tables={}
for v,p in dp.items():
    if 'protocol' not in p: continue
    n=pv.get(v)
    if n is None or (n<764 and n!=340) or n>100000: continue
    names.setdefault(n,[]).append(v)
    if n in seen: continue
    seen.add(n)
    pr=json.load(open('data/'+p['protocol']+'/protocol.json'))
    ids=[]
    for c,st,d,cands in SPEC:
        if st not in pr:
            ids.append(-1)
            continue
        m=pr[st][d]['types']['packet'][1][0]['type'][1]['mappings']
        inv={nm:int(k,16) for k,nm in m.items()}
        v_id=next((inv[x] for x in cands if x in inv),-1)
        if n==340 and c=='P_C_SPAWN_ENTITY': v_id=-1  # 1.12.2: objects only; players use named_entity_spawn
        if n==340 and c in ('P_C_MAP_CHUNK','P_C_UNLOAD_CHUNK','P_C_BLOCK_CHANGE','P_C_MULTI_BLOCK','P_C_WINDOW_ITEMS','P_C_SET_SLOT',
                            'P_C_OPEN_WINDOW','P_C_CLOSE_WINDOW','P_S_USE_ITEM_ON','P_S_WINDOW_CLICK','P_S_CLOSE_WINDOW','P_S_HELD_SLOT'):
            v_id=-1  # 1.12.2: map, building and containers not supported
        ids.append(v_id)
    ents=json.load(open('data/'+p['entities']+'/entities.json'))
    ptype=next((e['id'] for e in ents if e['name']=='player'),-1)
    player_types[n]=ptype
    # one char per entity type id: p=player h=hostile a=animal/passive i=item/xp o=other
    if n==340: ents=[e for e in ents if e.get('type')=='mob' or e['name']=='player']
    mx=max(e['id'] for e in ents)
    kinds=['o']*(mx+1); nm=['']*(mx+1)
    for e in ents:
        name=e['name']; t=e.get('type',''); cat=(e.get('category') or '')
        if name=='player': k='p'
        elif name in ('item','experience_orb','xp_orb'): k='i'
        elif t=='hostile' or cat.startswith('Hostile'): k='h'
        elif t in ('animal','passive','ambient','water_creature','mob') or cat.startswith('Passive'): k='a'
        else: k='o'
        kinds[e['id']]=k; nm[e['id']]=name
    entity_kinds[n]=''.join(kinds); entity_names[n]=','.join(nm)
    # blocks: per block (in state order) -> delta of minStateId and colour index; packed as chars
    if n==340:
        block_tables[n]=''
    else:
        blocks=sorted(json.load(open('data/'+p['blocks']+'/blocks.json')), key=lambda b:b['minStateId'])
        enc=[]; prevmin=0
        for b in blocks:
            col=color_for(b['name'])
            if col not in COLORS: COLORS[col]=len(COLORS)
            solid = 1 if b.get('boundingBox')=='block' else 0
            if b['name'] in ('water','lava'): solid = 2   # fluid
            enc.append(chr(b['minStateId']-prevmin+32)); enc.append(chr(COLORS[col]+32)); enc.append(chr(solid+32)); prevmin=b['minStateId']
        block_tables[n]=''.join(enc)
    rows.append((n,ids))
rows.sort()
import gzip, os
asset_dir=os.path.join(os.path.dirname(os.path.abspath(sys.argv[1])),'..','..','..','assets','proto')
asset_dir=os.path.normpath(asset_dir); os.makedirs(asset_dir,exist_ok=True)
for v,p in dp.items():
    if 'protocol' not in p: continue
    n=pv.get(v)
    if n is None or n<764 or n>100000: continue
    pr=json.load(open('data/'+p['protocol']+'/protocol.json'))
    T=pr['types']; TC=pr['play']['toClient']['types']
    want={}
    def refs(t):
        if isinstance(t,str):
            if t in T and T[t]!='native' and t not in want:
                want[t]=T[t]; refs(T[t])
            elif t in TC and t not in want and not t.startswith('packet'):
                want[t]=TC[t]; refs(TC[t])
        elif isinstance(t,list):
            for x in t: refs(x)
        elif isinstance(t,dict):
            for x in t.values(): refs(x)
    pk={}
    for name in ('window_items','set_slot','open_window'):
        d=TC.get('packet_'+name)
        if d: pk[name]=d; refs(d)
    items=json.load(open('data/'+p['items']+'/items.json'))
    mx=max(i['id'] for i in items)
    inames=['']*(mx+1)
    for i in items: inames[i['id']]=i['displayName']
    blocks=sorted(json.load(open('data/'+p['blocks']+'/blocks.json')), key=lambda b:b['minStateId'])
    def shape_code(b):
        nm=b['name']; bb=b.get('boundingBox')
        if nm.endswith('_button'): return 15
        if nm=='lever': return 16
        if nm.endswith('_pressure_plate'): return 17
        if nm=='ladder': return 19
        if bb!='block':
            return 11 if nm=='snow' else 0
        if nm.endswith('_slab'): return 2
        if nm.endswith('_stairs'): return 3
        if nm.endswith('_trapdoor'): return 5
        if nm.endswith('_door'): return 4
        if nm.endswith('_fence_gate'): return 6
        if nm.endswith('_fence'): return 7
        if nm.endswith('_wall') and not any(w in nm for w in ('sign','banner','torch','head','skull','fan','hanging')): return 8
        if 'glass_pane' in nm or nm=='iron_bars': return 9
        if nm.endswith('carpet'): return 10
        if nm.endswith('_bed'): return 12
        if nm.endswith('chest'): return 13 # chest, trapped, ender, copper (all stages, waxed)
        if nm in ('farmland','dirt_path'): return 14
        if nm in ('soul_sand','mud'): return 18
        if nm=='ladder': return 19
        if nm=='honey_block': return 20
        return 1
    def props(b):
        out=[]
        for st in b.get('states',[]):
            vals=st.get('values')
            if vals is None:
                vals=['true','false'] if st.get('type')=='bool' else [str(i) for i in range(st['num_values'])]
            out.append([st['name'],vals])
        return out
    # exact vanilla collision boxes per block state (fallback: computed shapes in Shapes.java)
    coll=None; cshapes=None
    if p.get('blockCollisionShapes'):
        cd=json.load(open('data/'+p['blockCollisionShapes']+'/blockCollisionShapes.json'))
        cshapes={k:v for k,v in cd['shapes'].items()}
        coll=[]
        for b in blocks:
            v=cd['blocks'].get(b['name'])
            if isinstance(v,list) and len(set(v))==1: v=v[0]
            if isinstance(v,list) and len(v)!=b['maxStateId']-b['minStateId']+1: v=None
            coll.append(-1 if v is None else v)
        used=set()
        for v in coll:
            if isinstance(v,list): used.update(v)
            elif v>=0: used.add(v)
        cshapes={k:[[round(x,5) for x in bx] for bx in cshapes[str(k)]] for k in sorted(used)}
    out_obj={'protocol':n,'packets':pk,'types':want,'items':inames,
             'blockMin':[b['minStateId'] for b in blocks],'blockNames':[b['displayName'] for b in blocks],
             'shapes':[shape_code(b) for b in blocks],
             'blockIds':[b['name'] for b in blocks],
             'hardness':[(-1 if b.get('hardness') is None else b['hardness']) for b in blocks],
             'material':[b.get('material','default') for b in blocks],
             'harvest':[[int(k) for k in (b.get('harvestTools') or {}).keys()] for b in blocks],
             'props':[props(b) if shape_code(b)>1 else [] for b in blocks]}
    if coll is not None:
        out_obj['collision']=coll; out_obj['collisionShapes']={str(k):v for k,v in cshapes.items()}
    # plain .json: the Android build silently un-gzips *.gz assets and renames them (it compresses the APK anyway)
    with open(os.path.join(asset_dir,'%d.json'%n),'w') as f: json.dump(out_obj,f,separators=(',',':'))
out=['// GENERATED by tools/gen_proto.py from minecraft-data. Do not edit.','package app.afkbot;','','final class Proto {']
for i,(c,_,_,_) in enumerate(SPEC): out.append(f'    static final int {c} = {i};')
out.append('    static final int[] PROTOCOLS = {'+', '.join(str(n) for n,_ in rows)+'};')
import re as _re
for _n,_ in rows:
    assert all(_re.match(r'^\d+(\.\d+)+$', v) for v in names[_n]), 'version names got corrupted: %r' % names[_n][:3]
out.append('    static final String[] NAMES = {'+', '.join('"'+'/'.join(names[n])+'"' for n,_ in rows)+'};')
out.append('    /** Entity type id of "minecraft:player" in spawn_entity (-1: uses a separate spawn packet). */')
out.append('    static final int[] PLAYER_TYPE = {'+', '.join(str(player_types[n]) for n,_ in rows)+'};')
out.append('    /** Per version: one char per entity type id (p player, h hostile, a animal, i item, o other). */')
out.append('    static final String[] ENTITY_KINDS = {')
for n,_ in rows: out.append('        "'+entity_kinds[n]+'",')
out.append('    };')
out.append('    /** Per version: comma-separated entity names indexed by type id. */')
out.append('    static final String[] ENTITY_NAMES = {')
for n,_ in rows: out.append('        "'+entity_names[n]+'",')
out.append('    };')
def jstr(t):
    out=[]
    for ch in t:
        o=ord(ch)
        if ch=='"': out.append('\\"')
        elif ch=='\\': out.append('\\\\')
        elif 32<=o<127: out.append(ch)
        else: out.append('\\u%04x'%o)
    return '"'+''.join(out)+'"'
out.append('    /** Map colours (RGB) referenced by BLOCKS. */')
out.append('    static final int[] COLORS = {'+', '.join('0x%06X'%c for c,_ in sorted(COLORS.items(), key=lambda kv:kv[1]))+'};')
out.append('    /** Per version: triples of chars (minStateId delta + 32, colour index + 32, flags + 32: 1 solid, 2 fluid) per block. */')
out.append('    static final String[] BLOCKS = {')
for n,_ in rows: out.append('        '+jstr(block_tables[n])+',')
out.append('    };')
out.append('    static final int[][] IDS = {')
for n,ids in rows: out.append('        {'+', '.join(str(x) for x in ids)+'}, // '+str(n))
out.append('    };')
out.append('}')
open(sys.argv[1],'w').write('\n'.join(out)+'\n')
print([ (n,names[n]) for n,_ in rows])
