package family.libretv.tv;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import org.json.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Runs real Views, Media3 and compatibility hot paths on the API23 AVD. No public source requests. */
@androidx.media3.common.util.UnstableApi
public final class MinimumApiCompatibilityTest extends TestCase {
    private static final String ROOT="http://10.0.2.2:19001";
    private Instrumentation instrument;
    private Context context;
    private Activity activity;
    private final Map<String,Map<String,?>> backups=new HashMap<>();
    @Override protected void setUp()throws Exception {
        instrument=InstrumentationRegistry.getInstrumentation();context=instrument.getTargetContext();
        // Avoid the API23 AVD setup failure observed with a six-entry filled-new-array/range literal.
        for(String name:"standalone-library,standalone-playback,standalone-player,live-library,recommendations,github-updates".split(",")){
            SharedPreferences p=context.getSharedPreferences(name,0);backups.put(name,new HashMap<>(p.getAll()));p.edit().clear().commit();
        }
        context.getSharedPreferences("github-updates",0).edit().putLong("lastCheck",System.currentTimeMillis()).commit();
        context.getSharedPreferences("standalone-player",0).edit().putBoolean("autoNext",false).commit();
        LocalRepository.fetch(ROOT+"/live/control?mode=normal");
    }
    @Override protected void tearDown()throws Exception {
        close();
        for(Map.Entry<String,Map<String,?>> row:backups.entrySet()){
            SharedPreferences.Editor e=context.getSharedPreferences(row.getKey(),0).edit().clear();
            for(Map.Entry<String,?> pair:row.getValue().entrySet()){
                String k=pair.getKey();Object v=pair.getValue();
                if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);
                else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));
            }e.commit();
        }
    }
    private JSONObject source()throws Exception{return new JSONObject().put("key","fixture").put("name","Fixture").put("url",ROOT+"/cms").put("enabled",true);}
    private JSONObject item()throws Exception{return new JSONObject().put("vodId","1").put("name","Fixture title").put("sourceKey","fixture");}
    private Object field(String name){try{java.lang.reflect.Field f=activity.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(activity);}catch(Exception e){throw new RuntimeException(e);}}
    private void launch(Class<? extends Activity> type,Intent intent){intent.setClass(context,type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);activity=instrument.startActivitySync(intent);instrument.waitForIdleSync();}
    private void close()throws Exception {if(activity==null)return;Activity old=activity;instrument.runOnMainSync(old::finish);long end=SystemClock.uptimeMillis()+3000;while(!old.isDestroyed()&&SystemClock.uptimeMillis()<end)Thread.sleep(50);activity=null;}
    private void playing()throws Exception {
        long end=SystemClock.uptimeMillis()+15000;boolean[] ok={false};
        while(SystemClock.uptimeMillis()<end){instrument.runOnMainSync(()->{ExoPlayer p=(ExoPlayer)field("player");ok[0]=p!=null&&p.isPlaying()&&p.getCurrentPosition()>500&&p.getVideoFormat()!=null;});if(ok[0])return;Thread.sleep(100);}fail("Media3 never decoded playing video");
    }
    private void capture(String name)throws Exception {Thread.sleep(400);android.graphics.Bitmap b=instrument.getUiAutomation().takeScreenshot();try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),name))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
    public void testSearchFavoritesAndPlaybackProgressPersist()throws Exception {
        LocalRepository repo=new LocalRepository(context);repo.saveSources(new JSONArray().put(source()));
        assertTrue(repo.search("Fixture title",repo.sources()).getJSONArray("list").length()>0);
        repo.favorites("/favorites",new JSONObject().put("source",source()).put("item",item()),"POST");
        assertEquals(1,new LocalRepository(context).favorites("/favorites",null,"GET").getJSONArray("items").length());
        PlaybackStore store=new PlaybackStore(context);store.add(source(),item(),"Fixture title");store.save(source(),item(),"Fixture title",0,6000,30000);
        assertEquals(6000,new PlaybackStore(context).find(source(),item()).getLong("position"));assertEquals(1,store.playlist().length());
    }
    public void testTvRecommendationRefreshAndRemoteFocus()throws Exception {
        JSONArray old=new JSONArray().put(new JSONObject().put("id","old").put("title","Old recommendation").put("cover",ROOT+"/poster.png").put("rate","8.0"));
        context.getSharedPreferences("recommendations",0).edit().putString("items-movie",old.toString()).putLong("updated-movie",System.currentTimeMillis()).commit();
        launch(TvHomeActivity.class,new Intent());
        java.lang.reflect.Field repository=TvHomeActivity.class.getDeclaredField("recommendations");repository.setAccessible(true);repository.set(activity,new RecommendationRepository(context,ROOT+"/recommendations"));
        context.getSharedPreferences("recommendations",0).edit().putLong("updated-movie",0).commit();
        java.lang.reflect.Method render=TvHomeActivity.class.getDeclaredMethod("render");render.setAccessible(true);
        instrument.runOnMainSync(()->{try{render.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});
        long end=SystemClock.uptimeMillis()+6000;boolean[] fresh={false};
        while(SystemClock.uptimeMillis()<end){instrument.runOnMainSync(()->fresh[0]=((JSONArray)field("items")).length()==24);if(fresh[0])break;Thread.sleep(75);}assertTrue("API23 catalogue refresh failed",fresh[0]);
        boolean[] laidOut={false};end=SystemClock.uptimeMillis()+3000;
        while(SystemClock.uptimeMillis()<end){instrument.runOnMainSync(()->{Map<String,View> targets=(Map<String,View>)field("targets");laidOut[0]=activity.hasWindowFocus()&&targets.get("hero").getWidth()>0;});if(laidOut[0])break;Thread.sleep(50);}assertTrue(laidOut[0]);
        instrument.runOnMainSync(()->{Map<String,View> targets=(Map<String,View>)field("targets");assertTrue(targets.containsKey("film-3"));targets.get("hero").requestFocusFromTouch();});
        instrument.waitForIdleSync();Thread.sleep(200);instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);instrument.waitForIdleSync();
        assertEquals("quick-search",field("focusKey"));capture("api23-tv-home.png");
    }
    public void testLiveParsingGroupingPlaybackAndRemoteChannelSwitch()throws Exception {
        LiveRepository repo=new LiveRepository(context);repo.importPlaylist("API23 synthetic live",LocalRepository.fetch(ROOT+"/live/list"));assertEquals(2,repo.channels().length());
        launch(LiveBrowseActivity.class,new Intent());assertEquals(2,((List)field("visible")).size());assertTrue(((Map)field("controls")).containsKey("group:央视"));close();
        launch(LivePlayerActivity.class,new Intent().putExtra("channelId",repo.channels().getJSONObject(0).getString("id")).putExtra("tvRemote",true));playing();
        assertEquals(Boolean.TRUE,field("renderedFrame"));capture("api23-live-playing.png");
        instrument.runOnMainSync(()->{((androidx.media3.ui.PlayerView)field("video")).hideController();activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_DOWN,KeyEvent.KEYCODE_DPAD_RIGHT));activity.dispatchKeyEvent(new KeyEvent(KeyEvent.ACTION_UP,KeyEvent.KEYCODE_DPAD_RIGHT));});
        assertEquals(1,field("index"));playing();assertEquals(0,new PlaybackStore(context).all().length());
    }
    public void testDirectPlayerDecodesMp4AndRecordsProgress()throws Exception {
        launch(DirectPlayerActivity.class,new Intent().putExtra("url",ROOT+"/sample.mp4").putExtra("title","API23 video").putExtra("source",source().toString()).putExtra("item",item().toString()));
        playing();capture("api23-vod-playing.png");close();assertTrue(new PlaybackStore(context).find(source(),item()).getLong("position")>300);
    }
    public void testUpdaterCallbackAndApiManifestOnMinimumAndroid()throws Exception {
        JSONObject metadata=new JSONObject().put("packageName",BuildConfig.APPLICATION_ID).put("versionCode",BuildConfig.VERSION_CODE+1).put("versionName","compat-test").put("minSdk",23).put("size",1)
            .put("sha256",new String(new char[64]).replace('\0','a')).put("downloadUrl",AppUpdater.REPOSITORY+"/releases/download/v1.0.0/LibreTV-1.0.0.apk");
        byte[] api=new JSONObject().put("encoding","base64").put("content",android.util.Base64.encodeToString(metadata.toString().getBytes(StandardCharsets.UTF_8),android.util.Base64.DEFAULT)).toString().getBytes(StandardCharsets.UTF_8);
        AppUpdater updater=new AppUpdater("gitee","",(uri,proxy)->new HttpURLConnection(uri.toURL()){
            @Override public int getResponseCode(){return 200;}@Override public InputStream getInputStream(){return new ByteArrayInputStream(api);}
            @Override public void disconnect(){}@Override public boolean usingProxy(){return false;}@Override public void connect(){}
        });String[] status={null};updater.routeStatus=text->status[0]=text;
        assertEquals(BuildConfig.VERSION_CODE+1,updater.check().code);assertEquals("Gitee 镜像",status[0]);
    }
    public void testPhoneEntryAndDlnaInitialization()throws Exception {
        context.getSharedPreferences("recommendations",0).edit().putBoolean("enabled",false).commit();
        launch(PhoneActivity.class,new Intent());assertFalse(activity.isFinishing());
        android.graphics.drawable.Drawable icon=context.getPackageManager().getApplicationIcon(context.getPackageName());assertTrue("Launcher icon failed to resolve on minimum Android",icon.getIntrinsicWidth()>0);
        com.yinnho.upnpcast.DLNACast.INSTANCE.init(context);try{assertNotNull(com.yinnho.upnpcast.DLNACast.INSTANCE.getState());}finally{com.yinnho.upnpcast.DLNACast.INSTANCE.cleanup();}
    }
}
