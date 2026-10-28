#!/usr/bin/env python3
"""Publish validated public APKs to a separate Gitee downloads branch over SSH.
Optionally publish a formal Gitee Release with a projects-scoped token file.
No credentials or private catalogs are copied into Git objects.
"""
import argparse, hashlib, json, os, pathlib, subprocess, tempfile, urllib.error, urllib.request, uuid, zipfile

parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--directory',type=pathlib.Path,required=True,help='Output of prepare-release.py')
parser.add_argument('--push',action='store_true',help='Publish artifacts; default only validates')
parser.add_argument('--token-file',type=pathlib.Path,help='Optional: also publish formal Release via API')
parser.add_argument('--attachment',type=pathlib.Path,action='append',default=[],help='Explicit public PNG/ZIP attachment inside the release directory')
parser.add_argument('--release-notes',type=pathlib.Path,help='Optional Release body; {{asset:filename}} resolves to verified formal attachment URLs')
args=parser.parse_args()
repo='xdmdpz/libretv-standalone'
remote='git@gitee.com:'+repo+'.git'
root=pathlib.Path(__file__).resolve().parents[1]
folder=args.directory.resolve()
manifest=folder/'update-family.libretv.standalone.json'
data=json.loads(manifest.read_text())
name=pathlib.PurePosixPath(data['downloadUrl']).name
apk=folder/name
assert data['packageName']=='family.libretv.standalone'
assert name=='LibreTV-'+data['versionName'].split('-')[0]+'.apk'
assert data['downloadUrl'].startswith('https://github.com/if-lensman/libretv-standalone/releases/download/')
assert data['size']==apk.stat().st_size
assert data['sha256']==hashlib.sha256(apk.read_bytes()).hexdigest()
with zipfile.ZipFile(apk) as z:
    assert json.loads(z.read('assets/default-sources.json'))=={'sources':[]}
    assert json.loads(z.read('assets/default-live-sources.json'))=={'subscriptions':[]}
for line in (folder/'SHA256SUMS.txt').read_text().splitlines():
    digest,filename=line.split()
    assert pathlib.Path(filename).name==filename
    assert hashlib.sha256((folder/filename).read_bytes()).hexdigest()==digest
extra_files=[p.resolve() for p in args.attachment]
assert len({p.name for p in extra_files})==len(extra_files), 'Duplicate attachment names'
for file in extra_files:
    assert file.parent==folder and file.is_file(), 'Attachments must be inside the public release directory'
    assert file.suffix.lower() in ('.png','.jpg','.jpeg','.webp','.zip'), 'Unsupported attachment type'
formal_files=[apk,manifest,folder/'SHA256SUMS.txt']+extra_files
assert len({p.name for p in formal_files})==len(formal_files), 'Attachment collides with a required artifact'
release_notes=args.release_notes.read_text() if args.release_notes else None
if release_notes is not None:
    import re
    for filename in re.findall(r'\{\{asset:([^}]+)\}\}',release_notes):
        assert filename in {p.name for p in formal_files}, 'Unknown Release attachment: '+filename
print('Public artifacts validated:' ,data['versionName'],data['versionCode'],flush=True)
if not args.push:
    print('Validation only; pass --push to publish.');raise SystemExit

# A separate index avoids changing source files, branch or staging area.
with tempfile.TemporaryDirectory(prefix='gitee-update-',dir=root/'local') as temp:
    env={**os.environ,'GIT_INDEX_FILE':str(pathlib.Path(temp)/'index')}
    def git(*cmd,input=None):
        return subprocess.check_output(['git',*cmd],cwd=root,env=env,input=input).decode().strip()
    refs=git('ls-remote',remote,'refs/heads/updates')
    parent=refs.split()[0] if refs else None
    if parent:
        git('fetch',remote,'refs/heads/updates');git('read-tree',parent)
    else:git('read-tree','--empty')
    # Preserve previous APKs so an already-read old manifest remains downloadable.
    for file in [apk,manifest,folder/'SHA256SUMS.txt']:
        blob=git('hash-object','-w',str(file));git('update-index','--add','--cacheinfo','100644',blob,file.name)
    text=('LibreTV public download mirror\n\nThis branch contains only empty-source signed public APKs and update metadata.\n'
          'Source code: main branch. The latest metadata retains canonical GitHub URLs; the App maps filenames to this mirror.\n'
          'Publishing this branch does not create a Gitee Release entry. See docs/releasing.md on main.\n')
    blob=git('hash-object','-w','--stdin',input=text.encode());git('update-index','--add','--cacheinfo','100644',blob,'README.md')
    tree=git('write-tree');cmd=['commit-tree',tree,'-m','Publish public download mirror '+data['versionName']]
    if parent:cmd+=['-p',parent]
    if subprocess.run(['git','config','--bool','commit.gpgsign'],cwd=root,capture_output=True,text=True).stdout.strip()=='true':cmd+=['-S']
    commit=git(*cmd)
    git('push','--force-with-lease=refs/heads/updates:'+(parent or ''),remote,commit+':refs/heads/updates')
print('Download mirror published:', 'https://gitee.com/'+repo+'/tree/updates')

# Read back anonymously. A login page or a stale response is a failed publication.
public_opener=urllib.request.build_opener(urllib.request.ProxyHandler({}))
for file in [apk,manifest]:
    url='https://gitee.com/'+repo+'/raw/updates/'+file.name
    with public_opener.open(url,timeout=45) as response:
        body=response.read()
        assert response.status==200 and hashlib.sha256(body).digest()==hashlib.sha256(file.read_bytes()).digest()
print('Anonymous manifest and APK downloads verified')
if not args.token_file:
    print('Formal Gitee Release not created: API token not supplied. SSH download mirror is available.')
    raise SystemExit

token=args.token_file.read_text().strip()
assert token, 'Empty Gitee token file'
base='https://gitee.com/api/v5/repos/'+repo
headers={'Authorization':'Bearer '+token}
class NoAuthRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self,req,fp,code,msg,headers,newurl):
        return None  # Do not forward API credentials across a redirect.
auth_opener=urllib.request.build_opener(urllib.request.ProxyHandler({}),NoAuthRedirect)

def api(method,path,payload=None,body=None,content_type=None):
    request_headers=dict(headers)
    if payload is not None:body=json.dumps(payload,ensure_ascii=False).encode();content_type='application/json'
    if content_type:request_headers['Content-Type']=content_type
    req=urllib.request.Request(base+path,data=body,headers=request_headers,method=method)
    try:
        with auth_opener.open(req,timeout=60) as response:return json.load(response)
    except urllib.error.HTTPError as error:
        # Never log request headers or token-bearing URLs.
        raise RuntimeError('Gitee API '+method+' '+path+' returned HTTP '+str(error.code)) from None
version=data['versionName'].split('-')[0];tag='v'+version
# Idempotent release lookup avoids duplicates after a partial upload.
releases=api('GET','/releases?per_page=100')
release=next((r for r in releases if r.get('tag_name')==tag),None)
if release is None:
    release=api('POST','/releases',payload={'tag_name':tag,'target_commitish':'main','name':tag,'body':data['notes'],'prerelease':False})
release_id=release['id']
existing=api('GET','/releases/'+str(release_id)+'/attach_files')
if isinstance(existing,dict):existing=existing.get('list',existing.get('data',[]))
for file in formal_files:
    if any(item.get('name')==file.name for item in existing):continue
    boundary='LibreTV'+uuid.uuid4().hex
    body=(f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{file.name}"\r\nContent-Type: application/octet-stream\r\n\r\n'.encode()
          +file.read_bytes()+f'\r\n--{boundary}--\r\n'.encode())
    api('POST','/releases/'+str(release_id)+'/attach_files',body=body,content_type='multipart/form-data; boundary='+boundary)
attachments=api('GET','/releases/'+str(release_id)+'/attach_files')
if isinstance(attachments,dict):attachments=attachments.get('list',attachments.get('data',[]))
for file in formal_files:
    item=next((entry for entry in attachments if entry.get('name')==file.name),None)
    assert item is not None, 'Uploaded attachment missing: '+file.name
    url=item.get('browser_download_url')
    assert url and url.startswith('https://gitee.com/'), 'Attachment download URL missing or unexpected'
    with public_opener.open(url,timeout=45) as response:
        assert response.status==200 and hashlib.sha256(response.read()).digest()==hashlib.sha256(file.read_bytes()).digest(), 'Attachment readback mismatch: '+file.name
if release_notes is not None:
    rendered=release_notes
    for item in attachments:
        rendered=rendered.replace('{{asset:'+item.get('name','')+'}}',item.get('browser_download_url',''))
    assert '{{asset:' not in rendered, 'Unresolved Release screenshot URL'
    updated=api('PATCH','/releases/'+str(release_id),payload={'body':rendered})
    assert updated.get('body')==rendered, 'Release body update mismatch'
    print('Formal Release screenshot body updated')
print('Formal Gitee Release attachments anonymously verified: https://gitee.com/'+repo+'/releases/tag/'+tag)

