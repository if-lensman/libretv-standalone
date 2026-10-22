package family.libretv.tv;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

/** Session is memory-only and bound to one NAS origin. No password is saved. */
public final class NasClient {
    final URI origin;
    final LocalRepository local;
    public NasClient(android.content.Context context) throws Exception { origin=URI.create("https://standalone.invalid/"); local=new LocalRepository(context); }
    private final CookieManager cookies = new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER);
    public NasClient(URI origin) { this.origin = origin; this.local=null; }
    public static final class ApiException extends IOException {
        public final int status;
        ApiException(int status, String message) { super(message); this.status = status; }
    }
    public static String encode(String text) {
        try { return URLEncoder.encode(text, "UTF-8"); } catch (UnsupportedEncodingException e) { throw new AssertionError(e); }
    }
    public synchronized void clearSession() { cookies.getCookieStore().removeAll(); }
    synchronized String cookieHeader(URI uri) throws IOException {
        if (!NasAddress.sameOrigin(origin, uri)) throw new IOException("已阻止向其他服务器发送会话");
        Map<String, List<String>> headers = cookies.get(uri, Collections.emptyMap());
        List<String> values = headers.get("Cookie");
        StringBuilder header = new StringBuilder();
        if (values != null) for (String value : values) {
            if (header.length() > 0) header.append("; ");
            header.append(value);
        }
        return header.toString();
    }
    HttpURLConnection open(URI uri) throws IOException {
        if(local!=null) return LocalRepository.open(uri);
        if (!NasAddress.sameOrigin(origin, uri)) throw new IOException("播放和接口请求只能访问已配置 NAS");
        HttpURLConnection conn = (HttpURLConnection) uri.toURL().openConnection();
        conn.setInstanceFollowRedirects(false); // Never forward credentials through redirects.
        conn.setConnectTimeout(12000);
        conn.setReadTimeout(45000);
        conn.setRequestProperty("Accept", "application/json, */*");
        String cookie = cookieHeader(uri);
        if (!cookie.isEmpty()) conn.setRequestProperty("Cookie", cookie);
        return conn;
    }
    public JSONObject request(String path, JSONObject body) throws Exception {
        return request(path,body,body==null?"GET":"POST");
    }
    public JSONObject request(String path, JSONObject body, String method) throws Exception {
        if(local!=null) return local.request(path,body,method);
        if(!method.equals("GET") && !method.equals("POST") && !method.equals("DELETE")) throw new IOException("不支持的请求方法");
        URI uri = origin.resolve(path);
        HttpURLConnection conn = open(uri);
        try {
            conn.setRequestMethod(method);
            if (body != null) {
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "application/json; charset=utf-8");
                byte[] data = body.toString().getBytes(StandardCharsets.UTF_8);
                conn.setFixedLengthStreamingMode(data.length);
                try (OutputStream out = conn.getOutputStream()) { out.write(data); }
            }
            int code = conn.getResponseCode();
            if (code >= 300 && code < 400) throw new ApiException(code, "NAS 返回重定向，请配置最终地址");
            InputStream stream = code >= 400 ? conn.getErrorStream() : conn.getInputStream();
            String text = readLimited(stream, 8 * 1024 * 1024);
            JSONObject json;
            try { json = new JSONObject(text); }
            catch (Exception e) { throw new IOException("NAS 未返回有效 JSON（HTTP " + code + "），请核对服务地址"); }
            if (code >= 400) throw new ApiException(code, json.optString("error", "HTTP " + code));
            if ("/api/auth".equals(path) && body != null && json.optBoolean("success")) {
                synchronized (this) { cookies.put(uri, conn.getHeaderFields());
                    // Android HttpCookie infers RFC2965 version 1 from Max-Age.
                    // NAS expects the browser RFC6265 unquoted Cookie representation.
                    for (HttpCookie cookie : cookies.getCookieStore().getCookies()) cookie.setVersion(0); }
            }
            return json;
        } finally { conn.disconnect(); }
    }
    private static String readLimited(InputStream stream, int max) throws IOException {
        if (stream == null) return "{}";
        try (InputStream in = stream; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            byte[] buffer = new byte[8192]; int count;
            while ((count = in.read(buffer)) != -1) {
                if (out.size() + count > max) throw new IOException("响应过大，请缩小搜索范围");
                out.write(buffer, 0, count);
            }
            return out.toString("UTF-8");
        }
    }
    public URI proxy(String upstream) throws IOException {
        try {
            URI uri = new URI(upstream);
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null) throw new IOException("不是可播放的 HTTP(S) 链接");
            if(local!=null) return uri;
            // Upstream public-address/DNS validation stays on the existing NAS proxy.
            return origin.resolve("/api/proxy?url=" + encode(upstream));
        } catch (URISyntaxException ex) { throw new IOException("播放地址格式错误"); }
    }
    public String playbackMime(URI proxy, String upstream) throws IOException {
        HttpURLConnection conn = open(proxy);
        try {
            conn.setRequestProperty("Range", "bytes=0-511");
            int code = conn.getResponseCode();
            if (code < 200 || code >= 300) throw new ApiException(code, "播放代理 HTTP " + code);
            String type = conn.getContentType();
            if ((type != null && type.toLowerCase(Locale.ROOT).contains("mpegurl"))
                    || upstream.toLowerCase(Locale.ROOT).contains(".m3u8")) return "application/x-mpegURL";
            return null;
        } finally { conn.disconnect(); }
    }
    public JSONArray sources(JSONObject status) throws Exception {
        JSONArray result = new JSONArray();
        Set<String> urls = new HashSet<>();
        appendSources(result, status.optJSONArray("defaultSources"), urls);
        JSONArray subscriptions = status.optJSONArray("defaultSubscriptions");
        if (subscriptions != null) {
            for (int i = 0; i < subscriptions.length() && i < 10; i++) {
                String url = subscriptions.getJSONObject(i).optString("url");
                JSONObject payload = request("/api/source-list?url=" + encode(url), null);
                appendSources(result, payload.optJSONArray("sources"), urls);
            }
        }
        return result;
    }
    private static void appendSources(JSONArray out, JSONArray incoming, Set<String> seen) throws Exception {
        if (incoming == null) return;
        for (int i = 0; i < incoming.length() && out.length() < 50; i++) {
            JSONObject s = new JSONObject(incoming.getJSONObject(i).toString());
            if (s.optBoolean("isAdult") || s.optString("url").isEmpty() || !seen.add(s.optString("url"))) continue;
            s.put("key", "tv_" + out.length());
            out.put(s);
        }
    }
}
