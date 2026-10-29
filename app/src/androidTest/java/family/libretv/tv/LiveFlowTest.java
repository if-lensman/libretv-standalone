package family.libretv.tv;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import junit.framework.TestCase;
import org.json.*;
import java.util.*;

/** Controlled local M3U + sliding HLS fixtures; never calls a public live source. */
@androidx.media3.common.util.UnstableApi
public final class LiveFlowTest extends TestCase {
    private android.app.Instrumentation instrument;
    private Context context;
    private LiveRepository repository;
    private Activity activity;
    private final Map<String,Map<String,?>> backups=new HashMap<>();
    private static final String ROOT="http://10.0.2.2:19001";
    @Override protected void setUp()throws Exception {
        instrument=InstrumentationRegistry.getInstrumentation();context=instrument.getTargetContext();
        for(String name:new String[]{"live-library","standalone-library","standalone-playback","standalone-player","github-updates"}) {SharedPreferences p=context.getSharedPreferences(name,0);backups.put(name,new HashMap<>(p.getAll()));p.edit().clear().commit();}
        context.getSharedPreferences("github-updates",0).edit().putLong("lastCheck",System.currentTimeMillis()).commit();
        repository=new LiveRepository(context);control("normal");
    }
    private void control(String mode)throws Exception {LocalRepository.fetch(ROOT+"/live/control?mode="+mode);}
    @Override protected void tearDown()throws Exception {
        if(activity!=null){instrument.runOnMainSync(activity::finish);instrument.waitForIdleSync();}
        for(Map.Entry<String,Map<String,?>> entry:backups.entrySet()) {
            SharedPreferences.Editor e=context.getSharedPreferences(entry.getKey(),0).edit().clear();
            for(Map.Entry<String,?> value:entry.getValue().entrySet()) {Object v=value.getValue();String k=value.getKey();if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Float)e.putFloat(k,(Float)v);}
            e.commit();
        }
        control("normal");
    }
    private String subscribe()throws Exception {String id=repository.saveSubscription(null,"测试订阅",ROOT+"/live/list");assertEquals(1,repository.refresh(true).updated);return id;}
    public void testCacheRefreshFavoritesStableAndEmptyFailureRetainsData()throws Exception {
        String source=subscribe();JSONArray original=repository.channels();assertEquals(2,original.length());assertEquals(2,original.getJSONObject(0).getJSONArray("lines").length());
        String channel=original.getJSONObject(0).getString("id");repository.toggleFavorite(channel);
        long stamp=repository.prefs.getLong("updated-"+source,0);assertTrue(stamp>0);
        assertEquals(0,repository.refresh(false).updated);
        control("renamed");assertEquals(1,repository.refresh(true).updated);assertEquals(channel,repository.channels().getJSONObject(0).getString("id"));assertTrue(repository.favorite(channel));assertTrue(repository.channels().getJSONObject(0).getString("name").contains("新名称"));
        String cached=repository.channels().toString();long before=repository.prefs.getLong("updated-"+source,0);
        control("empty");assertEquals(1,repository.refresh(true).failed);assertEquals(cached,repository.channels().toString());assertEquals(before,repository.prefs.getLong("updated-"+source,0));assertTrue(repository.favorite(channel));
        repository.enable(source,false);assertEquals(0,repository.channels().length());repository.enable(source,true);assertEquals(2,repository.channels().length());
        repository.remove(source);assertEquals(0,new LiveRepository(context).channels().length());
    }
    public void testLateRefreshCannotRestoreRemovedSubscription()throws Exception {
        String id=repository.saveSubscription(null,"慢订阅",ROOT+"/live/delayed");
        java.util.concurrent.FutureTask<LiveRepository.Refresh> task=new java.util.concurrent.FutureTask<>(()->repository.refresh(true));new Thread(task).start();
        long end=System.currentTimeMillis()+4000;while(repository.prefs.getLong("attempt-"+id,0)==0&&System.currentTimeMillis()<end)Thread.sleep(30);
        repository.remove(id);task.get(8,java.util.concurrent.TimeUnit.SECONDS);assertEquals(0,repository.subscriptions().length());assertEquals(0,repository.channels().length());assertFalse(repository.prefs.contains("cache-"+id));
    }
    public void testLocalImportAndLiveNeverWriteVodLibraryOrResume()throws Exception {
        LocalRepository vod=new LocalRepository(context);vod.importSources("{\"sources\":[{\"name\":\"影视夹具\",\"url\":\""+ROOT+"/cms\"}]}");
        context.getSharedPreferences("standalone-playback",0).edit().putString("history","keep-existing-playback").commit();
        Map<String,?> sources=new HashMap<>(context.getSharedPreferences("standalone-library",0).getAll()),progress=new HashMap<>(context.getSharedPreferences("standalone-playback",0).getAll());
        repository.importPlaylist("本地频道",LocalRepository.fetch(ROOT+"/live/list"));assertEquals(2,repository.channels().length());
        String id=repository.channels().getJSONObject(0).getString("id");repository.toggleFavorite(id);repository.remember(id);
        assertEquals(sources,context.getSharedPreferences("standalone-library",0).getAll());assertEquals(progress,context.getSharedPreferences("standalone-playback",0).getAll());
    }
    public void testFiveTabsKeepSearchAndMoveSourceControlsIntoSettings()throws Exception {
        new LocalRepository(context).importSources("{\"sources\":[{\"name\":\"影视夹具\",\"url\":\""+ROOT+"/cms\"}]}");subscribe();
        activity=instrument.startActivitySync(new Intent(context,PhoneActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        await("LibreTV");assertFalse(texts().toString().contains("无需 NAS"));
        assertFalse(texts().contains("来源配置"));assertTrue(texts().contains("首页"));
        clickDescription("底部导航 · 直播");await("测试直播一");clickDescription("底部导航 · 设置");await("影视来源");click("直播订阅");await("自动更新频道");
        clickDescription("底部导航 · 历史");await("观看历史");assertTrue(texts().contains("本机收藏夹"));assertTrue(texts().contains("播放列表"));
        clickDescription("底部导航 · 搜索");await("搜索电影、电视剧");
        instrument.runOnMainSync(()->{for(View v:viewsMain())if(v instanceof EditText)((EditText)v).setText("Fixture");});click("搜索");await("Fixture title");
        assertEquals(1,new LocalRepository(context).sources().length());
    }
    public void testSlidingHlsAndHeaderProtectedChannelPlayWithoutVodProgress()throws Exception {
        subscribe();Map<String,?> before=new HashMap<>(context.getSharedPreferences("standalone-playback",0).getAll());
        for(int i=0;i<2;i++){
            JSONObject c=repository.channels().getJSONObject(i);
            activity=instrument.startActivitySync(new Intent(context,LivePlayerActivity.class).putExtra("channelId",c.getString("id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            awaitPlaying();boolean[] live={false};instrument.runOnMainSync(()->live[0]=player().isCurrentMediaItemLive());assertTrue("Fixture was not treated as live HLS",live[0]);
            assertEquals(c.getString("id"),new LiveRepository(context).lastChannel());
            if(i==0){click("回到直播");awaitPlaying();click("下一个");awaitPlaying();assertEquals(repository.channels().getJSONObject(1).getString("id"),new LiveRepository(context).lastChannel());}
            instrument.runOnMainSync(activity::finish);instrument.waitForIdleSync();activity=null;
        }
        assertEquals(before,context.getSharedPreferences("standalone-playback",0).getAll());
    }
    public void testTvLiveEntranceUsesSameCacheAndDoesNotReplaceVod()throws Exception {
        subscribe();activity=instrument.startActivitySync(new Intent(context,LiveBrowseActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));await("测试直播一");assertTrue(texts().contains("返回选片"));assertTrue(texts().contains("频道分组"));
        Activity browse=activity;long end=android.os.SystemClock.uptimeMillis()+5000;View[] focus={null};
        while(android.os.SystemClock.uptimeMillis()<end){instrument.runOnMainSync(()->focus[0]=browse.getCurrentFocus());if(focus[0]!=null&&"播放频道 · 测试直播一".equals(String.valueOf(focus[0].getContentDescription())))break;Thread.sleep(100);}
        assertNotNull(focus[0]);assertEquals("播放频道 · 测试直播一",String.valueOf(focus[0].getContentDescription()));
        instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);instrument.waitForIdleSync();
        long openedUntil=android.os.SystemClock.uptimeMillis()+5000;
        while(!(activity instanceof LivePlayerActivity)&&android.os.SystemClock.uptimeMillis()<openedUntil){instrument.runOnMainSync(()->{for(Activity candidate:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(candidate instanceof LivePlayerActivity)activity=candidate;});Thread.sleep(100);}
        assertTrue("DPAD did not open live player",activity instanceof LivePlayerActivity);awaitPlaying();capture("tv-live-playing.png");
        assertEquals(2,repository.channels().length());instrument.runOnMainSync(browse::finish);
    }
    public void testLiveReconnectAfterSourceOutageAndRollingWindow()throws Exception {
        subscribe();context.getSharedPreferences("standalone-player",0).edit().putFloat("speed",2f).commit();
        String id=repository.channels().getJSONObject(0).getString("id");
        activity=instrument.startActivitySync(new Intent(context,LivePlayerActivity.class).putExtra("channelId",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));awaitPlaying();
        instrument.runOnMainSync(()->assertEquals(1f,player().getPlaybackParameters().speed));
        control("offline");long end=System.currentTimeMillis()+16000;boolean[] stalled={false};
        while(System.currentTimeMillis()<end){instrument.runOnMainSync(()->stalled[0]=player().getPlaybackState()!=Player.STATE_READY);if(stalled[0])break;Thread.sleep(200);}
        assertTrue("Source outage was not observed",stalled[0]);Thread.sleep(5000);control("normal");awaitPlaying();
        boolean[] playing={false};instrument.runOnMainSync(()->playing[0]=player().isPlaying());assertTrue("Live did not resume after outage",playing[0]);
        assertTrue(context.getSharedPreferences("standalone-playback",0).getAll().isEmpty());
    }
    public void testCaptureNewPhoneScreens()throws Exception {
        subscribe();LocalRepository vod=new LocalRepository(context);vod.importSources("{\"sources\":[{\"name\":\"影视夹具\",\"url\":\""+ROOT+"/cms\"}]}");
        JSONObject source=vod.sources().getJSONObject(0),item=new JSONObject().put("vodId","1").put("name","测试影片").put("pic","").put("sourceName","本机收藏");
        vod.favorites("/api/favorites",new JSONObject().put("source",source).put("item",item),"POST");
        activity=instrument.startActivitySync(new Intent(context,PhoneActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));await("搜索电影、电视剧");capture("tabs-search.png");
        clickDescription("底部导航 · 直播");await("测试直播一");capture("tabs-live.png");clickDescription("底部导航 · 历史");await("观看历史");capture("tabs-history.png");
        clickDescription("底部导航 · 设置");await("影视来源");capture("tabs-settings.png");click("直播订阅");await("自动更新频道");capture("tabs-live-subscriptions.png");
    }
    private void capture(String name)throws Exception {
        instrument.waitForIdleSync();Thread.sleep(250);android.graphics.Bitmap image=instrument.getUiAutomation().takeScreenshot();assertNotNull(image);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(context.getFilesDir(),name))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}image.recycle();
    }
    private ExoPlayer player(){try{java.lang.reflect.Field f=LivePlayerActivity.class.getDeclaredField("player");f.setAccessible(true);return (ExoPlayer)f.get(activity);}catch(Exception e){throw new RuntimeException(e);}}
    private void awaitPlaying()throws Exception {long end=System.currentTimeMillis()+20000;boolean[] playing={false};while(System.currentTimeMillis()<end){instrument.runOnMainSync(()->{ExoPlayer p=player();playing[0]=p!=null&&p.getPlaybackState()==Player.STATE_READY&&p.getCurrentPosition()>100;});if(playing[0])return;Thread.sleep(150);}fail("Live playback did not advance: "+texts());}
    private static void collect(View v,List<View> list){list.add(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),list);}
    private List<View> viewsMain(){List<View> out=new ArrayList<>();collect(activity.getWindow().getDecorView(),out);return out;}
    private List<View> views(){List<View>[] out=new List[]{null};instrument.runOnMainSync(()->out[0]=viewsMain());return out[0];}
    private Set<String> texts(){Set<String> result=new HashSet<>();for(View v:views())if(v instanceof TextView)result.add(((TextView)v).getText().toString());return result;}
    private void await(String value)throws Exception {long end=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<end){if(texts().contains(value))return;Thread.sleep(100);}fail("Missing UI: "+value+"; "+texts());}
    private void click(String value)throws Exception {for(View v:views())if(v instanceof TextView&&value.equals(((TextView)v).getText().toString())&&v.isClickable()){instrument.runOnMainSync(v::performClick);instrument.waitForIdleSync();return;}fail("Missing action: "+value);}
    private void clickDescription(String value)throws Exception {for(View v:views())if(value.contentEquals(v.getContentDescription()==null?"":v.getContentDescription())){instrument.runOnMainSync(v::performClick);instrument.waitForIdleSync();return;}fail("Missing navigation: "+value);}
}
