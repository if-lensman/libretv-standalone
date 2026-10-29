package family.libretv.tv;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.graphics.Bitmap;
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

/** Real emulator input and decoded local demo-video frames; screenshots are unmodified captures. */
@androidx.media3.common.util.UnstableApi
public final class PlayerDesignFlowTest extends TestCase {
    private Instrumentation instrument;
    private Context context;
    private Activity activity;
    private final Map<String,Map<String,?>> backups=new HashMap<>();
    private static final String ROOT="http://10.0.2.2:19001";
    @Override protected void setUp() throws Exception {
        instrument=InstrumentationRegistry.getInstrumentation();context=instrument.getTargetContext();
        for(String name:new String[]{"standalone-library","standalone-player","standalone-playback","MainActivity","family.libretv.tv.MainActivity","recommendations","github-updates"}){
            SharedPreferences p=context.getSharedPreferences(name,0);backups.put(name,new HashMap<>(p.getAll()));p.edit().clear().commit();
        }
        context.getSharedPreferences("github-updates",0).edit().putLong("lastCheck",System.currentTimeMillis()).commit();
        context.getSharedPreferences("standalone-player",0).edit().putBoolean("autoNext",false).commit();
        context.getSharedPreferences("MainActivity",0).edit().putBoolean("autoNext",false).commit();
        context.getSharedPreferences("family.libretv.tv.MainActivity",0).edit().putBoolean("autoNext",false).commit();
    }
    @Override protected void tearDown() throws Exception {
        if(activity!=null){instrument.runOnMainSync(activity::finish);instrument.waitForIdleSync();}
        for(Map.Entry<String,Map<String,?>> row:backups.entrySet()){
            SharedPreferences.Editor e=context.getSharedPreferences(row.getKey(),0).edit().clear();
            for(Map.Entry<String,?> pair:row.getValue().entrySet()){
                String k=pair.getKey();Object v=pair.getValue();if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));
            }e.commit();
        }
    }
    private JSONObject source() throws Exception {return new JSONObject().put("key",LocalRepository.key(ROOT+"/cms")).put("name","本地演示").put("url",ROOT+"/cms");}
    private JSONObject item() throws Exception {return new JSONObject().put("vodId","demo").put("name","山海之间");}
    private Object field(String name){try{java.lang.reflect.Field f=activity.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(activity);}catch(Exception e){throw new RuntimeException(e);}}
    private void set(String name,Object value){try{java.lang.reflect.Field f=activity.getClass().getDeclaredField(name);f.setAccessible(true);f.set(activity,value);}catch(Exception e){throw new RuntimeException(e);}}
    private void invoke(String name,Class<?>[] types,Object... args){try{java.lang.reflect.Method m=activity.getClass().getDeclaredMethod(name,types);m.setAccessible(true);m.invoke(activity,args);}catch(Exception e){throw new RuntimeException(e);}}
    private ExoPlayer player(){return (ExoPlayer)field("player");}
    private long position(){long[] p={0};instrument.runOnMainSync(()->p[0]=player().getCurrentPosition());return p[0];}
    private void ready()throws Exception {
        long deadline=SystemClock.uptimeMillis()+20000;boolean[] good={false};
        while(SystemClock.uptimeMillis()<deadline){instrument.runOnMainSync(()->good[0]=player()!=null&&player().getPlaybackState()==Player.STATE_READY&&player().getVideoSize().width>0);if(good[0])return;Thread.sleep(50);}
        fail("Demo video not ready: "+texts());
    }
    private void awaitPosition(long expected)throws Exception {long deadline=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<deadline){if(Math.abs(position()-expected)<400)return;Thread.sleep(50);}fail("Expected "+expected+", actual "+position());}
    private void at(long value)throws Exception {instrument.runOnMainSync(()->{player().pause();player().seekTo(value);});awaitPosition(value);ready();}
    private void collect(View v,List<View> out){out.add(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),out);}
    private List<View> views(){List<View> out=new ArrayList<>();instrument.runOnMainSync(()->collect(activity.getWindow().getDecorView(),out));return out;}
    private List<String> texts(){List<String> out=new ArrayList<>();for(View v:views())if(v instanceof TextView)out.add(((TextView)v).getText().toString());return out;}
    private void click(String text){for(View v:views())if(v instanceof TextView&&text.contentEquals(((TextView)v).getText())&&v.isShown()){instrument.runOnMainSync(v::performClick);instrument.waitForIdleSync();return;}fail("Missing "+text+": "+texts());}
    private void capture(String filename)throws Exception {
        instrument.waitForIdleSync();Thread.sleep(450);Bitmap b=instrument.getUiAutomation().takeScreenshot();assertNotNull(b);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(context.getFilesDir(),filename))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();
    }
    private void rotate(int orientation)throws Exception {
        Activity old=activity;instrument.runOnMainSync(()->old.setRequestedOrientation(orientation));long deadline=SystemClock.uptimeMillis()+10000;
        while(SystemClock.uptimeMillis()<deadline){Activity[] next={null};instrument.runOnMainSync(()->{for(Activity a:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(a instanceof DirectPlayerActivity&&a!=old)next[0]=a;});if(next[0]!=null){activity=next[0];ready();return;}Thread.sleep(50);}fail("Rotation did not recreate player");
    }
    private void tapSurface(float fraction)throws Exception {
        int[] location=new int[2];float[] point=new float[2];instrument.runOnMainSync(()->{View stage=(View)field("stage");stage.getLocationOnScreen(location);point[0]=location[0]+stage.getWidth()*fraction;point[1]=location[1]+stage.getHeight()*.45f;});
        long down=SystemClock.uptimeMillis();MotionEvent a=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,point[0],point[1],0);a.setSource(InputDevice.SOURCE_TOUCHSCREEN);instrument.sendPointerSync(a);a.recycle();Thread.sleep(40);
        MotionEvent b=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,point[0],point[1],0);b.setSource(InputDevice.SOURCE_TOUCHSCREEN);instrument.sendPointerSync(b);b.recycle();
    }
    private void doubleTapSurface(float fraction)throws Exception {tapSurface(fraction);Thread.sleep(80);tapSurface(fraction);instrument.waitForIdleSync();}
    private void chooseDialog(String fieldName,int which){
        instrument.runOnMainSync(()->{AlertDialog d=(AlertDialog)field(fieldName);ListView list=d.getListView();list.performItemClick(list.getChildAt(which),which,list.getAdapter().getItemId(which));});instrument.waitForIdleSync();
    }
    private void disableSubtitles(String menuField){
        instrument.runOnMainSync(()->{try{Object menu=field(menuField);java.lang.reflect.Field f=SubtitleMenu.class.getDeclaredField("dialog");f.setAccessible(true);AlertDialog d=(AlertDialog)f.get(menu);assertTrue(d.isShowing());ListView list=d.getListView();list.performItemClick(list.getChildAt(1),1,list.getAdapter().getItemId(1));}catch(Exception e){throw new RuntimeException(e);}});instrument.waitForIdleSync();
        instrument.runOnMainSync(()->assertEquals(View.GONE,((PlayerView)field("playerView")).getSubtitleView().getVisibility()));
    }
    private void showPhone(){instrument.runOnMainSync(()->((PlayerView)field("playerView")).showController());}
    public void testPhonePortraitLandscapeSeekEpisodesAndScreenshots() throws Exception {
        ArrayList<String> urls=new ArrayList<>();for(int i=0;i<24;i++)urls.add(ROOT+"/demo.mp4");
        activity=instrument.startActivitySync(new Intent(context,DirectPlayerActivity.class).putStringArrayListExtra("episodes",urls).putExtra("index",2).putExtra("title","山海之间").putExtra("source",source().toString()).putExtra("item",item().toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));ready();
        at(20000);instrument.runOnMainSync(()->((PlayerView)field("playerView")).hideController());
        doubleTapSurface(.85f);awaitPosition(30000);doubleTapSurface(.15f);awaitPosition(20000);
        instrument.runOnMainSync(()->assertFalse(player().getPlayWhenReady()));
        assertFalse(texts().contains("快退10秒"));assertFalse(texts().contains("快进10秒"));
        click("第04集");ready();assertEquals(3,field("index"));click("上一集");ready();assertEquals(2,field("index"));
        at(20000);click("收藏");assertEquals(1,new LocalRepository(context).favorites("/api/favorites",null,"GET").getJSONArray("items").length());
        showPhone();capture("player-phone-portrait.png");
        rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);assertEquals(2,field("index"));assertTrue(position()>=19600);instrument.runOnMainSync(()->assertFalse(player().getPlayWhenReady()));
        showPhone();capture("player-phone-landscape.png");
        click("更多");Thread.sleep(150);capture("player-phone-more.png");instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        instrument.runOnMainSync(()->((PlayerView)field("playerView")).hideController());Thread.sleep(100);capture("player-phone-hidden.png");
        doubleTapSurface(.85f);awaitPosition(30000);capture("player-phone-seek.png");doubleTapSurface(.15f);awaitPosition(20000);
        showPhone();click("字幕");disableSubtitles("subtitles");
        click("更多");chooseDialog("dialog",0);assertTrue("Restart did not seek to start",position()<1500);instrument.runOnMainSync(()->player().pause());
        click("更多");chooseDialog("dialog",1);assertTrue(context.getSharedPreferences("standalone-player",0).getBoolean("autoNext",false));
    }
    public void testTvDirectionsWakeControlsWithoutSeekingAndScreenshot() throws Exception {
        instrument.setInTouchMode(false);
        activity=instrument.startActivitySync(new Intent(context,MainActivity.class).putExtra("tvDestination","search").addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        JSONObject source=source(),item=item();JSONArray episodes=new JSONArray();for(int i=0;i<24;i++)episodes.put(ROOT+"/demo.mp4");
        instrument.runOnMainSync(()->{set("title","山海之间");set("detailSource",source);set("selected",item);set("episodes",episodes);set("episodeIndex",2);try{set("playbackUri",new java.net.URI(ROOT+"/demo.mp4"));}catch(Exception e){throw new RuntimeException(e);}set("position",0L);set("playWhenReady",true);invoke("showPlayer",new Class<?>[]{});});ready();at(20000);
        Thread.sleep(400);instrument.waitForIdleSync();
        long focusDeadline=SystemClock.uptimeMillis()+5000;while(!activity.hasWindowFocus()&&SystemClock.uptimeMillis()<focusDeadline)Thread.sleep(50);
        instrument.runOnMainSync(()->{assertTrue("TV window not focused",activity.hasWindowFocus());assertFalse("TV remote tests require non-touch input mode",activity.getWindow().getDecorView().isInTouchMode());invoke("hideControls",new Class<?>[]{});});
        instrument.getUiAutomation().waitForIdle(300,3000);Thread.sleep(400);
        for(int direction:new int[]{KeyEvent.KEYCODE_DPAD_LEFT,KeyEvent.KEYCODE_DPAD_RIGHT,KeyEvent.KEYCODE_DPAD_UP,KeyEvent.KEYCODE_DPAD_DOWN}){
            instrument.runOnMainSync(()->invoke("hideControls",new Class<?>[]{}));
            instrument.waitForIdleSync();Thread.sleep(150);
            instrument.sendKeyDownUpSync(direction);instrument.waitForIdleSync();Thread.sleep(100);
            instrument.runOnMainSync(()->{assertTrue("Direction "+direction+" did not wake controls; focus="+activity.getCurrentFocus()+" window="+activity.hasWindowFocus(),(Boolean)field("controlsVisible"));assertEquals(field("playToggle"),activity.getCurrentFocus());});
            awaitPosition(20000);
        }
        View[] focus={null};instrument.runOnMainSync(()->focus[0]=activity.getCurrentFocus());assertEquals(field("playToggle"),focus[0]);
        capture("player-tv-controls.png");
        instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);instrument.waitForIdleSync();instrument.runOnMainSync(()->focus[0]=activity.getCurrentFocus());assertTrue(focus[0] instanceof Button);assertEquals("上一集",((Button)focus[0]).getText().toString());
        assertFalse(texts().contains("−10 秒"));assertFalse(texts().contains("+10 秒"));
        click("更多");Thread.sleep(150);capture("player-tv-more.png");chooseDialog("playerOptions",1);assertTrue(activity.getPreferences(Context.MODE_PRIVATE).getBoolean("autoNext",false));
        click("字幕");disableSubtitles("subtitleMenu");
        click("更多");chooseDialog("playerOptions",0);assertTrue("TV restart did not seek to start",position()<1500);instrument.runOnMainSync(()->player().pause());
        instrument.runOnMainSync(()->invoke("hideControls",new Class<?>[]{}));capture("player-tv-hidden.png");
    }
}
