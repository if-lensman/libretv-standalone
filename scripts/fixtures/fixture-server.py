import http.server,json,urllib.parse,pathlib,time,argparse,math
parser=argparse.ArgumentParser();parser.add_argument('--media-dir',type=pathlib.Path,default=pathlib.Path(__file__).parent);args=parser.parse_args()
ROOT=args.media_dir.resolve()
LIVE_STARTED=time.monotonic()
LIVE_MODE='normal'
LIVE_REVISION=0
class Handler(http.server.SimpleHTTPRequestHandler):
 def __init__(self,*args,**kwargs):super().__init__(*args,directory=str(ROOT),**kwargs)
 def do_GET(self):
  parsed=urllib.parse.urlparse(self.path);q=urllib.parse.parse_qs(parsed.query)
  global LIVE_MODE,LIVE_REVISION
  if parsed.path=='/recommendations':
   payload={'subjects':[{'id':str(i),'title':'推荐测试影片 '+str(i),'cover':'http://10.0.2.2:19001/poster.png','rate':'8.5'} for i in range(1,25)]}
   data=json.dumps(payload).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
  if parsed.path=='/recommendations/offline':self.send_error(503);return
  if parsed.path=='/live/control':
   LIVE_MODE=q.get('mode',['normal'])[0];LIVE_REVISION+=1
   data=json.dumps({'mode':LIVE_MODE,'revision':LIVE_REVISION}).encode();self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
  if parsed.path=='/live/failure-list':
   kind=q.get('case',['failover'])[0]
   first='/live/slow.m3u8' if kind=='slow' else '/live/reject.m3u8'
   second='/live/missing.m3u8' if kind=='allbad' else '/live/stream'
   text='#EXTM3U\n'
   for url in [first,second]:text+='#EXTINF:-1 tvg-id="recovery-test" group-title="测试",容错测试频道\nhttp://10.0.2.2:19001'+url+'\n'
   text+='#EXTINF:-1 tvg-id="healthy-test" group-title="测试",稳定对照频道\nhttp://10.0.2.2:19001/live/stream\n'
   data=text.encode();self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
  if parsed.path=='/live/reject.m3u8':self.send_error(403);return
  if parsed.path=='/live/missing.m3u8':self.send_error(404);return
  if parsed.path=='/live/slow.m3u8':
   time.sleep(40)
   try:self.send_error(503)
   except (BrokenPipeError,ConnectionResetError):pass
   return
  if parsed.path in ('/live/list','/live/delayed'):
   if parsed.path=='/live/delayed':time.sleep(2)
   lines=['#EXTM3U','#EXTINF:-1 tvg-id="更新时间" group-title="更新时间",2026-10-22 12:00','#EXTGRP:更新时间','http://10.0.2.2:19001/sample.mp4']
   if LIVE_MODE!='empty':
    title='测试直播一' if LIVE_MODE!='renamed' else '测试直播一（新名称）'
    lines += ['#EXTINF:-1 tvg-id="fixture-live1" group-title="央视",'+title,'http://10.0.2.2:19001/live/stream?revision='+str(LIVE_REVISION),
     '#EXTINF:-1 tvg-id="fixture-live1" group-title="央视",'+title,'http://10.0.2.2:19001/live/stream?backup=1&revision='+str(LIVE_REVISION),
     '#EXTINF:-1 tvg-id="fixture-live2" group-title="卫视",测试直播二','#EXTVLCOPT:http-user-agent=FixtureLiveAgent','#EXTVLCOPT:http-referrer=http://10.0.2.2:19001/','http://10.0.2.2:19001/live/header-stream',
     '#EXTINF:-1,不支持的协议','rtmp://example.invalid/live']
   data=('\n'.join(lines)+'\n').encode();self.send_response(200);self.send_header('Content-Type','audio/x-mpegurl');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
  if parsed.path.startswith('/live/header'):
   if self.headers.get('User-Agent')!='FixtureLiveAgent' or self.headers.get('Referer')!='http://10.0.2.2:19001/':self.send_error(403);return
  if parsed.path in ('/live/stream','/live/header-stream'):
   if LIVE_MODE=='offline':self.send_error(503);return
   segment_files=sorted((ROOT/'live-media').glob('part*.ts'))
   if not segment_files:self.send_error(503);return
   sequence=int((time.monotonic()-LIVE_STARTED)//2)+(1000 if LIVE_MODE=='jump' else 0);prefix='/live/header/' if 'header' in parsed.path else '/live/media/'
   lines=['#EXTM3U','#EXT-X-VERSION:3','#EXT-X-TARGETDURATION:2','#EXT-X-MEDIA-SEQUENCE:'+str(sequence),'#EXT-X-DISCONTINUITY-SEQUENCE:'+str(sequence//len(segment_files))]
   for i in range(sequence,sequence+4):
    if i>sequence and i%len(segment_files)==0:lines.append('#EXT-X-DISCONTINUITY')
    lines+=['#EXTINF:2.0,',prefix+segment_files[i%len(segment_files)].name+'?seq='+str(i)]
   data=('\n'.join(lines)+'\n').encode();self.send_response(200);self.send_header('Content-Type','application/octet-stream');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
  if parsed.path.startswith(('/live/media/','/live/header/')):
   file=ROOT/'live-media'/pathlib.Path(parsed.path).name
   if not file.is_file():self.send_error(404);return
   data=file.read_bytes();self.send_response(200);self.send_header('Content-Type','video/mp2t');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
  if parsed.path=='/bad':self.send_error(503);return
  if parsed.path=='/redirect.m3u8':
   self.send_response(302);self.send_header('Location','/stream.m3u8');self.end_headers();return
  if parsed.path in ('/slow','/fast'):
   if parsed.path=='/slow':time.sleep(4)
   keyword=q.get('wd',['Fixture'])[0]
   data=json.dumps({'pagecount':1,'list':[{'vod_id':1,'vod_name':keyword+' '+parsed.path[1:],'vod_pic':''}]}).encode()
   self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
  if parsed.path=='/cms':
   if 'ids' in q:payload={'list':[{'vod_id':1,'vod_name':'Fixture title','vod_play_url':'网页$http://example.invalid/share/1$$$第1集$http://10.0.2.2:19001/redirect.m3u8#第2集$http://10.0.2.2:19001/sample.mp4'}]}
   else:payload={'pagecount':2,'list':[{'vod_id':1 if q.get('pg',['1'])[0]=='1' else 2,'vod_name':'Fixture title','vod_remarks':'测试集数','vod_pic':''}]}
   data=json.dumps(payload).encode();self.send_response(200);self.send_header('Content-Type','application/json');self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
  if parsed.path=='/subscription':
   data=json.dumps({'sources':[{'name':'Fixture source','url':'http://10.0.2.2:19001/cms'}]}).encode();self.send_response(200);self.send_header('Content-Length',str(len(data)));self.end_headers();self.wfile.write(data);return
  return super().do_GET()
http.server.ThreadingHTTPServer(('127.0.0.1',19001),Handler).serve_forever()
