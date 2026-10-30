package family.libretv.tv;

import android.app.*;
import android.content.*;
import android.os.SystemClock;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import java.io.*;
import java.net.URL;
import java.security.MessageDigest;
import org.json.JSONObject;

/** Opt-in candidate APK fixture; exercises saving without requesting storage permissions. */
public final class UpdateInstallCompatibilityTest extends TestCase {
    private Instrumentation instrument;
    private Context context;
    private Activity activity;
    private File candidate;
    private JSONObject metadata;
    private boolean enabled;
    private Instrumentation.ActivityMonitor monitor;
    @Override protected void setUp()throws Exception {
        instrument=InstrumentationRegistry.getInstrumentation();context=instrument.getTargetContext();
        String root=InstrumentationRegistry.getArguments().getString("candidateRoot");enabled=root!=null;if(!enabled)return;
        try(InputStream in=new URL(root+"/update-"+context.getPackageName()+".json").openStream();ByteArrayOutputStream out=new ByteArrayOutputStream()) {
            byte[] bytes=new byte[8192];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);metadata=new JSONObject(out.toString("UTF-8"));
        }
        assertTrue("Run on a lower version code",metadata.getInt("versionCode")>BuildConfig.VERSION_CODE);
        candidate=new File(context.getCacheDir(),"updates/update.apk");candidate.getParentFile().mkdirs();
        try(InputStream in=new URL(root+"/LibreTV-0.20.1.apk").openStream();OutputStream out=new FileOutputStream(candidate)) {
            byte[] bytes=new byte[32768];int n;while((n=in.read(bytes))!=-1)out.write(bytes,0,n);
        }
        assertEquals(metadata.getLong("size"),candidate.length());assertEquals(metadata.getString("sha256"),sha(candidate));
        context.getSharedPreferences(UpdateActivity.class.getName(),0).edit().clear().putInt("pendingCode",metadata.getInt("versionCode"))
            .putLong("pendingSize",candidate.length()).putString("pendingSha",metadata.getString("sha256")).commit();
        activity=instrument.startActivitySync(new Intent(context,UpdateActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK|Intent.FLAG_ACTIVITY_CLEAR_TASK));
        instrument.waitForIdleSync();Thread.sleep(300);
        assertNotNull(field("pending"));assertFalse((Boolean)field("busy"));
    }
    @Override protected void tearDown() {
        if(monitor!=null)instrument.removeMonitor(monitor);
        if(activity!=null)instrument.runOnMainSync(activity::finish);
        instrument.waitForIdleSync();
    }
    private Object field(String name)throws Exception {java.lang.reflect.Field f=UpdateActivity.class.getDeclaredField(name);f.setAccessible(true);return f.get(activity);}
    private void click(String name)throws Exception {Button button=(Button)field(name);assertEquals(View.VISIBLE,button.getVisibility());instrument.runOnMainSync(button::performClick);instrument.waitForIdleSync();}
    private String sha(File file)throws Exception {MessageDigest md=MessageDigest.getInstance("SHA-256");try(InputStream in=new FileInputStream(file)){byte[] b=new byte[32768];int n;while((n=in.read(b))!=-1)md.update(b,0,n);}return AppUpdater.hex(md.digest());}
    public void testCompatibleIntentSharesOnlyDownloadedApk()throws Exception {
        if(!enabled)return;
        Intent[] captured={null};
        monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){
            if(Intent.ACTION_VIEW.equals(intent.getAction())){captured[0]=intent;return new Instrumentation.ActivityResult(Activity.RESULT_CANCELED,null);}return null;
        }};instrument.addMonitor(monitor);click("compatible");
        assertNotNull("Compatible installer action",captured[0]);Intent intent=captured[0];
        assertEquals("content",intent.getData().getScheme());assertEquals(context.getPackageName()+".updates",intent.getData().getAuthority());
        assertEquals("application/vnd.android.package-archive",intent.getType());assertTrue((intent.getFlags()&Intent.FLAG_GRANT_READ_URI_PERMISSION)!=0);
        assertEquals(intent.getData(),intent.getClipData().getItemAt(0).getUri());
        for(String permission:context.getPackageManager().getPackageInfo(context.getPackageName(),android.content.pm.PackageManager.GET_PERMISSIONS).requestedPermissions)
            assertFalse("No broad storage permission: "+permission,permission.contains("EXTERNAL_STORAGE"));
        android.graphics.Bitmap image=instrument.getUiAutomation().takeScreenshot();try(FileOutputStream out=context.openFileOutput("update-car-fallback.png",0)){image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,out);}image.recycle();
    }
    public void testSavePickerCopiesVerifiedApkAndKeepsOriginal()throws Exception {
        if(!enabled)return;
        File exported=new File(candidate.getParentFile(),"exported.apk");if(exported.exists())assertTrue(exported.delete());
        Intent[] captured={null};
        monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){
            if(Intent.ACTION_CREATE_DOCUMENT.equals(intent.getAction())){captured[0]=intent;return new Instrumentation.ActivityResult(Activity.RESULT_OK,new Intent().setData(FileProvider.getUriForFile(context,context.getPackageName()+".updates",exported)));}return null;
        }};instrument.addMonitor(monitor);click("saveApk");
        long end=SystemClock.uptimeMillis()+5000;while(SystemClock.uptimeMillis()<end&&(!exported.isFile()||exported.length()!=candidate.length()||(Boolean)field("busy")))Thread.sleep(50);
        assertNotNull("System save picker was requested",captured[0]);assertTrue(captured[0].hasCategory(Intent.CATEGORY_OPENABLE));
        assertEquals("application/vnd.android.package-archive",captured[0].getType());assertEquals(metadata.getString("sha256"),sha(exported));
        assertEquals(metadata.getString("sha256"),sha(candidate));assertFalse((Boolean)field("busy"));
        assertTrue(((TextView)field("status")).getText().toString().contains("安装包已保存"));assertTrue(exported.delete());
    }
    public void testCompatibleOpensSystemInstallerWhenRequested()throws Exception {
        if(!enabled||!"true".equals(InstrumentationRegistry.getArguments().getString("realInstaller")))return;
        click("compatible");
        long end=SystemClock.uptimeMillis()+7000;boolean installer=false;
        while(SystemClock.uptimeMillis()<end){
            android.view.accessibility.AccessibilityNodeInfo root=instrument.getUiAutomation().getRootInActiveWindow();
            if(root!=null&&String.valueOf(root.getPackageName()).contains("packageinstaller")){
                installer=!root.findAccessibilityNodeInfosByText("片刻").isEmpty()&&!root.findAccessibilityNodeInfosByText("Install").isEmpty();if(installer)break;
            }
            Thread.sleep(100);
        }
        try {assertTrue("System installer read candidate APK and offers installation",installer);}
        finally {instrument.getUiAutomation().performGlobalAction(android.accessibilityservice.AccessibilityService.GLOBAL_ACTION_BACK);instrument.waitForIdleSync();}
    }

}
