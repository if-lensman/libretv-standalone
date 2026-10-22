package family.libretv.tv;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.media3.exoplayer.ExoPlayer;
import junit.framework.TestCase;
import org.json.*;
import java.util.*;

@androidx.media3.common.util.UnstableApi
public final class TvHomeFlowTest extends TestCase {
    private Instrumentation instrument;
    private Context context;
    private Activity home,child;
    private final Map<String,Map<String,?>> backups=new HashMap<>();
    private static final String ROOT="http://10.0.2.2:19001";
    @Override protected void setUp()throws Exception {
        instrument=InstrumentationRegistry.getInstrumentation();context=instrument.getTargetContext();
        for(String name:new String[]{"recommendations","standalone-library","standalone-playback","standalone-player","live-library","github-updates"}){SharedPreferences p=context.getSharedPreferences(name,0);backups.put(name,new HashMap<>(p.getAll()));p.edit().clear().commit();}
        context.getSharedPreferences("github-updates",0).edit().putLong("lastCheck",System.currentTimeMillis()).commit();context.getSharedPreferences("standalone-player",0).edit().putBoolean("autoNext",false).commit();
        JSONArray data=RecommendationRepository.parse(LocalRepository.fetch(ROOT+"/recommendations"));SharedPreferences.Editor e=context.getSharedPreferences("recommendations",0).edit();for(String c:new String[]{"movie","tv","show","anime","top"})e.putString("items-"+c,data.toString()).putLong("updated-"+c,System.currentTimeMillis());e.commit();new LocalRepository(context).saveSources(new JSONArray().put(source()));
    }
    private JSONObject source()throws Exception{return new JSONObject().put("key","fixture").put("name","Fixture source").put("url",ROOT+"/cms").put("enabled",true);}
    private JSONObject item()throws Exception{return new JSONObject().put("vodId","1").put("name","Fixture title").put("sourceKey","fixture").put("pic",ROOT+"/poster.png");}
    @Override protected void tearDown()throws Exception {
        instrument.runOnMainSync(()->{if(child!=null)child.finish();if(home!=null)home.finish();});instrument.waitForIdleSync();
        for(Map.Entry<String,Map<String,?>> row:backups.entrySet()){SharedPreferences.Editor e=context.getSharedPreferences(row.getKey(),0).edit().clear();for(Map.Entry<String,?> p:row.getValue().entrySet()){String k=p.getKey();Object v=p.getValue();if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));}e.commit();}
    }
    private Activity resumed(Class<?> clazz){Activity[] found={null};instrument.runOnMainSync(()->{for(Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(clazz.isInstance(a))found[0]=a;});return found[0];}
    private Activity awaitActivity(Class<?> clazz)throws Exception{long end=SystemClock.uptimeMillis()+7000;while(SystemClock.uptimeMillis()<end){Activity a=resumed(clazz);if(a!=null)return a;Thread.sleep(100);}fail("Missing resumed "+clazz.getSimpleName());return null;}
    private void launch()throws Exception{instrument.startActivitySync(new Intent(context,MainActivity.class).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));home=awaitActivity(TvHomeActivity.class);awaitFocus("hero");}
    private Object field(Activity a,String key){try{java.lang.reflect.Field f=a.getClass().getDeclaredField(key);f.setAccessible(true);return f.get(a);}catch(Exception e){throw new RuntimeException(e);}}
    private void awaitFocus(String key)throws Exception{long end=SystemClock.uptimeMillis()+4000;while(SystemClock.uptimeMillis()<end){String[] focused={""};instrument.runOnMainSync(()->focused[0]=(String)field(home,"focusKey"));if(key.equals(focused[0])&&home.hasWindowFocus())return;Thread.sleep(100);}fail("Focus missing "+key);}
    private void click(String key){View v=((Map<String,View>)field(home,"targets")).get(key);assertNotNull(key,v);instrument.runOnMainSync(v::performClick);instrument.waitForIdleSync();}
    private void collect(View v,List<String> list){if(v instanceof TextView)list.add(((TextView)v).getText().toString());if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),list);}
    private List<String> texts(Activity a){List<String> texts=new ArrayList<>();instrument.runOnMainSync(()->collect(a.getWindow().getDecorView(),texts));return texts;}
    private void capture(String name)throws Exception{android.graphics.Bitmap b=instrument.getUiAutomation().takeScreenshot();try(java.io.FileOutputStream o=new java.io.FileOutputStream(new java.io.File(context.getFilesDir(),name))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,o);}b.recycle();}
    public void testLauncherHomeAndRemoteSearchAndLiveNavigation()throws Exception{
        launch();assertEquals(24,((JSONArray)field(home,"items")).length());assertTrue(texts(home).contains("今晚看什么"));Thread.sleep(500);capture("tv-home-cached.png");
        instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);instrument.waitForIdleSync();assertEquals("quick-search",field(home,"focusKey"));instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_LEFT);awaitFocus("hero");instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN);awaitFocus("film-0");instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP);awaitFocus("hero");
        click("quick-search");child=awaitActivity(MainActivity.class);assertTrue(texts(child).contains("搜索"));instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);awaitActivity(TvHomeActivity.class);child=null;
        click("quick-live");child=awaitActivity(LiveBrowseActivity.class);instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);awaitActivity(TvHomeActivity.class);child=null;
        assertEquals(1,new LocalRepository(context).sources().length());
    }
    public void testRecommendationCardRoutesToExistingIncrementalSearch()throws Exception{
        launch();new LocalRepository(context).saveSources(new JSONArray().put(source().put("url",ROOT+"/fast")));
        click("film-0");child=awaitActivity(MainActivity.class);long end=SystemClock.uptimeMillis()+6000;
        while(SystemClock.uptimeMillis()<end){if(texts(child).contains("推荐测试影片 1 fast"))break;Thread.sleep(100);}
        assertTrue(texts(child).contains("推荐测试影片 1 fast"));assertEquals("推荐测试影片 1",field(child,"query"));
    }
    public void testContinueUsesFreshDetailAndSavedEpisodePosition()throws Exception{
        new PlaybackStore(context).save(source(),item(),"Fixture title",1,6000,30000);launch();assertTrue(texts(home).contains("Fixture title"));click("hero");child=awaitActivity(MainActivity.class);
        long end=SystemClock.uptimeMillis()+18000;boolean[] playing={false};
        while(SystemClock.uptimeMillis()<end){instrument.runOnMainSync(()->{ExoPlayer p=(ExoPlayer)field(child,"player");playing[0]=p!=null&&p.isPlaying()&&p.getCurrentPosition()>=5000;});if(playing[0])break;Thread.sleep(100);}
        assertTrue("Saved playback did not resume",playing[0]);assertEquals(1,field(child,"episodeIndex"));
    }
    public void testMoreCategoriesHistoryAndSettingsUseSharedRepositories()throws Exception{
        new PlaybackStore(context).save(source(),item(),"History fixture",0,5000,30000);launch();
        click("more");assertEquals("more",field(home,"page"));click("category-tv");assertEquals("tv",field(home,"category"));assertTrue(((Map)field(home,"targets")).containsKey("film-23"));
        instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);instrument.waitForIdleSync();assertEquals("home",field(home,"page"));click("nav-history");assertTrue(texts(home).stream().anyMatch(t->t.contains("History fixture")));click("back-home");
        click("nav-settings");child=awaitActivity(MainActivity.class);List<View> boxes=new ArrayList<>();instrument.runOnMainSync(()->findBoxes(child.getWindow().getDecorView(),boxes));assertFalse(boxes.isEmpty());instrument.runOnMainSync(boxes.get(0)::performClick);assertFalse(new RecommendationRepository(context).enabled());
        instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);awaitActivity(TvHomeActivity.class);child=null;assertTrue(texts(home).stream().anyMatch(t->t.contains("推荐已关闭")));
    }
    public void testFailedRefreshRetainsSharedCacheAndRemoteFocus()throws Exception{
        launch();java.lang.reflect.Field repository=TvHomeActivity.class.getDeclaredField("recommendations");repository.setAccessible(true);repository.set(home,new RecommendationRepository(context,ROOT+"/recommendations/offline"));
        context.getSharedPreferences("recommendations",0).edit().putLong("updated-movie",0).commit();
        java.lang.reflect.Method render=TvHomeActivity.class.getDeclaredMethod("render");render.setAccessible(true);
        instrument.runOnMainSync(()->{try{render.invoke(home);}catch(Exception e){throw new RuntimeException(e);}});
        long end=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<end){if(texts(home).stream().anyMatch(t->t.contains("显示上次片单")))break;Thread.sleep(100);}
        assertTrue(texts(home).stream().anyMatch(t->t.contains("显示上次片单")));assertEquals(24,((JSONArray)field(home,"items")).length());assertTrue(((Map)field(home,"targets")).containsKey("quick-live"));awaitFocus("hero");
    }
    public void testEmptyDisabledHomeStillHasWorkingSearch()throws Exception{
        context.getSharedPreferences("recommendations",0).edit().clear().putBoolean("enabled",false).commit();launch();assertTrue(texts(home).contains("今晚，想看点什么？"));assertTrue(texts(home).stream().anyMatch(t->t.contains("推荐已关闭")));
        click("hero");child=awaitActivity(MainActivity.class);assertEquals("",field(child,"query"));assertEquals("search",child.getIntent().getStringExtra("tvDestination"));
    }
    private void findBoxes(View v,List<View> out){if(v instanceof CheckBox&&"显示首页推荐".contentEquals(((CheckBox)v).getText()))out.add(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)findBoxes(((ViewGroup)v).getChildAt(i),out);}
}
