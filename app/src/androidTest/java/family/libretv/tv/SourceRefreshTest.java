package family.libretv.tv;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.*;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import org.json.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Self-contained changing subscription server; never uses real catalogs or user devices. */
public final class SourceRefreshTest extends TestCase {
    private Instrumentation instrument;
    private Context context;
    private SharedPreferences prefs;
    private Map<String,?> previous,previousLive;
    private LocalRepository local;
    private SourceSubscriptions subscriptions;
    private ServerSocket server;
    private Thread worker;
    private volatile String payload;
    private volatile int status=200, requests;
    private volatile boolean revalidated;
    private Activity activity;
    private String url;
    @Override protected void setUp() throws Exception {
        instrument=InstrumentationRegistry.getInstrumentation();context=instrument.getTargetContext();
        prefs=context.getSharedPreferences("standalone-library",0);previous=new HashMap<>(prefs.getAll());prefs.edit().clear().commit();
        previousLive=new HashMap<>(context.getSharedPreferences("live-library",0).getAll());context.getSharedPreferences("live-library",0).edit().clear().commit();
        local=new LocalRepository(context);local.saveSources(new JSONArray());subscriptions=new SourceSubscriptions(local);SourceSubscriptions.beginOpen();
        payload=catalog("Original",true);
        server=new ServerSocket(0,10,InetAddress.getByName("127.0.0.1"));url="http://127.0.0.1:"+server.getLocalPort()+"/subscription";
        worker=new Thread(()->{
            while(!server.isClosed())try(Socket socket=server.accept()) {
                BufferedReader reader=new BufferedReader(new InputStreamReader(socket.getInputStream(),StandardCharsets.UTF_8));
                String line;boolean noCache=false;
                while((line=reader.readLine())!=null&&!line.isEmpty())if(line.equalsIgnoreCase("Cache-Control: no-cache"))noCache=true;
                revalidated=noCache;
                requests++;byte[] bytes=payload.getBytes(StandardCharsets.UTF_8);
                OutputStream out=socket.getOutputStream();out.write(("HTTP/1.1 "+status+" Test\r\nContent-Type: application/json\r\nContent-Length: "+bytes.length+"\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.UTF_8));out.write(bytes);out.flush();
            }catch(IOException ignored){}
        });worker.start();
    }
    @Override protected void tearDown() throws Exception {
        if(activity!=null){instrument.runOnMainSync(()->activity.finish());instrument.waitForIdleSync();}
        server.close();worker.join(2000);
        restore(context.getSharedPreferences("live-library",0),previousLive);
        restore(prefs,previous);
    }
    private void restore(SharedPreferences target,Map<String,?> previous) {
        SharedPreferences.Editor edit=target.edit().clear();
        for(Map.Entry<String,?> entry:previous.entrySet()) {
            Object value=entry.getValue();String key=entry.getKey();
            if(value instanceof String)edit.putString(key,(String)value);
            else if(value instanceof Set)edit.putStringSet(key,new HashSet<>((Set<String>)value));
            else if(value instanceof Boolean)edit.putBoolean(key,(Boolean)value);
            else if(value instanceof Integer)edit.putInt(key,(Integer)value);
            else if(value instanceof Long)edit.putLong(key,(Long)value);
            else if(value instanceof Float)edit.putFloat(key,(Float)value);
        }edit.commit();
    }
    private String catalog(String name,boolean enabled) throws Exception {
        return new JSONObject().put("sources",new JSONArray().put(new JSONObject().put("name",name).put("url","https://one.example/api").put("enabled",enabled))).toString();
    }
    public void testLinkPersistsAndRefreshesWithoutDeletingSources() throws Exception {
        assertEquals(1,local.importSources(url));assertEquals(url,new SourceSubscriptions(new LocalRepository(context)).entries().getJSONObject(0).getString("url"));
        payload=catalog("Renamed",true);
        JSONObject root=new JSONObject(payload);root.getJSONArray("sources").put(new JSONObject().put("name","New source").put("url","https://two.example/api"));payload=root.toString();
        assertEquals(1,subscriptions.refresh(true).updated);assertTrue(revalidated);assertEquals(2,local.sources().length());assertEquals("Renamed",local.sources().getJSONObject(0).getString("name"));
    }
    public void testPreservesDisabledAndDeletedAndManualSources() throws Exception {
        local.importSources(url);String key=local.sources().getJSONObject(0).getString("key");local.setSourceEnabled(key,false);
        payload=catalog("Changed",true);subscriptions.refresh(true);assertFalse(local.sources().getJSONObject(0).getBoolean("enabled"));
        local.removeSource(key);subscriptions.refresh(true);assertEquals(0,local.sources().length());
        local.importSources("{\"name\":\"Manual\",\"url\":\"https://manual.example/api\"}");
        subscriptions.refresh(true);assertEquals(1,local.sources().length());assertEquals("Manual",local.sources().getJSONObject(0).getString("name"));
    }
    public void testFailuresAndUnsupportedRetainLastGoodData() throws Exception {
        local.importSources(url);String good=local.sources().toString();long updated=subscriptions.entries().getJSONObject(0).getLong("updated");
        for(String bad:new String[]{"broken","{\"sources\":[]}","{\"sites\":[{\"type\":3,\"api\":\"spider.jar\"}]}"}) {
            payload=bad;assertEquals(1,subscriptions.refresh(true).failed);assertEquals(good,local.sources().toString());
            assertEquals(updated,subscriptions.entries().getJSONObject(0).getLong("updated"));assertFalse(subscriptions.entries().getJSONObject(0).getString("error").isEmpty());
        }
        status=500;assertEquals(1,subscriptions.refresh(true).failed);assertEquals(good,local.sources().toString());
        status=200;payload=catalog("Recovered",true);subscriptions.refresh(true);assertEquals("",subscriptions.entries().getJSONObject(0).getString("error"));
    }
    public void testFrequencyAndManualOverride() throws Exception {
        local.importSources(url);JSONObject entry=subscriptions.entries().getJSONObject(0);long now=System.currentTimeMillis();
        assertEquals(0,subscriptions.frequency());subscriptions.setFrequency(1);assertFalse(subscriptions.due(entry,now));assertTrue(subscriptions.due(entry,now+25*60*60*1000L));
        subscriptions.setFrequency(2);assertFalse(subscriptions.due(entry,now+2*24*60*60*1000L));assertTrue(subscriptions.due(entry,now+8*24*60*60*1000L));
        subscriptions.setFrequency(3);assertEquals(0,subscriptions.refresh(false).updated);assertEquals(1,subscriptions.refresh(true).updated);
        assertEquals(3,new SourceSubscriptions(new LocalRepository(context)).frequency());
        subscriptions.setFrequency(0);SourceSubscriptions.beginOpen();entry=subscriptions.entries().getJSONObject(0);entry.remove("attempt");
        assertTrue(subscriptions.due(entry,now+60001));
        subscriptions.refresh(true);assertFalse(subscriptions.due(entry,now+60001));SourceSubscriptions.beginOpen();assertTrue(subscriptions.due(entry,now+60001));
    }
    public void testAutomaticUpdateOnForegroundEntry() throws Exception {
        local.importSources(url);payload=catalog("Automatic update",true);subscriptions.setFrequency(0);
        activity=instrument.startActivitySync(new Intent(context,PhoneActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        long end=System.currentTimeMillis()+6000;
        while(System.currentTimeMillis()<end&&!"Automatic update".equals(local.sources().getJSONObject(0).getString("name")))Thread.sleep(100);
        assertEquals("Automatic update",local.sources().getJSONObject(0).getString("name"));
        int before=requests;click("设置");click("影视来源");assertEquals(before,requests);
    }
    public void testCancelSubscriptionAndTextImportRemainLocal() throws Exception {
        local.importSources(catalog("Text only",true));assertEquals(0,subscriptions.entries().length());
        local.importSources(url);assertEquals(1,subscriptions.entries().length());subscriptions.remove(url);
        assertEquals(1,local.sources().length());int before=requests;subscriptions.refresh(true);assertEquals(before,requests);
    }
    public void testConcurrentChecksOnlyDownloadOnce() throws Exception {
        local.importSources(url);subscriptions.setFrequency(0);SourceSubscriptions.beginOpen();
        java.util.concurrent.ExecutorService pool=java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            int before=requests;
            java.util.concurrent.Future<SourceSubscriptions.Refresh> first=pool.submit(()->new SourceSubscriptions(new LocalRepository(context)).refresh(false));
            java.util.concurrent.Future<SourceSubscriptions.Refresh> second=pool.submit(()->new SourceSubscriptions(new LocalRepository(context)).refresh(false));
            assertEquals(1,first.get().updated+second.get().updated);assertEquals(before+1,requests);
        }finally{pool.shutdownNow();}
    }
    public void testCapacityFailureIsAtomic() throws Exception {
        local.importSources(url);JSONArray full=local.sources();
        for(int i=1;i<50;i++)full.put(new JSONObject().put("name","Manual "+i).put("url","https://manual"+i+".example/api").put("key",LocalRepository.key("https://manual"+i+".example/api")));
        local.saveSources(full);String good=local.sources().toString();
        JSONObject root=new JSONObject(catalog("Must not overwrite",true));root.getJSONArray("sources").put(new JSONObject().put("name","Overflow").put("url","https://overflow.example/api"));payload=root.toString();
        assertEquals(1,subscriptions.refresh(true).failed);assertEquals(good,local.sources().toString());
    }
    public void testLiveFrequencyFailureAndRefresh() throws Exception {
        LiveRepository live=new LiveRepository(context);String id=live.saveSubscription(null,"Fixture",url);
        payload="#EXTM3U\n#EXTINF:-1 tvg-id=\"fixture\",Original channel\nhttps://stream.example/one.m3u8\n";
        assertEquals(0,live.frequency());live.setFrequency(1);assertEquals(1,live.refresh(false).updated);assertTrue(revalidated);assertEquals(1,live.channels().length());
        JSONObject entry=live.subscriptions().getJSONObject(0);long now=System.currentTimeMillis();
        assertFalse(live.due(entry,now));assertTrue(live.due(entry,now+7*60*60*1000L));
        live.setFrequency(2);assertFalse(live.due(entry,now+7*60*60*1000L));assertTrue(live.due(entry,now+25*60*60*1000L));
        live.setFrequency(3);assertFalse(live.due(entry,now+2*24*60*60*1000L));assertTrue(live.due(entry,now+8*24*60*60*1000L));
        live.setFrequency(4);assertEquals(0,live.refresh(false).updated);assertEquals(1,live.refresh(true).updated);
        live.setFrequency(0);LiveRepository.beginOpen();assertEquals(1,live.refresh(false).updated);assertEquals(0,live.refresh(false).updated);
        LiveRepository.beginOpen();payload="#EXTM3U\n#EXTINF:-1 tvg-id=\"fixture\",Updated channel\nhttps://stream.example/two.m3u8\n";
        assertEquals(1,live.refresh(false).updated);assertEquals("Updated channel",live.channels().getJSONObject(0).getString("name"));
        String good=live.channels().toString();payload="invalid";assertEquals(1,live.refresh(true).failed);assertEquals(good,live.channels().toString());assertTrue(live.status(entry).contains("更新失败"));
        live.enable(id,false);assertEquals(0,live.refresh(true).updated);live.remove(id);assertEquals(0,live.refresh(true).updated);
    }
    public void testTvResumeLoadsChangedNasContentAtSameUrl() throws Exception {
        LiveRepository live=new LiveRepository(context);
        payload="#EXTM3U\n#EXTINF:-1 tvg-id=\"same-channel\",NAS old channel\nhttps://stream.example/old.m3u8\n";
        String subscriptionId=live.saveSubscription(null,"NAS source",url);
        live.refresh(true);String channelId=live.channels().getJSONObject(0).getString("id");live.toggleFavorite(channelId);
        assertEquals(0,live.frequency()); // No frequency selection needed for an old saved URL.
        activity=instrument.startActivitySync(new Intent(context,LiveBrowseActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        awaitText("NAS old channel");Activity original=activity;
        assertTrue(instrument.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_HOME));
        long end=System.currentTimeMillis()+5000;boolean[] stopped={false};
        while(!stopped[0]&&System.currentTimeMillis()<end){
            instrument.runOnMainSync(()->stopped[0]=androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry.getInstance().getLifecycleStageOf(original)==androidx.test.runner.lifecycle.Stage.STOPPED);
            Thread.sleep(50);
        }
        assertTrue("Browse activity did not stop",stopped[0]);Thread.sleep(1200);
        payload="#EXTM3U\n#EXTINF:-1 tvg-id=\"same-channel\",NAS new channel\nhttps://stream.example/new.m3u8\n";
        instrument.runOnMainSync(()->context.startActivity(new Intent(context,LiveBrowseActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
        awaitText("NAS new channel");
        assertFalse("Activity was recreated",original.isDestroyed());
        assertEquals(subscriptionId,live.subscriptions().getJSONObject(0).getString("id"));
        assertEquals(channelId,live.channels().getJSONObject(0).getString("id"));assertTrue(live.favorite(channelId));
        assertEquals("https://stream.example/new.m3u8",live.channels().getJSONObject(0).getJSONArray("lines").getJSONObject(0).getString("url"));
        assertTrue(revalidated);
        capture("source-refresh-tv-nas-resume");
    }
    private void awaitText(String text) throws Exception {
        long end=System.currentTimeMillis()+6000;boolean[] found={false};
        while(!found[0]&&System.currentTimeMillis()<end){
            instrument.runOnMainSync(()->found[0]=containsText(activity.getWindow().getDecorView(),text));
            if(!found[0])Thread.sleep(100);
        }
        assertTrue("Missing refreshed text: "+text,found[0]);
    }
    private boolean containsText(View view,String text){
        if(view instanceof TextView&&((TextView)view).getText().toString().contains(text))return true;
        if(view instanceof ViewGroup)for(int i=0;i<((ViewGroup)view).getChildCount();i++)if(containsText(((ViewGroup)view).getChildAt(i),text))return true;
        return false;
    }
    public void testLivePhoneAndTvFrequencySettings() throws Exception {
        LiveRepository live=new LiveRepository(context);
        for(Class<?> target:new Class<?>[]{PhoneActivity.class,LiveBrowseActivity.class}) {
            Intent intent=new Intent(context,target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if(target==LiveBrowseActivity.class)intent.putExtra("openSources",true);
            activity=instrument.startActivitySync(intent);instrument.waitForIdleSync();
            if(target==PhoneActivity.class){click("设置");click("直播订阅");}
            click("自动更新频率");capture("source-refresh-live-"+target.getSimpleName());click("每次打开 App");assertEquals(0,live.frequency());
            click("自动更新频率");
            click("仅手动");assertEquals(4,live.frequency());
            instrument.runOnMainSync(()->activity.finish());instrument.waitForIdleSync();activity=null;
        }
    }
    public void testPhoneAndTvFrequencySettings() throws Exception {
        for(Class<?> target:new Class<?>[]{PhoneActivity.class,MainActivity.class}) {
            Intent intent=new Intent(context,target).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK);
            if(target==MainActivity.class)intent.addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).putExtra("tvDestination","settings");
            activity=instrument.startActivitySync(intent);instrument.waitForIdleSync();
            if(target==PhoneActivity.class){click("设置");click("影视来源");}else click("视频来源");
            click("自动更新频率");capture("source-refresh-vod-"+target.getSimpleName());click("每周");
            assertEquals(2,subscriptions.frequency());click("自动更新频率");click("仅手动");assertEquals(3,subscriptions.frequency());
            instrument.runOnMainSync(()->activity.finish());instrument.waitForIdleSync();activity=null;
        }
    }
    private void capture(String name) throws Exception {
        android.graphics.Bitmap bitmap=instrument.getUiAutomation().takeScreenshot();
        assertNotNull(bitmap);
        try(FileOutputStream out=new FileOutputStream(new File(context.getFilesDir(),name+".png"))){bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}finally{bitmap.recycle();}
    }
    private void click(String text) throws Exception {
        instrument.waitForIdleSync();
        android.view.accessibility.AccessibilityNodeInfo node=null;
        long deadline=System.currentTimeMillis()+5000;
        while(node==null&&System.currentTimeMillis()<deadline){
            node=findNode(instrument.getUiAutomation().getRootInActiveWindow(),text);
            if(node==null)Thread.sleep(100);
        }
        assertNotNull("Missing action: "+text,node);
        while(!node.isClickable()&&node.getParent()!=null)node=node.getParent();
        assertTrue("Cannot click: "+text,node.performAction(android.view.accessibility.AccessibilityNodeInfo.ACTION_CLICK));
        instrument.waitForIdleSync();Thread.sleep(200);
    }
    private android.view.accessibility.AccessibilityNodeInfo findNode(android.view.accessibility.AccessibilityNodeInfo node,String text) {
        if(node==null)return null;
        if((node.getText()!=null&&node.getText().toString().contains(text))||(node.getContentDescription()!=null&&node.getContentDescription().toString().endsWith(" · "+text)))return node;
        for(int i=0;i<node.getChildCount();i++){
            android.view.accessibility.AccessibilityNodeInfo found=findNode(node.getChild(i),text);if(found!=null)return found;
        }
        return null;
    }
}
