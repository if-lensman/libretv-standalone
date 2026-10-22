package family.libretv.tv;

import android.net.Uri;
import androidx.media3.common.C;
import androidx.media3.datasource.BaseDataSource;
import androidx.media3.datasource.DataSpec;
import java.io.*;
import java.net.*;
import java.util.*;

/** All HLS manifests, segments, keys and maps remain on the authenticated NAS proxy. */
@androidx.media3.common.util.UnstableApi
final class NasDataSource extends BaseDataSource {
    private final NasClient client;
    private HttpURLConnection connection;
    private InputStream stream;
    private Uri uri;
    private long remaining;
    private boolean opened;
    NasDataSource(NasClient client) { super(true); this.client = client; }
    @Override public long open(DataSpec spec) throws IOException {
        transferInitializing(spec);
        uri = spec.uri;
        URI target;
        try { target = new URI(uri.toString()); } catch (URISyntaxException ex) { throw new IOException(ex); }
        if (client.local == null && (!NasAddress.sameOrigin(client.origin, target) || !"/api/proxy".equals(target.getPath())
                && !target.getPath().startsWith("/api/proxy/"))) {
            throw new IOException("已阻止非 NAS 代理的播放请求");
        }
        try {
            connection = client.open(target);
            if (spec.position != 0 || spec.length != C.LENGTH_UNSET) {
                String end = spec.length == C.LENGTH_UNSET ? "" : Long.toString(spec.position + spec.length - 1);
                connection.setRequestProperty("Range", "bytes=" + spec.position + "-" + end);
            }
            int code = connection.getResponseCode();
            if (code < 200 || code >= 300) throw new NasClient.ApiException(code, "NAS 播放代理 HTTP " + code);
            stream = connection.getInputStream();
            if(client.local!=null) uri=Uri.parse(connection.getURL().toString());
            long length = -1;
            try { length = Long.parseLong(connection.getHeaderField("Content-Length")); } catch (Exception ignored) { }
            // Some upstreams ignore Range. Skip exactly, rather than corrupting MP4 seeks.
            long skip = code == 200 ? spec.position : 0;
            while (skip > 0) {
                long n = stream.skip(skip);
                if (n == 0) { if (stream.read() == -1) throw new EOFException("跳转超出媒体范围"); n = 1; }
                skip -= n;
            }
            remaining = spec.length != C.LENGTH_UNSET ? spec.length
                    : length >= 0 ? length - (code == 200 ? spec.position : 0) : C.LENGTH_UNSET;
            opened = true;
            transferStarted(spec);
            return remaining;
        } catch (IOException e) { close(); throw e; }
    }
    @Override public int read(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) return 0;
        if (remaining == 0) return C.RESULT_END_OF_INPUT;
        int count = stream.read(buffer, offset, remaining < 0 ? length : (int) Math.min(length, remaining));
        if (count == -1) {
            if (remaining > 0) throw new EOFException("媒体连接提前中断");
            return C.RESULT_END_OF_INPUT;
        }
        if (remaining > 0) remaining -= count;
        bytesTransferred(count);
        return count;
    }
    @Override public Uri getUri() { return uri; }
    @Override public Map<String, List<String>> getResponseHeaders() {
        return connection == null ? Collections.emptyMap() : connection.getHeaderFields();
    }
    @Override public void close() throws IOException {
        try { if (stream != null) stream.close(); }
        finally {
            stream = null;
            if (connection != null) connection.disconnect();
            connection = null; uri = null;
            if (opened) { opened = false; transferEnded(); }
        }
    }
}
