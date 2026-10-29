package family.libretv.tv;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.Player;
import junit.framework.TestCase;
import org.json.*;
import java.util.*;

/** API30 landscape AVD + leanback entry; all navigation activation uses DPAD_CENTER. */
@androidx.media3.common.util.UnstableApi
public final class StandaloneTvFlowTest extends TestCase {
    private Instrumentation instrument;
    private Context context;
    private MainActivity activity;
    private LocalRepository repo;
    private final Map<String,Map<String,?>> backups=new HashMap<>();
    private final String root="http://10.0.2.2:19001";
    @Override protected void setUp()throws Exception {
        instrument=InstrumentationRegistry.getInstrumentation();context=instrument.getTargetContext();
        for(String name:new String[]{"standalone-library","standalone-playback","MainActivity","family.libretv.tv.MainActivity","recommendations"}) {
            SharedPreferences prefs=context.getSharedPreferences(name,0);backups.put(name,new HashMap<>(prefs.getAll()));prefs.edit().clear().commit();
        }
        repo=new LocalRepository(context);
    }
    @Override protected void tearDown()throws Exception {
        close();
        for(String name:backups.keySet()) {
            SharedPreferences.Editor e=context.getSharedPreferences(name,0).edit().clear();
            for(Map.Entry<String,?> entry:backups.get(name).entrySet()) {
                Object v=entry.getValue();String k=entry.getKey();
                if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);
                else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));else if(v instanceof Float)e.putFloat(k,(Float)v);
                else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);
            }e.commit();
        }
    }
    private JSONObject source(String path)throws Exception {return new JSONObject().put("key",LocalRepository.key(root+path)).put("name","TV source "+path).put("url",root+path).put("enabled",true);}
    private JSONObject item()throws Exception {return new JSONObject().put("vodId","1").put("name","Fixture title").put("sourceKey",source("/cms").getString("key")).put("sourceName","TV source /cms");}
    private void launch()throws Exception {
        activity=(MainActivity)instrument.startActivitySync(new Intent(context,MainActivity.class).putExtra("tvDestination","search").addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));await("搜索");
        long end=System.currentTimeMillis()+8000;boolean[] ready={false};
        while(!ready[0] && System.currentTimeMillis()<end) {
            instrument.runOnMainSync(()->{
                if(activity.isDestroyed())for(Activity candidate:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
                    if(candidate instanceof MainActivity)activity=(MainActivity)candidate;
                View focus=activity.getCurrentFocus();
                ready[0]=activity.hasWindowFocus() && focus instanceof Button && "搜索".contentEquals(((Button)focus).getText()) && focus.getWidth()>0 && focus.getHeight()>0;
            });if(!ready[0])Thread.sleep(75);
        }
        assertTrue("TV entry has not finished its first layout",ready[0]);
    }
    private void close()throws Exception {
        if(activity!=null){instrument.runOnMainSync(()->activity.finish());instrument.waitForIdleSync();activity=null;}
    }
    private Object field(String name) {try{java.lang.reflect.Field f=MainActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}catch(Exception e){throw new RuntimeException(e);}}
    private List<View> views(){List<View> list=new ArrayList<>();instrument.runOnMainSync(()->collect(activity.getWindow().getDecorView(),list));return list;}
    private void collect(View v,List<View> out){out.add(v);if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int n=0;n<g.getChildCount();n++)collect(g.getChildAt(n),out);}}
    private List<String> texts(){List<String> out=new ArrayList<>();for(View v:views())if(v instanceof TextView)out.add(((TextView)v).getText().toString());return out;}
    private void await(String text)throws Exception {long end=System.currentTimeMillis()+12000;while(System.currentTimeMillis()<end){if(texts().contains(text))return;Thread.sleep(75);}fail("Missing "+text+": "+texts());}
    private void awaitPrefix(String text)throws Exception {long end=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<end){if(texts().stream().anyMatch(t->t.startsWith(text)))return;Thread.sleep(75);}fail("Missing prefix "+text+": "+texts());}
    private View focused(){View[] current={null};instrument.runOnMainSync(()->current[0]=activity.getCurrentFocus());return current[0];}
    private void key(int key){instrument.sendKeyDownUpSync(key);instrument.waitForIdleSync();}
    private void click(String text)throws Exception {
        View target=null;long end=System.currentTimeMillis()+8000;
        while(target==null && System.currentTimeMillis()<end) {
            instrument.runOnMainSync(()->{
                if(activity.isDestroyed())for(Activity candidate:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
                    if(candidate instanceof MainActivity)activity=(MainActivity)candidate;
            });
            if(activity.hasWindowFocus()) target=views().stream().filter(v->v instanceof TextView && (text.contentEquals(((TextView)v).getText())||((TextView)v).getText().toString().startsWith(text+"    ›")) && !("搜索".equals(text)&&"顶部导航 · 搜索".contentEquals(String.valueOf(v.getContentDescription()))) && v.isShown() && v.getWidth()>0 && v.getHeight()>0 && v.isEnabled()).findFirst().orElse(null);
            if(target==null)Thread.sleep(75);
        }
        assertNotNull("Missing ready button "+text,target);View action=target;
        instrument.runOnMainSync(action::requestFocus);assertEquals(action,focused());key(KeyEvent.KEYCODE_DPAD_CENTER);
    }
    private void search(String query)throws Exception {
        instrument.runOnMainSync(()->{for(View v:viewsOnMain())if(v instanceof EditText)((EditText)v).setText(query);});click("搜索");
    }
    private List<View> viewsOnMain(){List<View> out=new ArrayList<>();collect(activity.getWindow().getDecorView(),out);return out;}
    private ExoPlayer player(){return (ExoPlayer)field("player");}
    private void ready()throws Exception {
        long end=System.currentTimeMillis()+15000;boolean[] ready={false};
        while(System.currentTimeMillis()<end){instrument.runOnMainSync(()->ready[0]=player()!=null && player().getPlaybackState()==Player.STATE_READY);if(ready[0])return;Thread.sleep(75);}fail("Player not ready: "+texts());
    }
    private void screenshot(String name)throws Exception {
        instrument.waitForIdleSync();Thread.sleep(250);
        android.graphics.Bitmap b=instrument.getUiAutomation().takeScreenshot();
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),name))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}b.recycle();
    }
    public void testIncrementalSearchKeepsRemoteFocusAndDoesNotStealPage()throws Exception {
        repo.saveSources(new JSONArray().put(source("/slow")).put(source("/cms")));launch();
        key(KeyEvent.KEYCODE_DPAD_RIGHT);assertTrue(focused() instanceof Button && "本机收藏夹".contentEquals(((Button)focused()).getText()));
        key(KeyEvent.KEYCODE_DPAD_RIGHT);assertTrue(focused() instanceof Button && "播放列表".contentEquals(((Button)focused()).getText()));
        key(KeyEvent.KEYCODE_DPAD_CENTER);await("片单还是空的，去找一部想看的吧。");key(KeyEvent.KEYCODE_BACK);
        key(KeyEvent.KEYCODE_DPAD_RIGHT);key(KeyEvent.KEYCODE_DPAD_CENTER);await("还没有收藏。喜欢的影片，点一下收藏就会出现在这里。");key(KeyEvent.KEYCODE_BACK);
        search("Fixture");await("Fixture title");
        assertTrue("Search focus was stolen",focused() instanceof Button && "搜索".contentEquals(((Button)focused()).getText()));
        key(KeyEvent.KEYCODE_DPAD_DOWN);View card=focused();assertTrue(card instanceof PosterCard);
        await("Fixture slow");assertEquals("Append stole focus",card,focused());
        instrument.waitForIdleSync();Thread.sleep(250);
        instrument.runOnMainSync(()->{
            android.graphics.Rect visible=new android.graphics.Rect();TextView metadata=((PosterCard)card).metadata;
            assertTrue("Poster metadata is outside viewport",metadata.getGlobalVisibleRect(visible));
            assertTrue("Poster metadata is clipped",visible.height()>=metadata.getHeight());
        });
        screenshot("tv-incremental-results.png");
        key(KeyEvent.KEYCODE_DPAD_RIGHT);assertTrue(focused() instanceof PosterCard);key(KeyEvent.KEYCODE_DPAD_UP);assertTrue(focused() instanceof Button);
        search("Second");awaitPrefix("「Second」 ·");click("本机收藏夹");await("还没有收藏。喜欢的影片，点一下收藏就会出现在这里。");Thread.sleep(4500);
        assertTrue("Background result switched page",texts().contains("我的收藏"));
    }
    public void testDetailCanOpenWhileSlowSourceStillSearching()throws Exception {
        repo.saveSources(new JSONArray().put(source("/slow")).put(source("/cms")));launch();search("Fixture");await("Fixture title");
        assertTrue(texts().stream().anyMatch(t->t.contains("正在搜索…")));
        key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_DPAD_CENTER);await("第 1 集");Thread.sleep(4500);
        assertTrue("Search replaced detail",texts().contains("第 1 集"));click("加入片单");await("移出片单");assertEquals(1,new PlaybackStore(context).playlist().length());
    }
    public void testPlaylistResumeAndEpisodeControlsPersistOnReopen()throws Exception {
        JSONObject source=source("/cms"),item=item();repo.saveSources(new JSONArray().put(source));
        PlaybackStore store=new PlaybackStore(context);store.add(source,item,"Fixture title");store.save(source,item,"Fixture title",1,4000,12000);
        launch();click("播放列表");await("第 2 集 · 已看 0分4秒");screenshot("tv-local-playlist.png");
        click("继续播放");ready();long[] pos={0};instrument.runOnMainSync(()->{player().pause();pos[0]=player().getCurrentPosition();});
        assertEquals(1,field("episodeIndex"));assertTrue("Seek was not restored: "+pos[0],pos[0]>=3900);
        key(KeyEvent.KEYCODE_MENU);key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertTrue("Cannot navigate right to previous episode",focused() instanceof Button && "上一集".contentEquals(((Button)focused()).getText()));
        key(KeyEvent.KEYCODE_DPAD_CENTER);ready();assertEquals(0,field("episodeIndex"));
        key(KeyEvent.KEYCODE_MENU);key(KeyEvent.KEYCODE_DPAD_RIGHT);
        assertTrue("Cannot navigate right to next episode",focused() instanceof Button && "下一集".contentEquals(((Button)focused()).getText()));
        key(KeyEvent.KEYCODE_DPAD_CENTER);ready();assertEquals(1,field("episodeIndex"));
        instrument.runOnMainSync(()->{player().seekTo(5000);player().pause();});screenshot("tv-player-controls.png");close();
        assertEquals(1,new PlaybackStore(context).find(source,item).getInt("index"));
        launch();click("播放列表");click("继续播放");ready();instrument.runOnMainSync(()->{player().pause();pos[0]=player().getCurrentPosition();});assertTrue(pos[0]>=4900);
    }
    public void testAutoNextAndLastEpisodeBoundary()throws Exception {
        JSONObject source=source("/cms"),item=item();repo.saveSources(new JSONArray().put(source));new PlaybackStore(context).add(source,item,"Fixture title");launch();click("播放列表");click("开始播放");ready();
        instrument.runOnMainSync(()->{player().seekTo(player().getDuration()-150);player().play();});
        long end=System.currentTimeMillis()+10000;while((int)field("episodeIndex")!=1 && System.currentTimeMillis()<end)Thread.sleep(100);assertEquals(1,field("episodeIndex"));ready();
        instrument.runOnMainSync(()->{player().seekTo(player().getDuration()-150);player().play();});Thread.sleep(1200);assertEquals(1,field("episodeIndex"));
    }
    public void testSourceFlagsAndLocalFavoritesRemainIndependent()throws Exception {
        JSONObject good=source("/cms"),bad=source("/bad").put("enabled",false);repo.saveSources(new JSONArray().put(good).put(bad));
        repo.favorites("/api/favorites",new JSONObject().put("source",good).put("item",item()),"POST");launch();search("Fixture");await("Fixture title");
        awaitPrefix("「Fixture」 · 2 个结果 · 1 / 1 个来源完成 · 0 个不可用");click("本机收藏夹");await("我的收藏");
        // Library menu behavior is exercised by PiankeDesignFlowTest. Source switches remain independent.
        assertEquals(1,repo.favorites("/api/favorites",null,"GET").getJSONArray("items").length());
        click("设置");click("视频来源");
        for(View v:views())if(v instanceof Switch && ((Switch)v).isChecked()){View choice=v;instrument.runOnMainSync(choice::requestFocus);key(KeyEvent.KEYCODE_DPAD_CENTER);}
        close();launch();search("Fixture");await("请在设置中启用至少一个来源");
        assertEquals(1,repo.favorites("/api/favorites",null,"GET").getJSONArray("items").length());
    }
    public void testCancelledSearchCannotAppendToNewKeyword()throws Exception {
        repo.saveSources(new JSONArray().put(source("/slow")).put(source("/fast")));launch();search("First");await("First fast");click("停止搜索");search("Second");await("Second fast");await("Second slow");
        assertFalse(texts().contains("First slow"));assertFalse(texts().contains("First fast"));
    }
}
