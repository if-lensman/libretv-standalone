package family.libretv.tv;

import android.app.*;
import android.content.*;
import android.graphics.Bitmap;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import org.json.*;
import java.util.*;

/** Exercise content identity, source edits and remote focus on the real native views. */
@androidx.media3.common.util.UnstableApi
public final class PiankeDesignFlowTest extends TestCase {
    private Instrumentation instrument;private Context context;private Activity activity;
    private final Map<String,Map<String,?>> backups=new HashMap<>();
    private static final String ROOT="http://10.0.2.2:19001";
    @Override protected void setUp()throws Exception {
        instrument=InstrumentationRegistry.getInstrumentation();context=instrument.getTargetContext();
        for(String name:new String[]{"standalone-library","standalone-playback","standalone-player","phone-ui","phone-cast","recommendations","github-updates","live-library"}){SharedPreferences p=context.getSharedPreferences(name,0);backups.put(name,new HashMap<>(p.getAll()));p.edit().clear().commit();}
        context.getSharedPreferences("github-updates",0).edit().putLong("lastCheck",System.currentTimeMillis()).commit();
        new RecommendationRepository(context).setEnabled(false);
        new LocalRepository(context).saveSources(new JSONArray().put(source()));
        for(int i=4;i>=1;i--){JSONObject item=item(i);new PlaybackStore(context).add(source(),item,item.optString("name"));new PlaybackStore(context).save(source(),item,item.optString("name"),i-1,i*27000,i==4?0:100000);new LocalRepository(context).favorites("/api/favorites",new JSONObject().put("source",source()).put("item",item),"POST");}
    }
    @Override protected void tearDown()throws Exception {
        if(activity!=null){instrument.runOnMainSync(activity::finish);instrument.waitForIdleSync();}
        for(Map.Entry<String,Map<String,?>> row:backups.entrySet()){
            SharedPreferences.Editor e=context.getSharedPreferences(row.getKey(),0).edit().clear();
            for(Map.Entry<String,?> p:row.getValue().entrySet()){String k=p.getKey();Object v=p.getValue();if(v instanceof String)e.putString(k,(String)v);else if(v instanceof Boolean)e.putBoolean(k,(Boolean)v);else if(v instanceof Long)e.putLong(k,(Long)v);else if(v instanceof Integer)e.putInt(k,(Integer)v);else if(v instanceof Float)e.putFloat(k,(Float)v);else if(v instanceof Set)e.putStringSet(k,new HashSet<>((Set<String>)v));}e.commit();
        }
    }
    private JSONObject source()throws Exception{return new JSONObject().put("key",LocalRepository.key(ROOT+"/pianke/cms")).put("url",ROOT+"/pianke/cms").put("name","演示影视来源").put("enabled",true);}
    private JSONObject item(int i)throws Exception{return new JSONObject().put("vodId",String.valueOf(i)).put("name",new String[]{"山海之间","星河漫游","春日来信","远山回响"}[i-1]).put("sourceName","演示影视来源").put("sourceKey",LocalRepository.key(ROOT+"/pianke/cms")).put("pic",i==4?"":ROOT+"/pianke-cover.jpg");}
    private void launch(Class<? extends Activity> type,String destination)throws Exception{
        Intent intent=new Intent(context,type).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
        if(destination!=null)intent.putExtra("tvDestination",destination).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER);
        activity=instrument.startActivitySync(intent);instrument.waitForIdleSync();Thread.sleep(350);
    }
    private void collect(View v,List<View> all){all.add(v);if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),all);}
    private List<View> views(){List<View> all=new ArrayList<>();instrument.runOnMainSync(()->collect(activity.getWindow().getDecorView(),all));return all;}
    private boolean has(String text){for(View v:views())if(v instanceof TextView&&((TextView)v).getText().toString().contains(text))return true;return false;}
    private void await(String text)throws Exception{long end=SystemClock.uptimeMillis()+10000;while(SystemClock.uptimeMillis()<end){if(has(text))return;Thread.sleep(60);}fail("Missing text: "+text);}
    private void click(String label){for(View v:views())if(v instanceof Button&&v.isShown()) {String value=((Button)v).getText().toString();if(value.equals(label)||value.startsWith(label+"    ›")){instrument.runOnMainSync(v::performClick);instrument.waitForIdleSync();return;}}fail("Missing button: "+label);}
    private void invoke(String method){instrument.runOnMainSync(()->{try{java.lang.reflect.Method m=activity.getClass().getDeclaredMethod(method);m.setAccessible(true);m.invoke(activity);}catch(Exception e){throw new RuntimeException(e);}});instrument.waitForIdleSync();}
    private AlertDialog dialog(){try{java.lang.reflect.Field f=activity.getClass().getDeclaredField("dialog");f.setAccessible(true);return (AlertDialog)f.get(activity);}catch(Exception e){throw new RuntimeException(e);}}
    private void choice(int index){instrument.runOnMainSync(()->{AlertDialog d=dialog();ListView list=d.getListView();list.performItemClick(list.getChildAt(index),index,list.getAdapter().getItemId(index));});instrument.waitForIdleSync();}
    private void capture(String name)throws Exception{Thread.sleep(800);Bitmap image=instrument.getUiAutomation().takeScreenshot();try(java.io.FileOutputStream out=context.openFileOutput(name,0)){image.compress(Bitmap.CompressFormat.PNG,100,out);}try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),name))){image.compress(Bitmap.CompressFormat.PNG,100,out);}image.recycle();}
    private void fill(String hint,String value){for(View v:views())if(v instanceof EditText&&hint.contentEquals(((EditText)v).getHint())){instrument.runOnMainSync(()->((EditText)v).setText(value));return;}fail("Missing input "+hint);}
    public void testPhoneHistoryFavoritesPlaylistAndDetails()throws Exception{
        launch(PhoneActivity.class,null);click("历史");await("接着看");
        assertEquals(3,views().stream().filter(v->v instanceof ProgressBar).count()); // Unknown duration has no fabricated ratio.
        capture("pianke-phone-history.png");click("收藏");await("4 部收藏");capture("pianke-phone-favorites.png");
        View cover=views().stream().filter(v->"山海之间，演示影视来源".contentEquals(String.valueOf(v.getContentDescription()))).findFirst().orElse(null);assertNotNull(cover);instrument.runOnMainSync(cover::performClick);await("选择剧集 · 共 24 集");assertTrue(has("继续播放 · 第 1 集"));capture("pianke-phone-detail.png");
        invoke("playlistScreen");capture("pianke-phone-playlist.png");
        View menu=views().stream().filter(v->"管理 · 山海之间".contentEquals(String.valueOf(v.getContentDescription()))).findFirst().orElse(null);assertNotNull(menu);instrument.runOnMainSync(menu::performClick);choice(1);
        instrument.runOnMainSync(()->dialog().getButton(AlertDialog.BUTTON_POSITIVE).performClick());await("我的片单");
        long end=SystemClock.uptimeMillis()+3000;while(new PlaybackStore(context).contains(source(),item(1))&&SystemClock.uptimeMillis()<end)Thread.sleep(50);
        assertFalse(new PlaybackStore(context).contains(source(),item(1)));assertNotNull(new PlaybackStore(context).find(source(),item(1)));assertEquals(4,new PlaybackStore(context).all().length());
    }
    public void testPhoneSettingsAndManualSourceFeedback()throws Exception{
        launch(PhoneActivity.class,null);click("设置");await("内容来源");capture("pianke-phone-settings.png");
        click("影视来源");await("手动添加来源");assertFalse(views().stream().anyMatch(v->v instanceof EditText&&"M3U 订阅地址".contentEquals(((EditText)v).getHint())));
        fill("源名称","手动演示");fill("来源地址","");click("添加来源");await("源地址必须是有效 HTTP(S) 地址");assertEquals(1,new LocalRepository(context).sources().length());
        fill("来源地址",ROOT+"/cms");click("添加来源");await("来源已添加");assertEquals(2,new LocalRepository(context).sources().length());capture("pianke-phone-sources.png");
        click("设置");click("直播订阅");await("导入 M3U 文件");assertFalse(has("手动添加来源"));capture("pianke-phone-live-sources.png");
        click("设置");click("关于片刻");instrument.waitForIdleSync();assertFalse(instrument.getUiAutomation().getRootInActiveWindow().findAccessibilityNodeInfosByText(PiankeUi.SLOGAN).isEmpty());capture("pianke-phone-about-dialog.png");
    }
    public void testTvHistoryAndLibraryNavigation()throws Exception{
        instrument.setInTouchMode(false);launch(TvHomeActivity.class,null);
        // Use the real header entry, then real D-pad navigation over the new content cards.
        click("历史");await("接着看");instrument.waitForIdleSync();Thread.sleep(300);
        assertNotNull(activity.getCurrentFocus());assertFalse(activity.getCurrentFocus().isInTouchMode());
        instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT);instrument.waitForIdleSync();assertNotNull(activity.getCurrentFocus());capture("pianke-tv-history.png");
        instrument.runOnMainSync(activity::finish);launch(MainActivity.class,"favorites");await("山海之间");capture("pianke-tv-favorites.png");
        View card=views().stream().filter(v->"山海之间".contentEquals(String.valueOf(v.getContentDescription()))).findFirst().orElse(null);assertNotNull(card);instrument.runOnMainSync(card::performClick);await("选择剧集");capture("pianke-tv-detail.png");
        click("设置");await("内容来源");capture("pianke-tv-settings.png");click("视频来源");await("添加来源 / 扫码导入");capture("pianke-tv-sources.png");
        click("‹ 返回设置");click("播放与显示");assertTrue(views().stream().anyMatch(v->v instanceof Switch));click("应用");await(PiankeUi.SLOGAN);capture("pianke-tv-about.png");
    }
    public void testUpdateWordmarkAndSlogan()throws Exception{
        launch(PhoneActivity.class,null);capture("pianke-phone-home-icon.png");
        launch(UpdateActivity.class,null);await(PiankeUi.SLOGAN);
        assertTrue(views().stream().anyMatch(v->v instanceof ImageView&&"片刻".contentEquals(String.valueOf(v.getContentDescription()))));
        assertTrue(has("当前版本"));assertTrue(has("更新通道"));capture("pianke-phone-update.png");
    }
    public void testEmptyAndUnknownDuration()throws Exception{
        context.getSharedPreferences("standalone-playback",0).edit().putString("history","[]").putString("playlist","[]").commit();
        new LocalRepository(context).favorites("/api/favorites/"+new LocalRepository(context).favorites("/api/favorites",null,"GET").getJSONArray("items").getJSONObject(0).getString("id"),null,"DELETE");
        launch(PhoneActivity.class,null);click("历史");await("还没有观看记录");assertEquals(0,views().stream().filter(v->v instanceof ProgressBar).count());capture("pianke-phone-empty.png");click("片单");await("片单还是空的");
    }
}
