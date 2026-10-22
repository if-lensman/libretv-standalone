package family.libretv.tv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

/** On-device CMS requests and storage. No NAS service or login is involved. */
final class LocalRepository {
    private final SharedPreferences prefs;
    static final String UA = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 Chrome/122.0.0.0 Safari/537.36";
    private static final Object DEFAULTS_LOCK = new Object();
    LocalRepository(Context context) throws Exception {
        prefs = context.getApplicationContext().getSharedPreferences("standalone-library", 0);
        try (InputStream in = context.getAssets().open("default-sources.json")) {
            mergeDefaults(prefs, parseSources(read(in, 1024 * 1024), true));
        }
    }
    /** Append each newly shipped URL once; never restore a removed default or change user flags. */
    static void mergeDefaults(SharedPreferences prefs, JSONArray catalog) throws Exception {
        synchronized (DEFAULTS_LOCK) {
            JSONArray all = new JSONArray(prefs.getString("sources", "[]"));
            Set<String> seen = new HashSet<>(prefs.getStringSet("knownDefaultUrls", Collections.emptySet()));
            if (prefs.contains("sources") && !prefs.contains("knownDefaultUrls")) {
                // Older installs did not track shipped defaults; catalog metadata preserves deletions.
                for (int i = 0; i < catalog.length(); i++) {
                    JSONObject source = catalog.getJSONObject(i);
                    if (source.optBoolean("legacyDefault")) seen.add(source.getString("url"));
                }
            }
            Set<String> present = new HashSet<>();
            for (int i = 0; i < all.length(); i++) present.add(all.getJSONObject(i).getString("url"));
            for (int i = 0; i < catalog.length(); i++) {
                JSONObject source = catalog.getJSONObject(i); String url = source.getString("url");
                if (!seen.contains(url) && present.add(url) && all.length() < 50) all.put(source);
                seen.add(url);
            }
            if (!prefs.edit().putString("sources", all.toString()).putStringSet("knownDefaultUrls", seen).commit())
                throw new IOException("默认来源保存失败，请重新打开应用重试");
        }
    }
    synchronized JSONArray sources() throws Exception { return new JSONArray(prefs.getString("sources", "[]")); }
    synchronized void saveSources(JSONArray sources) throws Exception {
        if (!prefs.edit().putString("sources", sources.toString()).commit()) throw new IOException("配置保存失败");
    }
    synchronized void removeSource(String key) throws Exception {
        JSONArray next = new JSONArray(), all = sources();
        for(int i=0;i<all.length();i++) if(!all.getJSONObject(i).optString("key").equals(key)) next.put(all.getJSONObject(i));
        saveSources(next);
    }
    synchronized void setSourceEnabled(String key, boolean enabled) throws Exception {
        JSONArray all = sources();
        for (int i = 0; i < all.length(); i++)
            if (all.getJSONObject(i).getString("key").equals(key)) all.getJSONObject(i).put("enabled", enabled);
        saveSources(all);
    }
    synchronized String exportSources() throws Exception {
        return new JSONObject().put("name", "LibreTV 本机来源").put("sources", sources()).toString(2);
    }
    synchronized int importSources(String input) throws Exception {
        String text = input.trim();
        if (text.startsWith("http://") || text.startsWith("https://")) text = fetch(text);
        JSONArray incoming = parseSources(text), all = sources();
        Set<String> urls = new HashSet<>();
        for(int i=0;i<all.length();i++) urls.add(all.getJSONObject(i).getString("url"));
        int added=0;
        for(int i=0;i<incoming.length();i++) if(urls.add(incoming.getJSONObject(i).getString("url"))) {
            if(all.length()>=50) throw new IOException("最多保存 50 个源");
            all.put(incoming.getJSONObject(i)); added++;
        }
        saveSources(all);return added;
    }
    static JSONArray parseSources(String text) throws Exception {
        return parseSources(text, false);
    }
    static JSONArray parseSources(String text, boolean allowEmpty) throws Exception {
        Object root = new JSONTokener(text).nextValue();
        JSONArray list;
        boolean tvbox=false;
        if(root instanceof JSONArray) list=(JSONArray)root;
        else if(root instanceof JSONObject) {
            JSONObject obj=(JSONObject)root;
            list=obj.optJSONArray("sources");
            if(list==null) {list=obj.optJSONArray("sites");tvbox=true;}
            if(list==null && obj.has("url")) list=new JSONArray().put(obj);
        } else list=null;
        if(list==null) throw new IOException("请填写源 JSON 或订阅地址");
        JSONArray result=new JSONArray();Set<String> seen=new HashSet<>();
        for(int i=0;i<list.length();i++) {
            JSONObject item=list.optJSONObject(i);if(item==null || item.optBoolean("isAdult"))continue;
            if(tvbox && item.optInt("type",-1)!=1) continue;
            String url=item.optString(tvbox?"api":"url").trim().replaceAll("/+$", "");
            validate(url);
            if(!seen.add(url)) continue;
            if(result.length()>=50) throw new IOException("一次最多导入 50 个源");
            JSONObject source=new JSONObject().put("key",key(url)).put("name",item.optString("name","自定义源")).put("url",url).put("enabled",item.optBoolean("enabled",true));
            if (allowEmpty && item.optBoolean("legacyDefault")) source.put("legacyDefault", true);
            String detail=item.optString("detail").trim();if(!detail.isEmpty()){validate(detail);source.put("detail",detail);}
            result.put(source);
        }
        if(result.length()==0 && !allowEmpty) throw new IOException("没有可导入的 CMS JSON 源（暂不支持 Spider/JAR）");
        return result;
    }
    static String key(String url) throws Exception {return AppUpdater.hex(MessageDigest.getInstance("SHA-256").digest(url.getBytes(StandardCharsets.UTF_8))).substring(0,16);}
    static URI validate(String url) throws IOException {
        try {
            URI u=new URI(url);
            if(!("http".equalsIgnoreCase(u.getScheme()) || "https".equalsIgnoreCase(u.getScheme())) || u.getHost()==null || u.getRawUserInfo()!=null || u.getFragment()!=null) throw new Exception();
            return u;
        } catch(Exception e){throw new IOException("源地址必须是有效 HTTP(S) 地址");}
    }
    static HttpURLConnection open(URI uri) throws IOException {
        validate(uri.toString());
        HttpURLConnection conn=(HttpURLConnection)uri.toURL().openConnection();
        conn.setInstanceFollowRedirects(true);conn.setConnectTimeout(6000);conn.setReadTimeout(8000);
        conn.setRequestProperty("User-Agent",UA);conn.setRequestProperty("Accept","*/*");return conn;
    }
    static String read(InputStream in,int limit) throws IOException {
        try(InputStream stream=in;ByteArrayOutputStream out=new ByteArrayOutputStream()){
            byte[] b=new byte[8192];int n;while((n=stream.read(b))!=-1){
                if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("请求已取消");
                if(out.size()+n>limit)throw new IOException("源响应过大");out.write(b,0,n);
            }return out.toString("UTF-8");
        }
    }
    static String fetch(String url) throws Exception {
        HttpURLConnection conn=open(validate(url));
        try{int code=conn.getResponseCode();if(code<200 || code>=300)throw new IOException("源站 HTTP "+code);return read(conn.getInputStream(),4*1024*1024);}finally{conn.disconnect();}
    }
    static String api(String root,String action,String value) {
        return root+(root.contains("?")?"&":"?")+"ac=videolist&"+action+"="+NasClient.encode(value);
    }
    private static Map<String,String> query(String path) throws Exception {
        Map<String,String> q=new HashMap<>();String raw=URI.create(path).getRawQuery();if(raw==null)return q;
        for(String part:raw.split("&")){String[] kv=part.split("=",2);q.put(URLDecoder.decode(kv[0],"UTF-8"),kv.length>1?URLDecoder.decode(kv[1],"UTF-8"):"");}return q;
    }
    JSONObject request(String path,JSONObject body,String method) throws Exception {
        String route=path.split("\\?",2)[0];Map<String,String> q=query(path);
        if(route.equals("/api/search"))return search(body.getString("wd"),body.optJSONArray("sources"));
        if(route.equals("/api/detail"))return detail(q.get("id"),new JSONObject(q.get("source")));
        if(route.equals("/api/favorites") || route.startsWith("/api/favorites/"))return favorites(route,body,method);
        throw new IOException("独立版不使用此 NAS 接口");
    }
    interface SearchListener {
        void onSource(JSONArray items, int completed, int total, int failed);
    }
    JSONObject search(String wd, JSONArray selected) throws Exception { return search(wd, selected, null); }
    JSONObject search(String wd, JSONArray selected, SearchListener listener) throws Exception {
        if(selected==null) selected=sources();
        List<JSONObject> enabled=new ArrayList<>();
        for(int i=0;i<selected.length();i++) if(selected.getJSONObject(i).optBoolean("enabled",true)) enabled.add(selected.getJSONObject(i));
        if(enabled.isEmpty()) throw new IOException("请先在来源配置中至少启用一个来源");
        ExecutorService pool=Executors.newFixedThreadPool(Math.min(4,enabled.size()));
        CompletionService<JSONArray> ready=new ExecutorCompletionService<>(pool);
        Map<Future<JSONArray>,JSONObject> pending=new HashMap<>();
        JSONArray list=new JSONArray(),failures=new JSONArray();
        final long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(22);
        int completed=0;
        try {
            for(JSONObject source:enabled) pending.put(ready.submit(()->{
                JSONArray found=new JSONArray();
                JSONObject first=new JSONObject(fetch(api(source.getString("url"),"wd",wd)+"&pg=1"));
                appendResults(found,first,source,wd);
                int pages=Math.max(1,Math.min(5,first.optInt("pagecount",1)));
                for(int p=2;p<=pages;p++) {
                    if(Thread.currentThread().isInterrupted() || System.nanoTime()>deadline) break;
                    try { appendResults(found,new JSONObject(fetch(api(source.getString("url"),"wd",wd)+"&pg="+p)),source,wd); }
                    catch(InterruptedIOException e) { throw e; }
                    catch(Exception ignored) {}
                }
                return found;
            }),source);
            Set<String> seen=new HashSet<>();
            while(!pending.isEmpty()) {
                long left=deadline-System.nanoTime();
                Future<JSONArray> done=ready.poll(Math.max(0,left),TimeUnit.NANOSECONDS);
                if(done==null) break;
                JSONObject source=pending.remove(done); JSONArray added=new JSONArray();
                try {
                    JSONArray got=done.get();
                    for(int j=0;j<got.length();j++) {
                        JSONObject item=got.getJSONObject(j);
                        if(seen.add(item.optString("sourceKey")+":"+item.optString("vodId"))) { list.put(item); added.put(item); }
                    }
                } catch(ExecutionException e) {
                    failures.put(new JSONObject().put("sourceKey",source.optString("key")).put("error","来源不可用或超时"));
                }
                completed++;
                if(listener!=null) listener.onSource(added,completed,enabled.size(),failures.length());
            }
            if(!pending.isEmpty()) {
                for(JSONObject source:pending.values()) failures.put(new JSONObject().put("sourceKey",source.optString("key")).put("error","来源超时"));
                if(listener!=null) listener.onSource(new JSONArray(),enabled.size(),enabled.size(),failures.length());
            }
        } finally { for(Future<?> future:pending.keySet()) future.cancel(true); pool.shutdownNow(); }
        return new JSONObject().put("list",list).put("failures",failures);
    }
    static String normalize(String s){return s.toLowerCase(Locale.ROOT).replaceAll("[\\s\\p{P}\\p{S}]+","");}
    static void appendResults(JSONArray out,JSONObject payload,JSONObject source,String wd)throws Exception{
        JSONArray list=payload.optJSONArray("list");if(list==null)return;
        for(int i=0;i<list.length();i++){
            JSONObject vod=list.optJSONObject(i);if(vod==null)continue;
            String name=vod.optString("vod_name"),type=vod.optString("type_name");
            if(type.matches(".*(伦理|倫理|福利|情色|成人|里番).*"))continue;
            String normalized=normalize(name);boolean relevant=normalized.contains(normalize(wd));
            if(!relevant && wd.trim().contains(" ")){relevant=true;for(String token:wd.trim().split("\\s+"))if(!normalized.contains(normalize(token)))relevant=false;}
            if(!relevant)continue;
            JSONObject item=new JSONObject().put("sourceKey",source.getString("key")).put("sourceName",source.getString("name")).put("sourceUrl",source.getString("url"))
                .put("vodId",vod.optString("vod_id")).put("name",name).put("pic",vod.optString("vod_pic")).put("remarks",vod.optString("vod_remarks")).put("typeName",type);
            out.put(item);
        }
    }
    static JSONArray episodes(String play) {
        List<JSONArray> groups=new ArrayList<>();
        for(String group:play.split("\\$\\$\\$")){
            JSONArray eps=new JSONArray();for(String ep:group.split("#")){
                int split=ep.indexOf('$');if(split<0)continue;String url=ep.substring(split+1).trim();
                try{validate(url);eps.put(url);}catch(IOException ignored){}
            }if(eps.length()>0)groups.add(eps);
        }
        for(JSONArray group:groups)for(int i=0;i<group.length();i++)if(group.optString(i).contains(".m3u8"))return group;
        return groups.isEmpty()?new JSONArray():groups.get(0);
    }
    JSONObject detail(String id,JSONObject source)throws Exception{
        if(id==null || !id.matches("[\\w-]+"))throw new IOException("无效影片 ID");
        JSONObject vod=null;JSONArray eps=new JSONArray();Exception failure=null;
        try{JSONArray list=new JSONObject(fetch(api(source.getString("url"),"ids",id))).optJSONArray("list");
            if(list!=null && list.length()>0){vod=list.getJSONObject(0);eps=episodes(vod.optString("vod_play_url"));}
        }catch(Exception e){failure=e;}
        if(eps.length()==0 && !source.optString("detail").isEmpty()){
            try{
                String html=fetch(source.getString("detail").replaceAll("/+$","")+"/index.php/vod/detail/id/"+id+".html");
                java.util.regex.Matcher m=java.util.regex.Pattern.compile("\\$(https?://[^\\\"'\\s]+?\\.m3u8(?:\\?[^\\\"'\\s<]*)?)").matcher(html);
                Set<String> seen=new LinkedHashSet<>();while(m.find())if(seen.add(m.group(1)))eps.put(m.group(1));
            }catch(Exception e){failure=e;}
        }
        if(eps.length()==0)throw new IOException(failure==null?"此来源未提供可播放剧集":failure.getMessage());
        JSONObject info=new JSONObject().put("sourceKey",source.getString("key")).put("sourceName",source.getString("name")).put("sourceUrl",source.getString("url"));
        if(vod!=null){String[][] fields={{"title","vod_name"},{"cover","vod_pic"},{"desc","vod_content"},{"year","vod_year"},{"area","vod_area"},{"remarks","vod_remarks"}};for(String[] f:fields)info.put(f[0],vod.optString(f[1]));}
        return new JSONObject().put("episodes",eps).put("videoInfo",info);
    }
    synchronized JSONObject favorites(String route,JSONObject body,String method)throws Exception{
        JSONArray all=new JSONArray(prefs.getString("favorites","[]"));
        if(method.equals("GET"))return new JSONObject().put("items",all);
        if(method.equals("POST")){
            JSONObject source=body.getJSONObject("source"),item=body.getJSONObject("item");String id=key(source.getString("url")+":"+item.getString("vodId"));
            for(int i=0;i<all.length();i++)if(all.getJSONObject(i).getString("id").equals(id))return new JSONObject().put("item",all.getJSONObject(i));
            JSONObject entry=new JSONObject().put("id",id).put("source",source).put("item",item);all.put(entry);
            if(!prefs.edit().putString("favorites",all.toString()).commit())throw new IOException("收藏保存失败");return new JSONObject().put("item",entry);
        }
        String id=route.substring(route.lastIndexOf('/')+1);JSONArray next=new JSONArray();for(int i=0;i<all.length();i++)if(!all.getJSONObject(i).getString("id").equals(id))next.put(all.getJSONObject(i));
        if(!prefs.edit().putString("favorites",next.toString()).commit())throw new IOException("收藏删除失败");return new JSONObject().put("success",true);
    }
}
