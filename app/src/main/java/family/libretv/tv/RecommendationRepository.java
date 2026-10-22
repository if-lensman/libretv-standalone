package family.libretv.tv;

import android.content.Context;
import android.content.SharedPreferences;
import org.json.*;
import java.net.HttpURLConnection;
import java.io.IOException;

/** Public catalogue metadata only. Playback always goes through the user's configured sources. */
final class RecommendationRepository {
    static final long TTL=6*60*60*1000L;
    private final SharedPreferences prefs;
    private final String endpoint;
    RecommendationRepository(Context context){this(context,"https://movie.douban.com/j/search_subjects");}
    RecommendationRepository(Context context,String endpoint){prefs=context.getSharedPreferences("recommendations",0);this.endpoint=endpoint;}
    boolean enabled(){return prefs.getBoolean("enabled",true);}
    void setEnabled(boolean enabled){prefs.edit().putBoolean("enabled",enabled).apply();}
    static String label(String category){switch(category){case "tv":return "热门剧集";case "show":return "热门综艺";case "anime":return "热门动漫";case "top":return "高分佳作";default:return "热门电影";}}
    private static String tag(String category){switch(category){case "tv":return "热门";case "show":return "综艺";case "anime":return "日本动画";case "top":return "豆瓣高分";case "movie":return "热门";default:throw new IllegalArgumentException("未知推荐分类");}}
    JSONArray cached(String category){try{return new JSONArray(prefs.getString("items-"+category,"[]"));}catch(Exception ignored){return new JSONArray();}}
    boolean fresh(String category,long now){long age=now-prefs.getLong("updated-"+category,0);return age>=0&&age<TTL&&cached(category).length()>0;}
    JSONArray load(String category)throws Exception {
        if(fresh(category,System.currentTimeMillis()))return cached(category);
        String type=(category.equals("movie")||category.equals("top"))?"movie":"tv";
        String url=endpoint+"?type="+type+"&tag="+NasClient.encode(tag(category))+"&sort=recommend&page_limit=24&page_start=0";
        HttpURLConnection connection=LocalRepository.open(LocalRepository.validate(url));
        try{
            connection.setRequestProperty("Referer","https://movie.douban.com/");
            connection.setRequestProperty("Accept","application/json");
            if(connection.getResponseCode()!=200)throw new IOException("推荐暂时无法更新");
            JSONArray items=parse(LocalRepository.read(connection.getInputStream(),1024*1024));
            if(items.length()==0)throw new IOException("暂无推荐内容");
            // Only a complete, non-empty response replaces the last successful catalogue.
            prefs.edit().putString("items-"+category,items.toString()).putLong("updated-"+category,System.currentTimeMillis()).commit();
            return items;
        }finally{connection.disconnect();}
    }
    static JSONArray parse(String payload)throws Exception {
        JSONArray raw=new JSONObject(payload).getJSONArray("subjects"),items=new JSONArray();
        java.util.Set<String> ids=new java.util.HashSet<>();
        for(int i=0;i<raw.length()&&items.length()<24;i++){
            JSONObject source=raw.optJSONObject(i);if(source==null||source.isNull("id")||source.isNull("title"))continue;
            String title=source.optString("title").trim(),id=source.optString("id").trim();
            if(title.isEmpty()||title.length()>200||id.isEmpty()||!ids.add(id))continue;
            String cover=source.optString("cover");try{LocalRepository.validate(cover);}catch(Exception ignored){cover="";}
            String rating=source.optString("rate");if(!rating.matches("(?:[0-9](?:\\.[0-9])?|10(?:\\.0)?)")||rating.equals("0")||rating.equals("0.0"))rating="";
            items.put(new JSONObject().put("id",id).put("title",title).put("cover",cover).put("rating",rating));
        }
        return items;
    }
}
