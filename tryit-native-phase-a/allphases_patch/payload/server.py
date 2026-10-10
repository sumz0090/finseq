#!/usr/bin/env python3
import argparse, base64, getpass, hashlib, hmac, html, io, json, mimetypes, os, secrets, shutil, socket, subprocess, sys, threading, time, webbrowser, smtplib, ssl, zipfile, ipaddress
from datetime import datetime
from http.server import ThreadingHTTPServer, SimpleHTTPRequestHandler
from pathlib import Path
from email.message import EmailMessage
from urllib.parse import urlparse, parse_qs
from mobile_orders_api import handle as handle_mobile_orders
from mobile_workspace_api import handle as handle_mobile_workspace

ROOT=Path(__file__).resolve().parent
DISCOVERY_PORT=8789
DISCOVERY_REQUEST=b'KIRAN_OMS_DISCOVER_V1'
CONFIG_FILE=ROOT/'server_config.json'
CONFIG_AUDIT_FILE=ROOT/'server_config_audit.jsonl'
DEFAULT_CONFIG={
    'data_path':'data',
    'web_auth_enabled':False,
    'admin_username':'admin',
    'admin_password_hash':'',
    'behind_proxy':False,
    'session_hours':8,
    'max_restore_upload_mb':64,
    'auto_backup_hours':4,
    'auto_backup_keep':42,
    'smtp_host':'',
    'smtp_port':587,
    'smtp_username':'',
    'smtp_password':'',
    'smtp_from':'',
    'smtp_starttls':True,
}

def load_config():
    cfg=dict(DEFAULT_CONFIG)
    if CONFIG_FILE.exists():
        try:
            raw=json.loads(CONFIG_FILE.read_text('utf-8'))
            if isinstance(raw,dict): cfg.update(raw)
        except Exception as e: print('WARNING: config read failed:',e)
    return cfg

def save_config():
    tmp=CONFIG_FILE.with_suffix('.tmp')
    tmp.write_text(json.dumps(CONFIG,ensure_ascii=False,indent=2),'utf-8')
    os.replace(tmp,CONFIG_FILE)

def resolve_data_dir(value):
    raw=os.path.expandvars(os.path.expanduser(str(value or 'data').strip()))
    path=Path(raw)
    if not path.is_absolute(): path=ROOT/path
    return path.resolve() if not str(path).startswith('\\') else path

CONFIG=load_config()
DATA_DIR=resolve_data_dir(CONFIG.get('data_path','data'))
BACKUP_DIR=DATA_DIR/'backups'
BACKUP_PACKAGE_DIR=BACKUP_DIR/'packages'
BACKUP_STAGING_DIR=BACKUP_DIR/'staging'
TEST_COMPANY_DIR=DATA_DIR/'test_companies'
STATE_FILE=DATA_DIR/'intranet_state.json'
PREVIOUS_FILE=DATA_DIR/'intranet_state.previous.json'
SECURITY_DIR=DATA_DIR/'security'
ADMIN_MASTER_FILE=SECURITY_DIR/'admin_master.json'
USER_MASTER_FILE=SECURITY_DIR/'user_master.json'
LOCK=threading.RLock()
AUTH_LOCK=threading.RLock()
SESSIONS={}
LOGIN_ATTEMPTS={}
OMS_SESSIONS={}
RECOVERY_OTPS={}

def set_data_paths(path):
    global DATA_DIR,BACKUP_DIR,BACKUP_PACKAGE_DIR,BACKUP_STAGING_DIR,TEST_COMPANY_DIR,STATE_FILE,PREVIOUS_FILE,SECURITY_DIR,ADMIN_MASTER_FILE,USER_MASTER_FILE
    DATA_DIR=Path(path)
    BACKUP_DIR=DATA_DIR/'backups'
    BACKUP_PACKAGE_DIR=BACKUP_DIR/'packages'
    BACKUP_STAGING_DIR=BACKUP_DIR/'staging'
    TEST_COMPANY_DIR=DATA_DIR/'test_companies'
    STATE_FILE=DATA_DIR/'intranet_state.json'
    PREVIOUS_FILE=DATA_DIR/'intranet_state.previous.json'
    SECURITY_DIR=DATA_DIR/'security'
    ADMIN_MASTER_FILE=SECURITY_DIR/'admin_master.json'
    USER_MASTER_FILE=SECURITY_DIR/'user_master.json'

def append_config_audit(action,detail,actor='local'):
    try:
        row={'at':now_iso() if 'now_iso' in globals() else datetime.now().astimezone().isoformat(timespec='seconds'),'action':action,'detail':detail,'actor':actor}
        with CONFIG_AUDIT_FILE.open('a',encoding='utf-8') as f: f.write(json.dumps(row,ensure_ascii=False)+'\n')
    except Exception as e: print('Config audit warning:',e)
SYNC_KEYS={
 'st_orders','st_boxes','st_soles','st_masters','st_facArtSole','st_facArtDml','st_facArtMaterial','st_tpl',
 'st_history','st_audit_log','st_oms_bypass_log','st_bulk_history','st_trash','st_purged_trash','st_printlog',
 'st_printbatchseq','st_po_log','st_purchase_log','st_archive','st_staff_activity','st_status_event_history',
 'st_wabatchseq','sf_orders','sf_sync_paused'
}
ARRAY_UNION_KEYS={
 'st_history','st_audit_log','st_oms_bypass_log','st_bulk_history','st_printlog','st_po_log','st_purchase_log',
 'st_staff_activity','st_status_event_history','sf_orders'
}

def now_iso(): return datetime.now().astimezone().isoformat(timespec='seconds')
def empty_state(): return {'version':0,'updated_at':None,'values':{},'key_versions':{},'key_clients':{},'reset_epoch':''}
def load_state():
    DATA_DIR.mkdir(parents=True,exist_ok=True); BACKUP_DIR.mkdir(parents=True,exist_ok=True)
    if not STATE_FILE.exists(): return empty_state()
    try:
        d=json.loads(STATE_FILE.read_text('utf-8'))
        if not isinstance(d,dict): raise ValueError('invalid state')
        x=empty_state(); x.update(d)
        x['values']=x.get('values') if isinstance(x.get('values'),dict) else {}
        x['key_versions']=x.get('key_versions') if isinstance(x.get('key_versions'),dict) else {}
        x['key_clients']=x.get('key_clients') if isinstance(x.get('key_clients'),dict) else {}
        return x
    except Exception as e:
        print('WARNING: state read failed:',e)
        return empty_state()
STATE=load_state()

def rotate_daily_backup():
    if not STATE_FILE.exists(): return
    try:
        BACKUP_DIR.mkdir(parents=True,exist_ok=True)
        tag=datetime.now().strftime('%Y-%m-%d')
        dst=BACKUP_DIR/f'intranet_state_{tag}.json'
        if not dst.exists(): shutil.copy2(STATE_FILE,dst)
        files=sorted(BACKUP_DIR.glob('intranet_state_*.json'),key=lambda p:p.stat().st_mtime,reverse=True)
        for p in files[30:]:
            try:p.unlink()
            except:pass
    except Exception as e: print('Backup rotation warning:',e)

def atomic_save_state():
    DATA_DIR.mkdir(parents=True,exist_ok=True); BACKUP_DIR.mkdir(parents=True,exist_ok=True)
    tmp=STATE_FILE.with_suffix('.tmp')
    tmp.write_text(json.dumps(STATE,ensure_ascii=False,separators=(',',':')),'utf-8')
    if STATE_FILE.exists():
        try: shutil.copy2(STATE_FILE,PREVIOUS_FILE)
        except: pass
    os.replace(tmp,STATE_FILE)
    rotate_daily_backup()
    try:
        if 'maybe_create_auto_backup_package' in globals(): maybe_create_auto_backup_package()
    except Exception as e: print('Auto backup package warning:',e)

def parse_json_value(raw,default=None):
    try:return json.loads(raw)
    except:return default

def dump(v): return json.dumps(v,ensure_ascii=False,separators=(',',':'))
def item_id(item):
    if not isinstance(item,dict): return None
    for k in ('id','_trashId','orderNo','batchNo','poId','purchaseId','eventId'):
        if item.get(k) not in (None,''): return f'{k}:{item.get(k)}'
    return None

def event_ts(item):
    if not isinstance(item,dict): return -1
    for k in ('updatedAt','lastEdited','createdAt','deletedAt','timestamp','timeMs','at'):
        v=item.get(k)
        if isinstance(v,(int,float)): return float(v)
        if isinstance(v,str) and v:
            try:return datetime.fromisoformat(v.replace('Z','+00:00')).timestamp()
            except:pass
    return -1

def union_array(old,new):
    if not isinstance(old,list):old=[]
    if not isinstance(new,list):return old
    out=[]; pos={}
    for src in (old,new):
        for item in src:
            iid=item_id(item) or ('json:'+dump(item))
            if iid not in pos: pos[iid]=len(out); out.append(item)
            elif event_ts(item)>=event_ts(out[pos[iid]]): out[pos[iid]]=item
    return out

def purged_ids(values):
    raw=values.get('st_purged_trash'); arr=parse_json_value(raw,[]) if isinstance(raw,str) else []
    return {str(x) for x in arr if x}
def trash_order_nos(values):
    raw=values.get('st_trash'); arr=parse_json_value(raw,[]) if isinstance(raw,str) else []
    out=set()
    for e in arr if isinstance(arr,list) else []:
        try:
            no=e.get('order',{}).get('orderNo')
            if no is not None:out.add(str(no))
        except:pass
    return out

def merge_value(key,old_raw,new_raw):
    if new_raw is None:return None
    if key=='st_purged_trash':
        a=parse_json_value(old_raw,[]) if isinstance(old_raw,str) else []
        b=parse_json_value(new_raw,[]) if isinstance(new_raw,str) else []
        seen=[]
        for x in list(a or [])+list(b or []):
            if x not in seen:seen.append(x)
        return dump(seen)
    if key=='st_trash':
        a=parse_json_value(old_raw,[]) if isinstance(old_raw,str) else []
        b=parse_json_value(new_raw,[]) if isinstance(new_raw,str) else []
        purged=purged_ids(STATE['values'])
        return dump([e for e in union_array(a,b) if not (isinstance(e,dict) and str(e.get('_trashId')) in purged)])
    if key=='st_orders':
        old=parse_json_value(old_raw,[]) if isinstance(old_raw,str) else []
        new=parse_json_value(new_raw,[]) if isinstance(new_raw,str) else []
        if not isinstance(new,list):return old_raw if old_raw is not None else new_raw
        by={}
        for rank,arr in ((0,old),(1,new)):
            if not isinstance(arr,list):continue
            for o in arr:
                if not isinstance(o,dict) or o.get('orderNo') is None:continue
                k=str(o.get('orderNo')); prev=by.get(k)
                if prev is None:by[k]=(o,rank)
                else:
                    p,pr=prev; t1,t2=event_ts(p),event_ts(o)
                    if t2>t1 or (t2==t1 and rank>=pr):by[k]=(o,rank)
        deleted=trash_order_nos(STATE['values'])
        result=[v[0] for k,v in by.items() if k not in deleted]
        result.sort(key=lambda o:(0,float(o.get('orderNo'))) if str(o.get('orderNo','')).replace('.','',1).isdigit() else (1,str(o.get('orderNo'))))
        return dump(result)
    if key in ('st_printbatchseq','st_wabatchseq'):
        try:
            a=parse_json_value(old_raw,0) if old_raw is not None else 0; b=parse_json_value(new_raw,0)
            return dump(max(int(a or 0),int(b or 0)))
        except:return new_raw
    if key in ARRAY_UNION_KEYS:
        a=parse_json_value(old_raw,[]) if old_raw is not None else []; b=parse_json_value(new_raw,[])
        return dump(union_array(a,b))
    return new_raw

def apply_change(key,value,client='unknown'):
    if key not in SYNC_KEYS: raise ValueError('key not allowed')
    merged=merge_value(key,STATE['values'].get(key),value)
    STATE['version']=int(STATE.get('version',0))+1; ver=STATE['version']
    if merged is None: STATE['values'].pop(key,None)
    else: STATE['values'][key]=merged
    STATE['key_versions'][key]=ver; STATE['key_clients'][key]=client; STATE['updated_at']=now_iso(); atomic_save_state()
    return ver,merged

def _pbkdf2_hash(password, iterations=240000):
    salt=secrets.token_bytes(16)
    digest=hashlib.pbkdf2_hmac('sha256',password.encode('utf-8'),salt,iterations)
    return 'pbkdf2_sha256$%d$%s$%s' % (iterations,base64.urlsafe_b64encode(salt).decode().rstrip('='),base64.urlsafe_b64encode(digest).decode().rstrip('='))

def _b64decode_nopad(value):
    return base64.urlsafe_b64decode(value + '='*((4-len(value)%4)%4))

def _verify_password(password, stored):
    try:
        scheme,it,salt,digest=stored.split('$',3)
        if scheme!='pbkdf2_sha256': return False
        got=hashlib.pbkdf2_hmac('sha256',password.encode('utf-8'),_b64decode_nopad(salt),int(it))
        return hmac.compare_digest(got,_b64decode_nopad(digest))
    except Exception:return False

def _load_admin_master():
    try:
        if not ADMIN_MASTER_FILE.exists(): return {'configured':False}
        d=json.loads(ADMIN_MASTER_FILE.read_text('utf-8'))
        if not isinstance(d,dict): return {'configured':False}
        return d
    except Exception as e:
        print('WARNING: admin master file read failed:',e)
        return {'configured':False}

def _admin_master_configured():
    d=_load_admin_master()
    return bool(d.get('configured') and d.get('password_hash'))

def _verify_admin_master(password):
    d=_load_admin_master()
    return bool(d.get('configured') and _verify_password(str(password or ''),str(d.get('password_hash') or '')))

def _write_admin_master(password):
    SECURITY_DIR.mkdir(parents=True,exist_ok=True)
    row={
        'version':1,
        'configured':True,
        'username':'admin',
        'password_hash':_pbkdf2_hash(password),
        'updated_at':now_iso(),
        'note':'Server-side KIRAN OMS admin master credential. Do not copy this file to client PCs.'
    }
    tmp=ADMIN_MASTER_FILE.with_suffix('.tmp')
    tmp.write_text(json.dumps(row,ensure_ascii=False,indent=2),'utf-8')
    os.replace(tmp,ADMIN_MASTER_FILE)
    return row


def _profile_defaults(role='staff'):
    role=str(role or 'staff').lower()
    return {
        'father_name':'','mobile':'','address':'','aadhaar':'','pan':'','joining_date':'','salary':'',
        'profile_visibility':{
            'full_name':True,'father_name':True,'email':True,'mobile':True,'address':True,
            'aadhaar':False,'pan':False,'joining_date':True,'salary':False,
        },
        'restrict_devices':False,'approved_devices':[],'pending_devices':[],
        'session_version':0,
    }

def _default_user_master():
    admin={'role':'admin','email':'','password_hash':'','full_name':'Administrator','designation':'Administrator','active':True,'permissions':{}}
    admin.update(_profile_defaults('admin'))
    staff={'role':'staff','email':'','password_hash':'','full_name':'Staff User','designation':'Staff','active':True,'permissions':{'orders_view':True,'production_view':True,'dispatch_view':True,'production_approve':True,'dispatch_approve':True}}
    staff.update(_profile_defaults('staff'))
    return {'version':3,'users':{'admin':admin,'staff':staff},'updated_at':now_iso()}

def _load_user_master():
    try:
        if not USER_MASTER_FILE.exists(): return _default_user_master()
        d=json.loads(USER_MASTER_FILE.read_text('utf-8'))
        if not isinstance(d,dict): return _default_user_master()
        base=_default_user_master()
        users=d.get('users') if isinstance(d.get('users'),dict) else {}
        # Preserve every configured staff username; keep admin/staff compatibility entries.
        for uname,cur in users.items():
            if not isinstance(cur,dict): continue
            key=_norm_username(uname) if '_norm_username' in globals() else str(uname).strip().lower()
            if not key: continue
            if key not in base['users']:
                base['users'][key]={'role':'staff','email':'','password_hash':'','full_name':key.title(),'designation':'Staff','active':True,'permissions':{}}
            base['users'][key].update(cur)
            role=str(base['users'][key].get('role') or ('admin' if key=='admin' else 'staff')).strip().lower()
            if key=='admin': role='admin'
            if role not in ('admin','staff','client'): role='staff'
            base['users'][key]['role']=role
            base['users'][key].setdefault('full_name','Administrator' if key=='admin' else key.title())
            base['users'][key].setdefault('designation','Administrator' if key=='admin' else ('Client' if role=='client' else 'Staff'))
            base['users'][key].setdefault('permissions',{})
            base['users'][key].setdefault('active',True)
            for dk,dv in _profile_defaults(role).items():
                if dk=='profile_visibility':
                    curv=base['users'][key].get(dk) if isinstance(base['users'][key].get(dk),dict) else {}
                    merged=dict(dv); merged.update(curv); base['users'][key][dk]=merged
                else:
                    base['users'][key].setdefault(dk,dv.copy() if isinstance(dv,list) else dv)
        base['version']=max(3,int(base.get('version') or 0))
        return base
    except Exception as e:
        print('WARNING: user master read failed:',e)
        return _default_user_master()

def _save_user_master(data):
    SECURITY_DIR.mkdir(parents=True,exist_ok=True)
    data['updated_at']=now_iso()
    tmp=USER_MASTER_FILE.with_suffix('.tmp')
    tmp.write_text(json.dumps(data,ensure_ascii=False,indent=2),'utf-8')
    os.replace(tmp,USER_MASTER_FILE)

def _norm_username(value):
    u=str(value or '').strip().lower()
    if u=='administrator': u='admin'
    if u=='user': u='staff'
    return u

def _verify_oms_user(username,password):
    u=_norm_username(username); pwd=str(password or '')
    if u=='admin': return _verify_admin_master(pwd), 'admin'
    row=_load_user_master().get('users',{}).get(u,{})
    if not row or not bool(row.get('active',True)): return False,''
    ph=str(row.get('password_hash') or '')
    return bool(ph and _verify_password(pwd,ph)), str(row.get('role') or 'staff')


def _mask_identifier(value,keep=4):
    v=str(value or '').strip()
    if not v:return ''
    if len(v)<=keep:return '*'*len(v)
    return '*'*(len(v)-keep)+v[-keep:]

def _public_user(uname,row,include_sensitive=False):
    row=row or {}; vis=row.get('profile_visibility') if isinstance(row.get('profile_visibility'),dict) else {}
    def allowed(field,default=True): return bool(include_sensitive or vis.get(field,default))
    salary=str(row.get('salary') or '')
    aadhaar=str(row.get('aadhaar') or '')
    pan=str(row.get('pan') or '')
    return {
        'username':uname,
        'full_name':str(row.get('full_name') or ('Administrator' if uname=='admin' else uname.title())),
        'father_name':str(row.get('father_name') or '') if allowed('father_name') else '',
        'designation':str(row.get('designation') or ('Administrator' if uname=='admin' else ('Client' if row.get('role')=='client' else 'Staff'))),
        'role':str(row.get('role') or ('admin' if uname=='admin' else 'staff')),
        'email':str(row.get('email') or '') if allowed('email') else '',
        'mobile':str(row.get('mobile') or '') if allowed('mobile') else '',
        'address':str(row.get('address') or '') if allowed('address') else '',
        'aadhaar':aadhaar if allowed('aadhaar',False) else _mask_identifier(aadhaar),
        'pan':pan if allowed('pan',False) else _mask_identifier(pan,4),
        'joining_date':str(row.get('joining_date') or '') if allowed('joining_date') else '',
        'salary':salary if allowed('salary',False) else ('••••••' if salary else ''),
        'profile_visibility':dict(vis),
        'active':bool(row.get('active',True)),
        'password_configured':bool(_admin_master_configured() if uname=='admin' else row.get('password_hash')),
        'permissions':row.get('permissions') if isinstance(row.get('permissions'),dict) else {},
        'last_login':str(row.get('last_login') or ''),
        'updated_at':str(row.get('updated_at') or ''),
        'allow_wan':bool(row.get('allow_wan',True)),
        'allowed_ips':row.get('allowed_ips') if isinstance(row.get('allowed_ips'),list) else [],
        'single_device':bool(row.get('single_device',False)),
        'must_change_password':bool(row.get('must_change_password',False)),
        'restrict_devices':bool(row.get('restrict_devices',False)),
        'approved_devices':row.get('approved_devices') if include_sensitive and isinstance(row.get('approved_devices'),list) else [],
        'pending_devices':row.get('pending_devices') if include_sensitive and isinstance(row.get('pending_devices'),list) else [],
    }



# ============================================================
# Phase 10.29 — server-side granular permission enforcement
# ============================================================
WRITE_PERMISSION_KEYS = {
    'orders_add','orders_edit','orders_delete',
    'production_add','production_edit','production_delete','production_approve',
    'dispatch_add','dispatch_edit','dispatch_delete','dispatch_approve',
    'stock_add','stock_edit','stock_delete',
    'accounts_add','accounts_edit','accounts_delete',
    'reports_add','reports_edit','reports_delete','reports_export',
    'masters_add','masters_edit','masters_delete',
    'settings_add','settings_edit','settings_delete'
}

def _oms_user_row(sess):
    if not sess: return {}
    uname=_norm_username(sess.get('user'))
    return _load_user_master().get('users',{}).get(uname,{}) or {}

def _has_perm(sess, perm):
    if not sess: return False
    if str(sess.get('role') or '').lower()=='admin': return True
    row=_oms_user_row(sess)
    perms=row.get('permissions') if isinstance(row.get('permissions'),dict) else {}
    return bool(perms.get(str(perm or '').lower(),False))

def _has_any_perm(sess, perms):
    return any(_has_perm(sess,p) for p in perms)

def _has_any_write_perm(sess):
    if not sess: return False
    if str(sess.get('role') or '').lower()=='admin': return True
    row=_oms_user_row(sess)
    perms=row.get('permissions') if isinstance(row.get('permissions'),dict) else {}
    return any(bool(perms.get(k)) for k in WRITE_PERMISSION_KEYS)

def _can_read_sync_key(sess,key):
    if str(sess.get('role') or '').lower()=='admin': return True
    key=str(key or '')
    if key=='st_orders': return _has_any_perm(sess,('orders_view','production_view','dispatch_view','accounts_view'))
    if key in {'st_soles','st_boxes','st_po_log','st_purchase_log'}: return _has_perm(sess,'stock_view')
    if key in {'st_masters','st_facArtSole','st_facArtDml','st_facArtMaterial','st_tpl'}: return _has_perm(sess,'masters_view')
    if key in {'sf_orders','sf_sync_paused'}: return _has_perm(sess,'production_view')
    if key in {'st_printlog','st_printbatchseq','st_history','st_audit_log','st_oms_bypass_log','st_bulk_history','st_archive','st_staff_activity','st_status_event_history','st_wabatchseq','st_trash','st_purged_trash'}:
        return _has_any_perm(sess,('orders_view','production_view','dispatch_view','stock_view','reports_view'))
    return False

def _safe_json(raw, default):
    if raw is None: return default
    if isinstance(raw,(dict,list)): return raw
    try: return json.loads(raw)
    except Exception: return default

def _order_identity(o, idx):
    if not isinstance(o,dict): return f'idx:{idx}'
    for k in ('orderNo','order_no','id'):
        if o.get(k) not in (None,''): return f'{k}:{o.get(k)}'
    return f'idx:{idx}'

def _order_write_perms(old_raw,new_raw):
    old_arr=_safe_json(old_raw,[]) if old_raw is not None else []
    new_arr=_safe_json(new_raw,[]) if new_raw is not None else []
    if not isinstance(old_arr,list): old_arr=[]
    if not isinstance(new_arr,list): new_arr=[]
    old={_order_identity(o,i):o for i,o in enumerate(old_arr)}
    new={_order_identity(o,i):o for i,o in enumerate(new_arr)}
    req=set()
    if set(new)-set(old): req.add('orders_add')
    if set(old)-set(new): req.add('orders_delete')
    lifecycle_fields={
        'status','prodDate','readyDate','dispatchDate','dispatchDateChangedAt','statusChangedAt',
        'lastEdited','updatedAt','workDate','workAt','staffWorked','staffWorkAt','staffWorkAction',
        'staffReadyWorked','staffDispatchWorked','printed','lastBatch','printCount','lastWaBatch',
        'waBatchDate','waSent','waSentAt'
    }
    for ident in (set(old)&set(new)):
        a,b=old[ident],new[ident]
        if a==b: continue
        if not isinstance(a,dict) or not isinstance(b,dict):
            req.add('orders_edit'); continue
        oldst=str(a.get('status') or 'Pending')
        newst=str(b.get('status') or 'Pending')
        if oldst!=newst:
            if newst=='Dispatched': req.add('dispatch_approve')
            elif newst=='Ready': req.add('production_approve')
            elif newst=='In Production': req.add('production_edit')
            else: req.add('orders_edit')
        changed={k for k in set(a)|set(b) if a.get(k)!=b.get(k)}
        meaningful=changed-lifecycle_fields
        if meaningful:
            req.add('orders_edit')
        elif oldst==newst and changed:
            if changed & {'dispatchDate','dispatchDateChangedAt'}: req.add('dispatch_edit')
            elif changed & {'prodDate','readyDate','printed','lastBatch','printCount','lastWaBatch','waBatchDate','waSent','waSentAt'}: req.add('production_edit')
            else: req.add('orders_edit')
    return req

def _node_count(obj):
    if isinstance(obj,dict): return 1+sum(_node_count(v) for v in obj.values())
    if isinstance(obj,list): return 1+sum(_node_count(v) for v in obj)
    return 1

def _generic_mutation_kind(old_raw,new_raw):
    old=_safe_json(old_raw,None)
    new=_safe_json(new_raw,None)
    if old is None and new is not None:return 'add'
    if new is None and old is not None:return 'delete'
    if old==new:return 'edit'
    oc,nc=_node_count(old),_node_count(new)
    if nc>oc:return 'add'
    if nc<oc:return 'delete'
    return 'edit'

def _required_write_perms(key, old_raw, new_raw):
    key=str(key or '')
    if key=='st_orders': return _order_write_perms(old_raw,new_raw)
    if key in {'st_soles','st_boxes','st_po_log'}:
        return {'stock_'+_generic_mutation_kind(old_raw,new_raw)}
    if key=='st_purchase_log': return {'stock_edit'}
    if key in {'st_masters','st_tpl'}:
        return {'masters_'+_generic_mutation_kind(old_raw,new_raw)}
    if key in {'st_facArtSole','st_facArtDml','st_facArtMaterial'}:
        return {'__orders_or_masters_write__'}
    if key in {'sf_orders','sf_sync_paused'}: return {'production_edit'}
    if key in {'st_trash','st_purged_trash'}: return {'orders_delete'}
    # Audit/history/print/support keys are side-effects of authorised business actions.
    # Non-admin users may write them only when they hold at least one operational write permission.
    if key in {'st_history','st_audit_log','st_oms_bypass_log','st_bulk_history','st_printlog','st_printbatchseq','st_archive','st_staff_activity','st_status_event_history','st_wabatchseq'}:
        return {'__any_write__'}
    return {'settings_edit'}

def _sync_values_equal(old_raw,new_raw):
    # Compare decoded JSON where possible so formatting-only differences do not
    # create writes, versions or permission errors.
    if old_raw == new_raw:
        return True
    try:
        return _safe_json(old_raw,object()) == _safe_json(new_raw,object())
    except Exception:
        return False

def _authorize_sync_write(sess,key,old_raw,new_raw):
    if _sync_values_equal(old_raw,new_raw):
        return True,[]
    req=_required_write_perms(key,old_raw,new_raw)
    missing=[]
    for perm in req:
        if perm=='__any_write__':
            if not _has_any_write_perm(sess): missing.append('operational write permission')
        elif perm=='__orders_or_masters_write__':
            if not _has_any_perm(sess,('orders_add','orders_edit','masters_add','masters_edit')): missing.append('orders/master write permission')
        elif not _has_perm(sess,perm):
            missing.append(perm)
    return (not missing),missing

def _filtered_state_for_session(sess):
    vals={}
    vers={}
    clients={}
    for k in SYNC_KEYS:
        if _can_read_sync_key(sess,k):
            # Explicit null also clears stale browser data when the server has no value yet.
            vals[k]=STATE['values'].get(k) if k in STATE['values'] else None
            vers[k]=STATE['key_versions'].get(k,0)
            clients[k]=STATE['key_clients'].get(k,'')
        else:
            # Explicit null clears stale local data from a previous more-privileged session.
            vals[k]=None
            vers[k]=STATE['version']
            clients[k]='permission-filter'
    return vals,vers,clients

def _recent_access_logs(limit=100):
    rows=[]
    try:
        if CONFIG_AUDIT_FILE.exists():
            lines=CONFIG_AUDIT_FILE.read_text('utf-8',errors='ignore').splitlines()[-max(1,min(500,int(limit or 100))):]
            for line in reversed(lines):
                try:
                    d=json.loads(line)
                    if isinstance(d,dict): rows.append(d)
                except Exception: pass
    except Exception: pass
    return rows

def _cleanup_oms_sessions():
    now=time.time()
    users=_load_user_master().get('users',{})
    with AUTH_LOCK:
        for token,row in list(OMS_SESSIONS.items()):
            u=str(row.get('user') or '')
            ur=users.get(u,{})
            invalid=(row.get('expires',0)<=now or str(row.get('role') or '')!=str(ur.get('role') or '') or not ur or not bool(ur.get('active',True)) or int(row.get('session_version',0) or 0)!=int(ur.get('session_version',0) or 0))
            if invalid: OMS_SESSIONS.pop(token,None)

def _revoke_user_sessions(username,exclude_token=None):
    username=_norm_username(username); removed=0
    with AUTH_LOCK:
        for tok,sess in list(OMS_SESSIONS.items()):
            if sess.get('user')==username and tok!=exclude_token:
                OMS_SESSIONS.pop(tok,None); removed+=1
    return removed

def _device_rows(row,key):
    val=row.get(key)
    return val if isinstance(val,list) else []

def _find_device(rows,device_id):
    device_id=str(device_id or '').strip()
    for d in rows:
        if isinstance(d,dict) and str(d.get('id') or '')==device_id:return d
    return None

def _masked_email(email):
    e=str(email or '').strip()
    if '@' not in e:return ''
    a,b=e.split('@',1)
    if len(a)<=2: a2=(a[:1] or '*')+'*'
    else:a2=a[:2]+'*'*(max(2,len(a)-2))
    return a2+'@'+b

def _send_recovery_email(to_email,otp,username):
    host=str(CONFIG.get('smtp_host') or '').strip(); port=int(CONFIG.get('smtp_port') or 587)
    user=str(CONFIG.get('smtp_username') or '').strip(); pwd=str(CONFIG.get('smtp_password') or '')
    from_email=str(CONFIG.get('smtp_from') or user or '').strip()
    if not host or not from_email:
        raise RuntimeError('Email recovery is not configured. Ask the administrator to configure SMTP settings in Settings → Email OTP Recovery.')
    msg=EmailMessage(); msg['Subject']='KIRAN OMS password reset verification code'; msg['From']=from_email; msg['To']=to_email
    msg.set_content('KIRAN OMS Password Recovery\n\nUser: %s\nVerification code: %s\n\nThis code is valid for 10 minutes. If you did not request a password reset, you can ignore this email.' % (username,otp))
    if port==465:
        with smtplib.SMTP_SSL(host,port,context=ssl.create_default_context(),timeout=20) as smtp:
            if user:smtp.login(user,pwd)
            smtp.send_message(msg)
    else:
        with smtplib.SMTP(host,port,timeout=20) as smtp:
            smtp.ehlo()
            if bool(CONFIG.get('smtp_starttls',True)):
                smtp.starttls(context=ssl.create_default_context()); smtp.ehlo()
            if user:smtp.login(user,pwd)
            smtp.send_message(msg)

def _request_recovery(username,ip):
    u=_norm_username(username)
    data=_load_user_master(); row=data.get('users',{}).get(u,{})
    if u!='admin' and (not row or not bool(row.get('active',True))): raise ValueError('No account was found for this username.')
    email=str(row.get('email') or '').strip()
    if not email:
        raise LookupError('No registered email address is available for this user. Please contact the administrator.')
    key=f'{u}:{ip}'; now=time.time(); old=RECOVERY_OTPS.get(key,{})
    if old and now-float(old.get('sent_at',0))<60:
        raise RuntimeError('A verification code was sent recently. Please wait 60 seconds before requesting another code.')
    otp=f'{secrets.randbelow(1000000):06d}'
    _send_recovery_email(email,otp,u)
    RECOVERY_OTPS[key]={'username':u,'otp_hash':hashlib.sha256(otp.encode()).hexdigest(),'expires':now+600,'tries':0,'sent_at':now}
    append_config_audit('password_recovery_otp_sent',{'username':u,'ip':ip},actor=u)
    return _masked_email(email)

def _verify_recovery(username,otp,new_password,ip):
    u=_norm_username(username); key=f'{u}:{ip}'; row=RECOVERY_OTPS.get(key)
    if not row or row.get('expires',0)<time.time():
        RECOVERY_OTPS.pop(key,None); raise ValueError('The verification code has expired. Request a new code.')
    if int(row.get('tries',0))>=5: raise ValueError('Too many incorrect verification attempts. Request a new code.')
    got=hashlib.sha256(str(otp or '').strip().encode()).hexdigest()
    if not hmac.compare_digest(got,str(row.get('otp_hash') or '')):
        row['tries']=int(row.get('tries',0))+1; raise ValueError('The verification code is incorrect.')
    pwd=str(new_password or '')
    if len(pwd)<8: raise ValueError('Password must contain at least 8 characters.')
    if u=='admin': _write_admin_master(pwd)
    else:
        d=_load_user_master(); row=d.get('users',{}).get(u)
        if not row or not bool(row.get('active',True)): raise ValueError('Unsupported user account.')
        row['password_hash']=_pbkdf2_hash(pwd); _save_user_master(d)
    RECOVERY_OTPS.pop(key,None)
    append_config_audit('password_recovered',{'username':u,'ip':ip},actor=u)

def configure_admin_master():
    print('\nKIRAN OMS — Server Master Admin Password Setup')
    print('Master file:',ADMIN_MASTER_FILE)
    current=_load_admin_master()
    if current.get('configured') and current.get('password_hash'):
        old=getpass.getpass('Current Admin master password: ')
        if not _verify_admin_master(old):
            print('ERROR: Current password is incorrect. Password not changed.')
            return 2
    while True:
        p1=getpass.getpass('New Admin master password (minimum 8 characters): ')
        p2=getpass.getpass('Confirm new password: ')
        if len(p1)<8:
            print('Password must be at least 8 characters.'); continue
        if p1!=p2:
            print('Passwords do not match.'); continue
        break
    _write_admin_master(p1)
    append_config_audit('admin_master_password_set',{'master_file':str(ADMIN_MASTER_FILE)},actor='local-console')
    print('Admin master password saved securely (hashed).')
    print('Remote/browser users cannot create or replace this master password.')
    return 0

def _is_local_ip(ip):
    try:
        return ipaddress.ip_address(str(ip).strip()).is_loopback
    except Exception:
        return str(ip).strip().lower()=='localhost'

def _request_ip(handler):
    # Trust forwarding headers only from a trusted local reverse proxy.
    # Without this check, a client connecting directly to the OMS port could
    # spoof X-Forwarded-For and bypass IP-based restrictions/rate limits.
    direct=(handler.client_address[0] if getattr(handler,'client_address',None) else '')
    trusted_proxy=False
    try:
        addr=ipaddress.ip_address(str(direct).strip())
        trusted_proxy=addr.is_loopback or str(direct).strip() in set(CONFIG.get('trusted_proxy_ips') or [])
    except Exception:
        trusted_proxy=False
    if CONFIG.get('behind_proxy') and trusted_proxy:
        xff=str(handler.headers.get('X-Forwarded-For') or '').strip()
        if xff:
            cand=xff.split(',')[0].strip()
            try:
                ipaddress.ip_address(cand)
                return cand
            except Exception:
                pass
        xrip=str(handler.headers.get('X-Real-IP') or '').strip()
        if xrip:
            try:
                ipaddress.ip_address(xrip)
                return xrip
            except Exception:
                pass
    return direct


def _cleanup_sessions():
    now=time.time()
    with AUTH_LOCK:
        for token,row in list(SESSIONS.items()):
            if row.get('expires',0)<=now: SESSIONS.pop(token,None)

def _validate_data_path(raw):
    path=resolve_data_dir(raw)
    path.mkdir(parents=True,exist_ok=True)
    probe=path/f'.kiran_write_test_{os.getpid()}_{int(time.time()*1000)}.tmp'
    probe.write_text('ok','utf-8'); probe.unlink(missing_ok=True)
    return path

def _switch_data_path(raw,mode='copy_current',force=False):
    global STATE
    new_dir=_validate_data_path(raw)
    old_dir=DATA_DIR
    if new_dir==old_dir:
        CONFIG['data_path']=str(new_dir); save_config(); return {'changed':False,'data_path':str(new_dir)}
    target_state=new_dir/'intranet_state.json'
    if mode=='use_existing':
        if not target_state.exists(): raise ValueError('Selected path does not contain intranet_state.json')
        set_data_paths(new_dir)
        STATE=load_state()
    else:
        atomic_save_state()
        if target_state.exists() and not force:
            raise FileExistsError('Target already contains KIRAN OMS data. Confirm overwrite or choose Use Existing Data.')
        if target_state.exists() and force:
            safe=new_dir/'backups'/f'pre_switch_target_{datetime.now().strftime("%Y%m%d_%H%M%S")}.json'
            safe.parent.mkdir(parents=True,exist_ok=True); shutil.copy2(target_state,safe)
        new_dir.mkdir(parents=True,exist_ok=True)
        if old_dir.exists(): shutil.copytree(old_dir,new_dir,dirs_exist_ok=True)
        set_data_paths(new_dir)
        atomic_save_state()
    CONFIG['data_path']=str(new_dir); save_config()
    return {'changed':True,'data_path':str(new_dir),'state_file':str(STATE_FILE),'backup_dir':str(BACKUP_DIR),'mode':mode}


BACKUP_FORMAT='KIRAN_OMS_BACKUP'
BACKUP_FORMAT_VERSION=1
APP_BACKUP_VERSION='10.15'

def _json_from_state_value(state,key,default):
    raw=(state or {}).get('values',{}).get(key)
    if raw is None:return default
    if isinstance(raw,(dict,list)):return raw
    try:return json.loads(raw)
    except:return default

def _state_preview(state,manifest=None):
    orders=_json_from_state_value(state,'st_orders',[])
    boxes=_json_from_state_value(state,'st_boxes',{})
    soles=_json_from_state_value(state,'st_soles',{})
    masters=_json_from_state_value(state,'st_masters',{})
    if not isinstance(orders,list):orders=[]
    if not isinstance(boxes,(dict,list)):boxes={}
    if not isinstance(soles,(dict,list)):soles={}
    if not isinstance(masters,dict):masters={}
    active=[o for o in orders if isinstance(o,dict) and str(o.get('status') or '').lower() not in ('cancelled','delivered')]
    return {
        'format':(manifest or {}).get('format',BACKUP_FORMAT),
        'format_version':(manifest or {}).get('format_version',BACKUP_FORMAT_VERSION),
        'created_at':(manifest or {}).get('created_at') or state.get('updated_at'),
        'state_version':int(state.get('version',0) or 0),
        'orders':len(orders),'active_orders':len(active),
        'boxes':len(boxes),'soles':len(soles),
        'parties':len(masters.get('party',[]) if isinstance(masters.get('party',[]),list) else []),
        'factories':len(masters.get('factory',[]) if isinstance(masters.get('factory',[]),list) else []),
        'users_included':bool((manifest or {}).get('users_included',False)),
        'source_server':(manifest or {}).get('source_server') or socket.gethostname(),
        'integrity':'Valid',
    }

def _validate_state_dict(d):
    if not isinstance(d,dict) or not isinstance(d.get('values'),dict):
        raise ValueError('Unsupported backup format: KIRAN OMS state data is required.')
    x=empty_state();x.update(d)
    if not isinstance(x.get('values'),dict):raise ValueError('Invalid values section.')
    if not isinstance(x.get('key_versions'),dict):x['key_versions']={}
    if not isinstance(x.get('key_clients'),dict):x['key_clients']={}
    unknown=[k for k in x['values'].keys() if k not in SYNC_KEYS]
    if unknown:raise ValueError('Backup contains unsupported data keys: '+', '.join(unknown[:8]))
    try:x['version']=int(x.get('version',0) or 0)
    except:x['version']=0
    x['updated_at']=x.get('updated_at') or now_iso()
    return x

def _backup_package_bytes(state=None,include_users=True,kind='manual'):
    state=state or STATE
    state_bytes=json.dumps(state,ensure_ascii=False,separators=(',',':')).encode('utf-8')
    manifest={
        'format':BACKUP_FORMAT,'format_version':BACKUP_FORMAT_VERSION,'app_version':APP_BACKUP_VERSION,
        'created_at':now_iso(),'kind':kind,'source_server':socket.gethostname(),
        'state_version':int(state.get('version',0) or 0),'state_sha256':hashlib.sha256(state_bytes).hexdigest(),
        'users_included':bool(include_users),
    }
    manifest['preview']=_state_preview(state,manifest)
    bio=io.BytesIO()
    with zipfile.ZipFile(bio,'w',zipfile.ZIP_DEFLATED,compresslevel=6) as z:
        z.writestr('manifest.json',json.dumps(manifest,ensure_ascii=False,indent=2).encode('utf-8'))
        z.writestr('data/intranet_state.json',state_bytes)
        if include_users:
            users=_load_user_master()
            z.writestr('security/user_master.json',json.dumps(users,ensure_ascii=False,indent=2).encode('utf-8'))
        z.writestr('README.txt',('KIRAN OMS backup package. Restore only through Settings > Data & Backup > Server Backup Center.\n'
                                 'Admin master password and SMTP secrets are intentionally NOT included.\n').encode('utf-8'))
    return bio.getvalue(),manifest

def _parse_backup_bytes(raw,filename='backup.omsbackup'):
    if raw[:2]==b'PK':
        try:
            with zipfile.ZipFile(io.BytesIO(raw),'r') as z:
                names=set(z.namelist())
                if 'manifest.json' not in names or 'data/intranet_state.json' not in names:
                    raise ValueError('Invalid .omsbackup package: required files are missing.')
                manifest=json.loads(z.read('manifest.json').decode('utf-8'))
                if manifest.get('format')!=BACKUP_FORMAT:
                    raise ValueError('This backup was not created by KIRAN OMS.')
                state_bytes=z.read('data/intranet_state.json')
                expected=str(manifest.get('state_sha256') or '')
                if expected and not hmac.compare_digest(hashlib.sha256(state_bytes).hexdigest(),expected):
                    raise ValueError('Backup integrity check failed. The package may be damaged or modified.')
                state=_validate_state_dict(json.loads(state_bytes.decode('utf-8-sig')))
                users=None
                if 'security/user_master.json' in names:
                    users=json.loads(z.read('security/user_master.json').decode('utf-8-sig'))
                    if not isinstance(users,dict) or not isinstance(users.get('users'),dict):
                        raise ValueError('Backup user master is invalid.')
                return state,manifest,users
        except zipfile.BadZipFile as e:raise ValueError('The .omsbackup package is corrupt or incomplete.') from e
    # Legacy JSON server-state backup support
    try:d=json.loads(raw.decode('utf-8-sig'))
    except Exception as e:raise ValueError('Uploaded file is not a valid KIRAN OMS backup.') from e
    state=_validate_state_dict(d)
    manifest={'format':'KIRAN_OMS_LEGACY_JSON','format_version':0,'created_at':state.get('updated_at'),'kind':'legacy-json','source_server':'Legacy backup','users_included':False}
    return state,manifest,None

def _create_backup_package(kind='manual',include_users=True,prefix=None):
    with LOCK:
        atomic_save_state()
        raw,manifest=_backup_package_bytes(STATE,include_users=include_users,kind=kind)
        BACKUP_PACKAGE_DIR.mkdir(parents=True,exist_ok=True)
        tag=datetime.now().strftime('%Y%m%d_%H%M%S')
        safe_prefix=(prefix or kind or 'backup').replace(' ','_')[:32]
        dst=BACKUP_PACKAGE_DIR/f'{safe_prefix}_{tag}.omsbackup'
        dst.write_bytes(raw)
    return dst,manifest

def maybe_create_auto_backup_package(force=False):
    hours=max(1,min(168,int(CONFIG.get('auto_backup_hours',4) or 4)))
    keep=max(3,min(365,int(CONFIG.get('auto_backup_keep',42) or 42)))
    BACKUP_PACKAGE_DIR.mkdir(parents=True,exist_ok=True)
    autos=sorted(BACKUP_PACKAGE_DIR.glob('auto_*.omsbackup'),key=lambda x:x.stat().st_mtime,reverse=True)
    if not force and autos and time.time()-autos[0].stat().st_mtime < hours*3600:return autos[0]
    raw,manifest=_backup_package_bytes(STATE,include_users=True,kind='automatic')
    tag=datetime.now().strftime('%Y%m%d_%H%M%S');dst=BACKUP_PACKAGE_DIR/f'auto_{tag}.omsbackup';dst.write_bytes(raw)
    autos=sorted(BACKUP_PACKAGE_DIR.glob('auto_*.omsbackup'),key=lambda x:x.stat().st_mtime,reverse=True)
    for old in autos[keep:]:
        try:old.unlink()
        except:pass
    append_config_audit('automatic_server_backup_created',{'name':dst.name,'bytes':dst.stat().st_size},actor='server')
    return dst

def _list_backup_packages(limit=40):
    BACKUP_PACKAGE_DIR.mkdir(parents=True,exist_ok=True)
    rows=[]
    for p in sorted(BACKUP_PACKAGE_DIR.glob('*.omsbackup'),key=lambda x:x.stat().st_mtime,reverse=True)[:max(1,min(200,int(limit or 40)))]:
        try:
            raw=p.read_bytes();state,manifest,_=_parse_backup_bytes(raw,p.name);prev=_state_preview(state,manifest)
            rows.append({'name':p.name,'bytes':p.stat().st_size,'modified_at':datetime.fromtimestamp(p.stat().st_mtime).astimezone().isoformat(timespec='seconds'),'kind':manifest.get('kind','backup'),'preview':prev})
        except Exception as e:
            rows.append({'name':p.name,'bytes':p.stat().st_size,'modified_at':datetime.fromtimestamp(p.stat().st_mtime).astimezone().isoformat(timespec='seconds'),'kind':'invalid','error':str(e)})
    return rows

def _safe_backup_file(name):
    safe=Path(str(name or '')).name
    if not safe or safe!=str(name or '') or not safe.lower().endswith('.omsbackup'):raise ValueError('Invalid backup file name.')
    p=BACKUP_PACKAGE_DIR/safe
    if not p.exists() or not p.is_file():raise FileNotFoundError('Backup file was not found.')
    return p

def _stage_backup_bytes(raw,filename):
    state,manifest,users=_parse_backup_bytes(raw,filename)
    BACKUP_STAGING_DIR.mkdir(parents=True,exist_ok=True)
    token=secrets.token_urlsafe(18).replace('-','').replace('_','')[:24]
    ext='.omsbackup' if raw[:2]==b'PK' else '.json'
    p=BACKUP_STAGING_DIR/f'{token}{ext}'
    p.write_bytes(raw)
    meta={'token':token,'filename':Path(filename).name,'staged_at':now_iso(),'preview':_state_preview(state,manifest),'has_users':bool(users),'bytes':len(raw)}
    (BACKUP_STAGING_DIR/f'{token}.meta.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2),'utf-8')
    return meta

def _load_staged_backup(token):
    token=''.join(c for c in str(token or '') if c.isalnum())[:32]
    if not token:raise ValueError('Invalid staged backup token.')
    candidates=[BACKUP_STAGING_DIR/f'{token}.omsbackup',BACKUP_STAGING_DIR/f'{token}.json']
    p=next((x for x in candidates if x.exists()),None)
    if not p:raise FileNotFoundError('The staged backup is no longer available. Upload it again.')
    return p,*_parse_backup_bytes(p.read_bytes(),p.name)

def _discard_staged(token):
    token=''.join(c for c in str(token or '') if c.isalnum())[:32]
    for p in (BACKUP_STAGING_DIR/f'{token}.omsbackup',BACKUP_STAGING_DIR/f'{token}.json',BACKUP_STAGING_DIR/f'{token}.meta.json'):
        try:p.unlink(missing_ok=True)
        except:pass

def _restore_state_safely(incoming,users=None,restore_users=False,actor='admin',source='uploaded backup'):
    global STATE
    with LOCK:
        old_version=int(STATE.get('version',0) or 0)
        safety,safety_manifest=_create_backup_package(kind='pre-restore-safety',include_users=True,prefix='pre_restore')
        new_state=_validate_state_dict(incoming)
        new_version=max(old_version+1,int(new_state.get('version',0) or 0)+1)
        new_state['version']=new_version;new_state['updated_at']=now_iso();new_state['reset_epoch']=secrets.token_hex(12)
        # Force all restored keys to appear newer than every connected client's pre-restore version.
        new_state['key_versions']={k:new_version for k in new_state.get('values',{})}
        new_state['key_clients']={k:'server-restore' for k in new_state.get('values',{})}
        STATE=new_state
        atomic_save_state()
        if restore_users and users:
            # Admin master password remains server-owned and is never replaced by a backup.
            _save_user_master(users)
    detail={'source':source,'safety_backup':safety.name,'restored_version':STATE['version'],'restore_users':bool(restore_users),'preview':_state_preview(STATE)}
    append_config_audit('server_backup_restore',detail,actor=actor)
    return detail

def _create_test_copy_from_staged(token,actor='admin'):
    p,state,manifest,users=_load_staged_backup(token)
    TEST_COMPANY_DIR.mkdir(parents=True,exist_ok=True)
    tag=datetime.now().strftime('%Y%m%d_%H%M%S')
    test_dir=TEST_COMPANY_DIR/f'TEST_{tag}_{token[:6]}'
    (test_dir/'security').mkdir(parents=True,exist_ok=True)
    (test_dir/'intranet_state.json').write_text(json.dumps(state,ensure_ascii=False,separators=(',',':')),'utf-8')
    if users:(test_dir/'security'/'user_master.json').write_text(json.dumps(users,ensure_ascii=False,indent=2),'utf-8')
    meta={'created_at':now_iso(),'source_file':p.name,'preview':_state_preview(state,manifest),'note':'Isolated test copy. It does not replace live data.'}
    (test_dir/'test_copy.json').write_text(json.dumps(meta,ensure_ascii=False,indent=2),'utf-8')
    append_config_audit('server_backup_test_copy_created',{'path':str(test_dir),'preview':meta['preview']},actor=actor)
    return test_dir,meta

class Handler(SimpleHTTPRequestHandler):
    server_version='KIRAN-OMS-Web/4.3-P10.36-LinkedDashboardDataControl'
    def log_message(self,fmt,*args): print('[KIRAN]',_request_ip(self),'-',fmt%args)
    def send_json(self,obj,status=200,extra_headers=None):
        b=json.dumps(obj,ensure_ascii=False).encode('utf-8'); self.send_response(status)
        self.send_header('Content-Type','application/json; charset=utf-8'); self.send_header('Content-Length',str(len(b)))
        self.send_header('Cache-Control','no-store'); self.send_header('X-Content-Type-Options','nosniff')
        if extra_headers:
            for k,v in extra_headers.items(): self.send_header(k,v)
        self.end_headers(); self.wfile.write(b)
    def end_headers(self):
        self.send_header('X-Frame-Options','SAMEORIGIN')
        self.send_header('Referrer-Policy','no-referrer')
        self.send_header('Permissions-Policy','camera=(), microphone=(), geolocation=()')
        if self.path.endswith(('.html','.js','.css')) or self.path=='/':
            self.send_header('Cache-Control','no-store, no-cache, must-revalidate, max-age=0')
            self.send_header('Pragma','no-cache')
            self.send_header('Expires','0')
        super().end_headers()
    def _read_json(self):
        try:
            n=int(self.headers.get('Content-Length','0') or 0)
            if n<0 or n>8*1024*1024: raise ValueError('request too large')
            return json.loads(self.rfile.read(n).decode('utf-8') or '{}')
        except Exception as e: raise ValueError('invalid json') from e
    def _read_raw_upload(self):
        max_mb=max(1,min(256,int(CONFIG.get('max_restore_upload_mb',64) or 64)))
        max_bytes=max_mb*1024*1024
        try:n=int(self.headers.get('Content-Length','0') or 0)
        except: raise ValueError('invalid content length')
        if n<=0: raise ValueError('empty upload')
        if n>max_bytes: raise ValueError(f'upload too large; maximum {max_mb} MB')
        raw=self.rfile.read(n)
        if len(raw)!=n: raise ValueError('incomplete upload')
        return raw
    def _validated_state_from_bytes(self,raw):
        state,manifest,users=_parse_backup_bytes(raw,'uploaded_backup')
        return state
    def _cookie_map(self):
        out={}
        for part in (self.headers.get('Cookie') or '').split(';'):
            if '=' in part:
                k,v=part.strip().split('=',1); out[k]=v
        return out
    def _session(self):
        if not CONFIG.get('web_auth_enabled'): return {'user':'local','csrf':''}
        _cleanup_sessions(); token=self._cookie_map().get('kiran_session','')
        with AUTH_LOCK:return SESSIONS.get(token)
    def _oms_session(self):
        _cleanup_oms_sessions(); token=self._cookie_map().get('kiran_oms_user','')
        with AUTH_LOCK:
            sess=OMS_SESSIONS.get(token)
            if sess:sess['last_seen']=time.time()
            return sess
    def _require_oms_admin(self):
        sess=self._oms_session()
        if sess and sess.get('role')=='admin': return sess
        self.send_json({'ok':False,'error':'Administrator authentication is required.'},401); return None
    def _require_oms_session(self):
        sess=self._oms_session()
        if sess: return sess
        self.send_json({'ok':False,'error':'Sign in is required.'},401); return None
    def _require_auth(self):
        if not CONFIG.get('web_auth_enabled'): return True
        if self._session(): return True
        self.send_json({'ok':False,'error':'authentication required'},401); return False
    def _require_csrf(self):
        if not CONFIG.get('web_auth_enabled'): return True
        sess=self._session()
        if not sess:
            self.send_json({'ok':False,'error':'authentication required'},401);return False
        if not hmac.compare_digest(str(sess.get('csrf','')),str(self.headers.get('X-Kiran-CSRF',''))):
            self.send_json({'ok':False,'error':'csrf validation failed'},403);return False
        return True
    def _can_manage_server(self):
        if _is_local_ip(_request_ip(self)): return True
        return bool(CONFIG.get('web_auth_enabled') and self._session())
    def _secure_cookie(self):
        return bool(self.headers.get('X-Forwarded-Proto','').lower()=='https' or self.headers.get('Forwarded','').lower().find('proto=https')>=0)
    def _redirect(self, location, status=302):
        self.send_response(status)
        self.send_header('Location', location)
        self.send_header('Cache-Control','no-store, no-cache, must-revalidate, max-age=0')
        self.send_header('Pragma','no-cache')
        self.send_header('Expires','0')
        self.end_headers()
    def _serve_html(self, filename):
        p=(ROOT/filename).resolve()
        if p.parent!=ROOT or not p.exists():
            self.send_error(404); return
        raw=p.read_bytes()
        self.send_response(200)
        self.send_header('Content-Type','text/html; charset=utf-8')
        self.send_header('Content-Length',str(len(raw)))
        self.send_header('Cache-Control','no-store, no-cache, must-revalidate, max-age=0')
        self.send_header('Pragma','no-cache')
        self.send_header('Expires','0')
        self.end_headers()
        try:self.wfile.write(raw)
        except (ConnectionAbortedError, BrokenPipeError, ConnectionResetError):pass
    def do_GET(self):
        u=urlparse(self.path)
        if handle_mobile_workspace(self, globals(), 'GET', u.path): return
        if handle_mobile_orders(self, globals(), 'GET', u.path): return
        # Phase 10.20: server decides Login vs Dashboard before any app HTML is sent.
        # There is no visible auth splash and unauthenticated users never receive dashboard HTML.
        if u.path in ('/','/index.html'):
            sess=self._oms_session()
            if sess: self._redirect('/dashboard')
            else: self._serve_html('login.html')
            return
        if u.path in ('/login','/login.html'):
            sess=self._oms_session()
            if sess: self._redirect('/dashboard')
            else: self._serve_html('login.html')
            return
        if u.path in ('/dashboard','/dashboard/'):
            sess=self._oms_session()
            if not sess:
                self._redirect('/login')
            else:
                p=(ROOT/'index.html').resolve()
                raw=p.read_text(encoding='utf-8')
                # Phase 10.22: seed the server-authenticated role before legacy UI scripts run.
                # This prevents the obsolete in-dashboard login overlay from ever appearing.
                role=json.dumps(str(sess.get('role') or 'staff'))
                uname=str(sess.get('user') or '')
                user=json.dumps(uname)
                urow=_load_user_master().get('users',{}).get(uname,{})
                full_name=str(urow.get('full_name') or ('Administrator' if uname=='admin' else uname.title() or 'User'))
                designation=str(urow.get('designation') or ('Administrator' if uname=='admin' else ('Client' if urow.get('role')=='client' else 'Staff')))
                display=full_name + ((' · '+designation) if designation else '')
                raw=raw.replace('__KIRAN_USER_DISPLAY__', html.escape(display, quote=True))
                raw=raw.replace('__KIRAN_USER_NAME__', html.escape(full_name, quote=True))
                profile=json.dumps(_public_user(uname,urow),ensure_ascii=False).replace('</','<' + '\\/')
                boot=f"<script>window.KIRAN_BOOT_PROFILE={profile};try{{sessionStorage.setItem('st_role',{role});sessionStorage.setItem('kiran_server_user',{user});}}catch(e){{}}</script>"
                raw=raw.replace('<head>', '<head>'+boot, 1)
                data=raw.encode('utf-8')
                self.send_response(200)
                self.send_header('Content-Type','text/html; charset=utf-8')
                self.send_header('Content-Length',str(len(data)))
                self.send_header('Cache-Control','no-store, no-cache, must-revalidate, max-age=0')
                self.send_header('Pragma','no-cache')
                self.send_header('Expires','0')
                self.end_headers()
                try:self.wfile.write(data)
                except (ConnectionAbortedError, BrokenPipeError, ConnectionResetError):pass
            return
        if u.path=='/api/health':
            with LOCK:self.send_json({'ok':True,'version':STATE['version'],'updated_at':STATE['updated_at'],'reset_epoch':STATE.get('reset_epoch',''),'server':socket.gethostname()}); return
        if u.path=='/api/info':
            self.send_json({'name':'KIRAN OMS Premium UI','version':'4.3 Phase 10.36 — Linked Dashboard & Safe Data Control','server':socket.gethostname(),'lan_only':False,'domain_ready':True,'auth_enabled':True,'legacy_domain_auth_enabled':bool(CONFIG.get('web_auth_enabled')),'mobile_discovery_port':DISCOVERY_PORT}); return
        if u.path=='/api/auth/status':
            sess=self._session(); self.send_json({'ok':True,'auth_enabled':True,'legacy_domain_auth_enabled':bool(CONFIG.get('web_auth_enabled')),'authenticated':bool(sess),'user':sess.get('user') if sess else None,'csrf':sess.get('csrf') if sess else None}); return
        if u.path=='/api/admin/master/status':
            self.send_json({'ok':True,'configured':_admin_master_configured(),'username':'admin'}); return
        if u.path=='/api/admin/users-list':
            if not self._require_oms_admin(): return
            users=_load_user_master().get('users',{})
            clean=[_public_user(uname,row,include_sensitive=True) for uname,row in sorted(users.items())]
            self.send_json({'ok':True,'users':clean});return
        if u.path=='/api/admin/access-logs':
            if not self._require_oms_admin(): return
            try:limit=int(parse_qs(u.query).get('limit',['100'])[0])
            except Exception:limit=100
            self.send_json({'ok':True,'logs':_recent_access_logs(limit)});return
        if u.path=='/api/admin/sessions':
            admin=self._require_oms_admin()
            if not admin:return
            _cleanup_oms_sessions(); users=_load_user_master().get('users',{})
            rows=[]
            with AUTH_LOCK:
                for tok,sess in OMS_SESSIONS.items():
                    uname=str(sess.get('user') or ''); ur=users.get(uname,{})
                    rows.append({'session_id':str(sess.get('session_id') or ''),'username':uname,'full_name':str(ur.get('full_name') or uname),'designation':str(ur.get('designation') or ''),'role':str(sess.get('role') or ur.get('role') or ''),'device_id':str(sess.get('device_id') or ''),'device_name':str(sess.get('device_name') or ''),'ip':str(sess.get('ip') or ''),'login_at':str(sess.get('login_at') or ''),'last_seen':float(sess.get('last_seen') or 0),'expires':float(sess.get('expires') or 0),'current':tok==self._cookie_map().get('kiran_oms_user','')})
            self.send_json({'ok':True,'sessions':rows});return
        if u.path=='/api/oms/session':
            sess=self._oms_session()
            if not sess:
                self.send_json({'ok':True,'authenticated':False,'user':None});return
            uname=str(sess.get('user') or '')
            row=_load_user_master().get('users',{}).get(uname,{})
            if uname=='admin' and not row: row={'role':'admin','full_name':'Administrator','designation':'Administrator','email':'','active':True}
            self.send_json({'ok':True,'authenticated':True,'user':_public_user(uname,row,include_sensitive=(str(sess.get('role') or '')=='admin'))});return
        if u.path=='/api/admin/recovery-config':
            if not self._require_oms_admin(): return
            users=_load_user_master().get('users',{})
            self.send_json({'ok':True,'users':{'admin':{'email':users.get('admin',{}).get('email','')},'staff':{'email':users.get('staff',{}).get('email','')}},'smtp':{'host':CONFIG.get('smtp_host',''),'port':CONFIG.get('smtp_port',587),'username':CONFIG.get('smtp_username',''),'from_email':CONFIG.get('smtp_from',''),'starttls':bool(CONFIG.get('smtp_starttls',True))}});return
        if u.path=='/api/admin/backup-center/status':
            sess=self._require_oms_admin()
            if not sess:return
            try:
                latest=_list_backup_packages(40)
                self.send_json({'ok':True,'live':_state_preview(STATE),'latest':latest[0] if latest else None,'history':latest,'data_path':str(DATA_DIR),'state_file':str(STATE_FILE),'backup_dir':str(BACKUP_PACKAGE_DIR),'max_upload_mb':int(CONFIG.get('max_restore_upload_mb',64) or 64),'test_copy_dir':str(TEST_COMPANY_DIR)})
            except Exception as e:self.send_json({'ok':False,'error':str(e)},500)
            return
        if u.path=='/api/admin/backup-center/download':
            sess=self._require_oms_admin()
            if not sess:return
            try:
                name=parse_qs(u.query).get('name',[''])[0];p=_safe_backup_file(name);raw=p.read_bytes()
                self.send_response(200);self.send_header('Content-Type','application/octet-stream');self.send_header('Content-Disposition',f'attachment; filename="{p.name}"');self.send_header('Content-Length',str(len(raw)));self.send_header('Cache-Control','no-store');self.end_headers();self.wfile.write(raw)
            except FileNotFoundError as e:self.send_json({'ok':False,'error':str(e)},404)
            except Exception as e:self.send_json({'ok':False,'error':str(e)},400)
            return
        if u.path=='/api/config':
            if not self._require_oms_admin(): return
            self.send_json({'ok':True,'data_path':str(DATA_DIR),'state_file':str(STATE_FILE),'backup_dir':str(BACKUP_DIR),'auth_enabled':True,'behind_proxy':bool(CONFIG.get('behind_proxy')),'domain_ready':True,'can_manage':True});return
        if u.path in ('/api/state','/api/changes'):
            oms_sess=self._require_oms_session()
            if not oms_sess:return
        if u.path=='/api/state':
            with LOCK:
                vals,vers,clients=_filtered_state_for_session(oms_sess)
                visible_initialized=any(v is not None for v in vals.values())
                self.send_json({'ok':True,'version':STATE['version'],'updated_at':STATE['updated_at'],'reset_epoch':STATE.get('reset_epoch',''),'initialized':visible_initialized,'values':vals,'key_versions':vers,'key_clients':clients,'permissions_enforced':True}); return
        if u.path=='/api/changes':
            try:since=int(parse_qs(u.query).get('since',['0'])[0])
            except:since=0
            with LOCK:
                changed={}
                meta={}
                for k,v in STATE['key_versions'].items():
                    if int(v)<=since: continue
                    if _can_read_sync_key(oms_sess,k):
                        changed[k]=STATE['values'].get(k)
                        meta[k]={'version':STATE['key_versions'].get(k,0),'client':STATE['key_clients'].get(k,'')}
                    else:
                        changed[k]=None
                        meta[k]={'version':STATE['key_versions'].get(k,0),'client':'permission-filter'}
                self.send_json({'ok':True,'version':STATE['version'],'updated_at':STATE['updated_at'],'reset_epoch':STATE.get('reset_epoch',''),'values':changed,'meta':meta,'permissions_enforced':True}); return
        return super().do_GET()
    def do_POST(self):
        global STATE
        u=urlparse(self.path)
        if handle_mobile_workspace(self, globals(), 'POST', u.path): return
        if handle_mobile_orders(self, globals(), 'POST', u.path): return
        if u.path=='/api/oms/login':
            try: body=self._read_json()
            except ValueError as e:self.send_json({'ok':False,'error':str(e)},400);return
            ip=_request_ip(self); now=time.time(); key='oms:'+ip
            arr=[t for t in LOGIN_ATTEMPTS.get(key,[]) if now-t<600]
            if len(arr)>=5:self.send_json({'ok':False,'error':'Too many sign-in attempts. Please try again later.'},429);return
            uname=_norm_username(body.get('username'))
            device_id=str(body.get('device_id') or '').strip()[:128]
            device_name=str(body.get('device_name') or '').strip()[:160]
            good,role=_verify_oms_user(uname,body.get('password'))
            if good:
                master=_load_user_master(); row=master.get('users',{}).get(uname,{})
                ip=_request_ip(self)
                allowed=row.get('allowed_ips') if isinstance(row.get('allowed_ips'),list) else []
                if allowed and ip not in allowed:
                    good=False
                # Treat non-private addresses as WAN; user can explicitly block WAN access.
                try:
                    private_ip=ipaddress.ip_address(ip).is_private or ipaddress.ip_address(ip).is_loopback
                except ValueError:
                    private_ip=False
                if row.get('allow_wan',True) is False and not private_ip:
                    good=False
                if good and bool(row.get('restrict_devices',False)):
                    approved=_device_rows(row,'approved_devices')
                    if not device_id or not _find_device(approved,device_id):
                        pending=_device_rows(row,'pending_devices')
                        if device_id and not _find_device(pending,device_id):
                            pending.append({'id':device_id,'name':device_name or 'Unknown browser/device','requested_at':now_iso(),'last_ip':ip})
                            row['pending_devices']=pending[-25:]
                            master['users'][uname]=row; _save_user_master(master)
                            append_config_audit('device_approval_requested',{'username':uname,'device_id':device_id,'device_name':device_name,'ip':ip},actor=uname)
                        self.send_json({'ok':False,'error':'This device is not authorised. Please contact the administrator to approve this device.'},403);return
            if not good:
                arr.append(now);LOGIN_ATTEMPTS[key]=arr
                uname=_norm_username(body.get('username')); row=_load_user_master().get('users',{}).get(uname,{})
                if uname!='admin' and row and not row.get('password_hash'):
                    self.send_json({'ok':False,'error':'This staff account is not configured. An administrator must set its password in Settings → User Accounts & Access.'},503);return
                self.send_json({'ok':False,'error':'Invalid username or password.'},401);return
            LOGIN_ATTEMPTS.pop(key,None)
            token=secrets.token_urlsafe(32);hours=max(1,min(72,int(CONFIG.get('session_hours',8) or 8)))
            session_id=secrets.token_hex(8)
            with AUTH_LOCK:
                if row.get('single_device'):
                    for _tok,_s in list(OMS_SESSIONS.items()):
                        if _s.get('user')==uname: OMS_SESSIONS.pop(_tok,None)
                OMS_SESSIONS[token]={'session_id':session_id,'user':uname,'role':role,'expires':now+hours*3600,'login_at':now_iso(),'last_seen':now,'ip':ip,'device_id':device_id,'device_name':device_name,'session_version':int(row.get('session_version',0) or 0)}
            try:
                um=_load_user_master();ur=um.get('users',{}).setdefault(uname,{'role':role,'email':'','password_hash':'','active':True})
                ur['last_login']=now_iso();ur['updated_at']=now_iso()
                if device_id:
                    approved=_device_rows(ur,'approved_devices'); found=_find_device(approved,device_id)
                    if found:
                        found['name']=device_name or found.get('name') or 'Approved device';found['last_seen']=now_iso();found['last_ip']=ip
                    elif not ur.get('restrict_devices',False):
                        approved.append({'id':device_id,'name':device_name or 'Browser/device','approved_at':now_iso(),'last_seen':now_iso(),'last_ip':ip,'auto_recorded':True})
                        ur['approved_devices']=approved[-25:]
                _save_user_master(um)
            except Exception: pass
            cookie=f'kiran_oms_user={token}; Path=/; HttpOnly; SameSite=Strict; Max-Age={hours*3600}'
            if self._secure_cookie():cookie+='; Secure'
            append_config_audit('oms_login_ok',{'role':role,'ip':ip},actor=uname)
            row=_load_user_master().get('users',{}).get(uname,{})
            self.send_json({'ok':True,'role':role,'user':uname,'profile':_public_user(uname,row)},extra_headers={'Set-Cookie':cookie});return
        if u.path=='/api/oms/logout':
            token=self._cookie_map().get('kiran_oms_user','')
            with AUTH_LOCK:OMS_SESSIONS.pop(token,None)
            cookie='kiran_oms_user=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0'
            if self._secure_cookie():cookie+='; Secure'
            self.send_json({'ok':True},extra_headers={'Set-Cookie':cookie});return
        if u.path=='/api/auth/recovery/request':
            try:
                body=self._read_json();masked=_request_recovery(body.get('username'),_request_ip(self));self.send_json({'ok':True,'masked_email':masked,'message':'Verification code sent.'});return
            except LookupError as e:self.send_json({'ok':False,'error':str(e)},404);return
            except (ValueError,RuntimeError) as e:self.send_json({'ok':False,'error':str(e)},400);return
            except Exception:self.send_json({'ok':False,'error':'The verification email could not be sent. Please contact the administrator.'},500);return
        if u.path=='/api/auth/recovery/verify':
            try:
                body=self._read_json();_verify_recovery(body.get('username'),body.get('otp'),body.get('new_password'),_request_ip(self));self.send_json({'ok':True,'message':'Password updated successfully. You can now sign in.'});return
            except (ValueError,RuntimeError) as e:self.send_json({'ok':False,'error':str(e)},400);return
        if u.path=='/api/admin/users':
            if not self._require_oms_admin():return
            try:body=self._read_json()
            except ValueError as e:self.send_json({'ok':False,'error':str(e)},400);return
            if not _verify_admin_master(str(body.get('admin_password') or '')):self.send_json({'ok':False,'error':'The Admin master password is incorrect.'},401);return
            d=_load_user_master();ae=str(body.get('admin_email') or '').strip();su=_norm_username(body.get('staff_username') or 'staff');se=str(body.get('staff_email') or '').strip();sp=str(body.get('staff_password') or '')
            if not su or su in ('admin','administrator','user'):
                if su=='user': su='staff'
                elif su!='staff': self.send_json({'ok':False,'error':'Enter a valid staff username.'},400);return
            d['users']['admin']['email']=ae
            row=d['users'].setdefault(su,{'role':'staff','email':'','password_hash':'','active':True});row['role']='staff';row['email']=se;row['active']=bool(body.get('staff_active',True))
            if sp:
                if len(sp)<8:self.send_json({'ok':False,'error':'Staff password must contain at least 8 characters.'},400);return
                row['password_hash']=_pbkdf2_hash(sp)
            _save_user_master(d);append_config_audit('user_access_updated',{'admin_email_set':bool(ae),'staff_username':su,'staff_email_set':bool(se),'staff_password_changed':bool(sp),'staff_active':row['active']},actor='admin')
            self.send_json({'ok':True,'message':'User accounts and access settings saved.','staff_username':su});return
        if u.path=='/api/oms/change-password':
            sess=self._oms_session()
            if not sess:
                self.send_json({'ok':False,'error':'Sign in is required.'},401);return
            try: body=self._read_json()
            except ValueError as e:self.send_json({'ok':False,'error':str(e)},400);return
            pwd=str(body.get('password') or '')
            if len(pwd)<8:
                self.send_json({'ok':False,'error':'Password must contain at least 8 characters.'},400);return
            uname=str(sess.get('user') or '')
            d=_load_user_master(); row=d.setdefault('users',{}).setdefault(uname,{'role':sess.get('role','staff'),'active':True})
            if uname=='admin': _write_admin_master(pwd)
            else: row['password_hash']=_pbkdf2_hash(pwd)
            row['must_change_password']=False;row['session_version']=int(row.get('session_version',0) or 0)+1;row['updated_at']=now_iso();_save_user_master(d)
            _revoke_user_sessions(uname)
            append_config_audit('own_password_changed',{'username':uname},actor=uname)
            self.send_json({'ok':True,'message':'Password updated successfully.'});return
        if u.path=='/api/admin/users/manage':
            sess=self._require_oms_admin()
            if not sess:return
            try: body=self._read_json()
            except ValueError as e:self.send_json({'ok':False,'error':str(e)},400);return
            action=str(body.get('action') or 'save').strip().lower()
            uname=_norm_username(body.get('username'))
            if not uname or uname in ('administrator','user'):
                self.send_json({'ok':False,'error':'Enter a valid username.'},400);return
            d=_load_user_master();users=d.setdefault('users',{})
            if action=='save':
                if uname!='admin' and uname not in users:
                    users[uname]={'role':'staff','email':'','password_hash':'','full_name':uname.title(),'designation':'Staff','active':True,'permissions':{}}
                row=users.get(uname)
                if not row:
                    self.send_json({'ok':False,'error':'User account not found.'},404);return
                role=str(body.get('role') or row.get('role') or ('admin' if uname=='admin' else 'staff')).strip().lower()
                if uname=='admin': role='admin'
                if role not in ('admin','staff','client'):
                    self.send_json({'ok':False,'error':'Permission role must be Admin, Staff or Client.'},400);return
                full_name=str(body.get('full_name') or '').strip()
                designation=str(body.get('designation') or '').strip()
                email=str(body.get('email') or '').strip()
                if not full_name:self.send_json({'ok':False,'error':'Full name is required.'},400);return
                if not designation:self.send_json({'ok':False,'error':'Designation is required.'},400);return
                visibility=body.get('profile_visibility') if isinstance(body.get('profile_visibility'),dict) else row.get('profile_visibility',{})
                merged_vis=dict(_profile_defaults(role)['profile_visibility']); merged_vis.update({k:bool(v) for k,v in visibility.items()})
                father_name=str(body.get('father_name',row.get('father_name','')) or '').strip()
                mobile=str(body.get('mobile',row.get('mobile','')) or '').strip()
                address=str(body.get('address',row.get('address','')) or '').strip()
                aadhaar=str(body.get('aadhaar',row.get('aadhaar','')) or '').replace(' ','').strip()
                pan=str(body.get('pan',row.get('pan','')) or '').replace(' ','').strip().upper()
                joining_date=str(body.get('joining_date',row.get('joining_date','')) or '').strip()
                salary=str(body.get('salary',row.get('salary','')) or '').strip()
                if aadhaar and (not aadhaar.isdigit() or len(aadhaar)!=12):self.send_json({'ok':False,'error':'Aadhaar number must contain exactly 12 digits.'},400);return
                if pan and (len(pan)!=10 or not (pan[:5].isalpha() and pan[5:9].isdigit() and pan[9:].isalpha())):self.send_json({'ok':False,'error':'Enter a valid 10-character PAN number.'},400);return
                if salary:
                    try:
                        if float(salary)<0:raise ValueError()
                    except Exception:self.send_json({'ok':False,'error':'Salary must be a valid non-negative amount.'},400);return
                row.update({'role':role,'full_name':full_name,'father_name':father_name,'designation':designation,'email':email,'mobile':mobile,'address':address,'aadhaar':aadhaar,'pan':pan,'joining_date':joining_date,'salary':salary,'profile_visibility':merged_vis,'active':bool(body.get('active')) if 'active' in body else bool(row.get('active',True)),'allow_wan':bool(body.get('allow_wan')) if 'allow_wan' in body else bool(row.get('allow_wan',True)),'single_device':bool(body.get('single_device')) if 'single_device' in body else bool(row.get('single_device',False)),'must_change_password':bool(body.get('must_change_password')) if 'must_change_password' in body else bool(row.get('must_change_password',False)),'restrict_devices':bool(body.get('restrict_devices')) if 'restrict_devices' in body else bool(row.get('restrict_devices',False)),'allowed_ips':[str(x).strip() for x in (body.get('allowed_ips') or []) if str(x).strip()] if 'allowed_ips' in body else (row.get('allowed_ips') if isinstance(row.get('allowed_ips'),list) else []),'updated_at':now_iso()})
                perms=body.get('permissions')
                if isinstance(perms,dict): row['permissions']=perms
                pwd=str(body.get('password') or '')
                if pwd:
                    if len(pwd)<8:self.send_json({'ok':False,'error':'Password must contain at least 8 characters.'},400);return
                    if uname=='admin': _write_admin_master(pwd)
                    else: row['password_hash']=_pbkdf2_hash(pwd)
                    row['session_version']=int(row.get('session_version',0) or 0)+1
                    _revoke_user_sessions(uname)
                _save_user_master(d)
                append_config_audit('user_saved',{'username':uname,'role':role,'active':row['active'],'designation':designation,'email_set':bool(email),'password_changed':bool(pwd)},actor=sess.get('user','admin'))
                self.send_json({'ok':True,'message':'User account saved successfully.','user':_public_user(uname,row)});return
            if action in ('deactivate','activate','lock','unlock'):
                row=users.get(uname)
                if not row:self.send_json({'ok':False,'error':'User account not found.'},404);return
                if uname=='admin' and action in ('deactivate','lock'):
                    self.send_json({'ok':False,'error':'The primary administrator account cannot be disabled.'},400);return
                row['active']=action in ('activate','unlock');row['updated_at']=now_iso();_save_user_master(d)
                append_config_audit('user_status_changed',{'username':uname,'active':row['active']},actor=sess.get('user','admin'))
                self.send_json({'ok':True,'message':'User status updated.','user':_public_user(uname,row)});return
            if action=='delete':
                if uname=='admin':self.send_json({'ok':False,'error':'The primary administrator account cannot be deleted.'},400);return
                row=users.get(uname)
                if not row:self.send_json({'ok':False,'error':'User account not found.'},404);return
                # Preserve audit identity: soft delete/deactivate only.
                row['active']=False;row['deleted_at']=now_iso();row['updated_at']=now_iso();_save_user_master(d)
                append_config_audit('user_deactivated',{'username':uname},actor=sess.get('user','admin'))
                self.send_json({'ok':True,'message':'User deactivated. Historical activity remains preserved.'});return
            self.send_json({'ok':False,'error':'Unsupported user management action.'},400);return
        if u.path=='/api/admin/sessions/manage':
            admin=self._require_oms_admin()
            if not admin:return
            try:body=self._read_json()
            except ValueError as e:self.send_json({'ok':False,'error':str(e)},400);return
            action=str(body.get('action') or '').strip().lower(); current_token=self._cookie_map().get('kiran_oms_user','')
            removed=0; clear_current=False
            if action=='logout_session':
                sid=str(body.get('session_id') or '')
                with AUTH_LOCK:
                    for tok,sess in list(OMS_SESSIONS.items()):
                        if str(sess.get('session_id') or '')==sid:
                            OMS_SESSIONS.pop(tok,None);removed+=1;clear_current=clear_current or tok==current_token
                append_config_audit('force_logout_session',{'session_id':sid,'removed':removed},actor=admin.get('user','admin'))
            elif action=='logout_user':
                uname=_norm_username(body.get('username')); removed=_revoke_user_sessions(uname)
                append_config_audit('force_logout_user',{'username':uname,'removed':removed},actor=admin.get('user','admin'))
            elif action=='logout_all':
                include_admin=bool(body.get('include_admin',False))
                with AUTH_LOCK:
                    for tok,sess in list(OMS_SESSIONS.items()):
                        if not include_admin and tok==current_token:continue
                        OMS_SESSIONS.pop(tok,None);removed+=1;clear_current=clear_current or tok==current_token
                append_config_audit('force_logout_all',{'include_admin':include_admin,'removed':removed},actor=admin.get('user','admin'))
            elif action in ('approve_device','revoke_device'):
                uname=_norm_username(body.get('username'));did=str(body.get('device_id') or '').strip(); data=_load_user_master();row=data.get('users',{}).get(uname)
                if not row:self.send_json({'ok':False,'error':'User account not found.'},404);return
                approved=_device_rows(row,'approved_devices');pending=_device_rows(row,'pending_devices')
                if action=='approve_device':
                    d=_find_device(pending,did) or _find_device(approved,did) or {'id':did,'name':str(body.get('device_name') or 'Approved device')}
                    if not _find_device(approved,did):approved.append({'id':did,'name':d.get('name') or 'Approved device','approved_at':now_iso(),'last_seen':d.get('last_seen',''),'last_ip':d.get('last_ip','')})
                    pending=[x for x in pending if not (isinstance(x,dict) and str(x.get('id') or '')==did)]
                    row['approved_devices']=approved[-25:];row['pending_devices']=pending
                else:
                    row['approved_devices']=[x for x in approved if not (isinstance(x,dict) and str(x.get('id') or '')==did)]
                    _revoke_user_sessions(uname)
                row['updated_at']=now_iso();_save_user_master(data)
                append_config_audit(action,{'username':uname,'device_id':did},actor=admin.get('user','admin'))
            else:
                self.send_json({'ok':False,'error':'Unsupported session/device action.'},400);return
            headers={}
            if clear_current:
                cookie='kiran_oms_user=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0'
                if self._secure_cookie():cookie+='; Secure'
                headers['Set-Cookie']=cookie
            self.send_json({'ok':True,'removed':removed,'message':'Action completed successfully.'},extra_headers=headers or None);return
        if u.path=='/api/admin/recovery-config':
            if not self._require_oms_admin():return
            try:body=self._read_json()
            except ValueError as e:self.send_json({'ok':False,'error':str(e)},400);return
            if not _verify_admin_master(str(body.get('admin_password') or '')):self.send_json({'ok':False,'error':'The Admin master password is incorrect.'},401);return
            CONFIG['smtp_host']=str(body.get('host') or '').strip();CONFIG['smtp_port']=max(1,min(65535,int(body.get('port') or 587)));CONFIG['smtp_username']=str(body.get('username') or '').strip();CONFIG['smtp_from']=str(body.get('from_email') or '').strip()
            if str(body.get('password') or ''):CONFIG['smtp_password']=str(body.get('password'))
            save_config();append_config_audit('email_recovery_config_updated',{'host':CONFIG['smtp_host'],'port':CONFIG['smtp_port'],'username_set':bool(CONFIG['smtp_username']),'from_email':CONFIG['smtp_from']},actor='admin');self.send_json({'ok':True,'message':'Email OTP recovery settings saved.'});return
        if u.path=='/api/admin/master/verify':
            try: body=self._read_json()
            except ValueError as e:self.send_json({'ok':False,'error':str(e)},400);return
            ip=_request_ip(self); now=time.time(); key='master:'+ip
            arr=[t for t in LOGIN_ATTEMPTS.get(key,[]) if now-t<600]
            if len(arr)>=5:
                self.send_json({'ok':False,'error':'too many login attempts; try again later'},429);return
            user=str(body.get('username') or '').strip().lower(); pwd=str(body.get('password') or '')
            if not _admin_master_configured():
                self.send_json({'ok':False,'error':'Admin master password is not configured on the server. Run SET_ADMIN_MASTER_PASSWORD.bat on the server PC.'},503);return
            good=(user in ('admin','administrator') and _verify_admin_master(pwd))
            if not good:
                arr.append(now); LOGIN_ATTEMPTS[key]=arr
                append_config_audit('admin_master_login_failed',{'ip':ip},actor='login')
                self.send_json({'ok':False,'error':'invalid username or password'},401);return
            LOGIN_ATTEMPTS.pop(key,None)
            append_config_audit('admin_master_login_ok',{'ip':ip},actor='admin')
            self.send_json({'ok':True,'role':'admin'});return
        if u.path=='/api/auth/login':
            if not CONFIG.get('web_auth_enabled'):
                self.send_json({'ok':True,'auth_enabled':False});return
            try: body=self._read_json()
            except ValueError as e:self.send_json({'ok':False,'error':str(e)},400);return
            ip=_request_ip(self); now=time.time()
            arr=[t for t in LOGIN_ATTEMPTS.get(ip,[]) if now-t<600]
            if len(arr)>=5:
                self.send_json({'ok':False,'error':'too many login attempts; try again later'},429);return
            user=str(body.get('username') or '')[:120]; pwd=str(body.get('password') or '')
            good=(hmac.compare_digest(user,str(CONFIG.get('admin_username','admin'))) and _verify_password(pwd,str(CONFIG.get('admin_password_hash',''))))
            if not good:
                arr.append(now); LOGIN_ATTEMPTS[ip]=arr; self.send_json({'ok':False,'error':'invalid username or password'},401);return
            LOGIN_ATTEMPTS.pop(ip,None)
            token=secrets.token_urlsafe(32); csrf=secrets.token_urlsafe(24); hours=max(1,min(72,int(CONFIG.get('session_hours',8) or 8)))
            with AUTH_LOCK:SESSIONS[token]={'user':user,'csrf':csrf,'expires':now+hours*3600}
            cookie=f'kiran_session={token}; Path=/; HttpOnly; SameSite=Strict; Max-Age={hours*3600}'
            if self._secure_cookie(): cookie+='; Secure'
            self.send_json({'ok':True,'authenticated':True,'user':user,'csrf':csrf},extra_headers={'Set-Cookie':cookie});return
        if u.path=='/api/auth/logout':
            token=self._cookie_map().get('kiran_session','')
            with AUTH_LOCK:SESSIONS.pop(token,None)
            cookie='kiran_session=; Path=/; HttpOnly; SameSite=Strict; Max-Age=0'
            if self._secure_cookie():cookie+='; Secure'
            self.send_json({'ok':True},extra_headers={'Set-Cookie':cookie});return
        if u.path=='/api/admin/backup-center/create':
            sess=self._require_oms_admin()
            if not sess:return
            try:
                body=self._read_json();include_users=bool(body.get('include_users',True));dst,manifest=_create_backup_package(kind='manual',include_users=include_users,prefix='KIRAN_OMS')
                append_config_audit('server_backup_created',{'name':dst.name,'bytes':dst.stat().st_size,'include_users':include_users},actor=sess.get('user','admin'))
                self.send_json({'ok':True,'name':dst.name,'bytes':dst.stat().st_size,'preview':_state_preview(STATE,manifest),'message':'Server backup created successfully.'})
            except Exception as e:self.send_json({'ok':False,'error':str(e)},500)
            return
        if u.path=='/api/admin/backup-center/reset-business-data':
            sess=self._require_oms_admin()
            if not sess:return
            try:
                body=self._read_json()
                confirm=str(body.get('confirm') or '').strip().upper()
                admin_password=str(body.get('admin_password') or '')
                if confirm!='DELETE LIVE DATA':
                    self.send_json({'ok':False,'error':'Type DELETE LIVE DATA exactly to confirm.'},400);return
                if not _verify_admin_master(admin_password):
                    self.send_json({'ok':False,'error':'The Admin master password is incorrect.'},401);return
                with LOCK:
                    safety,safety_manifest=_create_backup_package(kind='pre-reset-safety',include_users=True,prefix='pre_reset')
                    new_version=max(int(STATE.get('version',0) or 0)+1,1)
                    epoch=secrets.token_hex(12)
                    STATE['version']=new_version
                    STATE['updated_at']=now_iso()
                    STATE['reset_epoch']=epoch
                    STATE['values']={k:None for k in SYNC_KEYS}
                    STATE['key_versions']={k:new_version for k in SYNC_KEYS}
                    STATE['key_clients']={k:'server-live-reset' for k in SYNC_KEYS}
                    atomic_save_state()
                detail={'safety_backup':safety.name,'reset_version':new_version,'reset_epoch':epoch,'cleared_keys':len(SYNC_KEYS)}
                append_config_audit('live_business_data_reset',detail,actor=sess.get('user','admin'))
                self.send_json({'ok':True,**detail,'message':'Live business data was cleared safely. Users, passwords, security and server configuration were preserved.'})
            except Exception as e:self.send_json({'ok':False,'error':str(e)},500)
            return
        if u.path=='/api/admin/backup-center/upload':
            sess=self._require_oms_admin()
            if not sess:return
            filename=(self.headers.get('X-Kiran-Filename') or 'uploaded.omsbackup').strip()[:180]
            safe_name=Path(filename).name
            if Path(safe_name).suffix.lower() not in ('.omsbackup','.json'):
                self.send_json({'ok':False,'error':'Only .omsbackup or legacy KIRAN OMS .json backup files are allowed.'},400);return
            try:
                raw=self._read_raw_upload();meta=_stage_backup_bytes(raw,safe_name)
                append_config_audit('server_backup_staged',{'filename':safe_name,'bytes':len(raw),'preview':meta['preview']},actor=sess.get('user','admin'))
                self.send_json({'ok':True,**meta,'message':'Backup validated and staged. Live data has not been changed.'})
            except Exception as e:self.send_json({'ok':False,'error':str(e)},400)
            return
        if u.path=='/api/admin/backup-center/restore':
            sess=self._require_oms_admin()
            if not sess:return
            try:
                body=self._read_json();token=body.get('token');p,state,manifest,users=_load_staged_backup(token)
                result=_restore_state_safely(state,users=users,restore_users=bool(body.get('restore_users',False)),actor=sess.get('user','admin'),source=str(body.get('source') or p.name))
                _discard_staged(token)
                self.send_json({'ok':True,**result,'message':'Live server data restored successfully. Connected clients will receive the restored state automatically.'})
            except FileNotFoundError as e:self.send_json({'ok':False,'error':str(e)},404)
            except Exception as e:self.send_json({'ok':False,'error':str(e)},400)
            return
        if u.path=='/api/admin/backup-center/restore-existing':
            sess=self._require_oms_admin()
            if not sess:return
            try:
                body=self._read_json();p=_safe_backup_file(body.get('name'));state,manifest,users=_parse_backup_bytes(p.read_bytes(),p.name)
                result=_restore_state_safely(state,users=users,restore_users=bool(body.get('restore_users',False)),actor=sess.get('user','admin'),source=p.name)
                self.send_json({'ok':True,**result,'message':'Selected server backup restored successfully.'})
            except FileNotFoundError as e:self.send_json({'ok':False,'error':str(e)},404)
            except Exception as e:self.send_json({'ok':False,'error':str(e)},400)
            return
        if u.path=='/api/admin/backup-center/test-copy':
            sess=self._require_oms_admin()
            if not sess:return
            try:
                body=self._read_json();test_dir,meta=_create_test_copy_from_staged(body.get('token'),actor=sess.get('user','admin'))
                self.send_json({'ok':True,'path':str(test_dir),'preview':meta['preview'],'message':'Isolated test copy created on the server. Live data was not changed.'})
            except FileNotFoundError as e:self.send_json({'ok':False,'error':str(e)},404)
            except Exception as e:self.send_json({'ok':False,'error':str(e)},400)
            return
        if u.path=='/api/admin/backup-center/discard':
            sess=self._require_oms_admin()
            if not sess:return
            try:
                body=self._read_json();_discard_staged(body.get('token'));self.send_json({'ok':True,'message':'Staged backup discarded.'})
            except Exception as e:self.send_json({'ok':False,'error':str(e)},400)
            return
        if u.path=='/api/admin/data-upload':
            admin=self._require_oms_admin()
            if not admin:return
            filename=(self.headers.get('X-Kiran-Filename') or 'uploaded_backup.json').strip()[:180]
            safe_name=''.join(c for c in filename if c.isalnum() or c in ('-','_','.',' ')).strip() or 'uploaded_backup.json'
            ext=Path(safe_name).suffix.lower()
            if ext not in ('.json','.omsbackup'):
                self.send_json({'ok':False,'error':'only .json or .omsbackup files are allowed'},400);return
            try:
                raw=self._read_raw_upload(); incoming=self._validated_state_from_bytes(raw)
                with LOCK:
                    # 1) persist current state; 2) create immutable pre-restore safety copy; 3) keep uploaded source; 4) switch atomically.
                    atomic_save_state()
                    tag=datetime.now().strftime('%Y%m%d_%H%M%S')
                    BACKUP_DIR.mkdir(parents=True,exist_ok=True)
                    safety=BACKUP_DIR/f'pre_remote_restore_{tag}.json'
                    if STATE_FILE.exists(): shutil.copy2(STATE_FILE,safety)
                    imported=BACKUP_DIR/f'uploaded_{tag}_{Path(safe_name).stem[:60]}.json'
                    imported.write_bytes(raw)
                    STATE=incoming
                    STATE['version']=max(int(STATE.get('version',0) or 0),1)
                    STATE['updated_at']=now_iso()
                    atomic_save_state()
                actor=admin.get('user','admin')
                append_config_audit('remote_data_restore',{'filename':safe_name,'bytes':len(raw),'safety_backup':safety.name if safety.exists() else None,'uploaded_copy':imported.name,'state_version':STATE['version']},actor=actor)
                self.send_json({'ok':True,'filename':safe_name,'bytes':len(raw),'safety_backup':safety.name if safety.exists() else None,'uploaded_copy':imported.name,'version':STATE['version'],'message':'Data restored successfully. Reload clients to use restored data.'});return
            except Exception as e:
                self.send_json({'ok':False,'error':str(e)},400);return
        if u.path.startswith('/api/config/'):
            if not self._require_oms_admin(): return
            try:body=self._read_json()
            except ValueError as e:self.send_json({'ok':False,'error':str(e)},400);return
            if u.path=='/api/config/data-path':
                action=str(body.get('action') or 'test'); raw=body.get('path')
                try:
                    if action=='test':
                        path=_validate_data_path(raw); self.send_json({'ok':True,'path':str(path),'writable':True});return
                    if action=='open':
                        path=_validate_data_path(raw or str(DATA_DIR))
                        if not _is_local_ip(_request_ip(self)): raise PermissionError('Open Folder is available only on the server PC')
                        if os.name=='nt': os.startfile(str(path))
                        elif sys.platform=='darwin': subprocess.Popen(['open',str(path)])
                        else: subprocess.Popen(['xdg-open',str(path)])
                        self.send_json({'ok':True,'path':str(path)});return
                    if action=='browse':
                        if not _is_local_ip(_request_ip(self)): raise PermissionError('Browse Folder is available only on the server PC')
                        initial=str(raw or DATA_DIR)
                        chosen=''
                        try:
                            import tkinter as tk
                            from tkinter import filedialog
                            root=tk.Tk(); root.withdraw(); root.attributes('-topmost',True)
                            start=initial if os.path.isdir(initial) else str(Path(initial).parent if initial else DATA_DIR)
                            chosen=filedialog.askdirectory(title='Choose KIRAN OMS Data Folder',initialdir=start,mustexist=False) or ''
                            root.destroy()
                        except Exception as e:
                            raise RuntimeError('Windows folder picker is unavailable. Type the folder path manually.') from e
                        self.send_json({'ok':True,'path':chosen});return
                    if action=='apply':
                        mode=str(body.get('mode') or 'copy_current')
                        if mode not in ('copy_current','use_existing'): raise ValueError('invalid mode')
                        with LOCK:r=_switch_data_path(raw,mode=mode,force=bool(body.get('force')))
                        append_config_audit('data_path_change',r,actor=(self._oms_session() or {}).get('user',_request_ip(self)))
                        self.send_json({'ok':True,**r});return
                    raise ValueError('unknown action')
                except FileExistsError as e:self.send_json({'ok':False,'error':str(e),'requires_force':True},409);return
                except Exception as e:self.send_json({'ok':False,'error':str(e)},400);return
            self.send_json({'ok':False,'error':'not found'},404);return
        if u.path not in ('/api/kv','/api/bulk','/api/backup'):
            self.send_json({'ok':False,'error':'not found'},404);return
        if u.path=='/api/backup':
            oms_sess=self._require_oms_admin()
            if not oms_sess:return
            with LOCK:
                atomic_save_state(); tag=datetime.now().strftime('%Y%m%d_%H%M%S'); dst=BACKUP_DIR/f'manual_{tag}.json'; shutil.copy2(STATE_FILE,dst)
                self.send_json({'ok':True,'backup':str(dst.name),'version':STATE['version']});return
        oms_sess=self._require_oms_session()
        if not oms_sess:return
        try: body=self._read_json()
        except ValueError as e:self.send_json({'ok':False,'error':str(e)},400);return
        client=str(body.get('client_id') or 'unknown')[:120]
        with LOCK:
            try:
                if u.path=='/api/kv':
                    key=body.get('key'); value=body.get('value')
                    if key not in SYNC_KEYS:raise ValueError('key not allowed')
                    if value is not None and not isinstance(value,str):raise ValueError('value must be string or null')
                    old_value=STATE['values'].get(key)
                    if _sync_values_equal(old_value,value):
                        self.send_json({'ok':True,'version':STATE['version'],'key':key,'value':old_value,'permissions_enforced':True,'no_change':True});return
                    allowed,missing=_authorize_sync_write(oms_sess,key,old_value,value)
                    if not allowed:
                        append_config_audit('permission_denied',{'key':key,'missing':missing},actor=str(oms_sess.get('user') or 'unknown'))
                        self.send_json({'ok':False,'error':'Permission denied for this action.','missing_permissions':missing,'key':key},403);return
                    ver,merged=apply_change(key,value,client); self.send_json({'ok':True,'version':ver,'key':key,'value':merged,'permissions_enforced':True})
                else:
                    vals=body.get('values')
                    if not isinstance(vals,dict):raise ValueError('values must be object')
                    candidates=[]
                    denied=[]
                    for key,value in vals.items():
                        if key not in SYNC_KEYS or (value is not None and not isinstance(value,str)):continue
                        allowed,missing=_authorize_sync_write(oms_sess,key,STATE['values'].get(key),value)
                        if allowed:candidates.append((key,value))
                        else:denied.append({'key':key,'missing_permissions':missing})
                    if denied:
                        append_config_audit('permission_denied_bulk',{'denied':denied[:20]},actor=str(oms_sess.get('user') or 'unknown'))
                        self.send_json({'ok':False,'error':'Permission denied for one or more data changes.','denied':denied},403);return
                    changed=[]
                    for key,value in candidates:
                        apply_change(key,value,client); changed.append(key)
                    self.send_json({'ok':True,'version':STATE['version'],'changed':changed,'permissions_enforced':True})
            except ValueError as e:self.send_json({'ok':False,'error':str(e)},400)
            except Exception as e:self.send_json({'ok':False,'error':'server write failed','detail':str(e)},500)

def lan_ip():
    s=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
    try:s.connect(('8.8.8.8',80));return s.getsockname()[0]
    except:return 'SERVER-PC-IP'
    finally:s.close()


def discovery_service(http_port):
    """Reply to KIRAN Android clients on the trusted local LAN."""
    sock=socket.socket(socket.AF_INET,socket.SOCK_DGRAM)
    try:
        sock.setsockopt(socket.SOL_SOCKET,socket.SO_REUSEADDR,1)
        sock.setsockopt(socket.SOL_SOCKET,socket.SO_BROADCAST,1)
        sock.bind(('0.0.0.0',DISCOVERY_PORT))
        print(f' Mobile discovery: UDP {DISCOVERY_PORT}')
        while True:
            try:
                data,addr=sock.recvfrom(512)
                if data.strip()==DISCOVERY_REQUEST:
                    host=lan_ip()
                    if host!='SERVER-PC-IP':
                        msg=f'KIRAN_OMS_HERE|http://{host}:{http_port}/'.encode('utf-8')
                        sock.sendto(msg,addr)
            except OSError:
                time.sleep(0.2)
            except Exception as e:
                print('Discovery warning:',e)
    finally:
        try:sock.close()
        except:pass

def open_app(url):
    candidates=[
      os.path.expandvars(r'%ProgramFiles(x86)%\Microsoft\Edge\Application\msedge.exe'),
      os.path.expandvars(r'%ProgramFiles%\Microsoft\Edge\Application\msedge.exe'),
      os.path.expandvars(r'%ProgramFiles%\Google\Chrome\Application\chrome.exe'),
      os.path.expandvars(r'%ProgramFiles(x86)%\Google\Chrome\Application\chrome.exe'),
    ]
    for exe in candidates:
        if exe and os.path.exists(exe):
            try: subprocess.Popen([exe,f'--app={url}','--start-maximized']); return
            except:pass
    webbrowser.open(url)



def reset_admin_master_local():
    print('\nKIRAN OMS — LOCAL ADMIN MASTER PASSWORD RESET')
    print('This utility must be run directly on the server PC.')
    print('It does NOT require the previous Admin master password.')
    confirm=input('Type RESET to continue: ').strip()
    if confirm!='RESET':
        print('Cancelled. No changes were made.')
        return 1
    while True:
        p1=getpass.getpass('New Admin master password (minimum 8 characters): ')
        p2=getpass.getpass('Confirm new password: ')
        if len(p1)<8:
            print('Password must be at least 8 characters.'); continue
        if p1!=p2:
            print('Passwords do not match.'); continue
        break
    _write_admin_master(p1)
    # Revoke existing sessions after an emergency credential reset.
    try:
        d=_load_user_master()
        row=d.get('users',{}).setdefault('admin',{'role':'admin','email':'','password_hash':'','active':True})
        row['session_version']=int(row.get('session_version',0) or 0)+1
        row['updated_at']=now_iso()
        _save_user_master(d)
    except Exception:
        pass
    append_config_audit('admin_master_password_reset_local',{'master_file':str(ADMIN_MASTER_FILE)},actor='local-console')
    print('Admin master password reset successfully. Existing Admin sessions were invalidated.')
    return 0

def configure_domain_auth():
    print('\nKIRAN OMS — Domain/Web Authentication Setup')
    user=input(f"Admin username [{CONFIG.get('admin_username','admin')}]: ").strip() or str(CONFIG.get('admin_username','admin'))
    while True:
        p1=getpass.getpass('New admin password (minimum 10 characters): ')
        p2=getpass.getpass('Confirm password: ')
        if p1!=p2: print('Passwords do not match.'); continue
        if len(p1)<10: print('Use at least 10 characters.'); continue
        break
    CONFIG['admin_username']=user; CONFIG['admin_password_hash']=_pbkdf2_hash(p1); CONFIG['web_auth_enabled']=True; CONFIG['behind_proxy']=True; save_config()
    append_config_audit('domain_auth_enabled',{'username':user},actor='local-console')
    print('Domain authentication enabled. Put this server behind HTTPS reverse proxy (Caddy).')

def main():
    ap=argparse.ArgumentParser(); ap.add_argument('--port',type=int,default=8787); ap.add_argument('--no-open',action='store_true'); ap.add_argument('--configure-domain-auth',action='store_true'); ap.add_argument('--disable-web-auth',action='store_true'); ap.add_argument('--set-admin-master',action='store_true'); ap.add_argument('--reset-admin-master-local',action='store_true'); args=ap.parse_args()
    if args.reset_admin_master_local: return reset_admin_master_local()
    if args.set_admin_master: return configure_admin_master()
    if args.configure_domain_auth: configure_domain_auth(); return 0
    if args.disable_web_auth:
        CONFIG['web_auth_enabled']=False; save_config(); append_config_audit('domain_auth_disabled',{},actor='local-console'); print('Web authentication disabled.'); return 0
    if CONFIG.get('web_auth_enabled') and not CONFIG.get('admin_password_hash'):
        print('ERROR: web_auth_enabled is true but no password is configured. Run: python server.py --configure-domain-auth'); return 2
    os.chdir(ROOT); mimetypes.add_type('application/javascript','.js'); rotate_daily_backup(); maybe_create_auto_backup_package()
    try:srv=ThreadingHTTPServer(('0.0.0.0',args.port),Handler)
    except OSError as e:
        print('\nERROR: Port',args.port,'could not start:',e); input('Press Enter...'); return 2
    local=f'http://127.0.0.1:{args.port}/'; lan=f'http://{lan_ip()}:{args.port}/'
    print('\n'+'='*74); print(' KIRAN OMS - WEB / CENTRAL SERVER - PHASE 10.37'); print('='*74)
    print(' Main PC   :',local); print(' Other PCs :',lan); print(' Mobile    : Auto-discovery UDP',DISCOVERY_PORT); print(' Data Path :',DATA_DIR); print(' State     :',STATE_FILE); print(' Web Auth  :','ON' if CONFIG.get('web_auth_enabled') else 'OFF (LAN mode)'); print(' Domain    :','Ready behind HTTPS reverse proxy'); print(' Close this window = server stop'); print('='*74+'\n')
    threading.Thread(target=discovery_service,args=(args.port,),daemon=True).start()
    if not args.no_open: threading.Timer(1.0,lambda:open_app(local)).start()
    try:srv.serve_forever()
    except KeyboardInterrupt:pass
    finally:srv.server_close()
    return 0

if __name__=='__main__': raise SystemExit(main())
