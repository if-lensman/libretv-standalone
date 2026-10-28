package family.libretv.tv;

import android.app.Activity;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.common.Player;
import junit.framework.TestCase;
import org.json.*;
import java.util.*;

/** Uses a CMS/media fixture directly; never a NAS/API service. Run on the API30 phone AVD. */
@androidx.media3.common.util.UnstableApi
public final class StandaloneFlowTest extends TestCase {
    private android.app.Instrumentation instrument;
    private Context context;
    private Map<String,?> previous;
    private Activity activity;
    private Map<String,?> oldHistory, oldPlayer;
    private LocalRepository repo;
    private final String root="http://10.0.2.2:19001";
    @Override protected void setUp()throws Exception{
        instrument=InstrumentationRegistry.getInstrumentation();context=instrument.getTargetContext();
        SharedPreferences prefs=context.getSharedPreferences("standalone-library",0);previous=new HashMap<>(prefs.getAll());prefs.edit().clear().commit();repo=new LocalRepository(context);
        oldHistory=new HashMap<>(context.getSharedPreferences("standalone-playback",0).getAll());
        oldPlayer=new HashMap<>(context.getSharedPreferences("standalone-player",0).getAll());
        context.getSharedPreferences("standalone-playback",0).edit().clear().commit();
        context.getSharedPreferences("standalone-player",0).edit().clear().commit();
    }
    @Override protected void tearDown()throws Exception{
        if(activity!=null)instrument.runOnMainSync(()->activity.finish());
        SharedPreferences.Editor edit=context.getSharedPreferences("standalone-library",0).edit().clear();
        for(Map.Entry<String,?> e:previous.entrySet()) {
            if(e.getValue() instanceof String)edit.putString(e.getKey(),(String)e.getValue());
            else if(e.getValue() instanceof Set)edit.putStringSet(e.getKey(),new HashSet<>((Set<String>)e.getValue()));
        }
        edit.commit();
        restore("standalone-playback",oldHistory);restore("standalone-player",oldPlayer);
        instrument.waitForIdleSync();
    }
    private void restore(String name,Map<String,?> old) {
        SharedPreferences.Editor e=context.getSharedPreferences(name,0).edit().clear();
        for(Map.Entry<String,?> entry:old.entrySet()){
            Object value=entry.getValue();if(value instanceof String)e.putString(entry.getKey(),(String)value);
            else if(value instanceof Boolean)e.putBoolean(entry.getKey(),(Boolean)value);
            else if(value instanceof Float)e.putFloat(entry.getKey(),(Float)value);
        }e.commit();
    }
    private JSONObject source(String path)throws Exception{return new JSONObject().put("key",LocalRepository.key(root+path)).put("name","Fixture source").put("url",root+path);}
    public void testDefaultsDeletionAndEmptyPersistence()throws Exception{
        JSONArray all=repo.sources();
        if(all.length()==0) {repo.importSources(new JSONArray().put(source("/cms")).toString());all=repo.sources();}
        for(int i=0;i<all.length();i++)repo.removeSource(all.getJSONObject(i).getString("key"));
        assertEquals(0,new LocalRepository(context).sources().length());
    }
    public void testImportSubscriptionDedupAndUnsupportedTypes()throws Exception{
        repo.saveSources(new JSONArray());assertEquals(1,repo.importSources(root+"/subscription"));assertEquals(0,repo.importSources(root+"/subscription"));
        try{repo.importSources("{\"sites\":[{\"type\":3,\"api\":\"spider.jar\"}]}");fail();}catch(java.io.IOException expected){}
        assertEquals(1,repo.sources().length());
    }
    public void testManualSingleSourceParsingAndPersistence() throws Exception {
        repo.saveSources(new JSONArray());
        String single = new JSONObject().put("name", "手动测试源").put("url", "https://manual.example/api.php/provide/vod/").toString();
        assertEquals(1, repo.importSources(single));
        assertEquals("https://manual.example/api.php/provide/vod", new LocalRepository(context).sources().getJSONObject(0).getString("url"));
        assertEquals(0, repo.importSources(single));
        assertEquals(1, LocalRepository.parseSources("{\"sites\":[{\"name\":\"TVBox 测试\",\"type\":1,\"api\":\"https://tvbox.example/api\"}]}").length());
        try { repo.importSources("{\"name\":\"无效\",\"url\":\"invalid\"}"); fail("Invalid URL accepted"); }
        catch (java.io.IOException expected) { assertTrue(expected.getMessage().contains("HTTP(S)")); }
        assertEquals(1, repo.sources().length());
    }

    public void testPhoneManualSourceFeedbackAndSave() throws Exception {
        repo.saveSources(new JSONArray());
        activity=instrument.startActivitySync(new Intent(context,PhoneActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        openSources();
        fillManual("手动测试源", ""); click("添加来源"); await("源地址必须是有效 HTTP(S) 地址");
        assertEquals(0, repo.sources().length());
        List<View> current=views();
        assertTrue(current.stream().anyMatch(v -> v instanceof TextView && "源地址必须是有效 HTTP(S) 地址".contentEquals(((TextView)v).getText()) && v.getParent() instanceof LinearLayout && ((LinearLayout)v.getParent()).getChildAt(0) instanceof TextView && "手动添加来源".contentEquals(((TextView)((LinearLayout)v.getParent()).getChildAt(0)).getText())));
        fillManual("  手动测试源  ", "https://manual.example/api.php/provide/vod/"); click("添加来源"); await("来源已添加");
        assertEquals(1,new LocalRepository(context).sources().length());
        assertEquals("手动测试源",repo.sources().getJSONObject(0).getString("name"));
        await("删除 · 手动测试源");
        fillManual("重复", "https://manual.example/api.php/provide/vod"); click("添加来源"); await("该来源地址已存在，无需重复添加");
        assertEquals(1,repo.sources().length());
    }
    public void testPhoneSeparatesVideoAndLiveSourceSettings() throws Exception {
        activity=instrument.startActivitySync(new Intent(context,PhoneActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        click("设置"); await("扫码添加视频源"); await("扫码添加直播源");
        View video=null,live=null;
        for(View v:views()) if(v instanceof Button) {
            if("扫码添加视频源".contentEquals(((Button)v).getText())) video=v;
            if("扫码添加直播源".contentEquals(((Button)v).getText())) live=v;
        }
        assertNotNull(video);assertNotNull(live);assertNotSame(video.getParent(),live.getParent());
        assertFalse(texts().contains("扫码添加来源"));
        click("影视来源");await("视频来源配置");await("手动添加来源");
        assertFalse(texts().contains("导入 M3U 文件"));
        click("设置");click("直播订阅");await("导入 M3U 文件");await("添加直播订阅");
        assertFalse(texts().contains("手动添加来源"));assertFalse(texts().contains("添加来源"));
    }

    private void fillManual(String name, String url) {
        List<View> all=views();
        instrument.runOnMainSync(()->{ for(View v:all) if(v instanceof EditText) {
            EditText input=(EditText)v;
            if("源名称".contentEquals(input.getHint())) input.setText(name);
            if("来源地址".contentEquals(input.getHint())) input.setText(url);
        }});
    }

    public void testSearchDetailAndLocalFavorites()throws Exception{
        JSONObject s=source("/cms");repo.saveSources(new JSONArray().put(s).put(source("/bad")));
        JSONObject response=repo.search("Fixture",repo.sources());assertEquals(2,response.getJSONArray("list").length());assertEquals(1,response.getJSONArray("failures").length());
        JSONObject detail=repo.detail("1",s);assertEquals(2,detail.getJSONArray("episodes").length());assertTrue(detail.getJSONArray("episodes").getString(0).contains(".m3u8"));
        JSONObject body=new JSONObject().put("source",s).put("item",response.getJSONArray("list").getJSONObject(0));
        JSONObject added=repo.favorites("/api/favorites",body,"POST").getJSONObject("item");repo.favorites("/api/favorites",body,"POST");
        assertEquals(1,new LocalRepository(context).favorites("/api/favorites",null,"GET").getJSONArray("items").length());
        repo.favorites("/api/favorites/"+added.getString("id"),null,"DELETE");assertEquals(0,repo.favorites("/api/favorites",null,"GET").getJSONArray("items").length());
    }
    public void testPhoneHasNoLoginAndCanSearchAndDeleteSource()throws Exception{
        repo.saveSources(new JSONArray().put(source("/cms")));
        activity=instrument.startActivitySync(new Intent(context,PhoneActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        await("搜索电影、电视剧");assertFalse(texts().contains("NAS 密码"));
        List<View> fields=views();instrument.runOnMainSync(()->{for(View v:fields)if(v instanceof EditText)((EditText)v).setText("Fixture");});
        click("搜索");await("Fixture title");click("Fixture title");await("第 1 集");click("加入本机收藏");await("已加入本机收藏");click("取消收藏");await("已取消收藏");click("加入本机收藏");await("已加入本机收藏");click("返回选片");openLibrary("本机收藏夹");await("Fixture title");
        click("返回选片");openSources();await("删除 · Fixture source");click("删除 · Fixture source");
        Thread.sleep(200);
        java.lang.reflect.Field dialogField=PhoneActivity.class.getDeclaredField("dialog");dialogField.setAccessible(true);
        instrument.runOnMainSync(()->((android.app.AlertDialog)read(dialogField,activity)).getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick());
        long deadline=System.currentTimeMillis()+5000;while(repo.sources().length()!=0 && System.currentTimeMillis()<deadline)Thread.sleep(100);
        assertEquals(0,new LocalRepository(context).sources().length());
    }
    public void testDirectHlsRedirectRelativeSegmentsAndMp4Playback()throws Exception{
        for(String path:new String[]{"/redirect.m3u8","/sample.mp4"}){
            activity=instrument.startActivitySync(new Intent(context,DirectPlayerActivity.class).putExtra("url",root+path).putExtra("title","Synthetic playback").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
            java.lang.reflect.Field field=DirectPlayerActivity.class.getDeclaredField("player");field.setAccessible(true);
            long deadline=System.currentTimeMillis()+15000;boolean[] ready={false};
            while(System.currentTimeMillis()<deadline){instrument.runOnMainSync(()->{ExoPlayer p=(ExoPlayer)read(field,activity);ready[0]=p!=null && p.getPlaybackState()==Player.STATE_READY && p.getCurrentPosition()>200;});if(ready[0])break;Thread.sleep(100);}
            assertTrue("Playback did not advance: "+path,ready[0]);instrument.runOnMainSync(()->activity.finish());instrument.waitForIdleSync();activity=null;
        }
    }
    public void testDisabledSourcesAndConfigurationExportRoundTrip()throws Exception{
        JSONObject good=source("/cms"),bad=source("/bad");repo.saveSources(new JSONArray().put(good).put(bad));
        repo.setSourceEnabled(bad.getString("key"),false);
        String export=repo.exportSources();repo.saveSources(new JSONArray());assertEquals(2,repo.importSources(export));
        assertFalse(new LocalRepository(context).sources().getJSONObject(1).getBoolean("enabled"));
        assertEquals(0,repo.search("Fixture",repo.sources()).getJSONArray("failures").length());
        repo.setSourceEnabled(good.getString("key"),false);
        try{repo.search("Fixture",repo.sources());fail("Disabled-only search must give a configuration error");}catch(java.io.IOException expected){assertTrue(expected.getMessage().contains("启用"));}
    }
    private JSONObject playbackItem()throws Exception{
        return new JSONObject().put("vodId","1").put("name","Fixture title").put("sourceKey",source("/cms").getString("key")).put("sourceName","Fixture source");
    }
    private void launchSeries(int index)throws Exception{
        activity=instrument.startActivitySync(new Intent(context,DirectPlayerActivity.class)
            .putStringArrayListExtra("episodes",new ArrayList<>(Arrays.asList(root+"/redirect.m3u8",root+"/sample.mp4")))
            .putExtra("index",index).putExtra("title","Fixture title").putExtra("source",source("/cms").toString()).putExtra("item",playbackItem().toString())
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        awaitPlayer();
    }
    private ExoPlayer currentPlayer(){try{java.lang.reflect.Field f=DirectPlayerActivity.class.getDeclaredField("player");f.setAccessible(true);return (ExoPlayer)f.get(activity);}catch(Exception e){throw new RuntimeException(e);}}
    private int currentIndex(){try{java.lang.reflect.Field f=DirectPlayerActivity.class.getDeclaredField("index");f.setAccessible(true);return f.getInt(activity);}catch(Exception e){throw new RuntimeException(e);}}
    private void awaitPlayer()throws Exception{
        long end=System.currentTimeMillis()+15000;boolean[] ready={false};
        while(System.currentTimeMillis()<end){instrument.runOnMainSync(()->{ExoPlayer p=currentPlayer();ready[0]=p!=null && p.getPlaybackState()==Player.STATE_READY;});if(ready[0])return;Thread.sleep(100);}fail("player not ready");
    }
    public void testPreviousNextAndPersistentResume()throws Exception{
        launchSeries(0);assertFalse(views().stream().filter(v->v instanceof Button && "上一集".contentEquals(((Button)v).getText())).findFirst().get().isEnabled());
        instrument.runOnMainSync(()->{currentPlayer().seekTo(3000);currentPlayer().pause();});
        click("下一集");awaitPlayer();assertEquals(1,currentIndex());
        assertFalse(views().stream().filter(v->v instanceof Button && "下一集".contentEquals(((Button)v).getText())).findFirst().get().isEnabled());
        click("上一集");awaitPlayer();assertEquals(0,currentIndex());
        instrument.runOnMainSync(()->{currentPlayer().seekTo(4000);currentPlayer().pause();});
        finishPlayer();
        JSONObject saved=new PlaybackStore(context).find(source("/cms"),playbackItem());assertNotNull(saved);assertTrue("Saved history: "+saved,saved.getLong("position")>=3900);
        assertFalse(saved.toString().contains("redirect.m3u8"));assertFalse(saved.toString().contains("sample.mp4"));
        launchSeries(0);long[] position={0};instrument.runOnMainSync(()->position[0]=currentPlayer().getCurrentPosition());assertTrue("Resume position was lost",position[0]>=3900);
    }
    public void testAutoNextCanBeDisabledAndStopsAtLastEpisode()throws Exception{
        launchSeries(0);
        instrument.runOnMainSync(()->{for(View v:findViewsOnMain())if(v instanceof CompoundButton)((CompoundButton)v).setChecked(false);currentPlayer().seekTo(currentPlayer().getDuration()-100);});
        Thread.sleep(1200);assertEquals(0,currentIndex());
        instrument.runOnMainSync(()->{for(View v:findViewsOnMain())if(v instanceof CompoundButton)((CompoundButton)v).setChecked(true);currentPlayer().seekTo(0);currentPlayer().play();currentPlayer().seekTo(currentPlayer().getDuration()-100);});
        long end=System.currentTimeMillis()+6000;while(currentIndex()!=1 && System.currentTimeMillis()<end)Thread.sleep(100);assertEquals(1,currentIndex());awaitPlayer();
        instrument.runOnMainSync(()->currentPlayer().seekTo(currentPlayer().getDuration()-100));Thread.sleep(1200);assertEquals(1,currentIndex());
    }
    private List<View> findViewsOnMain(){List<View> out=new ArrayList<>();collect(activity.getWindow().getDecorView(),out);return out;}
    public void testHistoryRefreshesDetailsAndContinuesSavedEpisode()throws Exception{
        repo.saveSources(new JSONArray().put(source("/cms")));
        new PlaybackStore(context).save(source("/cms"),playbackItem(),"Fixture title",1,4000,12000);
        activity=instrument.startActivitySync(new Intent(context,PhoneActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        await("搜索电影、电视剧");openLibrary("观看历史");await("Fixture title · 第 2 集 · 0:04");click("Fixture title · 第 2 集 · 0:04");await("继续播放 · 第 2 集");
        assertEquals(2L,views().stream().filter(v->v instanceof Button && (((Button)v).getText().toString().equals("第 1 集") || ((Button)v).getText().toString().equals("第 2 集"))).count());
    }
    private void finishPlayer()throws Exception {
        Activity old=activity;instrument.runOnMainSync(old::finish);long end=System.currentTimeMillis()+5000;boolean[] destroyed={false};
        while(System.currentTimeMillis()<end){instrument.runOnMainSync(()->destroyed[0]=old.isDestroyed());if(destroyed[0])break;Thread.sleep(50);}
        assertTrue("Activity did not finish",destroyed[0]);activity=null;
    }
    private void screenshot(String name)throws Exception {
        android.graphics.Bitmap image=instrument.getUiAutomation().takeScreenshot();assertNotNull(image);
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(new java.io.File(context.getExternalFilesDir(null),name))){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}image.recycle();
    }
    public void testRotationKeepsEpisodePositionAndPlaybackSettings()throws Exception {
        launchSeries(1);await("字幕");screenshot("standalone-player-portrait.png");
        instrument.runOnMainSync(()->{currentPlayer().seekTo(4000);currentPlayer().pause();});
        Activity old=activity;
        int target=old.getResources().getConfiguration().orientation==android.content.res.Configuration.ORIENTATION_LANDSCAPE
            ? android.content.pm.ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE;
        instrument.runOnMainSync(()->old.setRequestedOrientation(target));
        long end=System.currentTimeMillis()+8000;Activity[] replacement={null};
        while(System.currentTimeMillis()<end){
            instrument.runOnMainSync(()->{
                for(Activity candidate:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
                    if(candidate instanceof DirectPlayerActivity && candidate!=old)replacement[0]=candidate;
            });
            if(replacement[0]!=null)break;Thread.sleep(100);
        }
        assertNotNull("Rotation did not recreate the player",replacement[0]);activity=replacement[0];awaitPlayer();
        assertEquals(1,currentIndex());long[] position={0};boolean[] playing={true};instrument.runOnMainSync(()->{position[0]=currentPlayer().getCurrentPosition();playing[0]=currentPlayer().getPlayWhenReady();});
        assertTrue("Rotation lost seek position",position[0]>=3900);assertFalse(playing[0]);
        screenshot("standalone-player-landscape.png");
    }
    public void testQrChineseJsonAndCameraLuminanceDecode()throws Exception {
        String value=new JSONObject().put("sources",new JSONArray().put(new JSONObject().put("name","中文扫码来源").put("url",root+"/cms"))).toString();
        android.graphics.Bitmap qr=PairingQr.create(value,720);
        assertEquals(value,SourceQrDecoder.payload(SourceQrDecoder.bitmap(qr)));
        int width=qr.getWidth(),height=qr.getHeight();int[] colors=new int[width*height];qr.getPixels(colors,0,width,0,0,width,height);
        byte[] nv21=new byte[width*height*3/2];for(int n=0;n<colors.length;n++)nv21[n]=(byte)(colors[n]&255);
        assertEquals(value,SourceQrDecoder.payload(SourceQrDecoder.preview(nv21,width,height)));qr.recycle();
        assertEquals(root+"/subscription",SourceQrDecoder.payload("libretv://sources?url="+NasClient.encode(root+"/subscription")));
        try{SourceQrDecoder.payload("tel:123456");fail("unrelated QR accepted");}catch(IllegalArgumentException expected){}
    }
    public void testQrPhotoNeedsConfirmationAndImportsIntoLibrary()throws Exception {
        repo.saveSources(new JSONArray());
        activity=instrument.startActivitySync(new Intent(context,PhoneActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        openSources();await("扫码配置源");
        androidx.test.runner.lifecycle.ActivityLifecycleMonitor monitor=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance();
        click("扫码配置源");Activity[] scanner={null};long end=System.currentTimeMillis()+5000;
        while(scanner[0]==null && System.currentTimeMillis()<end){instrument.runOnMainSync(()->{for(Activity candidate:monitor.getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))if(candidate instanceof SourceScannerActivity)scanner[0]=candidate;});Thread.sleep(100);}
        assertNotNull(scanner[0]);
        String value=new JSONObject().put("sources",new JSONArray().put(new JSONObject().put("name","扫码测试源").put("url",root+"/cms"))).toString();
        android.graphics.Bitmap qr=PairingQr.create(value,720);java.io.File file=new java.io.File(context.getCacheDir(),"source-qr-test.png");
        try(java.io.FileOutputStream out=new java.io.FileOutputStream(file)){qr.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}qr.recycle();
        java.lang.reflect.Method result=SourceScannerActivity.class.getDeclaredMethod("onActivityResult",int.class,int.class,Intent.class);result.setAccessible(true);
        instrument.runOnMainSync(()->{try{result.invoke(scanner[0],71,Activity.RESULT_OK,new Intent().setData(android.net.Uri.fromFile(file)));}catch(Exception e){throw new RuntimeException(e);}});
        java.lang.reflect.Field field=PhoneActivity.class.getDeclaredField("dialog");field.setAccessible(true);android.app.AlertDialog[] confirm={null};
        end=System.currentTimeMillis()+7000;
        while(System.currentTimeMillis()<end){instrument.runOnMainSync(()->confirm[0]=(android.app.AlertDialog)read(field,activity));if(confirm[0]!=null && confirm[0].isShowing())break;Thread.sleep(100);}
        assertNotNull(confirm[0]);assertEquals(0,repo.sources().length());
        instrument.runOnMainSync(()->confirm[0].getButton(android.app.AlertDialog.BUTTON_POSITIVE).performClick());
        end=System.currentTimeMillis()+5000;while(repo.sources().length()==0 && System.currentTimeMillis()<end)Thread.sleep(100);
        assertEquals("扫码测试源",repo.sources().getJSONObject(0).getString("name"));assertTrue(file.delete());
    }
    public void testDefaultCatalogUpgradePreservesDeletionsDisabledAndCustomSources()throws Exception {
        SharedPreferences prefs=context.getSharedPreferences("standalone-library",0);
        JSONObject disabled=new JSONObject().put("key","legacy").put("name","自定义旧源名").put("url","https://legacy-a.example/api.php/provide/vod").put("enabled",false);
        JSONObject custom=source("/custom");
        prefs.edit().clear().putString("sources",new JSONArray().put(disabled).put(custom).toString()).commit();
        JSONArray newDefaults=LocalRepository.parseSources("{\"sources\":[{\"name\":\"旧源 A\",\"url\":\"https://legacy-a.example/api.php/provide/vod\"},{\"name\":\"旧源 B\",\"url\":\"https://legacy-b.example/api.php/provide/vod\"},{\"name\":\"新增来源\",\"url\":\"https://new.example/api.php/provide/vod\"}]}");
        newDefaults.getJSONObject(0).put("legacyDefault",true);newDefaults.getJSONObject(1).put("legacyDefault",true);
        LocalRepository.mergeDefaults(prefs,newDefaults);JSONArray all=repo.sources();assertEquals(3,all.length());
        assertEquals("自定义旧源名",all.getJSONObject(0).getString("name"));assertFalse(all.getJSONObject(0).getBoolean("enabled"));
        assertEquals(custom.getString("url"),all.getJSONObject(1).getString("url"));assertEquals("新增来源",all.getJSONObject(2).getString("name"));
        repo.removeSource(all.getJSONObject(2).getString("key"));LocalRepository.mergeDefaults(prefs,newDefaults);assertEquals(2,repo.sources().length());
        repo.saveSources(new JSONArray());LocalRepository.mergeDefaults(prefs,newDefaults);assertEquals(0,repo.sources().length());
    }
    public void testExpandedDefaultCatalogQrRoundTrip()throws Exception {
        JSONArray fixtures=new JSONArray();
        for(int i=0;i<13;i++) fixtures.put(new JSONObject().put("name","测试源 "+i).put("url","https://source"+i+".example/api.php/provide/vod").put("enabled",i<9));
        repo.saveSources(LocalRepository.parseSources(fixtures.toString()));
        String packed=SourceQrDecoder.sharePayload(repo.exportSources());
        android.graphics.Bitmap image=PairingQr.create(packed,720);
        JSONArray decoded=LocalRepository.parseSources(SourceQrDecoder.payload(SourceQrDecoder.bitmap(image)));image.recycle();
        assertEquals(13,decoded.length());
        assertEquals("测试源 0",decoded.getJSONObject(0).getString("name"));
    }
    public void testEmptyBundledCatalogIsValidButEmptyImportIsRejected()throws Exception {
        assertEquals(0,LocalRepository.parseSources("{\"sources\":[]}",true).length());
        try {LocalRepository.parseSources("{\"sources\":[]}");fail("Empty import accepted");}catch(java.io.IOException expected){}
    }

    public void testSearchReportsFastSourceBeforeEarlierSlowSource()throws Exception {
        JSONArray selected=new JSONArray().put(source("/slow")).put(source("/fast")).put(source("/bad"));
        List<String> order=new ArrayList<>();List<Integer> progress=new ArrayList<>();
        long start=System.currentTimeMillis();long[] first={0};
        JSONObject response=repo.search("Fixture",selected,(items,completed,total,failed)->{
            assertEquals(3,total);progress.add(completed);
            if(items.length()>0) { if(first[0]==0) first[0]=System.currentTimeMillis()-start; order.add(items.optJSONObject(0).optString("name")); }
        });
        assertEquals(Arrays.asList("Fixture fast","Fixture slow"),order);
        assertTrue("Fast source waited for slow source: "+first[0],first[0]<3000);
        assertEquals(Arrays.asList(1,2,3),progress);assertEquals(2,response.getJSONArray("list").length());assertEquals(1,response.getJSONArray("failures").length());
    }
    public void testPhoneResultsAppearWhileSearchingAndNavigationStaysPut()throws Exception {
        repo.saveSources(new JSONArray().put(source("/slow")).put(source("/fast")));
        activity=instrument.startActivitySync(new Intent(context,PhoneActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        instrument.runOnMainSync(()->{for(View v:findViewsOnMain())if(v instanceof EditText)((EditText)v).setText("Fixture");});
        click("搜索");await("搜索 · Fixture");await("Fixture fast");
        assertTrue("No partial progress shown",texts().stream().anyMatch(t->t.startsWith("正在搜索 · 1 / 2")));
        View card=views().stream().filter(v->v instanceof TextView && "Fixture fast".contentEquals(((TextView)v).getText())).findFirst().get();
        await("Fixture slow");assertTrue("Existing card replaced",card.isAttachedToWindow());
        assertTrue(texts().stream().anyMatch(t->t.startsWith("搜索已结束 · 2 / 2")));
        click("返回选片");
        instrument.runOnMainSync(()->{for(View v:findViewsOnMain())if(v instanceof EditText)((EditText)v).setText("Second");});
        click("搜索");await("Second fast");click("返回选片");openSources();Thread.sleep(4500);
        assertTrue("Background search replaced current page",texts().contains("扫码配置源"));
        click("返回选片");
        instrument.runOnMainSync(()->{for(View v:findViewsOnMain())if(v instanceof EditText)((EditText)v).setText("Third");});
        click("搜索");await("Third fast");click("停止搜索");Thread.sleep(4500);
        assertFalse("Cancelled source appended results",texts().contains("Third slow"));
    }
    public void testLocalPlaylistAndFavoritesSurviveReopenWithoutNetwork()throws Exception {
        // Invalid host makes accidental server access fail; all operations below are local.
        JSONObject src=new JSONObject().put("key","offline").put("name","离线源").put("url","https://offline.invalid/cms");
        JSONObject item=new JSONObject().put("vodId","42").put("name","离线影片");
        repo.favorites("/api/favorites",new JSONObject().put("source",src).put("item",item),"POST");
        PlaybackStore store=new PlaybackStore(context);store.add(src,item,"离线影片");store.add(src,item,"离线影片");
        assertEquals(1,store.playlist().length());
        store.save(src,item,"离线影片",2,125000,1800000);
        store=new PlaybackStore(context);assertEquals(125000L,store.find(src,item).getLong("position"));
        assertEquals(1,new LocalRepository(context).favorites("/api/favorites",null,"GET").getJSONArray("items").length());
        // Playlist progress must survive the bounded 50-item history and clearing its listing.
        for(int n=0;n<55;n++)store.save(src,new JSONObject().put("vodId","other"+n),"Other",0,1000,12000);
        assertEquals(50,store.all().length());assertEquals(2,store.find(src,item).getInt("index"));
        store.clear();assertEquals(125000L,new PlaybackStore(context).find(src,item).getLong("position"));
        store.remove(PlaybackStore.identity(src,item));assertEquals(0,store.playlist().length());
        assertEquals(1,repo.favorites("/api/favorites",null,"GET").getJSONArray("items").length());
    }
    public void testPlaylistUiResumesSavedPositionAndCanRemoveWithoutUnfavoriting()throws Exception {
        JSONObject src=source("/cms"),item=playbackItem();repo.saveSources(new JSONArray().put(src));
        repo.favorites("/api/favorites",new JSONObject().put("source",src).put("item",item),"POST");
        PlaybackStore store=new PlaybackStore(context);store.add(src,item,"Fixture title");store.save(src,item,"Fixture title",1,4000,12000);
        activity=instrument.startActivitySync(new Intent(context,PhoneActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        openLibrary("播放列表");await("上次看到第 2 集 · 0:04 / 0:12");screenshot("standalone-playlist.png");
        click("继续播放 · Fixture title");
        Activity[] playerActivity={null};long end=System.currentTimeMillis()+10000;
        while(playerActivity[0]==null && System.currentTimeMillis()<end){instrument.runOnMainSync(()->{
            for(Activity candidate:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
                if(candidate instanceof DirectPlayerActivity)playerActivity[0]=candidate;
        });Thread.sleep(50);}
        assertNotNull("Playlist did not open player",playerActivity[0]);Activity phone=activity;activity=playerActivity[0];awaitPlayer();
        long[] position={0};instrument.runOnMainSync(()->{currentPlayer().pause();position[0]=currentPlayer().getCurrentPosition();});
        assertEquals(1,currentIndex());assertTrue("Playlist did not restore seek: "+position[0],position[0]>=3900);
        assertTrue(new PlaybackStore(context).contains(src,item));finishPlayer();activity=phone;
        await("返回选片");click("返回选片");openLibrary("播放列表");click("移出列表 · Fixture title");
        await("播放列表为空，先搜索一部想看的影片吧");assertNotNull(new PlaybackStore(context).find(src,item));
        assertEquals(1,repo.favorites("/api/favorites",null,"GET").getJSONArray("items").length());
        click("返回选片");openLibrary("本机收藏夹");await("收藏仅保存在本机，无需 NAS、账号或服务器。");screenshot("standalone-favorites-local.png");
        click("加入播放列表 · Fixture title");await("已加入播放列表");assertEquals(1,new PlaybackStore(context).playlist().length());
        click("移除收藏 · Fixture title");await("暂无收藏，在影片详情页点击加入本机收藏");
        assertEquals(1,new PlaybackStore(context).playlist().length());
    }
    private void adoptRotatedPlayer(Activity old)throws Exception {
        long end=System.currentTimeMillis()+8000;Activity[] replacement={null};
        while(System.currentTimeMillis()<end) {
            instrument.runOnMainSync(()->{for(Activity candidate:androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(androidx.test.runner.lifecycle.Stage.RESUMED))
                if(candidate instanceof DirectPlayerActivity && candidate!=old)replacement[0]=candidate;});
            if(replacement[0]!=null)break;Thread.sleep(75);
        }
        assertNotNull("Player did not rotate",replacement[0]);activity=replacement[0];awaitPlayer();
    }
    private boolean chromeVisible() {
        boolean[] visible={false};instrument.runOnMainSync(()->{try {
            java.lang.reflect.Field field=DirectPlayerActivity.class.getDeclaredField("bottomChrome");field.setAccessible(true);
            visible[0]=((View)field.get(activity)).getVisibility()==View.VISIBLE;
        }catch(Exception e){throw new RuntimeException(e);}});return visible[0];
    }
    private void awaitChrome(boolean visible)throws Exception {
        long end=System.currentTimeMillis()+6500;while(chromeVisible()!=visible && System.currentTimeMillis()<end)Thread.sleep(100);
        assertEquals("Unexpected controls visibility",visible,chromeVisible());
    }
    public void testPhoneLandscapeIsFullScreenAndChromeHidesUntilTapped()throws Exception {
        context.getSharedPreferences("standalone-player",0).edit().putBoolean("autoNext",false).commit();
        launchSeries(0);instrument.runOnMainSync(()->{currentPlayer().pause();currentPlayer().seekTo(2000);});
        if(activity.getResources().getConfiguration().orientation!=android.content.res.Configuration.ORIENTATION_LANDSCAPE) {
            Activity old=activity;instrument.runOnMainSync(()->old.setRequestedOrientation(android.content.pm.ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));adoptRotatedPlayer(old);
        }
        instrument.runOnMainSync(()->{currentPlayer().seekTo(0);currentPlayer().play();});awaitChrome(false);
        instrument.runOnMainSync(()->{try {
            java.lang.reflect.Field view=DirectPlayerActivity.class.getDeclaredField("playerView");view.setAccessible(true);View player=(View)view.get(activity);
            View decor=activity.getWindow().getDecorView();assertEquals("Video width is constrained",decor.getWidth(),player.getWidth());
            assertEquals("Video height is constrained by controls",decor.getHeight(),player.getHeight());
            androidx.core.view.WindowInsetsCompat insets=androidx.core.view.ViewCompat.getRootWindowInsets(decor);assertNotNull(insets);
            assertFalse("Status bar still visible",insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.statusBars()));
            assertFalse("Navigation bar still visible",insets.isVisible(androidx.core.view.WindowInsetsCompat.Type.navigationBars()));
        }catch(Exception e){throw new RuntimeException(e);}});
        screenshot("phone-landscape-hidden.png");
        int[] location=new int[2];instrument.runOnMainSync(()->activity.getWindow().getDecorView().getLocationOnScreen(location));
        int x=activity.getResources().getDisplayMetrics().widthPixels/2,y=activity.getResources().getDisplayMetrics().heightPixels/2;
        long now=android.os.SystemClock.uptimeMillis();
        android.view.MotionEvent down=android.view.MotionEvent.obtain(now,now,android.view.MotionEvent.ACTION_DOWN,x,y,0),up=android.view.MotionEvent.obtain(now,now+50,android.view.MotionEvent.ACTION_UP,x,y,0);
        instrument.sendPointerSync(down);instrument.sendPointerSync(up);down.recycle();up.recycle();awaitChrome(true);
        instrument.runOnMainSync(()->currentPlayer().pause());Thread.sleep(3400);assertTrue("Paused controls faded",chromeVisible());
        screenshot("phone-landscape-controls.png");
        instrument.runOnMainSync(()->{currentPlayer().seekTo(0);currentPlayer().play();});click("倍速 1.0×");Thread.sleep(3400);
        assertTrue("Menu controls faded while speed dialog open",chromeVisible());
        java.lang.reflect.Field dialog=DirectPlayerActivity.class.getDeclaredField("dialog");dialog.setAccessible(true);
        instrument.runOnMainSync(()->((android.app.AlertDialog)read(dialog,activity)).dismiss());
        instrument.runOnMainSync(()->{currentPlayer().seekTo(0);currentPlayer().play();});awaitChrome(false);
        Activity old=activity;instrument.runOnMainSync(old::onBackPressed);adoptRotatedPlayer(old);
        assertEquals(android.content.res.Configuration.ORIENTATION_PORTRAIT,activity.getResources().getConfiguration().orientation);
        assertEquals(0,currentIndex());
    }
    private static Object read(java.lang.reflect.Field f,Object o){try{return f.get(o);}catch(Exception e){throw new RuntimeException(e);}}
    private List<View> views(){List<View> out=new ArrayList<>();instrument.runOnMainSync(()->collect(activity.getWindow().getDecorView(),out));return out;}
    private void collect(View view,List<View> out){out.add(view);if(view instanceof ViewGroup){ViewGroup group=(ViewGroup)view;for(int i=0;i<group.getChildCount();i++)collect(group.getChildAt(i),out);}}
    private List<String> texts(){List<String> out=new ArrayList<>();for(View v:views())if(v instanceof TextView)out.add(((TextView)v).getText().toString());return out;}
    private void await(String text)throws Exception{long end=System.currentTimeMillis()+10000;while(System.currentTimeMillis()<end){if(texts().contains(text))return;Thread.sleep(100);}fail("Missing UI: "+text+"; "+texts());}
    private void openSources()throws Exception{click("设置");await("影视来源");click("影视来源");await("视频来源配置");}
    private void openLibrary(String label)throws Exception{click("历史");await("观看历史");if(!"观看历史".equals(label))click(label);}
    private void click(String text)throws Exception{for(View v:views())if(v instanceof TextView && ((TextView)v).getText().toString().equals(text)){View target=v;while(!target.isClickable() && target.getParent() instanceof View)target=(View)target.getParent();View action=target;instrument.runOnMainSync(action::performClick);instrument.waitForIdleSync();return;}fail("Missing action: "+text);}
}
