package family.libretv.tv;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import junit.framework.TestCase;
import org.json.*;
import java.util.*;

/** Cached catalogue UI and real remote events against local synthetic VOD/live streams. */
@androidx.media3.common.util.UnstableApi
public final class HomeRecommendationFlowTest extends TestCase {
    private Instrumentation instrument;
    private Context context;
    private Activity activity;
    private final Map<String,Map<String,?>> backups=new HashMap<>();
    private static final String ROOT="http://10.0.2.2:19001";
    @Override protected void setUp()throws Exception {
        instrument=InstrumentationRegistry.getInstrumentation();context=instrument.getTargetContext();
        for(String name:new String[]{"recommendations","live-library","standalone-library","standalone-playback","standalone-player","github-updates"}){
            SharedPreferences p=context.getSharedPreferences(name,0);backups.put(name,new HashMap<>(p.getAll()));p.edit().clear().commit();
        }
        context.getSharedPreferences("github-updates",0).edit().putLong("lastCheck",System.currentTimeMillis()).commit();
        LocalRepository.fetch(ROOT+"/live/control?mode=normal");
    }
    @Override protected void tearDown()throws Exception {
        if(activity!=null){instrument.runOnMainSync(activity::finish);instrument.waitForIdleSync();}
        for(Map.Entry<String,Map<String,?>> row:backups.entrySet()){
            SharedPreferences.Editor e=context.getSharedPreferences(row.getKey(),0).edit().clear();
            for(Map.Entry<String,?> pair:row.getValue().entrySet()){String k=pair.getKey();Object v=pair.getValue();if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));}e.commit();
        }
    }
    private void seed()throws Exception {
        JSONArray items=RecommendationRepository.parse(LocalRepository.fetch(ROOT+"/recommendations"));
        SharedPreferences.Editor e=context.getSharedPreferences("recommendations",0).edit();
        for(String category:new String[]{"movie","top","tv","show","anime"})e.putString("items-"+category,items.toString()).putLong("updated-"+category,System.currentTimeMillis());e.commit();
        new LocalRepository(context).saveSources(new JSONArray().put(new JSONObject().put("key","fixture").put("name","Fixture source").put("url",ROOT+"/fast").put("enabled",true)));
    }
    private void launchPhone()throws Exception {seed();activity=instrument.startActivitySync(new Intent(context,PhoneActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));awaitText("热门电影",5000);}
    private Object field(String name){try{java.lang.reflect.Field f=activity.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(activity);}catch(Exception e){throw new RuntimeException(e);}}
    private void collect(View v,List<View> list){list.add(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),list);}
    private List<View> views(){List<View> list=new ArrayList<>();instrument.runOnMainSync(()->collect(activity.getWindow().getDecorView(),list));return list;}
    private boolean hasText(String value){for(View v:views())if(v instanceof TextView&&((TextView)v).getText().toString().contains(value))return true;return false;}
    private void awaitText(String value,long ms)throws Exception {long end=SystemClock.uptimeMillis()+ms;while(SystemClock.uptimeMillis()<end){if(hasText(value))return;Thread.sleep(100);}fail("Missing text "+value);}
    private void click(String value){for(View v:views())if(v instanceof TextView&&value.contentEquals(((TextView)v).getText())&&v.isShown()){instrument.runOnMainSync(v::performClick);instrument.waitForIdleSync();return;}fail("Missing action "+value);}
    private void capture(String name)throws Exception {android.graphics.Bitmap b=instrument.getUiAutomation().takeScreenshot();try(java.io.FileOutputStream o=new java.io.FileOutputStream(new java.io.File(context.getFilesDir(),name))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,o);}b.recycle();}
    public void testCatalogueParsingAndFailedRefreshPreservesCache()throws Exception {
        RecommendationRepository repo=new RecommendationRepository(context,ROOT+"/recommendations");JSONArray items=repo.load("movie");assertEquals(24,items.length());assertTrue(repo.fresh("movie",System.currentTimeMillis()));
        context.getSharedPreferences("recommendations",0).edit().putLong("updated-movie",0).commit();
        try{new RecommendationRepository(context,ROOT+"/recommendations/offline").load("movie");fail("Failure expected");}catch(java.io.IOException expected){}
        assertEquals(items.toString(),repo.cached("movie").toString());
        JSONArray parsed=RecommendationRepository.parse("{\"subjects\":[{\"id\":null,\"title\":\"bad\"},{\"id\":\"1\",\"title\":\"valid\",\"cover\":\"javascript:bad\",\"rate\":\"0\"},{\"id\":\"1\",\"title\":\"duplicate\"}]}");
        assertEquals(1,parsed.length());assertEquals("",parsed.getJSONObject(0).getString("cover"));assertEquals("",parsed.getJSONObject(0).getString("rating"));
        assertFalse(repo.fresh("movie",System.currentTimeMillis()+RecommendationRepository.TTL));
    }
    public void testHomeHasFiveTabsAndCardUsesExistingSearch()throws Exception {
        launchPhone();Thread.sleep(1000);
        for(String label:new String[]{"首页","搜索","直播","历史","设置"}){
            boolean found=false;for(View v:views())if(("底部导航 · "+label).contentEquals(String.valueOf(v.getContentDescription())))found=true;assertTrue(label,found);
        }
        capture("home-recommendations-fixture.png");
        for(View v:views())if("搜索影片 · 推荐测试影片 1".contentEquals(String.valueOf(v.getContentDescription()))){instrument.runOnMainSync(v::performClick);break;}
        awaitText("推荐测试影片 1 fast",8000);assertEquals("search",field("page"));
        assertTrue(context.getSharedPreferences("standalone-playback",0).getAll().isEmpty());
    }
    public void testHomeSearchAndSeparateSearchTab()throws Exception {
        launchPhone();EditText input=null;for(View v:views())if(v instanceof EditText){input=(EditText)v;break;}assertNotNull(input);EditText entry=input;
        instrument.runOnMainSync(()->entry.setText("首页搜索测试"));click("搜索");awaitText("首页搜索测试 fast",8000);
        for(View v:views())if("底部导航 · 搜索".contentEquals(String.valueOf(v.getContentDescription()))){instrument.runOnMainSync(v::performClick);break;}
        assertEquals("search-landing",field("page"));assertFalse(hasText("热门电影"));assertTrue(hasText("搜索电影、电视剧"));
    }
    public void testMoreAndCategoryAndDisableRecommendations()throws Exception {
        launchPhone();click("更多 →");assertEquals("recommendations",field("page"));assertTrue(hasText("推荐测试影片 24"));
        click("返回首页");click("电视剧");awaitText("热门剧集",5000);assertFalse(hasText("高分佳作"));
        for(View v:views())if("底部导航 · 设置".contentEquals(String.valueOf(v.getContentDescription()))){instrument.runOnMainSync(v::performClick);break;}
        for(View v:views())if(v instanceof CheckBox&&"显示推荐片单".contentEquals(((CheckBox)v).getText())){instrument.runOnMainSync(v::performClick);break;}
        for(View v:views())if("底部导航 · 首页".contentEquals(String.valueOf(v.getContentDescription()))){instrument.runOnMainSync(v::performClick);break;}
        awaitText("推荐已关闭",3000);assertFalse(hasText("热门剧集"));
        assertTrue(context.getSharedPreferences("live-library",0).getAll().isEmpty());
    }
    private void launchLive(boolean tv)throws Exception {
        LiveRepository repo=new LiveRepository(context);repo.importPlaylist("Remote fixture",LocalRepository.fetch(ROOT+"/live/list"));
        String id=repo.channels().getJSONObject(0).getString("id");
        activity=instrument.startActivitySync(new Intent(context,LivePlayerActivity.class).putExtra("channelId",id).putExtra("tvRemote",tv).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));awaitPlaying();
    }
    private void awaitPlaying()throws Exception {long end=SystemClock.uptimeMillis()+20000;boolean[] ok={false};while(SystemClock.uptimeMillis()<end){instrument.runOnMainSync(()->{ExoPlayer p=(ExoPlayer)field("player");ok[0]=p!=null&&p.isPlaying()&&p.getCurrentPosition()>100;});if(ok[0])return;Thread.sleep(100);}fail("No live playback");}
    public void testTvRemoteChangesChannelsWithControlsShownAndHiddenAndWraps()throws Exception {
        launchLive(true);assertEquals(0,field("index"));
        instrument.runOnMainSync(()->((PlayerView)field("video")).hideController());
        instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);awaitPlaying();assertEquals(1,field("index"));
        instrument.runOnMainSync(()->((PlayerView)field("video")).showController());
        instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);awaitPlaying();assertEquals(0,field("index"));
        instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);awaitPlaying();assertEquals(1,field("index"));
        instrument.runOnMainSync(()->activity.dispatchKeyEvent(new KeyEvent(0,0,KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT,2)));assertEquals(1,field("index"));
        capture("tv-remote-channel-switch.png");assertTrue(context.getSharedPreferences("standalone-playback",0).getAll().isEmpty());
    }
    public void testPhoneLiveDoesNotUseTvChannelKeys()throws Exception {
        launchLive(false);instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);assertEquals(0,field("index"));
    }
}
