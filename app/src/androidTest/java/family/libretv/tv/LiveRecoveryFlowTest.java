package family.libretv.tv;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import org.json.*;
import java.util.*;

/** Controlled HTTP errors, slow startup and real live-window jumps; never a public source. */
@androidx.media3.common.util.UnstableApi
public final class LiveRecoveryFlowTest extends TestCase {
    private Instrumentation instrument;
    private Context context;
    private Activity activity;
    private LiveRepository repo;
    private final Map<String,Map<String,?>> backups=new HashMap<>();
    private static final String ROOT="http://10.0.2.2:19001";
    @Override protected void setUp()throws Exception {
        instrument=InstrumentationRegistry.getInstrumentation();context=instrument.getTargetContext();
        for(String name:new String[]{"live-library","standalone-library","standalone-playback","standalone-player"}){SharedPreferences p=context.getSharedPreferences(name,0);backups.put(name,new HashMap<>(p.getAll()));p.edit().clear().commit();}
        repo=new LiveRepository(context);control("normal");
    }
    @Override protected void tearDown()throws Exception {
        close();control("normal");
        for(Map.Entry<String,Map<String,?>> row:backups.entrySet()){
            SharedPreferences.Editor e=context.getSharedPreferences(row.getKey(),0).edit().clear();
            for(Map.Entry<String,?> pair:row.getValue().entrySet()){String k=pair.getKey();Object v=pair.getValue();if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));}e.commit();
        }
    }
    private void control(String mode)throws Exception{LocalRepository.fetch(ROOT+"/live/control?mode="+mode);}
    private void close(){if(activity!=null){instrument.runOnMainSync(activity::finish);instrument.waitForIdleSync();activity=null;}}
    private Object field(String name){try{java.lang.reflect.Field f=LivePlayerActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}catch(Exception e){throw new RuntimeException(e);}}
    private ExoPlayer player(){return (ExoPlayer)field("player");}
    private void awaitPlaying()throws Exception {
        long end=SystemClock.uptimeMillis()+20000;boolean[] ok={false};
        while(SystemClock.uptimeMillis()<end){instrument.runOnMainSync(()->ok[0]=player()!=null&&player().isPlaying()&&player().getCurrentPosition()>100);if(ok[0])return;Thread.sleep(100);}fail("No advancing playback: "+texts());
    }
    private JSONObject playlist(String kind)throws Exception {repo.importPlaylist("Recovery fixture",LocalRepository.fetch(ROOT+"/live/failure-list?case="+kind));return repo.channels().getJSONObject(0);}
    private void launch(JSONObject channel){activity=instrument.startActivitySync(new Intent(context,LivePlayerActivity.class).putExtra("channelId",channel.optString("id")).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));}
    private void collect(View v,List<View> list){list.add(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),list);}
    private List<View> views(){List<View> list=new ArrayList<>();instrument.runOnMainSync(()->collect(activity.getWindow().getDecorView(),list));return list;}
    private List<String> texts(){List<String> values=new ArrayList<>();for(View v:views())if(v instanceof TextView)values.add(((TextView)v).getText().toString());return values;}
    private void click(String label){for(View v:views())if(v instanceof Button&&label.contentEquals(((Button)v).getText())&&v.isShown()){instrument.runOnMainSync(v::performClick);return;}fail("Missing "+label);}
    private void awaitTerminal()throws Exception {long end=SystemClock.uptimeMillis()+10000;while(SystemClock.uptimeMillis()<end){boolean[] done={false};instrument.runOnMainSync(()->done[0]=(Boolean)field("terminal"));if(done[0])return;Thread.sleep(100);}fail("Retries never terminated: "+texts());}
    private void capture(String name)throws Exception {android.graphics.Bitmap b=instrument.getUiAutomation().takeScreenshot();try(java.io.FileOutputStream o=new java.io.FileOutputStream(new java.io.File(context.getFilesDir(),name))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,o);}b.recycle();}
    public void testBadFirstLineAutomaticallySwitchesAndStableLineIsRemembered()throws Exception {
        JSONObject c=playlist("failover");launch(c);awaitPlaying();assertEquals(1,field("lineIndex"));
        JSONObject health=new JSONObject(repo.prefs.getString("health-"+c.getString("id"),"{}"));assertEquals("",health.optString("preferred"));
        long end=SystemClock.uptimeMillis()+40000;
        while(SystemClock.uptimeMillis()<end){health=new JSONObject(repo.prefs.getString("health-"+c.getString("id"),"{}"));if(!health.optString("preferred").isEmpty())break;Thread.sleep(200);}
        assertFalse("No stable-success memory",health.optString("preferred").isEmpty());assertTrue(health.getLong("startupMs")>=0);assertEquals(1,new LiveRepository(context).preferredLine(c,System.currentTimeMillis()));
        close();launch(c);awaitPlaying();assertEquals(1,field("lineIndex"));assertTrue(context.getSharedPreferences("standalone-playback",0).getAll().isEmpty());
    }
    public void testAllBadLinesStopWithPersistentErrorAndManualRetryStartsNewRound()throws Exception {
        launch(playlist("allbad"));awaitTerminal();int generation=(Integer)field("ticket");
        instrument.runOnMainSync(()->((PlayerView)field("video")).hideController());Thread.sleep(3500);
        assertEquals(generation,field("ticket"));assertTrue(((View)field("statusPanel")).isShown());assertTrue(texts().stream().anyMatch(s->s.contains("HTTP 404")&&s.contains("停止自动重试")));capture("live-recovery-failed.png");
        click("重试播放");awaitTerminal();assertTrue((Integer)field("ticket")>generation);assertTrue(context.getSharedPreferences("standalone-playback",0).getAll().isEmpty());
    }
    public void testSlowStartupStatusSurvivesHiddenControlsAndManualLineSwitch()throws Exception {
        launch(playlist("slow"));Thread.sleep(1000);instrument.runOnMainSync(()->((PlayerView)field("video")).hideController());Thread.sleep(3500);
        assertTrue(((View)field("statusPanel")).isShown());assertFalse(((View)field("chrome")).isShown());capture("live-recovery-connecting.png");click("换线路");awaitPlaying();assertEquals(1,field("lineIndex"));assertFalse(((View)field("statusPanel")).isShown());
    }
    public void testOldSlowChannelCannotReplaceNewChannel()throws Exception {
        launch(playlist("slow"));Thread.sleep(500);click("下一台");awaitPlaying();String id=repo.channels().getJSONObject(1).getString("id");assertEquals(id,new LiveRepository(context).lastChannel());
        int generation=(Integer)field("ticket");Thread.sleep(13000);assertEquals(generation,field("ticket"));assertEquals(id,new LiveRepository(context).lastChannel());awaitPlaying();
    }
    public void testLiveWindowJumpReloadsFreshStreamWithoutVodWrites()throws Exception {
        JSONObject c=playlist("failover");repo.markLineStable(c,1,100);launch(c);awaitPlaying();int generation=(Integer)field("ticket");control("jump");
        long end=SystemClock.uptimeMillis()+15000;while((Integer)field("ticket")==generation&&SystemClock.uptimeMillis()<end)Thread.sleep(100);
        assertTrue("No recovery after live-window jump",(Integer)field("ticket")>generation);awaitPlaying();assertFalse((Boolean)field("terminal"));assertTrue(context.getSharedPreferences("standalone-playback",0).getAll().isEmpty());
    }
    public void testLineCooldownDoesNotDeleteSourcesAndExpires()throws Exception {
        JSONObject c=playlist("failover");String before=repo.channels().toString();long now=System.currentTimeMillis();repo.markLineStable(c,1,300);repo.toggleFavorite(c.getString("id"));assertEquals(1,new LiveRepository(context).preferredLine(c,now));
        repo.markLineFailed(c,1,now);assertEquals(0,repo.preferredLine(c,now));assertEquals(1,repo.preferredLine(c,now+11*60*1000));assertEquals(before,repo.channels().toString());assertTrue(repo.favorite(c.getString("id")));
    }
}
