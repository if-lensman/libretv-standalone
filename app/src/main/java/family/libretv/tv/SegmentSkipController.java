package family.libretv.tv;

import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.view.Gravity;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.widget.Toast;
import androidx.media3.common.Player;

/** Per-title marks, direct controls, and a cancellable end-of-episode transition. */
@androidx.media3.common.util.UnstableApi
final class SegmentSkipController implements Player.Listener {
    private final Context context;
    private final SegmentSkipStore store;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable reveal, restore, finishEpisode;
    private TextView summary;
    private Button toggle;
    private Player player;
    private boolean introPending, ignoreOutro, internalSeek, completed;
    private long countdown;
    private int generation;
    private final Runnable tick = new Runnable() {
        @Override public void run() {
            int opened = generation;
            check();
            if (player != null && opened == generation) main.postDelayed(this, 250);
        }
    };

    SegmentSkipController(Context context, String title, Runnable reveal, Runnable restore, Runnable finishEpisode) {
        this.context = context;
        this.reveal = reveal;
        this.restore = restore;
        this.finishEpisode = finishEpisode;
        store = new SegmentSkipStore(context, title);
    }

    private long intro() { return store.intro(); }
    private long outro() { return store.outro(); }
    private boolean enabled() { return store.enabled(); }
    boolean isCompleted() { return completed; }
    boolean isCountingDown() { return countdown != 0; }

    void addControls(LinearLayout parent, boolean tv) {
        LinearLayout box = new LinearLayout(context); box.setOrientation(LinearLayout.VERTICAL);
        LinearLayout row = new LinearLayout(context); box.addView(row);
        addButton(row, "设置片头", tv, () -> mark(false));
        addButton(row, "设置片尾", tv, () -> mark(true));
        toggle = addButton(row, "自动跳过 · 关", tv, this::toggle);
        summary = new TextView(context); summary.setTextColor(PlayerStyle.MUTED); summary.setTextSize(tv ? 13 : 11);
        summary.setGravity(Gravity.CENTER); box.addView(summary);
        parent.addView(box, Math.min(tv ? 3 : 1, parent.getChildCount()));
        refresh();
    }

    private Button addButton(LinearLayout row, String text, boolean tv, Runnable action) {
        Button b = PlayerStyle.action(context, text, "", tv);
        b.setCompoundDrawables(null, null, null, null); b.setTextSize(tv ? 16 : 12);
        b.setPadding(dp(4), 0, dp(4), 0); b.setMinWidth(0); b.setMinimumWidth(0);
        LinearLayout.LayoutParams p = new LinearLayout.LayoutParams(0, dp(48), 1);
        p.setMargins(dp(3), dp(2), dp(3), dp(2)); row.addView(b, p);
        b.setOnClickListener(v -> action.run());
        return b;
    }

    private int dp(int value) { return Math.round(value * context.getResources().getDisplayMetrics().density); }
    private void message(String value) { Toast.makeText(context, value, Toast.LENGTH_SHORT).show(); }
    private static String time(long millis) {
        long seconds = millis / 1000;
        return String.format(java.util.Locale.ROOT, "%02d:%02d", seconds / 60, seconds % 60);
    }

    private void mark(boolean tail) {
        if (player == null || !store.hasTitle() || !player.isCurrentMediaItemSeekable() || player.getDuration() <= 0) {
            message("视频就绪后才能设置片头片尾"); return;
        }
        long position = player.getCurrentPosition(), duration = player.getDuration();
        long head = tail ? intro() : position, end = tail ? duration - position : outro();
        if (position <= 0 || !SegmentSkipPolicy.valid(head, end, duration)) {
            message("请在正片开始或片尾开始的位置设置，片头片尾不能重叠"); return;
        }
        store.mark(tail, tail ? end : head);
        introPending = false; ignoreOutro = tail; cancelCountdown(); refresh();
        message(tail ? "已设置片尾，下一集起自动跳过" : "已设置片头，本剧全集自动沿用");
    }

    private void toggle() {
        if (countdown != 0) { ignoreOutro = true; cancelCountdown(); refresh(); message("本集保留片尾"); return; }
        if (intro() == 0 && outro() == 0) { message("先在播放到合适位置时设置片头或片尾"); return; }
        boolean on = !enabled(); store.setEnabled(on);
        // Toggling never pulls the viewer away from a manually selected position.
        introPending = false; ignoreOutro = player != null && SegmentSkipPolicy.inOutro(player.getCurrentPosition(), intro(), outro(), player.getDuration());
        refresh();
    }

    void bind(Player next) {
        close(); player = next; introPending = next.getCurrentPosition() == 0; ignoreOutro = false; completed = false;
        player.addListener(this); main.post(tick);
    }

    @Override public void onPositionDiscontinuity(Player.PositionInfo oldPosition, Player.PositionInfo newPosition, int reason) {
        if (reason != Player.DISCONTINUITY_REASON_SEEK || internalSeek || player == null) return;
        introPending = false; completed = false; cancelCountdown();
        // Seeking into the credits is an explicit request to watch them.
        ignoreOutro = SegmentSkipPolicy.inOutro(newPosition.positionMs, intro(), outro(), player.getDuration());
        refresh();
    }

    private void check() {
        if (player == null || !enabled() || completed) return;
        if (player.getPlaybackState() != Player.STATE_READY || !player.isCurrentMediaItemSeekable()) {
            if (countdown != 0) { cancelCountdown(); refresh(); } return;
        }
        long duration = player.getDuration(), position = player.getCurrentPosition();
        if (duration <= 0) return;
        if (introPending) {
            introPending = false;
            long target = SegmentSkipPolicy.introTarget(position, intro(), outro(), duration);
            if (target >= 0) { internalSeek = true; try { player.seekTo(target); } finally { internalSeek = false; } return; }
        }
        if (!player.isPlaying() || ignoreOutro || !SegmentSkipPolicy.inOutro(position, intro(), outro(), duration)) {
            if (countdown != 0) { cancelCountdown(); refresh(); } return;
        }
        if (countdown == 0) { countdown = SystemClock.elapsedRealtime() + 5000; reveal.run(); }
        long remaining = countdown - SystemClock.elapsedRealtime();
        if (remaining <= 0) {
            completed = true; cancelCountdown(); refresh(); finishEpisode.run();
        } else {
            toggle.setText("取消跳过");
            toggle.setContentDescription("取消跳过");
            summary.setText("片尾将在 " + ((remaining + 999) / 1000) + " 秒后跳过 · 可取消观看片尾");
        }
    }

    private void cancelCountdown() {
        if (countdown != 0) { countdown = 0; restore.run(); }
    }

    private void refresh() {
        if (toggle == null) return;
        toggle.setText(enabled() ? "自动跳过 · 开" : "自动跳过 · 关");
        toggle.setContentDescription(toggle.getText());
        toggle.setSelected(enabled());
        summary.setText("片头 " + (intro() > 0 ? time(intro()) : "未设置") + " · 片尾 "
                + (outro() > 0 ? time(outro()) : "未设置") + " · 本剧全集 / 换源沿用");
    }

    void close() {
        generation++;
        main.removeCallbacks(tick); cancelCountdown();
        if (player != null) player.removeListener(this);
        player = null;
    }
}
