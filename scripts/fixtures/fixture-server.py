import http.server,json,urllib.parse,pathlib,time
ROOT=pathlib.Path(__file__).parent
class Handler(http.server.SimpleHTTPRequestHandler):
 def __init__(self,*args,**kwargs):super().__init__(*args,directory=str(ROOT),**kwargs)
 def do_GET(self):
  parsed=urllib.parse.urlparse(self.path);q=urllib.parse.parse_qs(parsed.query)
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
