package family.libretv.tv;

import android.content.Context;
import android.content.SharedPreferences;

/** Durable per-series settings, independent of episode progress and favorite membership. */
final class SegmentSkipStore {
    private final SharedPreferences prefs;
    private final String key;

    SegmentSkipStore(Context context, String title) {
        prefs = context.getApplicationContext().getSharedPreferences("segment-skip", Context.MODE_PRIVATE);
        key = SegmentSkipPolicy.titleKey(title);
    }

    boolean hasTitle() { return !key.isEmpty(); }
    long intro() { return prefs.getLong(key + ":intro", 0); }
    long outro() { return prefs.getLong(key + ":outro", 0); }
    boolean enabled() { return hasTitle() && prefs.getBoolean(key + ":enabled", false); }
    void setEnabled(boolean on) { if (hasTitle()) prefs.edit().putBoolean(key + ":enabled", on).apply(); }
    void mark(boolean tail, long millis) {
        if (hasTitle()) prefs.edit().putLong(key + (tail ? ":outro" : ":intro"), millis)
                .putBoolean(key + ":enabled", true).apply();
    }

    String summary() {
        if (intro() == 0 && outro() == 0) return "片头片尾未设置 · 播放时可标记";
        return "片头 " + (intro() > 0 ? time(intro()) : "未设置")
                + " · 片尾 " + (outro() > 0 ? time(outro()) : "未设置")
                + " · 自动跳过" + (enabled() ? "开" : "关");
    }

    static String summary(Context context, String title) { return new SegmentSkipStore(context, title).summary(); }
    private static String time(long millis) {
        return String.format(java.util.Locale.ROOT, "%02d:%02d", millis / 60000, millis / 1000 % 60);
    }
}
