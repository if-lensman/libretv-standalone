package family.libretv.tv;

import android.app.*;
import android.content.*;
import android.graphics.Rect;
import android.media.AudioManager;
import android.os.SystemClock;
import android.provider.Settings;
import android.view.*;
import android.widget.*;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TimeBar;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import org.json.*;
import java.util.*;

/** Real pointer events on the API30 phone AVD; synthetic VOD and sliding live fixtures only. */
@androidx.media3.common.util.UnstableApi
public final class PlaybackGestureTest extends TestCase {
    private Instrumentation instrument;
    private Context context;
    private Activity activity;
    private AudioManager audio;
    private int oldVolume;
    private final Map<String,Map<String,?>> backups=new HashMap<>();
    private static final String ROOT="http://10.0.2.2:19001";
    @Override protected void setUp()throws Exception {
        instrument=InstrumentationRegistry.getInstrumentation();context=instrument.getTargetContext();audio=(AudioManager)context.getSystemService(Context.AUDIO_SERVICE);oldVolume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        for(String name:new String[]{"standalone-library","standalone-playback","standalone-player","live-library"}){SharedPreferences p=context.getSharedPreferences(name,0);backups.put(name,new HashMap<>(p.getAll()));p.edit().clear().commit();}
        context.getSharedPreferences("standalone-player",0).edit().putBoolean("autoNext",false).commit();
    }
    @Override protected void tearDown()throws Exception {
        if(activity!=null){instrument.runOnMainSync(activity::finish);instrument.waitForIdleSync();}
        audio.setStreamVolume(AudioManager.STREAM_MUSIC,oldVolume,0);
        for(Map.Entry<String,Map<String,?>> row:backups.entrySet()){
            SharedPreferences.Editor e=context.getSharedPreferences(row.getKey(),0).edit().clear();
            for(Map.Entry<String,?> pair:row.getValue().entrySet()){String k=pair.getKey();Object v=pair.getValue();if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));}e.commit();
        }
    }
    private Object field(String name){try{java.lang.reflect.Field f=activity.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(activity);}catch(Exception e){throw new RuntimeException(e);}}
    private ExoPlayer player(){return (ExoPlayer)field("player");}
    private PlayerView video(){return (PlayerView)field(activity instanceof DirectPlayerActivity?"playerView":"video");}
    private PlaybackGestureLayout stage(){return (PlaybackGestureLayout)field("stage");}
    private void ready()throws Exception {long end=SystemClock.uptimeMillis()+15000;boolean[] ready={false};while(SystemClock.uptimeMillis()<end){instrument.runOnMainSync(()->ready[0]=player()!=null&&player().getPlaybackState()==Player.STATE_READY);if(ready[0])return;Thread.sleep(75);}fail("Player never ready");}
    private long position(){long[] p={0};instrument.runOnMainSync(()->p[0]=player().getCurrentPosition());return p[0];}
    private void at(long position)throws Exception {instrument.runOnMainSync(()->{player().pause();player().seekTo(position);video().hideController();});awaitPosition(position);Thread.sleep(100);}
    private void awaitPosition(long target)throws Exception {long end=SystemClock.uptimeMillis()+4000;while(SystemClock.uptimeMillis()<end){if(Math.abs(position()-target)<350)return;Thread.sleep(50);}fail("Expected position "+target+", actual "+position());}
    private void launch()throws Exception {
        JSONObject source=new JSONObject().put("key",LocalRepository.key(ROOT+"/cms")).put("name","Gesture fixture").put("url",ROOT+"/cms");
        JSONObject item=new JSONObject().put("vodId","1").put("name","Gesture fixture");
        activity=instrument.startActivitySync(new Intent(context,DirectPlayerActivity.class).putStringArrayListExtra("episodes",new ArrayList<>(Arrays.asList(ROOT+"/sample.mp4",ROOT+"/sample.mp4"))).putExtra("title","Gesture fixture").putExtra("source",source.toString()).putExtra("item",item.toString()).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));ready();at(6000);
    }
    private float[] point(float x,float y){int[] loc=new int[2];float[] p=new float[2];instrument.runOnMainSync(()->{stage().getLocationOnScreen(loc);p[0]=loc[0]+stage().getWidth()*x;p[1]=loc[1]+stage().getHeight()*y;});return p;}
    private void event(long down,int action,float x,float y){MotionEvent e=MotionEvent.obtain(down,SystemClock.uptimeMillis(),action,x,y,0);e.setSource(InputDevice.SOURCE_TOUCHSCREEN);instrument.sendPointerSync(e);e.recycle();}
    private void tap(float x,float y)throws Exception {float[] p=point(x,y);long down=SystemClock.uptimeMillis();event(down,MotionEvent.ACTION_DOWN,p[0],p[1]);Thread.sleep(35);event(down,MotionEvent.ACTION_UP,p[0],p[1]);}
    private void doubleTap(float x)throws Exception {tap(x,.5f);Thread.sleep(80);tap(x,.5f);instrument.waitForIdleSync();}
    private void swipe(float x1,float y1,float x2,float y2,boolean cancel)throws Exception {float[] a=point(x1,y1),b=point(x2,y2);long down=SystemClock.uptimeMillis();event(down,MotionEvent.ACTION_DOWN,a[0],a[1]);for(int i=1;i<=8;i++){Thread.sleep(25);event(down,MotionEvent.ACTION_MOVE,a[0]+(b[0]-a[0])*i/8,a[1]+(b[1]-a[1])*i/8);}event(down,cancel?MotionEvent.ACTION_CANCEL:MotionEvent.ACTION_UP,b[0],b[1]);instrument.waitForIdleSync();}
    private List<View> views(){List<View> out=new ArrayList<>();instrument.runOnMainSync(()->collect(activity.getWindow().getDecorView(),out));return out;}
    private void collect(View v,List<View> out){out.add(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),out);}
    private void capture(String name)throws Exception {android.graphics.Bitmap b=instrument.getUiAutomation().takeScreenshot();try(java.io.FileOutputStream o=new java.io.FileOutputStream(new java.io.File(context.getFilesDir(),name))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,o);}b.recycle();}
    public void testDoubleTapLeftBackRightForwardAndClamp()throws Exception {
        launch();doubleTap(.25f);awaitPosition(1000);doubleTap(.75f);awaitPosition(6000);at(1000);doubleTap(.25f);awaitPosition(0);
        instrument.runOnMainSync(()->assertFalse("Seek resumed a paused player",player().getPlayWhenReady()));
    }
    public void testHorizontalPreviewCommitsOnReleaseAndCancelDoesNotSeek()throws Exception {
        launch();float[] a=point(.25f,.5f),b=point(.6f,.5f);long down=SystemClock.uptimeMillis();event(down,MotionEvent.ACTION_DOWN,a[0],a[1]);Thread.sleep(70);event(down,MotionEvent.ACTION_MOVE,b[0],b[1]);
        assertTrue(Math.abs(position()-6000)<350);assertTrue(views().stream().anyMatch(v->v instanceof TextView&&((TextView)v).getText().toString().startsWith("进度 ")&&v.isShown()));capture("gesture-seek-preview.png");
        event(down,MotionEvent.ACTION_UP,b[0],b[1]);assertTrue(position()>9000);
        at(6000);swipe(.65f,.5f,.3f,.5f,true);awaitPosition(6000);
        swipe(.65f,.5f,.3f,.5f,false);assertTrue(position()<3000);instrument.runOnMainSync(activity::finish);instrument.waitForIdleSync();activity=null;
        JSONArray history=new PlaybackStore(context).all();assertEquals(1,history.length());assertTrue(history.getJSONObject(0).getLong("position")<3000);
    }
    public void testLeftBrightnessAndRightMediaVolumeAreIndependent()throws Exception {
        launch();int global=Settings.System.getInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,128);
        instrument.runOnMainSync(()->{WindowManager.LayoutParams attrs=activity.getWindow().getAttributes();attrs.screenBrightness=.4f;activity.getWindow().setAttributes(attrs);});
        int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);audio.setStreamVolume(AudioManager.STREAM_MUSIC,max/3,0);int initial=audio.getStreamVolume(AudioManager.STREAM_MUSIC);
        swipe(.25f,.65f,.25f,.42f,false);assertTrue(activity.getWindow().getAttributes().screenBrightness>.65f);assertEquals(initial,audio.getStreamVolume(AudioManager.STREAM_MUSIC));awaitPosition(6000);capture("gesture-brightness.png");
        float brightness=activity.getWindow().getAttributes().screenBrightness;
        swipe(.75f,.65f,.75f,.42f,false);assertTrue(audio.getStreamVolume(AudioManager.STREAM_MUSIC)>initial);assertEquals(brightness,activity.getWindow().getAttributes().screenBrightness);assertEquals(global,Settings.System.getInt(context.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,128));awaitPosition(6000);capture("gesture-volume.png");
        swipe(.75f,.42f,.75f,.8f,false);assertTrue(audio.getStreamVolume(AudioManager.STREAM_MUSIC)<initial);
    }
    public void testSingleTapAndExistingEpisodeButtonsStillReceiveTouches()throws Exception {
        launch();tap(.5f,.5f);Thread.sleep(450);boolean[] visible={false};instrument.runOnMainSync(()->visible[0]=video().isControllerFullyVisible());assertTrue(visible[0]);
        View next=views().stream().filter(v->v instanceof Button&&"下一集".contentEquals(((Button)v).getText())).findFirst().orElseThrow();Rect r=new Rect();instrument.runOnMainSync(()->assertTrue(next.getGlobalVisibleRect(r)));long down=SystemClock.uptimeMillis();event(down,MotionEvent.ACTION_DOWN,r.centerX(),r.centerY());Thread.sleep(40);event(down,MotionEvent.ACTION_UP,r.centerX(),r.centerY());Thread.sleep(300);ready();assertEquals(1,field("index"));
    }
    public void testLiveGesturesStayInsideWindowAndNeverWriteVodHistory()throws Exception {
        LiveRepository repo=new LiveRepository(context);repo.importPlaylist("Gesture live",LocalRepository.fetch(ROOT+"/live/list"));String id=repo.channels().getJSONObject(0).getString("id");
        activity=instrument.startActivitySync(new Intent(context,LivePlayerActivity.class).putExtra("channelId",id).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));ready();
        instrument.runOnMainSync(()->{player().pause();video().hideController();});doubleTap(.25f);assertTrue(position()>=0);swipe(.2f,.5f,.8f,.5f,false);
        instrument.runOnMainSync(()->{assertTrue(player().getCurrentPosition()<=player().getDuration());assertFalse(player().getPlayWhenReady());});assertTrue(context.getSharedPreferences("standalone-playback",0).getAll().isEmpty());
    }
    public void testNativeTimeBarStillScrubsWithoutSurfaceGesture()throws Exception {
        launch();at(3000);instrument.runOnMainSync(()->video().showController());Thread.sleep(150);
        View bar=views().stream().filter(v->v instanceof TimeBar&&v.isShown()).findFirst().orElseThrow();Rect r=new Rect();instrument.runOnMainSync(()->assertTrue(bar.getGlobalVisibleRect(r)));
        long down=SystemClock.uptimeMillis();float x=r.left+r.width()*.2f,y=r.centerY();event(down,MotionEvent.ACTION_DOWN,x,y);
        for(int i=1;i<=5;i++){Thread.sleep(30);event(down,MotionEvent.ACTION_MOVE,x+r.width()*.45f*i/5,y);}
        assertFalse(views().stream().anyMatch(v->v instanceof TextView&&v.isShown()&&((TextView)v).getText().toString().startsWith("进度 ")));
        event(down,MotionEvent.ACTION_UP,r.left+r.width()*.65f,y);assertTrue(position()>6000);
    }
    public void testUnsupportedSeekShowsHintAndDoesNotChangePosition()throws Exception {
        launch();instrument.runOnMainSync(()->stage().bind(new androidx.media3.common.ForwardingPlayer(player()){
            @Override public boolean isCurrentMediaItemSeekable(){return false;}
        },video(),(View)field("topChrome"),(View)field("bottomChrome")));
        doubleTap(.25f);awaitPosition(6000);
        assertTrue(views().stream().anyMatch(v->v instanceof TextView&&v.isShown()&&((TextView)v).getText().toString().contains("暂不支持调整进度")));
        swipe(.3f,.5f,.7f,.5f,false);awaitPosition(6000);
    }
}
