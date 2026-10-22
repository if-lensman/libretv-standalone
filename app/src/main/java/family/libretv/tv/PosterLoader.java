package family.libretv.tv;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.util.LruCache;
import android.widget.ImageView;
import java.io.*;
import java.net.HttpURLConnection;
import java.util.concurrent.*;

/** Small, bounded cover loader. Images use the authenticated NAS proxy, never third-party cookies. */
final class PosterLoader {
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ThreadPoolExecutor workers = new ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(64), new ThreadPoolExecutor.DiscardOldestPolicy());
    private final LruCache<String, Bitmap> cache = new LruCache<String, Bitmap>(8 * 1024 * 1024) {
        @Override protected int sizeOf(String key, Bitmap value) { return value.getAllocationByteCount(); }
    };
    private boolean closed;
    void load(ImageView view, NasClient client, String url) { load(view,client,url,R.drawable.poster_placeholder); }
    void loadPublic(ImageView view,String url,int placeholder) { load(view,null,url,placeholder); }
    private void load(ImageView view,NasClient client,String url,int placeholder) {
        String key = (client==null?"public":client.origin) + "|" + url;
        view.setTag(key);
        view.setImageResource(placeholder);
        if (url.isEmpty() || closed) return;
        Bitmap saved = cache.get(key);
        if (saved != null) { view.setImageBitmap(saved); return; }
        workers.execute(() -> {
            Bitmap bitmap = fetch(client, url);
            main.post(() -> {
                if (closed || bitmap == null) return;
                cache.put(key, bitmap);
                if (key.equals(view.getTag())) view.setImageBitmap(bitmap);
            });
        });
    }
    private Bitmap fetch(NasClient client, String url) {
        HttpURLConnection connection = null;
        try {
            connection = client==null?LocalRepository.open(LocalRepository.validate(url)):client.open(client.proxy(url));
            String host=java.net.URI.create(url).getHost();
            if((client==null||client.local!=null) && host!=null && (host.equals("doubanio.com") || host.endsWith(".doubanio.com")))
                connection.setRequestProperty("Referer","https://movie.douban.com/");
            connection.setReadTimeout(12000);
            if (connection.getResponseCode() != 200) return null;
            byte[] data;
            try (InputStream in = connection.getInputStream(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[8192]; int count;
                while ((count = in.read(buffer)) != -1) {
                    if (out.size() + count > 5 * 1024 * 1024) return null;
                    out.write(buffer, 0, count);
                }
                data = out.toByteArray();
            }
            BitmapFactory.Options bounds = new BitmapFactory.Options(); bounds.inJustDecodeBounds = true;
            BitmapFactory.decodeByteArray(data, 0, data.length, bounds);
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0 || bounds.outWidth > 20000 || bounds.outHeight > 20000) return null;
            BitmapFactory.Options options = new BitmapFactory.Options(); options.inSampleSize = 1;
            while (bounds.outWidth / options.inSampleSize > 480 || bounds.outHeight / options.inSampleSize > 720) options.inSampleSize *= 2;
            return BitmapFactory.decodeByteArray(data, 0, data.length, options);
        } catch (IOException | IllegalArgumentException ignored) { return null; }
        finally { if (connection != null) connection.disconnect(); }
    }
    void close() { closed = true; workers.shutdownNow(); cache.evictAll(); main.removeCallbacksAndMessages(null); }
}
