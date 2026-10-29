package family.libretv.tv;

import android.app.*;
import android.content.*;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.media3.common.Player;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.ui.PlayerView;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import java.util.*;

/** Actual decoded sliding HLS, native touch/D-pad input and unmodified emulator captures. */
@androidx.media3.common.util.UnstableApi
public final class LivePlayerDesignFlowTest extends TestCase {
    private Instrumentation instrument;
    private Context context;
    private Activity activity;
    private LiveRepository repo;
    private final Map<String,Map<String,?>> backups=new HashMap<>();
    @Override protected void setUp()throws Exception {
        instrument=InstrumentationRegistry.getInstrumentation();context=instrument.getTargetContext();
        for(String name:new String[]{"live-library","standalone-playback"}){SharedPreferences p=context.getSharedPreferences(name,0);backups.put(name,new HashMap<>(p.getAll()));p.edit().clear().commit();}
        repo=new LiveRepository(context);
        StringBuilder m3u=new StringBuilder("#EXTM3U\n");
        for(String group:new String[]{"风景","纪实","音乐"})for(int i=1;i<=4;i++)m3u.append("#EXTINF:-1 group-title=\"").append(group).append("\",").append(group).append(i).append("台\nhttp://10.0.2.2:19001/live/demo-stream?channel=").append(group).append(i).append('\n');
        repo.importPlaylist("本地演示频道",m3u.toString());
    }
    @Override protected void tearDown()throws Exception {
        if(activity!=null){instrument.runOnMainSync(activity::finish);instrument.waitForIdleSync();}
        for(Map.Entry<String,Map<String,?>> row:backups.entrySet()){SharedPreferences.Editor e=context.getSharedPreferences(row.getKey(),0).edit().clear();for(Map.Entry<String,?> pair:row.getValue().entrySet()){String k=pair.getKey();Object v=pair.getValue();if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Float)e.putFloat(k,(Float)v);}e.commit();}
    }
    private Object field(String name){try{java.lang.reflect.Field f=LivePlayerActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}catch(Exception e){throw new RuntimeException(e);}}
    private ExoPlayer player(){return (ExoPlayer)field("player");}
    private void launch(boolean tv)throws Exception {
        activity=instrument.startActivitySync(new Intent(context,LivePlayerActivity.class).putExtra("channelId",repo.channels().getJSONObject(0).getString("id")).putExtra("tvRemote",tv).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        ready();long end=SystemClock.uptimeMillis()+5000;while(!activity.hasWindowFocus()&&SystemClock.uptimeMillis()<end)Thread.sleep(50);assertTrue(activity.hasWindowFocus());
        // Cold emulator activity transitions may retain input while the first HLS frame is already decoded.
        instrument.waitForIdleSync();instrument.getUiAutomation().waitForIdle(300,3000);Thread.sleep(400);
    }
    private void awaitIndex(int index)throws Exception {long end=SystemClock.uptimeMillis()+4000;while(SystemClock.uptimeMillis()<end){int[] actual={-1};instrument.runOnMainSync(()->actual[0]=(Integer)field("index"));if(actual[0]==index)return;Thread.sleep(50);}assertEquals(index,field("index"));}
    private void ready()throws Exception {long end=SystemClock.uptimeMillis()+22000;boolean[] ok={false};while(SystemClock.uptimeMillis()<end){instrument.runOnMainSync(()->ok[0]=player()!=null&&player().getPlaybackState()==Player.STATE_READY&&player().getVideoSize().width>0&&(Boolean)field("renderedFrame"));if(ok[0]){instrument.runOnMainSync(()->assertTrue(player().isCurrentMediaItemLive()));return;}Thread.sleep(75);}fail("Live HLS not decoded");}
    private void collect(View v,List<View> list){list.add(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),list);}
    private List<View> views(){List<View> list=new ArrayList<>();instrument.runOnMainSync(()->collect(activity.getWindow().getDecorView(),list));return list;}
    private View find(String value,boolean description){for(View v:views())if(v.isShown()&&(description?value.contentEquals(v.getContentDescription()==null?"":v.getContentDescription()):v instanceof TextView&&value.contentEquals(((TextView)v).getText())))return v;throw new AssertionError("Missing control "+value);}
    private void tap(View v)throws Exception {
        Rect r=new Rect();instrument.runOnMainSync(()->assertTrue(v.getGlobalVisibleRect(r)));long down=SystemClock.uptimeMillis();MotionEvent a=MotionEvent.obtain(down,down,MotionEvent.ACTION_DOWN,r.centerX(),r.centerY(),0);instrument.sendPointerSync(a);a.recycle();Thread.sleep(40);MotionEvent b=MotionEvent.obtain(down,SystemClock.uptimeMillis(),MotionEvent.ACTION_UP,r.centerX(),r.centerY(),0);instrument.sendPointerSync(b);b.recycle();instrument.waitForIdleSync();
    }
    private void key(int code){instrument.sendKeyDownUpSync(code);instrument.waitForIdleSync();}
    private void remote(View v){instrument.runOnMainSync(v::requestFocus);key(KeyEvent.KEYCODE_DPAD_CENTER);}
    private void controls(){instrument.runOnMainSync(()->((PlayerView)field("video")).showController());instrument.waitForIdleSync();}
    private void capture(String name)throws Exception {instrument.waitForIdleSync();Thread.sleep(350);Bitmap b=instrument.getUiAutomation().takeScreenshot();try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(context.getFilesDir(),name))){b.compress(Bitmap.CompressFormat.PNG,100,out);}b.recycle();}
    private void rotate(int orientation)throws Exception {instrument.runOnMainSync(()->activity.setRequestedOrientation(orientation));long end=SystemClock.uptimeMillis()+6000;while(SystemClock.uptimeMillis()<end){boolean[] done={false};instrument.runOnMainSync(()->done[0]=activity.getResources().getConfiguration().orientation==(orientation==ActivityInfo.SCREEN_ORIENTATION_PORTRAIT?Configuration.ORIENTATION_PORTRAIT:Configuration.ORIENTATION_LANDSCAPE)&&((View)field("stage")).getWidth()>0);if(done[0]){instrument.waitForIdleSync();Thread.sleep(900);return;}Thread.sleep(75);}fail("Orientation did not change");}
    public void testPhoneCategoriesRotationChannelSwitchAndScreenshots()throws Exception {
        launch(false);rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        assertNotNull(field("channelPanel"));tap(find("频道分类 · 纪实",true));assertEquals("纪实",field("pickerGroup"));find("纪实 · 4 个频道",false);
        tap(find("播放频道 · 纪实2台",true));awaitIndex(5);ready();
        tap(find("下一个",false));awaitIndex(6);ready();tap(find("上一个",false));awaitIndex(5);ready();
        controls();capture("live-phone-portrait.png");
        Object[] before=new Object[2];instrument.runOnMainSync(()->{before[0]=player();before[1]=field("ticket");});
        tap(find("横屏",false));rotate(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);ready();instrument.runOnMainSync(()->{assertSame(before[0],player());assertEquals(before[1],field("ticket"));});
        controls();capture("live-phone-landscape.png");tap(find("选台",false));find("频道分类",false);capture("live-phone-categories.png");
        tap(find("频道分类 · 音乐",true));find("音乐 · 4 个频道",false);tap(find("播放频道 · 音乐1台",true));ready();assertEquals(8,field("index"));assertFalse((Boolean)field("pickerOpen"));
        controls();key(KeyEvent.KEYCODE_BACK);assertFalse(activity.isFinishing());key(KeyEvent.KEYCODE_BACK);rotate(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);assertNotNull(field("channelPanel"));
        assertTrue(context.getSharedPreferences("standalone-playback",0).getAll().isEmpty());
    }
    public void testTvCategoriesDirectionKeysAndScreenshots()throws Exception {
        launch(true);assertNull(field("orientationButton"));
        instrument.runOnMainSync(()->assertEquals("TV opens with focus on the video surface",field("video"),activity.getCurrentFocus()));
        key(KeyEvent.KEYCODE_DPAD_RIGHT);awaitIndex(1);ready();
        key(KeyEvent.KEYCODE_DPAD_LEFT);awaitIndex(0);ready();
        controls();instrument.runOnMainSync(()->((View)field("previousChannel")).requestFocus());capture("live-tv-player.png");key(KeyEvent.KEYCODE_DPAD_UP);assertTrue((Boolean)field("pickerOpen"));
        int current=(Integer)field("index");key(KeyEvent.KEYCODE_DPAD_RIGHT);assertEquals("Navigation inside picker must not change channel",current,field("index"));
        remote(find("频道分类 · 纪实",true));assertEquals("纪实",field("pickerGroup"));find("纪实 · 4 个频道",false);
        remote(find("播放频道 · 纪实2台",true));awaitIndex(5);ready();assertFalse((Boolean)field("pickerOpen"));
        key(KeyEvent.KEYCODE_DPAD_UP);key(KeyEvent.KEYCODE_DPAD_DOWN);capture("live-tv-categories.png");key(KeyEvent.KEYCODE_BACK);
        key(KeyEvent.KEYCODE_DPAD_RIGHT);awaitIndex(6);ready();key(KeyEvent.KEYCODE_DPAD_RIGHT);awaitIndex(7);ready();key(KeyEvent.KEYCODE_DPAD_RIGHT);awaitIndex(4);ready();
        key(KeyEvent.KEYCODE_DPAD_DOWN);key(KeyEvent.KEYCODE_BACK);assertFalse((Boolean)field("pickerOpen"));assertFalse(activity.isFinishing());
        assertTrue(context.getSharedPreferences("standalone-playback",0).getAll().isEmpty());
    }
}
