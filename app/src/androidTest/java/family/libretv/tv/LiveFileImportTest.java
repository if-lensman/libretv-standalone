package family.libretv.tv;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.SystemClock;
import android.view.*;
import android.widget.TextView;
import androidx.core.content.FileProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import java.io.*;
import java.util.*;

/** Uses a real content provider and picker result, with no public playlist or media. */
public final class LiveFileImportTest extends TestCase {
    private Instrumentation instrument;
    private Context context;
    private LiveRepository repository;
    private LiveBrowseActivity activity;
    private Map<String,?> backup;
    private File document;
    private Instrumentation.ActivityMonitor monitor;

    @Override protected void setUp() throws Exception {
        instrument=InstrumentationRegistry.getInstrumentation();context=instrument.getTargetContext();
        SharedPreferences prefs=context.getSharedPreferences("live-library",0);
        backup=new HashMap<>(prefs.getAll());prefs.edit().clear().commit();
        repository=new LiveRepository(context);
        File directory=new File(context.getCacheDir(),"updates");directory.mkdirs();
        document=new File(directory,"file-import-test.m3u");
        activity=(LiveBrowseActivity)instrument.startActivitySync(new Intent(context,LiveBrowseActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        await("方向键选择频道");
    }
    @Override protected void tearDown() throws Exception {
        if(monitor!=null)instrument.removeMonitor(monitor);
        if(activity!=null){instrument.runOnMainSync(activity::finish);instrument.waitForIdleSync();}
        document.delete();SharedPreferences.Editor editor=repository.prefs.edit().clear();
        for(Map.Entry<String,?> row:backup.entrySet()){
            String k=row.getKey();Object v=row.getValue();
            if(v instanceof String)editor.putString(k,(String)v);else if(v instanceof Boolean)editor.putBoolean(k,(Boolean)v);
            else if(v instanceof Long)editor.putLong(k,(Long)v);else if(v instanceof Integer)editor.putInt(k,(Integer)v);
            else if(v instanceof Float)editor.putFloat(k,(Float)v);else if(v instanceof Set)editor.putStringSet(k,new HashSet<>((Set<String>)v));
        }
        editor.commit();
    }
    private void write(String text)throws Exception {try(FileOutputStream out=new FileOutputStream(document)){out.write(text.getBytes("UTF-8"));}}
    private Uri uri(){return FileProvider.getUriForFile(context,context.getPackageName()+".updates",document);}
    private String playlist(String suffix){return "#EXTM3U\n#EXTINF:-1 tvg-id=\"fixture\" group-title=\"测试\",文件频道\nhttp://127.0.0.1/"+suffix+"\n";}
    private void collect(View v,StringBuilder result){if(v instanceof TextView)result.append(((TextView)v).getText()).append('\n');if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++)collect(((ViewGroup)v).getChildAt(i),result);}
    private void await(String text)throws Exception {
        long deadline=SystemClock.uptimeMillis()+6000;
        while(SystemClock.uptimeMillis()<deadline){StringBuilder result=new StringBuilder();instrument.runOnMainSync(()->collect(activity.getWindow().getDecorView(),result));if(result.toString().contains(text))return;Thread.sleep(50);}
        fail("Missing text: "+text);
    }
    private View importButton(){final View[] result={null};instrument.runOnMainSync(()->{
        try{java.lang.reflect.Field field=LiveBrowseActivity.class.getDeclaredField("controls");field.setAccessible(true);result[0]=((Map<String,View>)field.get(activity)).get("import");}catch(Exception e){throw new RuntimeException(e);}
    });return result[0];}
    private void deliver(int result,Intent data){instrument.runOnMainSync(()->activity.onActivityResult(414,result,data));}

    public void testRemotePickerImportAndSameFilenameReplacementPreserveFavorite() throws Exception {
        write(playlist("one"));Uri picked=uri();
        IntentFilter filter=new IntentFilter(Intent.ACTION_OPEN_DOCUMENT);
        filter.addCategory(Intent.CATEGORY_OPENABLE);filter.addDataType("*/*");
        monitor=new Instrumentation.ActivityMonitor(filter,
                new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(picked)),true);
        instrument.addMonitor(monitor);View button=importButton();assertNotNull(button);
        instrument.runOnMainSync(button::requestFocus);instrument.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER);
        await("已导入频道");assertTrue(monitor.getHits()>0);assertEquals(1,repository.subscriptions().length());
        String id=repository.channels().getJSONObject(0).getString("id");repository.toggleFavorite(id);repository.remember(id);
        write(playlist("two"));deliver(Activity.RESULT_OK,new Intent().setData(picked));await("已导入频道");
        long deadline=SystemClock.uptimeMillis()+6000;
        while(!repository.channels().toString().contains("/two")&&SystemClock.uptimeMillis()<deadline)Thread.sleep(50);
        assertTrue(repository.channels().toString().contains("/two"));assertEquals(1,repository.subscriptions().length());
        assertEquals(id,repository.channels().getJSONObject(0).getString("id"));assertTrue(repository.favorite(id));assertEquals(id,repository.lastChannel());
        assertEquals(0,repository.refresh(true).updated);
        document.delete();assertEquals(1,new LiveRepository(context).channels().length());
    }
    public void testInvalidCancelledAndOversizedFileRetainExistingChannels() throws Exception {
        write(playlist("original"));LivePlaylistFile.importInto(context,repository,uri());String cached=repository.channels().toString();
        deliver(Activity.RESULT_CANCELED,new Intent().setData(uri()));assertEquals(cached,repository.channels().toString());
        write("not a playlist");deliver(Activity.RESULT_OK,new Intent().setData(uri()));await("导入失败");assertEquals(cached,repository.channels().toString());
        try(RandomAccessFile file=new RandomAccessFile(document,"rw")){file.setLength(M3uPlaylist.MAX_BYTES+1L);}
        try{LivePlaylistFile.importInto(context,repository,uri());fail("Oversized input accepted");}catch(IOException expected){assertTrue(expected.getMessage().contains("过大"));}
        assertEquals(cached,repository.channels().toString());
    }
}
