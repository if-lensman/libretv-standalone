package family.libretv.tv;

import android.content.Context;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.security.MessageDigest;
import java.util.*;

/** Fault injection uses only synthetic metadata and an opt-in, signed local candidate APK. */
@androidx.test.filters.SdkSuppress(minSdkVersion=26)
public final class UpdateFallbackTest extends TestCase {
    private final List<String> calls=new ArrayList<>();
    private JSONObject metadata(byte[] bytes,int code)throws Exception {
        return new JSONObject().put("packageName",BuildConfig.APPLICATION_ID).put("versionCode",code)
            .put("versionName","0.16.2").put("minSdk",24).put("size",bytes.length)
            .put("sha256",AppUpdater.hex(MessageDigest.getInstance("SHA-256").digest(bytes)))
            .put("downloadUrl",AppUpdater.REPOSITORY+"/releases/download/v0.16.2/app.apk");
    }
    private HttpURLConnection response(URI uri,byte[] bytes,int code)throws Exception {
        return new HttpURLConnection(uri.toURL()) {
            @Override public int getResponseCode(){return code;}
            @Override public InputStream getInputStream(){return new ByteArrayInputStream(bytes);}
            @Override public void disconnect(){}
            @Override public boolean usingProxy(){return false;}
            @Override public void connect(){}
        };
    }
    private String route(URI proxy){String value=proxy==null?"direct":proxy.getHost();calls.add(value);return value;}
    public void testManifestFailureFallsBackAndRemembersWorkingRoute()throws Exception {
        byte[] json=metadata(new byte[]{1},43).toString().getBytes(StandardCharsets.UTF_8);
        AppUpdater updater=new AppUpdater("auto","https://mirror.example/",(uri,proxy)->{
            if(!route(proxy).equals("mirror.example"))throw new SocketTimeoutException("synthetic direct timeout");return response(uri,json,200);
        });
        assertEquals(43,updater.check().code);assertEquals(Arrays.asList("direct","gitee.com","mirror.example"),calls);
        calls.clear();updater.check();assertEquals(Collections.singletonList("mirror.example"),calls);
    }
    public void testMalformedManifestAlsoFallsBackAndWrongRepositoryIsRejected()throws Exception {
        byte[] json=metadata(new byte[]{1},43).toString().getBytes(StandardCharsets.UTF_8);
        AppUpdater updater=new AppUpdater("auto","https://mirror.example/",(uri,proxy)->response(uri,proxy==null?"<html>error</html>".getBytes(StandardCharsets.UTF_8):json,200));
        assertEquals(43,updater.check().code);
        byte[] wrong=metadata(new byte[]{1},43).put("downloadUrl","https://example.com/app.apk").toString().getBytes(StandardCharsets.UTF_8);
        try{new AppUpdater("proxy","https://mirror.example/",(uri,proxy)->response(uri,wrong,200)).check();fail("Foreign APK URL accepted");}catch(IOException expected){}
    }
    public void testDirectOnlyNeverCallsProxyAndProxyOnlyNeverCallsDirect()throws Exception {
        for(String mode:new String[]{"direct","gitee","proxy"}){
            calls.clear();
            try{new AppUpdater(mode,"https://mirror.example/",(uri,proxy)->{route(proxy);throw new IOException("offline");}).check();fail();}catch(IOException expected){}
            assertEquals(Collections.singletonList(mode.equals("direct")?"direct":mode.equals("gitee")?"gitee.com":"mirror.example"),calls);
        }
    }
    public void testAllDefaultRoutesAreBounded()throws Exception {
        try{new AppUpdater("auto","",(uri,proxy)->{route(proxy);throw new IOException("offline");}).check();fail();}catch(IOException expected){}
        assertEquals(Arrays.asList("direct","gitee.com",URI.create(AppUpdater.DEFAULT_PROXY).getHost(),URI.create(AppUpdater.SECOND_PROXY).getHost()),calls);
    }
    public void testGiteeMappingOnlyAcceptsThisProjectsUpdateFiles()throws Exception {
        assertEquals(AppUpdater.GITEE_MIRROR+"releases/download/v0.16.2/LibreTV-0.16.2.apk",AppUpdater.mirrorTarget(URI.create(AppUpdater.REPOSITORY+"/releases/download/v0.16.2/LibreTV-0.16.2.apk")).toString());
        try{AppUpdater.mirrorTarget(URI.create("https://github.com/other/project/releases/download/v1/app.apk"));fail();}catch(IOException expected){}
    }
    public void testGiteeApiManifestDecodesBase64WithoutRawHost()throws Exception {
        JSONObject metadata=metadata(new byte[]{1},43);
        byte[] api=new JSONObject().put("encoding","base64").put("content",android.util.Base64.encodeToString(metadata.toString().getBytes(StandardCharsets.UTF_8),android.util.Base64.DEFAULT)).toString().getBytes(StandardCharsets.UTF_8);
        AppUpdater updater=new AppUpdater("gitee","",(uri,proxy)->response(uri,api,200));
        assertEquals(43,updater.check().code);
        assertEquals("gitee.com",AppUpdater.mirrorTarget(URI.create(AppUpdater.REPOSITORY+"/releases/latest/download/update-"+BuildConfig.APPLICATION_ID+".json")).getHost());
    }
    public void testProxyPrefixValidation()throws Exception {
        assertEquals("https://mirror.example/path/",AppUpdater.validateProxy("https://mirror.example/path").toString());
        for(String value:new String[]{"http://mirror.example/","https://user:secret@mirror.example/","https://mirror.example/?url=","https://mirror.example/#x","not a url"}){
            try{AppUpdater.validateProxy(value);fail(value);}catch(IOException expected){}
        }
    }
    public void testInterruptedCheckDoesNotTryAnotherRoute()throws Exception {
        try{
            new AppUpdater("auto","",(uri,proxy)->{route(proxy);Thread.currentThread().interrupt();throw new IOException("cancel");}).check();fail();
        }catch(InterruptedIOException expected){assertEquals(1,calls.size());}finally{Thread.interrupted();}
    }
    public void testSignedApkDownloadFallbackAndTamperedFilesAreRemoved()throws Exception {
        Context context=InstrumentationRegistry.getInstrumentation().getTargetContext();
        String fixture=InstrumentationRegistry.getArguments().getString("updateFixture");
        if(fixture==null)return;
        byte[] bytes=Files.readAllBytes(new File(context.getFilesDir(),fixture).toPath());
        int nextCode=BuildConfig.VERSION_CODE+1;
        AppUpdater.Release release=new AppUpdater.Release(metadata(bytes,nextCode));
        AppUpdater updater=new AppUpdater("auto","https://mirror.example/",(uri,proxy)->{
            String name=route(proxy);
            // First stream is truncated; fallback must truncate its partial file before starting again.
            return response(uri,!name.equals("mirror.example")?Arrays.copyOf(bytes,bytes.length/2):bytes,200);
        });
        File result=updater.download(context,release,percent->{});
        assertEquals(Arrays.asList("direct","gitee.com","mirror.example"),calls);assertEquals(bytes.length,result.length());
        assertFalse(new File(result.getParentFile(),"download.part").exists());
        AppUpdater.Release bad=new AppUpdater.Release(metadata(bytes,nextCode).put("sha256",String.join("",Collections.nCopies(64,"0"))));
        try{updater.download(context,bad,percent->{});fail("Wrong hash accepted");}catch(IOException expected){assertFalse(result.exists());assertFalse(new File(result.getParentFile(),"download.part").exists());}
        // A valid hash cannot authorize a different package/version/signature.
        AppUpdater.Release wrongVersion=new AppUpdater.Release(metadata(bytes,nextCode+1));
        try{updater.download(context,wrongVersion,percent->{});fail("Wrong package version accepted");}catch(IOException expected){assertFalse(result.exists());}
    }
}
