package family.libretv.tv;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.RecyclerView;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import java.util.*;
import org.json.*;

/** Remote grid navigation on a landscape emulator, synthetic channels and logos only. */
public final class TvLiveCardsFlowTest extends TestCase {
    private Instrumentation instrument;
    private Context context;
    private Activity activity,playerActivity;
    private LiveRepository repo;
    private final Map<String,Map<String,?>> backups=new HashMap<>();
    private static final String ROOT="http://10.0.2.2:19001";
    @Override protected void setUp()throws Exception {
        instrument=InstrumentationRegistry.getInstrumentation();context=instrument.getTargetContext();
        for(String name:new String[]{"live-library","standalone-library","standalone-playback"}){SharedPreferences p=context.getSharedPreferences(name,0);backups.put(name,new HashMap<>(p.getAll()));p.edit().clear().commit();}
        repo=new LiveRepository(context);repo.setAutomatic(false);
        StringBuilder m=new StringBuilder("#EXTM3U\n");
        for(int i=0;i<80;i++)m.append("#EXTINF:-1 tvg-id=\"card-").append(i).append("\" group-title=\"").append(i<40?"央视":"卫视").append("\" tvg-logo=\"").append(i==0?ROOT+"/poster.png":i==1?ROOT+"/missing-logo.png":"").append("\",测试频道 ").append(i+1).append("\n").append(ROOT).append("/live/stream?card=").append(i).append("\n");
        repo.importPlaylist("Cards fixture",m.toString());
    }
    @Override protected void tearDown()throws Exception {
        instrument.runOnMainSync(()->{if(playerActivity!=null)playerActivity.finish();if(activity!=null)activity.finish();});instrument.waitForIdleSync();
        for(Map.Entry<String,Map<String,?>> row:backups.entrySet()){
            SharedPreferences.Editor e=context.getSharedPreferences(row.getKey(),0).edit().clear();for(Map.Entry<String,?> pair:row.getValue().entrySet()){String k=pair.getKey();Object v=pair.getValue();if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));}e.commit();
        }
    }
    private void launch()throws Exception {activity=instrument.startActivitySync(new Intent(context,LiveBrowseActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));awaitFocus("播放频道 · 测试频道 1");}
    private Object field(String name){try{java.lang.reflect.Field f=LiveBrowseActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}catch(Exception e){throw new RuntimeException(e);}}
    private View focused(){View[] v={null};instrument.runOnMainSync(()->v[0]=activity.getCurrentFocus());return v[0];}
    private String description(){View v=focused();return v==null?"":String.valueOf(v.getContentDescription());}
    private void awaitFocus(String text)throws Exception {long end=SystemClock.uptimeMillis()+6000;while(SystemClock.uptimeMillis()<end){if(description().equals(text))return;Thread.sleep(100);}fail("Expected "+text+", focused "+description());}
    private void key(int code){instrument.sendKeyDownUpSync(code);instrument.waitForIdleSync();}
    private void capture(String name)throws Exception {android.graphics.Bitmap b=instrument.getUiAutomation().takeScreenshot();try(java.io.FileOutputStream o=new java.io.FileOutputStream(new java.io.File(context.getFilesDir(),name))){b.compress(android.graphics.Bitmap.CompressFormat.PNG,100,o);}b.recycle();}
    private void group(String name)throws Exception {
        Map<String,View> controls=(Map<String,View>)field("controls");instrument.runOnMainSync(()->controls.get("group:"+name).requestFocus());key(KeyEvent.KEYCODE_DPAD_CENTER);
        long end=SystemClock.uptimeMillis()+3000;while(SystemClock.uptimeMillis()<end){if(name.equals(field("group")))return;Thread.sleep(50);}fail("Group not applied");
    }
    public void testRemoteGridNavigationLogoFallbackAndLongList()throws Exception {
        launch();Thread.sleep(1000);capture("tv-channel-cards.png");
        assertEquals(80,((RecyclerView)field("grid")).getAdapter().getItemCount());
        key(KeyEvent.KEYCODE_DPAD_RIGHT);awaitFocus("播放频道 · 测试频道 2");key(KeyEvent.KEYCODE_DPAD_LEFT);awaitFocus("播放频道 · 测试频道 1");
        key(KeyEvent.KEYCODE_DPAD_LEFT);awaitFocus("频道分组 · 全部");key(KeyEvent.KEYCODE_DPAD_RIGHT);awaitFocus("播放频道 · 测试频道 1");
        for(int i=0;i<12;i++){key(KeyEvent.KEYCODE_DPAD_DOWN);Thread.sleep(80);}
        assertTrue("Grid did not scroll beyond the old first 40 channels: "+description(),description().startsWith("播放频道 · 测试频道 ")&&Integer.parseInt(description().substring(description().lastIndexOf(' ')+1))>40);
        RecyclerView grid=(RecyclerView)field("grid");assertTrue(grid.getChildCount()<80);capture("tv-channel-cards-scrolled.png");
        assertTrue(context.getSharedPreferences("standalone-library",0).getAll().isEmpty());assertTrue(context.getSharedPreferences("standalone-playback",0).getAll().isEmpty());
    }
    public void testMenuFavoriteAndGroupAndRemovalKeepFocus()throws Exception {
        launch();key(KeyEvent.KEYCODE_MENU);
        AlertDialog dialog=(AlertDialog)field("channelActions");assertTrue(dialog.isShowing());
        instrument.runOnMainSync(()->{dialog.getListView().requestFocus();dialog.getListView().setSelection(1);});instrument.waitForIdleSync();key(KeyEvent.KEYCODE_DPAD_CENTER);
        String id=repo.channels().getJSONObject(0).getString("id");assertTrue(repo.favorite(id));awaitFocus("播放频道 · 测试频道 1 · 已收藏");
        group("收藏");awaitFocus("播放频道 · 测试频道 1 · 已收藏");assertEquals(1,((RecyclerView)field("grid")).getAdapter().getItemCount());
        View selected=focused();instrument.runOnMainSync(selected::performLongClick);AlertDialog remove=(AlertDialog)field("channelActions");assertTrue(remove.isShowing());
        instrument.runOnMainSync(()->{remove.getListView().requestFocus();remove.getListView().setSelection(1);});instrument.waitForIdleSync();key(KeyEvent.KEYCODE_DPAD_CENTER);assertFalse(repo.favorite(id));awaitFocus("频道分组 · 收藏");
        group("卫视");awaitFocus("播放频道 · 测试频道 41");assertEquals(40,((RecyclerView)field("grid")).getAdapter().getItemCount());
    }
    public void testRefreshAndPlayerReturnPreserveCurrentCard()throws Exception {
        launch();key(KeyEvent.KEYCODE_DPAD_RIGHT);awaitFocus("播放频道 · 测试频道 2");
        java.lang.reflect.Method refresh=LiveBrowseActivity.class.getDeclaredMethod("refresh",boolean.class);refresh.setAccessible(true);
        int before=(Integer)field("renderGeneration");instrument.runOnMainSync(()->{try{refresh.invoke(activity,false);}catch(Exception e){throw new RuntimeException(e);}});
        long end=SystemClock.uptimeMillis()+4000;while((Integer)field("renderGeneration")==before&&SystemClock.uptimeMillis()<end)Thread.sleep(50);assertTrue((Integer)field("renderGeneration")>before);awaitFocus("播放频道 · 测试频道 2");
        key(KeyEvent.KEYCODE_DPAD_CENTER);end=SystemClock.uptimeMillis()+5000;
        while(playerActivity==null&&SystemClock.uptimeMillis()<end){instrument.runOnMainSync(()->{for(Activity c:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(c instanceof LivePlayerActivity)playerActivity=c;});Thread.sleep(100);}
        assertNotNull("Confirm did not open playback",playerActivity);assertTrue(playerActivity.getIntent().getBooleanExtra("tvRemote",false));
        key(KeyEvent.KEYCODE_BACK);awaitFocus("播放频道 · 测试频道 2");
    }
}
