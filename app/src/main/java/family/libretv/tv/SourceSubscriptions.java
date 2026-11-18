package family.libretv.tv;

import android.content.SharedPreferences;
import java.io.IOException;
import java.util.*;
import org.json.*;

/** Persistent VOD subscription links. Downloads never hold the source storage lock. */
final class SourceSubscriptions {
    static final String[] FREQUENCIES = {"每次打开 App", "每天", "每周", "仅手动"};
    private static final long DAY = 24*60*60*1000L;
    private static final Set<String> IN_FLIGHT = new HashSet<>();
    private static final Set<String> CHECKED_THIS_OPEN = new HashSet<>();
    private final LocalRepository local;
    private final SharedPreferences prefs;
    SourceSubscriptions(LocalRepository local) { this.local=local; prefs=local.prefs; }
    JSONArray entries() throws Exception { return new JSONArray(prefs.getString("sourceSubscriptions", "[]")); }
    static void beginOpen() { synchronized(LocalRepository.DEFAULTS_LOCK) { CHECKED_THIS_OPEN.clear(); } }
    int frequency() { return Math.max(0, Math.min(3, prefs.getInt("sourceRefreshFrequency", 0))); }
    void setFrequency(int value) { prefs.edit().putInt("sourceRefreshFrequency", value).apply(); }
    void remove(String url) throws Exception {
        synchronized(LocalRepository.DEFAULTS_LOCK) {
            JSONArray next=new JSONArray(), all=entries();
            for(int i=0;i<all.length();i++) if(!url.equals(all.getJSONObject(i).getString("url"))) next.put(all.getJSONObject(i));
            if(!prefs.edit().putString("sourceSubscriptions",next.toString()).commit()) throw new IOException("订阅保存失败");
        }
    }
    int subscribe(String url) throws Exception {
        LocalRepository.validate(url);
        JSONArray incoming=LocalRepository.parseSources(LocalRepository.fetchSubscription(url));
        return merge(url,incoming,true);
    }
    private JSONObject find(JSONArray entries,String url) {
        for(int i=0;i<entries.length();i++) if(url.equals(entries.optJSONObject(i).optString("url"))) return entries.optJSONObject(i);
        return null;
    }
    /** Retain saved sources when upstream removes them; never resurrect user-deleted known keys. */
    private int merge(String url,JSONArray incoming,boolean create) throws Exception {
        synchronized(LocalRepository.DEFAULTS_LOCK) {
            JSONArray subscriptions=entries(); JSONObject entry=find(subscriptions,url);
            if(entry==null) {
                if(!create)return 0;
                if(subscriptions.length()>=10)throw new IOException("最多保存 10 个影视订阅");
                entry=new JSONObject().put("url",url);subscriptions.put(entry);
            }
            Set<String> known=new HashSet<>(); JSONArray keys=entry.optJSONArray("knownKeys");
            if(keys!=null)for(int i=0;i<keys.length();i++)known.add(keys.getString(i));
            JSONArray all=local.sources(); Map<String,Integer> present=new HashMap<>();
            for(int i=0;i<all.length();i++)present.put(all.getJSONObject(i).getString("key"),i);
            int added=0;
            for(int i=0;i<incoming.length();i++) {
                JSONObject source=incoming.getJSONObject(i); String key=source.getString("key");
                Integer index=present.get(key);
                if(index!=null) {
                    source.put("enabled",all.getJSONObject(index).optBoolean("enabled",true));
                    all.put(index,source);
                } else if(!known.contains(key)) {
                    if(all.length()>=50)throw new IOException("最多保存 50 个源，请先清理后重试");
                    present.put(key,all.length());all.put(source);added++;
                }
                known.add(key);
            }
            entry.put("knownKeys",new JSONArray(known)).put("updated",System.currentTimeMillis()).put("error","");
            if(!prefs.edit().putString("sources",all.toString()).putString("sourceSubscriptions",subscriptions.toString()).commit())
                throw new IOException("订阅保存失败");
            return added;
        }
    }
    boolean due(JSONObject entry,long now) {
        int frequency=frequency();
        if(frequency==3)return false;
        if(frequency==0) synchronized(LocalRepository.DEFAULTS_LOCK) { if(CHECKED_THIS_OPEN.contains(entry.optString("url")))return false; }
        long attempt=entry.optLong("attempt"),updated=entry.optLong("updated");
        if((attempt>updated || !entry.optString("error").isEmpty()) && now>=attempt && now-attempt<60000)return false;
        return frequency==0 || updated==0 || now<updated || now-updated>=(frequency==2?7*DAY:DAY);
    }
    static final class Refresh {
        int updated,failed;
        String message() { return failed>0?"部分订阅更新失败，已保留原来源，请查看订阅状态":updated>0?"影视订阅已更新":"暂无需要更新的影视订阅"; }
    }
    Refresh refresh(boolean force) throws Exception {
        Refresh result=new Refresh(); JSONArray all=entries();
        for(int i=0;i<all.length();i++) {
            JSONObject entry=all.getJSONObject(i);String url=entry.getString("url");
            synchronized(LocalRepository.DEFAULTS_LOCK) {
                while(IN_FLIGHT.contains(url))LocalRepository.DEFAULTS_LOCK.wait();
                JSONObject current=find(entries(),url);
                if(current==null||(!force&&!due(current,System.currentTimeMillis())))continue;
                IN_FLIGHT.add(url);CHECKED_THIS_OPEN.add(url);
            }
            try {
                state(url,"attempt",System.currentTimeMillis());
                merge(url,LocalRepository.parseSources(LocalRepository.fetchSubscription(url)),false);result.updated++;
            } catch(Exception e) {
                result.failed++;
                // Do not persist server response bodies or URLs containing credentials in errors.
                state(url,"error","下载或解析失败，请检查网络与订阅内容后重试");
            } finally { synchronized(LocalRepository.DEFAULTS_LOCK) { IN_FLIGHT.remove(url);LocalRepository.DEFAULTS_LOCK.notifyAll(); } }
        }
        return result;
    }
    private void state(String url,String field,Object value) throws Exception {
        synchronized(LocalRepository.DEFAULTS_LOCK) {
            JSONArray all=entries();JSONObject entry=find(all,url);if(entry==null)return;
            entry.put(field,value);
            if(!prefs.edit().putString("sourceSubscriptions",all.toString()).commit())throw new IOException("订阅状态保存失败");
        }
    }
    static String status(JSONObject entry) {
        long updated=entry.optLong("updated");
        String time=updated==0?"尚未更新":"上次成功："+new java.text.SimpleDateFormat("MM-dd HH:mm",Locale.getDefault()).format(new Date(updated));
        return time+(entry.optString("error").isEmpty()?"":" · "+entry.optString("error"));
    }
}
