package family.libretv.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.view.Gravity;
import android.view.ViewGroup;
import androidx.core.view.WindowInsetsControllerCompat;
import android.widget.*;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowCompat;
import androidx.core.view.WindowInsetsCompat;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.Arrays;

/** Phone playback: one player, bounded episode controls, and durable local progress. */
@androidx.media3.common.util.UnstableApi
public final class DirectPlayerActivity extends Activity {
    private ExoPlayer player;
    private PlayerView playerView;
    private PlaybackGestureLayout stage;
    private LinearLayout topChrome,bottomChrome;
    private boolean controlsVisible=true;
    private SubtitleMenu subtitles;
    private AlertDialog dialog;
    private long position;
    private boolean resume = true;
    private int index;
    private ArrayList<String> urls;
    private JSONObject source, item;
    private String title;
    private TextView status;
    private Button previous, next, restart, speed, subtitle;
    private CheckBox autoNext;
    private PlaybackStore history;
    private SharedPreferences settings;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Runnable checkpoint = new Runnable() {
        @Override public void run() { saveProgress(); main.postDelayed(this, 5000); }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        if(state!=null&&state.containsKey("windowBrightness")){android.view.WindowManager.LayoutParams attrs=getWindow().getAttributes();attrs.screenBrightness=state.getFloat("windowBrightness");getWindow().setAttributes(attrs);}
        WindowCompat.setDecorFitsSystemWindows(getWindow(), false);
        history = new PlaybackStore(this);
        settings = getSharedPreferences("standalone-player", 0);
        urls = getIntent().getStringArrayListExtra("episodes");
        if (urls == null || urls.isEmpty()) urls = new ArrayList<>(Arrays.asList(getIntent().getStringExtra("url")));
        index = Math.max(0, Math.min(urls.size() - 1, getIntent().getIntExtra("index", 0)));
        title = getIntent().getStringExtra("title");
        try {
            source = new JSONObject(getIntent().getStringExtra("source"));
            item = new JSONObject(getIntent().getStringExtra("item"));
            JSONObject saved = history.find(source, item);
            if (saved != null && saved.optInt("index") == index)
                position = PlaybackStore.resumePosition(saved.optLong("position"), saved.optLong("duration"));
        } catch (Exception ignored) { source = null; item = null; }
        if (state != null) {
            index = Math.max(0, Math.min(urls.size() - 1, state.getInt("index", index)));
            position = state.getLong("position"); resume = state.getBoolean("resume", true);
            controlsVisible=state.getBoolean("controlsVisible",true);
        }
    }
    private int dp(int n) { return Math.round(n * getResources().getDisplayMetrics().density); }
    private Button button(LinearLayout row, String text, Runnable action) {
        Button b = new Button(this); b.setText(text); b.setAllCaps(false); b.setTextSize(13);
        row.addView(b, new LinearLayout.LayoutParams(0, dp(48), 1));
        b.setOnClickListener(v -> { if(playerView!=null)playerView.showController(); action.run(); }); return b;
    }
    @Override protected void onStart() {
        super.onStart();
        boolean restoreControls=controlsVisible;
        stage=new PlaybackGestureLayout(this);stage.setBackgroundColor(0xff000000);
        playerView=new PlayerView(this);stage.addView(playerView,new FrameLayout.LayoutParams(-1,-1));
        playerView.setControllerShowTimeoutMs(3000);playerView.setControllerHideOnTouch(true);
        playerView.setControllerAutoShow(true);
        topChrome=new LinearLayout(this);topChrome.setOrientation(LinearLayout.VERTICAL);topChrome.setBackgroundColor(0xbb0c141d);
        stage.addView(topChrome,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        LinearLayout top=new LinearLayout(this);topChrome.addView(top);
        button(top,"返回选集",this::finish);
        button(top,"横屏 / 竖屏",()->setRequestedOrientation(getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE
            ? ActivityInfo.SCREEN_ORIENTATION_PORTRAIT : ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE));
        status=new TextView(this);status.setTextColor(0xffffffff);status.setTextSize(15);status.setPadding(dp(6),dp(4),dp(6),dp(4));topChrome.addView(status);
        bottomChrome=new LinearLayout(this);bottomChrome.setOrientation(LinearLayout.VERTICAL);bottomChrome.setBackgroundColor(0xbb0c141d);
        stage.addView(bottomChrome,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        LinearLayout controls=new LinearLayout(this);bottomChrome.addView(controls);
        previous=button(controls,"上一集",()->switchEpisode(index-1));
        restart=button(controls,"从头播放",()->{if(player!=null){player.seekTo(0);player.play();}});
        next=button(controls,"下一集",()->switchEpisode(index+1));
        LinearLayout options=new LinearLayout(this);bottomChrome.addView(options);
        speed=button(options,"倍速",this::chooseSpeed);
        subtitle=button(options,"字幕",()->{if(subtitles!=null){holdControls();subtitles.show();}});
        autoNext=new CheckBox(this);autoNext.setText("自动下一集");autoNext.setTextColor(0xffffffff);autoNext.setTextSize(13);
        autoNext.setChecked(settings.getBoolean("autoNext",true));options.addView(autoNext,new LinearLayout.LayoutParams(-2,dp(48)));
        autoNext.setOnCheckedChangeListener((v,on)->{settings.edit().putBoolean("autoNext",on).apply();playerView.showController();});
        ViewCompat.setOnApplyWindowInsetsListener(stage,(v,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            topChrome.setPadding(bars.left+dp(8),bars.top,bars.right+dp(8),0);
            bottomChrome.setPadding(bars.left+dp(8),0,bars.right+dp(8),bars.bottom);return insets;
        });
        bottomChrome.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->adjustControllerInsets());
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener)visibility->{
            controlsVisible=visibility==View.VISIBLE;
            topChrome.setVisibility(visibility);bottomChrome.setVisibility(visibility);adjustControllerInsets();
        });
        setContentView(stage);applySystemBars();ViewCompat.requestApplyInsets(stage);
        try {
            NasClient direct = new NasClient(this);
            player = new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(() -> new NasDataSource(direct))).build();
            playerView.setPlayer(player); playerView.setKeepScreenOn(true);
            stage.bind(player,playerView,topChrome,bottomChrome);
            subtitles = new SubtitleMenu(this, player, subtitle, playerView.getSubtitleView(), settings, this::resumeControls, true);
            player.setPlaybackSpeed(settings.getFloat("speed", 1));
            player.addListener(new Player.Listener() {
                @Override public void onPlayerError(PlaybackException error) {
                    status.setText("播放失败，请返回选集换源（" + error.getErrorCodeName() + "）");
                    playerView.showController();
                    restart.setText("重试播放"); restart.setOnClickListener(v -> { player.prepare(); player.play(); });
                }
                @Override public void onTracksChanged(Tracks tracks) { if (subtitles != null) subtitles.refresh(); }
                @Override public void onPlaybackStateChanged(int state) {
                    if (subtitles != null) subtitles.refresh();
                    if (state == Player.STATE_READY) {
                        updateHeading();
                        if(source != null && item != null) try { history.add(source,item,title); }
                        catch(Exception ignored) { /* A full list must not interrupt playback. */ }
                    }
                    if (state == Player.STATE_ENDED) {
                        saveProgress();
                        if (autoNext.isChecked() && index + 1 < urls.size()) switchEpisode(index + 1);
                        else {status.setText(title + " · 第 " + (index + 1) + " 集已播放完");playerView.showController();}
                    }
                }
            });
            loadEpisode();
            if(restoreControls)playerView.showController();else playerView.hideController();
            main.postDelayed(checkpoint, 5000);
        } catch (Exception e) { status.setText("无法播放：" + e.getMessage()); }
    }
    private void updateHeading() {
        status.setText(title + (urls.size() > 1 ? " · 第 " + (index + 1) + " / " + urls.size() + " 集" : ""));
        previous.setEnabled(index > 0); next.setEnabled(index + 1 < urls.size());
        speed.setText("倍速 " + settings.getFloat("speed", 1) + "×");
    }
    private void loadEpisode() throws Exception {
        String url = urls.get(index); LocalRepository.validate(url);
        updateHeading(); restart.setText("从头播放"); restart.setOnClickListener(v -> { player.seekTo(0); player.play(); });
        MediaItem.Builder media = new MediaItem.Builder().setUri(url);
        if (url.toLowerCase(java.util.Locale.ROOT).contains(".m3u8")) media.setMimeType(MimeTypes.APPLICATION_M3U8);
        player.setMediaItem(media.build()); player.seekTo(position); player.prepare(); player.setPlayWhenReady(resume);
    }
    private void switchEpisode(int target) {
        if (target < 0 || target >= urls.size() || player == null) return;
        saveProgress(); index = target; position = 0; resume = true;
        try { loadEpisode(); } catch (Exception e) { status.setText("无法播放：" + e.getMessage()); }
    }
    private void chooseSpeed() {
        holdControls();
        float[] values = {.75f, 1, 1.25f, 1.5f, 2}; String[] labels = {"0.75×", "1×", "1.25×", "1.5×", "2×"};
        dialog = new AlertDialog.Builder(this).setTitle("播放速度").setItems(labels, (d, which) -> {
            settings.edit().putFloat("speed", values[which]).apply(); if (player != null) player.setPlaybackSpeed(values[which]); updateHeading();
        }).setOnDismissListener(d->resumeControls()).show();
    }
    private void applySystemBars() {
        if(stage==null)return;
        WindowInsetsControllerCompat bars=WindowCompat.getInsetsController(getWindow(),stage);
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);
        if(getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE)bars.hide(WindowInsetsCompat.Type.systemBars());
        else bars.show(WindowInsetsCompat.Type.systemBars());
    }
    private void holdControls(){playerView.setControllerShowTimeoutMs(0);playerView.showController();}
    private void resumeControls(){if(playerView!=null){playerView.setControllerShowTimeoutMs(3000);playerView.showController();}}
    private void adjustControllerInsets() {
        if(playerView==null || bottomChrome==null)return;
        View controller=playerView.findViewById(androidx.media3.ui.R.id.exo_controller);
        if(controller!=null)controller.setPadding(0,0,0,bottomChrome.getHeight());
        if(playerView.getSubtitleView()!=null && playerView.getSubtitleView().getLayoutParams() instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams params=(FrameLayout.LayoutParams)playerView.getSubtitleView().getLayoutParams();
            int bottom=controlsVisible?bottomChrome.getHeight()+dp(64):0;
            if(params.bottomMargin!=bottom){params.bottomMargin=bottom;playerView.getSubtitleView().setLayoutParams(params);}
        }
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)applySystemBars();}
    @SuppressWarnings("deprecation") @Override public void onBackPressed(){
        if(playerView!=null&&(controlsVisible||playerView.isControllerFullyVisible())){
            playerView.hideController();controlsVisible=false;
            topChrome.setVisibility(View.GONE);bottomChrome.setVisibility(View.GONE);return;
        }
        if(getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE)setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT);
        else super.onBackPressed();
    }
    private void saveProgress() {
        if (player != null) history.save(source, item, title, index, player.getCurrentPosition(), player.getDuration());
    }
    @Override protected void onSaveInstanceState(Bundle state) {
        state.putFloat("windowBrightness",getWindow().getAttributes().screenBrightness);
        state.putInt("index", index);
        state.putBoolean("controlsVisible",controlsVisible);
        state.putLong("position", player == null ? position : player.getCurrentPosition());
        state.putBoolean("resume", player == null ? resume : player.getPlayWhenReady()); super.onSaveInstanceState(state);
    }
    @Override protected void onPause() { saveProgress(); super.onPause(); }
    @Override protected void onStop() {
        if(stage!=null)stage.release();
        main.removeCallbacks(checkpoint); if (dialog != null) dialog.dismiss(); if (subtitles != null) subtitles.close();
        if (player != null) { saveProgress(); position = player.getCurrentPosition(); resume = player.getPlayWhenReady(); player.release(); player = null; }
        super.onStop();
    }
}
