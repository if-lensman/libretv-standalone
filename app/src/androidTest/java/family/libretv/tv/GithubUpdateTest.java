package family.libretv.tv;

import junit.framework.TestCase;
import static androidx.test.platform.app.InstrumentationRegistry.getInstrumentation;
import org.json.JSONObject;
import java.io.File;

public final class GithubUpdateTest extends TestCase {
    private JSONObject metadata() throws Exception {
        return new JSONObject().put("packageName",BuildConfig.APPLICATION_ID).put("versionCode",27)
            .put("versionName","0.13.0").put("minSdk",24).put("size",10).put("sha256",new String(new char[64]).replace('\0','a'))
            .put("downloadUrl",AppUpdater.REPOSITORY+"/releases/download/v0.13.0/app.apk");
    }
    public void testAcceptOnlyThisRepositoryApkUrls() throws Exception {
        AppUpdater.validateGithubAsset(AppUpdater.REPOSITORY+"/releases/download/v0.13.0/app.apk");
        for(String url:new String[]{"http://github.com/if-lensman/libretv-standalone/releases/download/v1/app.apk",
                "https://github.com/other/project/releases/download/v1/app.apk",
                "https://github.com.evil.example/if-lensman/libretv-standalone/releases/download/v1/app.apk",
                "https://github.com/if-lensman/libretv-standalone/releases/download/v1/app.apk?redirect=1"}) {
            try{AppUpdater.validateGithubAsset(url);fail(url);}catch(java.io.IOException expected){}
        }
    }
    public void testManifestRejectsWrongIdentityAndInvalidHash() throws Exception {
        new AppUpdater.Release(metadata());
        try{new AppUpdater.Release(metadata().put("packageName","other.app"));fail();}catch(java.io.IOException expected){}
        try{new AppUpdater.Release(metadata().put("sha256","invalid"));fail();}catch(java.io.IOException expected){}
        try{new AppUpdater.Release(metadata().put("size",AppUpdater.MAX_APK+1));fail();}catch(java.io.IOException expected){}
    }
    /** Explicit opt-in: run on a lower-code build to validate actual GitHub bytes and installed signing identity. */
    public void testLiveReleaseDownloadWhenRequested() throws Exception {
        android.os.Bundle args=androidx.test.platform.app.InstrumentationRegistry.getArguments();
        String expected=args.getString("expectedUpdateCode");if(expected==null)return;
        AppUpdater updater=new AppUpdater();AppUpdater.Release release=updater.check();
        assertEquals(Integer.parseInt(expected),release.code);
        assertTrue(release.code>BuildConfig.VERSION_CODE);
        File apk=updater.download(getInstrumentation().getTargetContext(),release,percent->{});
        assertEquals(release.size,apk.length());
        // A matching file with a deliberately incorrect expected digest must never be retained.
        JSONObject broken=new JSONObject().put("packageName",BuildConfig.APPLICATION_ID).put("versionCode",release.code)
            .put("versionName",release.name).put("minSdk",24).put("size",release.size)
            .put("sha256",new String(new char[64]).replace('\0','0')).put("downloadUrl",release.downloadUrl);
        try{updater.download(getInstrumentation().getTargetContext(),new AppUpdater.Release(broken),percent->{});fail("Bad digest accepted");}
        catch(java.io.IOException expectedFailure){assertFalse(apk.exists());}
        updater.download(getInstrumentation().getTargetContext(),release,percent->{});
    }
}
