package family.libretv.tv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.JSONArray;
import org.json.JSONObject;

/** Local viewing history holds content identity and progress, never expiring media URLs. */
final class PlaybackStore {
    private static final Object LOCK = new Object();
    private final SharedPreferences prefs;
    PlaybackStore(Context context) {
        prefs = context.getApplicationContext().getSharedPreferences("standalone-playback", 0);
        synchronized (LOCK) {
            // One-time upgrade: keep existing viewing progress visible in the new list.
            if(!prefs.contains("playlist")) prefs.edit().putString("playlist",all().toString()).commit();
        }
    }
    static String identity(JSONObject source, JSONObject item) {
        return source.optString("url").replaceAll("/+$", "") + "|" + item.optString("vodId");
    }
    JSONArray all() {
        synchronized (LOCK) {
            try { return new JSONArray(prefs.getString("history", "[]")); }
            catch (Exception ignored) { return new JSONArray(); }
        }
    }
    JSONObject find(JSONObject source, JSONObject item) {
        String id = identity(source, item);
        JSONArray entries = all();
        for (int i = 0; i < entries.length(); i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry != null && id.equals(entry.optString("id"))) return entry;
        }
        JSONArray queued = playlist();
        for (int i = 0; i < queued.length(); i++) {
            JSONObject entry = queued.optJSONObject(i);
            if (entry != null && id.equals(entry.optString("id")) && entry.has("position")) return entry;
        }
        return null;
    }
    JSONArray playlist() {
        synchronized (LOCK) {
            try { return new JSONArray(prefs.getString("playlist", "[]")); }
            catch (Exception ignored) { return new JSONArray(); }
        }
    }
    boolean contains(JSONObject source, JSONObject item) {
        String id = identity(source, item); JSONArray entries = playlist();
        for (int i = 0; i < entries.length(); i++) if(id.equals(entries.optJSONObject(i).optString("id"))) return true;
        return false;
    }
    private static JSONObject cleanItem(JSONObject item) throws Exception {
        JSONObject clean = new JSONObject();
        for (String field : new String[]{"vodId", "name", "pic", "sourceKey", "sourceName", "sourceUrl", "remarks"}) clean.put(field,item.optString(field));
        return clean;
    }
    void add(JSONObject source, JSONObject item, String title) throws Exception {
        synchronized (LOCK) {
            if (contains(source,item)) return;
            JSONArray old = playlist();
            if (old.length() >= 200) throw new java.io.IOException("播放列表最多保存 200 部影片，请先移除部分条目");
            JSONObject saved = find(source,item);
            JSONObject entry = new JSONObject().put("id",identity(source,item)).put("source",source)
                .put("item",cleanItem(item)).put("title",title);
            if(saved != null) for(String field:new String[]{"index","position","duration","updatedAt"}) entry.put(field,saved.opt(field));
            JSONArray next = new JSONArray().put(entry);
            for(int i=0;i<old.length();i++) next.put(old.getJSONObject(i));
            if(!prefs.edit().putString("playlist",next.toString()).commit()) throw new java.io.IOException("播放列表保存失败");
        }
    }
    void remove(String id) throws Exception {
        synchronized (LOCK) {
            JSONArray next = new JSONArray(), old = playlist();
            for(int i=0;i<old.length();i++) if(!id.equals(old.getJSONObject(i).optString("id"))) next.put(old.getJSONObject(i));
            if(!prefs.edit().putString("playlist",next.toString()).commit()) throw new java.io.IOException("播放列表删除失败");
        }
    }
    void save(JSONObject source, JSONObject item, String title, int index, long position, long duration) {
        if (source == null || item == null || item.optString("vodId").isEmpty() || index < 0 || position < 0 || (position == 0 && duration <= 0)) return;
        synchronized (LOCK) {
            try {
                String id = identity(source, item);
                JSONObject clean = cleanItem(item);
                JSONObject entry = new JSONObject().put("id", id).put("source", source).put("item", clean)
                        .put("title", title).put("index", index).put("position", position)
                        .put("duration", Math.max(0, duration)).put("updatedAt", System.currentTimeMillis());
                JSONArray next = new JSONArray().put(entry), old = all();
                for (int i = 0; i < old.length() && next.length() < 50; i++)
                    if (!id.equals(old.getJSONObject(i).optString("id"))) next.put(old.getJSONObject(i));
                JSONArray queued = playlist();
                for(int i=0;i<queued.length();i++) if(id.equals(queued.getJSONObject(i).optString("id"))) queued.put(i,entry);
                prefs.edit().putString("history", next.toString()).putString("playlist",queued.toString()).apply();
            } catch (Exception ignored) { /* Playback must continue even if an old history entry is malformed. */ }
        }
    }
    void clear() { synchronized (LOCK) { prefs.edit().remove("history").apply(); } }
    static long resumePosition(long position, long duration) {
        if (position <= 0) return 0;
        long margin = Math.min(15000, duration / 20);
        return duration > 0 && position >= duration - margin ? 0 : position;
    }
}
