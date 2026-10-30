package family.libretv.tv;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import java.util.*;

/** Run on a landscape display; exercise system navigation as well as hardware Back events. */
@androidx.media3.common.util.UnstableApi
public final class SystemBackFlowTest extends TestCase {
    private Instrumentation instrument;
    private Context context;
    private Activity activity;
    private final Map<String,Map<String,?>> backups=new HashMap<>();
    @Override protected void setUp() {
        instrument=InstrumentationRegistry.getInstrumentation();context=instrument.getTargetContext();
        for(String name:new String[]{"recommendations","github-updates","live-library"}) {
            SharedPreferences prefs=context.getSharedPreferences(name,0);backups.put(name,new HashMap<>(prefs.getAll()));prefs.edit().clear().commit();
        }
        new RecommendationRepository(context).setEnabled(false);
        context.getSharedPreferences("github-updates",0).edit().putLong("lastCheck",System.currentTimeMillis()).commit();
    }
    @Override protected void tearDown() {
        if(activity!=null)instrument.runOnMainSync(activity::finish);
        instrument.waitForIdleSync();
        for(Map.Entry<String,Map<String,?>> row:backups.entrySet()) {
            SharedPreferences.Editor e=context.getSharedPreferences(row.getKey(),0).edit().clear();
            for(Map.Entry<String,?> p:row.getValue().entrySet()) {
                String k=p.getKey();Object v=p.getValue();
                if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));
            }
            e.commit();
        }
    }
    private void launch(Intent intent)throws Exception {
        if(intent.getComponent().getClassName().equals(PhoneActivity.class.getName())) {
            activity=instrument.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        } else {
            activity=instrument.startActivitySync(new Intent(context,PhoneActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
            activity=instrument.startActivitySync(intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        }
        long end=SystemClock.uptimeMillis()+5000;
        while(!activity.hasWindowFocus()&&SystemClock.uptimeMillis()<end)Thread.sleep(50);
        assertTrue("Activity has window focus",activity.hasWindowFocus());instrument.waitForIdleSync();Thread.sleep(400);
    }
    private Object field(String name)throws Exception {
        java.lang.reflect.Field f=activity.getClass().getDeclaredField(name);f.setAccessible(true);return f.get(activity);
    }
    private void back(boolean navigation)throws Exception {
        if(navigation)assertTrue(instrument.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK));
        else instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_BACK);
        instrument.waitForIdleSync();Thread.sleep(450);
    }
    private View named(View view,String description) {
        if(description.equals(String.valueOf(view.getContentDescription())))return view;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++){View found=named(((ViewGroup)view).getChildAt(i),description);if(found!=null)return found;}
        return null;
    }
    private void phone(boolean navigation)throws Exception {
        launch(new Intent(context,PhoneActivity.class));
        for(String entry:new String[]{"设置","搜索","历史"}) {
            instrument.runOnMainSync(()->{
                View root=activity.getWindow().getDecorView();View button=named(root,"侧边导航 · "+entry);if(button==null)button=named(root,"底部导航 · "+entry);
                assertNotNull("Navigation "+entry,button);button.performClick();
            });instrument.waitForIdleSync();assertFalse("Entered child page",field("page").equals("home"));
            back(navigation);assertFalse("Back keeps phone Activity alive",activity.isFinishing());assertEquals("home",field("page"));
        }
    }
    public void testPhoneHardwareBack()throws Exception {phone(false);}
    public void testPhoneSystemNavigationBack()throws Exception {phone(true);}
    private void vod(boolean navigation)throws Exception {
        launch(new Intent(context,DirectPlayerActivity.class).putExtra("url","http://10.0.2.2:19001/demo.mp4").putExtra("title","System Back"));
        instrument.runOnMainSync(()->((androidx.media3.ui.PlayerView)uncheckedField("playerView")).showController());
        back(navigation);assertFalse("First Back hides controls",activity.isFinishing());assertEquals(false,field("controlsVisible"));
        back(navigation);assertTrue("Second Back leaves the landscape player",activity.isFinishing());
    }
    private Object uncheckedField(String name){try{return field(name);}catch(Exception e){throw new RuntimeException(e);}}
    public void testVodHardwareBack()throws Exception {vod(false);}
    public void testVodSystemNavigationBack()throws Exception {vod(true);}
    private void live(boolean navigation,boolean controls,boolean picker)throws Exception {
        LiveRepository repo=new LiveRepository(context);repo.importPlaylist("Back fixture","#EXTM3U\n#EXTINF:-1,Test\nhttp://10.0.2.2:19001/live/demo-stream\n");
        launch(new Intent(context,LivePlayerActivity.class).putExtra("channelId",repo.channels().getJSONObject(0).getString("id")));
        long end=SystemClock.uptimeMillis()+20000;boolean[] decoded={false};
        while(SystemClock.uptimeMillis()<end){instrument.runOnMainSync(()->decoded[0]=(Boolean)uncheckedField("renderedFrame"));if(decoded[0])break;Thread.sleep(75);}
        assertTrue("Back is tested during decoded live playback",decoded[0]);
        instrument.runOnMainSync(()->{
            androidx.media3.ui.PlayerView video=(androidx.media3.ui.PlayerView)uncheckedField("video");
            if(controls)video.showController();else video.hideController();
            if(picker)((View)uncheckedField("channelButton")).performClick();
        });
        if(picker){
            assertEquals(true,field("pickerOpen"));back(navigation);
            assertFalse("Back closes picker before leaving",activity.isFinishing());assertEquals(false,field("pickerOpen"));
        }
        back(navigation);assertTrue("One Back returns from touch live playback",activity.isFinishing());
        assertEquals("Back must not force portrait",android.content.pm.ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED,activity.getRequestedOrientation());
        long stopped=SystemClock.uptimeMillis()+3000;while(SystemClock.uptimeMillis()<stopped&&field("player")!=null)Thread.sleep(50);
        assertNull("Playback released after leaving",field("player"));
    }
    public void testLiveHardwareBack()throws Exception {live(false,true,false);}
    public void testLiveSystemNavigationBack()throws Exception {live(true,true,false);}
    private void liveWhileConnecting(boolean navigation,boolean failed)throws Exception {
        String route=failed?"/live/reject.m3u8":"/live/slow.m3u8";
        LiveRepository repo=new LiveRepository(context);repo.importPlaylist("Connecting fixture","#EXTM3U\n#EXTINF:-1,Connecting\nhttp://10.0.2.2:19001"+route+"\n");
        launch(new Intent(context,LivePlayerActivity.class).putExtra("channelId",repo.channels().getJSONObject(0).getString("id")));
        if(failed){
            long end=SystemClock.uptimeMillis()+5000;
            while(SystemClock.uptimeMillis()<end&&!(Boolean)field("terminal"))Thread.sleep(50);
            assertEquals("Rejected source reaches terminal error",true,field("terminal"));
        }
        instrument.runOnMainSync(()->{
            assertEquals(View.VISIBLE,((View)uncheckedField("statusPanel")).getVisibility());
            ((androidx.media3.ui.PlayerView)uncheckedField("video")).showController();
        });
        back(navigation);assertTrue("Back exits even during connection or error",activity.isFinishing());
        Thread.sleep(600);assertNull("No player survives exit",field("player"));
        assertEquals("Recovery is inactive after exit",false,field("active"));assertNull("Retry canceled after exit",field("retryTask"));
    }
    public void testLiveConnectingHardwareBack()throws Exception {liveWhileConnecting(false,false);}
    public void testLiveConnectingSystemNavigationBack()throws Exception {liveWhileConnecting(true,false);}
    public void testLiveErrorHardwareBack()throws Exception {liveWhileConnecting(false,true);}
    public void testLiveErrorSystemNavigationBack()throws Exception {liveWhileConnecting(true,true);}
    public void testLiveHiddenControlsHardwareBack()throws Exception {live(false,false,false);}
    public void testLiveHiddenControlsSystemNavigationBack()throws Exception {live(true,false,false);}
    public void testLivePickerHardwareBack()throws Exception {live(false,true,true);}
    public void testLivePickerSystemNavigationBack()throws Exception {live(true,true,true);}
}
