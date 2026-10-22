package family.libretv.tv;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;

/** GitHub Releases updater, with the private NAS transport retained for existing installations. */
@SuppressWarnings("deprecation")
final class AppUpdater {
    static final String REPOSITORY = "https://github.com/if-lensman/libretv-standalone";
    static final long MAX_APK = 100L * 1024 * 1024;
    final NasClient nas;
    final URI origin;
    static final class Release {
        final int code;
        final String name, sha256, notes, downloadUrl;
        final long size;
        Release(JSONObject json) throws Exception {
            code = json.getInt("versionCode"); name = json.getString("versionName");
            downloadUrl = json.optString("downloadUrl", "");
            if (!downloadUrl.isEmpty()) validateGithubAsset(downloadUrl);
            sha256 = json.getString("sha256"); size = json.getLong("size"); notes = json.optString("notes", "");
            if (!BuildConfig.APPLICATION_ID.equals(json.getString("packageName")) || code < 1
                    || !sha256.matches("[a-f0-9]{64}") || size < 1 || size > MAX_APK
                    || name.length() > 80 || notes.length() > 2000 || json.getInt("minSdk") > Build.VERSION.SDK_INT)
                throw new IOException("更新信息不适用于本设备");
        }
    }
    AppUpdater() { nas = null; origin = URI.create(REPOSITORY + "/releases/latest/download/"); }
    AppUpdater(NasClient nas) throws Exception {
        this.nas = nas;
        origin = new URI(nas.origin.getScheme(), null, nas.origin.getHost(), 8898, "/", null, null);
    }
    static URI validateGithubAsset(String url) throws IOException {
        URI uri;
        try { uri = URI.create(url); } catch (IllegalArgumentException e) { throw new IOException("更新下载地址无效", e); }
        if (!"https".equals(uri.getScheme()) || !"github.com".equals(uri.getHost()) || uri.getPort()!=-1
                || uri.getRawUserInfo()!=null || uri.getFragment()!=null || uri.getQuery()!=null
                || !uri.getPath().startsWith("/if-lensman/libretv-standalone/releases/download/")
                || !uri.getPath().endsWith(".apk") || uri.getPath().contains("/../"))
            throw new IOException("更新下载地址不属于本项目 Release");
        return uri;
    }
    private static HttpURLConnection githubConnection(URI uri) throws Exception {
        for (int i=0;i<6;i++) {
            String host=uri.getHost();
            if (!"https".equals(uri.getScheme()) || uri.getRawUserInfo()!=null || uri.getPort()!=-1
                    || !("github.com".equals(host) || "release-assets.githubusercontent.com".equals(host) || "objects.githubusercontent.com".equals(host)))
                throw new IOException("更新服务重定向地址无效");
            HttpURLConnection conn=(HttpURLConnection)uri.toURL().openConnection();
            conn.setInstanceFollowRedirects(false);conn.setConnectTimeout(8000);conn.setReadTimeout(20000);
            conn.setRequestProperty("User-Agent","LibreTV-Standalone/"+BuildConfig.VERSION_NAME);
            int status=conn.getResponseCode();
            if(status==301 || status==302 || status==303 || status==307 || status==308) {
                String next=conn.getHeaderField("Location");conn.disconnect();
                if(next==null)throw new IOException("更新服务重定向缺少地址");
                uri=uri.resolve(next);continue;
            }
            return conn;
        }
        throw new IOException("更新服务重定向过多");
    }
    private HttpURLConnection open(String path) throws Exception {
        if (nas == null) return githubConnection(origin.resolve(path));
        if (BuildConfig.APPLICATION_ID.equals("family.libretv.standalone"))
            throw new IOException("独立研究版已禁用家庭 NAS 更新通道");
        HttpURLConnection conn = (HttpURLConnection) origin.resolve(path).toURL().openConnection();
        conn.setInstanceFollowRedirects(false); conn.setConnectTimeout(5000); conn.setReadTimeout(20000);
        conn.setRequestProperty("Cookie", nas.cookieHeader(nas.origin));
        return conn;
    }
    Release check() throws Exception {
        HttpURLConnection conn = open(nas == null ? "update-"+BuildConfig.APPLICATION_ID+".json" : "/latest.json");
        try {
            requireOk(conn);
            try (InputStream in = conn.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                copy(in, out, 65536);
                Release release = new Release(new JSONObject(new String(out.toByteArray(), StandardCharsets.UTF_8)));
                if (nas == null && release.downloadUrl.isEmpty()) throw new IOException("Release 缺少安装包地址");
                return release;
            }
        } finally { conn.disconnect(); }
    }
    interface Progress { void accept(int percent); }
    File download(Context context, Release release, Progress progress) throws Exception {
        File dir = new File(context.getCacheDir(), "updates");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("无法创建更新目录");
        File partial = new File(dir, "download.part"), apk = new File(dir, "update.apk");
        if (apk.exists() && !apk.delete()) throw new IOException("无法清理旧安装包");
        HttpURLConnection conn = nas == null ? githubConnection(validateGithubAsset(release.downloadUrl)) : open("/apk/" + release.code + ".apk");
        boolean complete = false;
        try {
            requireOk(conn);
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            long count = 0; int last = -1;
            try (InputStream in = conn.getInputStream(); OutputStream out = new FileOutputStream(partial)) {
                byte[] buffer = new byte[32768]; int n;
                while ((n = in.read(buffer)) != -1) {
                    if (Thread.currentThread().isInterrupted()) throw new IOException("更新已取消");
                    count += n;
                    if (count > release.size) throw new IOException("安装包大小异常");
                    digest.update(buffer, 0, n); out.write(buffer, 0, n);
                    int percent = (int)(count * 100 / release.size);
                    if (percent != last) { progress.accept(percent); last = percent; }
                }
            }
            if (count != release.size || !hex(digest.digest()).equals(release.sha256)) throw new IOException("安装包校验失败，请重新下载");
            validateApk(context, partial, release.code);
            if (!partial.renameTo(apk)) throw new IOException("无法保存安装包");
            complete = true; return apk;
        } finally { conn.disconnect(); if (!complete) partial.delete(); }
    }
    static void validateApk(Context context, File apk, int expectedCode) throws Exception {
        PackageManager pm = context.getPackageManager();
        PackageInfo incoming = pm.getPackageArchiveInfo(apk.getAbsolutePath(), PackageManager.GET_SIGNATURES);
        PackageInfo installed = pm.getPackageInfo(context.getPackageName(), PackageManager.GET_SIGNATURES);
        if (incoming == null || !context.getPackageName().equals(incoming.packageName)
                || incoming.versionCode != expectedCode || incoming.versionCode <= installed.versionCode)
            throw new IOException("安装包包名或版本不正确");
        if (Build.VERSION.SDK_INT >= 24 && incoming.applicationInfo.minSdkVersion > Build.VERSION.SDK_INT)
            throw new IOException("新版不支持此 Android 系统");
        if (incoming.signatures == null || installed.signatures == null
                || !new HashSet<Signature>(Arrays.asList(incoming.signatures)).equals(new HashSet<Signature>(Arrays.asList(installed.signatures))))
            throw new IOException("安装包签名与当前 App 不同，已拒绝更新");
    }
    private static void requireOk(HttpURLConnection conn) throws Exception {
        int code = conn.getResponseCode();
        if (code != 200) throw new IOException(code == 401 ? "请重新登录 NAS 后检查更新" : "更新服务暂不可用（HTTP " + code + "）");
    }
    private static void copy(InputStream in, OutputStream out, int max) throws IOException {
        byte[] buffer = new byte[8192]; int n, count = 0;
        while ((n = in.read(buffer)) != -1) { count += n; if (count > max) throw new IOException("更新信息过大"); out.write(buffer, 0, n); }
    }
    static String hex(byte[] bytes) {
        StringBuilder value = new StringBuilder(); for (byte b : bytes) value.append(String.format(Locale.ROOT, "%02x", b & 255)); return value.toString();
    }
}
