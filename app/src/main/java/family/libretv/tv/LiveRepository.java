package family.libretv.tv;

import android.content.*;
import java.io.*;
import java.net.*;
import java.util.*;
import org.json.*;

/** Live subscriptions/cache only. Never writes VOD sources, favorites or playback progress. */
final class LiveRepository {
    private static final Object LOCK=new Object();
    final SharedPreferences prefs;
    LiveRepository(Context context) throws Exception {
        prefs=context.getApplicationContext().getSharedPreferences("live-library",0);
        try(InputStream in=context.getAssets().open("default-live-sources.json")) {
            JSONArray defaults=new JSONObject(LocalRepository.read(in,1024*1024)).optJSONArray("subscriptions");
            if(defaults!=null)synchronized(LOCK){
                JSONArray all=subscriptions();Set<String> seen=new HashSet<>(prefs.getStringSet("seenDefaults",Collections.emptySet()));
                for(int i=0;i<defaults.length();i++) {
                    JSONObject entry=defaults.getJSONObject(i);String url=entry.getString("url");LocalRepository.validate(url);
                    if(seen.add(url)&&findByUrl(all,url)==null&&all.length()<10)all.put(subscription(LocalRepository.key(url),entry.optString("name","直播订阅"),url,true));
                }
                if(!prefs.edit().putString("subscriptions",all.toString()).putStringSet("seenDefaults",seen).commit())throw new IOException("直播订阅保存失败");
            }
        }catch(FileNotFoundException ignored){/* Public/no-config builds still open normally. */}
    }
    private static JSONObject subscription(String id,String name,String url,boolean enabled)throws Exception {
        return new JSONObject().put("id",id).put("name",name).put("url",url).put("enabled",enabled);
    }
    JSONArray subscriptions()throws Exception{return new JSONArray(prefs.getString("subscriptions","[]"));}
    private static JSONObject findByUrl(JSONArray all,String url){for(int i=0;i<all.length();i++){JSONObject s=all.optJSONObject(i);if(s!=null&&url.equals(s.optString("url")))return s;}return null;}
    String saveSubscription(String id,String name,String url)throws Exception {
        url=url.trim();LocalRepository.validate(url);if(url.length()>4096)throw new IOException("订阅地址过长");
        synchronized(LOCK){
            JSONArray all=subscriptions();JSONObject duplicate=findByUrl(all,url);
            if(id==null&&duplicate!=null)return duplicate.getString("id");
            if(duplicate!=null&&!duplicate.optString("id").equals(id))throw new IOException("已存在相同订阅");
            if(id==null)id=LocalRepository.key(url);
            boolean replaced=false;JSONArray next=new JSONArray();
            for(int i=0;i<all.length();i++){
                JSONObject old=all.getJSONObject(i);
                if(old.getString("id").equals(id)){next.put(subscription(id,name.trim().isEmpty()?"直播订阅":name.trim(),url,old.optBoolean("enabled",true)));replaced=true;}else next.put(old);
            }
            if(!replaced){if(all.length()>=10)throw new IOException("最多保存10个直播订阅");next.put(subscription(id,name.trim().isEmpty()?"直播订阅":name.trim(),url,true));}
            if(!prefs.edit().putString("subscriptions",next.toString()).remove("attempt-"+id).commit())throw new IOException("直播订阅保存失败");return id;
        }
    }
    void remove(String id)throws Exception{synchronized(LOCK){JSONArray next=new JSONArray(),all=subscriptions();for(int i=0;i<all.length();i++)if(!id.equals(all.getJSONObject(i).getString("id")))next.put(all.getJSONObject(i));prefs.edit().putString("subscriptions",next.toString()).remove("cache-"+id).remove("updated-"+id).remove("url-"+id).remove("attempt-"+id).remove("error-"+id).commit();}}
    void enable(String id,boolean enabled)throws Exception{synchronized(LOCK){JSONArray all=subscriptions();for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString("id")))all.getJSONObject(i).put("enabled",enabled);prefs.edit().putString("subscriptions",all.toString()).commit();}}
    boolean automatic(){return prefs.getBoolean("automatic",true);}
    long interval(){return prefs.getLong("interval",6*60*60*1000L);}
    void setAutomatic(boolean enabled){prefs.edit().putBoolean("automatic",enabled).apply();}
    void setInterval(long ms){prefs.edit().putLong("interval",ms).apply();}
    boolean due(JSONObject s,long now){String id=s.optString("id");return automatic()&&s.optBoolean("enabled",true)&&!s.optString("url").isEmpty()&&now-prefs.getLong("attempt-"+id,0)>60000&&(now-prefs.getLong("updated-"+id,0)>=interval()||!s.optString("url").equals(prefs.getString("url-"+id,"")));}
    static final class Refresh {int updated,failed;String error="";}
    Refresh refresh(boolean force)throws Exception {
        Refresh result=new Refresh();JSONArray all=subscriptions();
        for(int i=0;i<all.length();i++) {
            if(Thread.currentThread().isInterrupted())throw new InterruptedIOException("更新已取消");
            JSONObject s=all.getJSONObject(i);String id=s.getString("id"),url=s.optString("url");
            if(!s.optBoolean("enabled",true)||url.isEmpty()||(!force&&!due(s,System.currentTimeMillis())))continue;
            prefs.edit().putLong("attempt-"+id,System.currentTimeMillis()).commit();
            try {
                HttpURLConnection conn=LocalRepository.open(LocalRepository.validate(url));String text;
                try{if(conn.getResponseCode()!=200)throw new IOException("频道订阅暂时无法访问");text=LocalRepository.read(conn.getInputStream(),M3uPlaylist.MAX_BYTES);}finally{conn.disconnect();}
                JSONArray channels=encode(id,M3uPlaylist.parse(text));
                synchronized(LOCK){
                    JSONObject current=findByUrl(subscriptions(),url);
                    if(current==null||!id.equals(current.optString("id"))||!current.optBoolean("enabled",true))continue;
                    if(!prefs.edit().putString("cache-"+id,channels.toString()).putString("url-"+id,url).putLong("updated-"+id,System.currentTimeMillis()).remove("error-"+id).commit())throw new IOException("频道缓存保存失败");
                }
                result.updated++;
            }catch(Exception e){result.failed++;result.error=e.getMessage()==null?"频道更新失败":e.getMessage();prefs.edit().putString("error-"+id,result.error).apply();}
        }
        return result;
    }
    private static JSONArray encode(String source,List<M3uPlaylist.Channel> parsed)throws Exception {
        LinkedHashMap<String,JSONObject> unique=new LinkedHashMap<>();Map<String,Set<String>> seen=new HashMap<>();
        for(M3uPlaylist.Channel c:parsed){
            String identity=c.tvgId.isEmpty()?c.name.trim().toLowerCase(Locale.ROOT):c.tvgId.trim();String id=LocalRepository.key(source+"|"+identity);
            JSONObject channel=unique.get(id);
            if(channel==null){channel=new JSONObject().put("id",id).put("name",c.name).put("group",c.group).put("logo",c.logo).put("subscription",source).put("lines",new JSONArray());unique.put(id,channel);seen.put(id,new HashSet<>());}
            if(seen.get(id).add(c.url+"|"+c.userAgent+"|"+c.referer))channel.getJSONArray("lines").put(new JSONObject().put("url",c.url).put("userAgent",c.userAgent).put("referer",c.referer));
        }
        return new JSONArray(unique.values());
    }
    String importPlaylist(String name,String text)throws Exception {
        String id="file-"+LocalRepository.key(name);JSONArray parsed=encode(id,M3uPlaylist.parse(text));
        synchronized(LOCK){JSONArray all=subscriptions(),next=new JSONArray();boolean found=false;for(int i=0;i<all.length();i++){JSONObject s=all.getJSONObject(i);if(s.optString("id").equals(id))found=true;next.put(s);}if(!found){if(all.length()>=10)throw new IOException("最多保存10个直播订阅");next.put(subscription(id,name,"",true));}if(!prefs.edit().putString("subscriptions",next.toString()).putString("cache-"+id,parsed.toString()).putLong("updated-"+id,System.currentTimeMillis()).commit())throw new IOException("频道导入失败");}return id;
    }
    JSONArray channels()throws Exception {JSONArray all=subscriptions(),result=new JSONArray();for(int i=0;i<all.length();i++){JSONObject s=all.getJSONObject(i);if(!s.optBoolean("enabled",true))continue;JSONArray cache=new JSONArray(prefs.getString("cache-"+s.getString("id"),"[]"));for(int j=0;j<cache.length();j++)result.put(cache.getJSONObject(j));}return result;}
    JSONObject channel(String id)throws Exception {JSONArray all=channels();for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).optString("id")))return all.getJSONObject(i);return null;}
    boolean favorite(String id){return prefs.getStringSet("favorites",Collections.emptySet()).contains(id);}
    void toggleFavorite(String id){synchronized(LOCK){Set<String> set=new HashSet<>(prefs.getStringSet("favorites",Collections.emptySet()));if(!set.remove(id))set.add(id);prefs.edit().putStringSet("favorites",set).commit();}}
    void remember(String id){prefs.edit().putString("lastChannel",id).apply();}
    String lastChannel(){return prefs.getString("lastChannel","");}
    private static String lineKey(JSONObject line)throws Exception{return LocalRepository.key(line.optString("url")+"|"+line.optString("userAgent")+"|"+line.optString("referer"));}
    int preferredLine(JSONObject channel,long now)throws Exception {
        JSONArray lines=channel.getJSONArray("lines");JSONObject health=new JSONObject(prefs.getString("health-"+channel.getString("id"),"{}"));
        int preferred=-1,available=-1;
        for(int i=0;i<lines.length();i++){
            String key=lineKey(lines.getJSONObject(i));boolean usable=health.optLong("cooldown-"+key,0)<=now;
            if(usable&&available<0)available=i;
            if(key.equals(health.optString("preferred")))preferred=i;
        }
        if(preferred>=0&&health.optLong("cooldown-"+lineKey(lines.getJSONObject(preferred)),0)<=now)return preferred;
        return available>=0?available:Math.max(0,preferred);
    }
    void markLineFailed(JSONObject channel,int index,long now)throws Exception {
        synchronized(LOCK){String key="health-"+channel.getString("id");JSONObject health=new JSONObject(prefs.getString(key,"{}"));health.put("cooldown-"+lineKey(channel.getJSONArray("lines").getJSONObject(index)),now+10*60*1000L);prefs.edit().putString(key,health.toString()).apply();}
    }
    void markLineStable(JSONObject channel,int index,long startupMs)throws Exception {
        synchronized(LOCK){String key="health-"+channel.getString("id"),line=lineKey(channel.getJSONArray("lines").getJSONObject(index));JSONObject health=new JSONObject(prefs.getString(key,"{}"));health.put("preferred",line).put("startupMs",startupMs).put("lastSuccess",System.currentTimeMillis()).remove("cooldown-"+line);prefs.edit().putString(key,health.toString()).apply();}
    }
    static Map<String,String> headers(JSONObject line){Map<String,String> result=new HashMap<>();String ua=line.optString("userAgent"),ref=line.optString("referer");if(!ua.isEmpty())result.put("User-Agent",ua);if(!ref.isEmpty())result.put("Referer",ref);return result;}
    static final class StreamHttpException extends IOException {
        final int status;StreamHttpException(int status){super("频道请求失败（HTTP "+status+"）");this.status=status;}
    }
    static String mime(JSONObject line)throws Exception {
        String url=line.getString("url");LocalRepository.validate(url);
        if(URI.create(url).getPath().toLowerCase(Locale.ROOT).endsWith(".m3u8"))return androidx.media3.common.MimeTypes.APPLICATION_M3U8;
        HttpURLConnection conn=LocalRepository.open(URI.create(url));for(Map.Entry<String,String> h:headers(line).entrySet())conn.setRequestProperty(h.getKey(),h.getValue());conn.setRequestProperty("Range","bytes=0-1023");
        try{int status=conn.getResponseCode();if(status<200||status>=300)throw new StreamHttpException(status);String type=conn.getContentType();if(type!=null&&type.toLowerCase(Locale.ROOT).contains("mpegurl"))return androidx.media3.common.MimeTypes.APPLICATION_M3U8;try(InputStream in=conn.getInputStream()){byte[] b=new byte[1024];int n=in.read(b);if(n>0&&new String(b,0,n,java.nio.charset.StandardCharsets.UTF_8).trim().startsWith("#EXTM3U"))return androidx.media3.common.MimeTypes.APPLICATION_M3U8;}return null;}finally{conn.disconnect();}
    }
}
