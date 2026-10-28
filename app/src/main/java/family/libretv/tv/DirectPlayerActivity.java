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
import android.view.LayoutInflater;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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
    private TextView status, episodeCaption;
    private LinearLayout episodeGrid;
    private Button favorite;
    private TextView landscapeCaption;
    private boolean landscape;
    private Button previous, next, speed, subtitle;
    private Switch autoNext;
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
    private TextView label(String value,int size) {
        TextView t=new TextView(this);t.setText(value);t.setTextColor(Color.WHITE);t.setTextSize(size);return t;
    }
    private LinearLayout column(){LinearLayout l=new LinearLayout(this);l.setOrientation(LinearLayout.VERTICAL);return l;}
    private Button action(LinearLayout row,String text,String icon,Runnable run){
        Button b=PlayerStyle.action(this,text,icon,false);row.addView(b,new LinearLayout.LayoutParams(0,dp(64),1));
        b.setOnClickListener(v->{playerView.showController();run.run();});return b;
    }
    private void changeOrientation(){setRequestedOrientation(landscape?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);}
    @Override protected void onStart() {
        super.onStart();
        boolean restoreControls=controlsVisible;
        landscape=getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;
        stage=new PlaybackGestureLayout(this);stage.setBackgroundColor(Color.BLACK);
        playerView=(PlayerView)LayoutInflater.from(this).inflate(R.layout.player_phone_view,stage,false);
        stage.addView(playerView,new FrameLayout.LayoutParams(-1,-1));
        playerView.setControllerShowTimeoutMs(3000);playerView.setControllerHideOnTouch(true);playerView.setControllerAutoShow(true);
        previous=playerView.findViewById(R.id.player_previous_episode);next=playerView.findViewById(R.id.player_next_episode);
        for(Button b:new Button[]{previous,next}){b.setMinWidth(0);b.setMinimumWidth(0);b.setText("");b.setBackground(PlayerStyle.background(this,false));}
        PlayerStyle.setIcon(previous,"previous",24);PlayerStyle.setIcon(next,"next",24);
        previous.setOnClickListener(v->switchEpisode(index-1));next.setOnClickListener(v->switchEpisode(index+1));
        previous.setVisibility(landscape?View.VISIBLE:View.GONE);next.setVisibility(landscape?View.VISIBLE:View.GONE);
        topChrome=column();topChrome.setBackgroundResource(R.drawable.player_top);
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);topChrome.addView(heading);
        Button back=PlayerStyle.action(this,"","back",false);back.setContentDescription("返回选集");heading.addView(back,new LinearLayout.LayoutParams(dp(44),dp(48)));back.setOnClickListener(v->finish());
        LinearLayout titleBox=column();heading.addView(titleBox,new LinearLayout.LayoutParams(0,-2,1));
        status=label(landscape?title:"播放详情",landscape?18:20);status.setMaxLines(2);titleBox.addView(status);
        episodeCaption=label("",12);episodeCaption.setTextColor(PlayerStyle.MUTED);if(landscape)titleBox.addView(episodeCaption);
        Button rotate=PlayerStyle.action(this,"","rotate",false);rotate.setContentDescription("横屏 / 竖屏");heading.addView(rotate,new LinearLayout.LayoutParams(dp(48),dp(48)));rotate.setOnClickListener(v->changeOrientation());
        Button moreTop=PlayerStyle.action(this,"","more",false);moreTop.setContentDescription("更多");heading.addView(moreTop,new LinearLayout.LayoutParams(dp(44),dp(48)));moreTop.setOnClickListener(v->showMore());
        bottomChrome=column();bottomChrome.setBackgroundResource(R.drawable.player_bottom);
        autoNext=new Switch(this);autoNext.setText("自动下一集");autoNext.setTextColor(Color.WHITE);autoNext.setThumbTintList(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_checked},new int[]{}},new int[]{PlayerStyle.PINK,PlayerStyle.MUTED}));autoNext.setTrackTintList(android.content.res.ColorStateList.valueOf(0x66344253));
        autoNext.setChecked(settings.getBoolean("autoNext",true));autoNext.setOnCheckedChangeListener((v,on)->settings.edit().putBoolean("autoNext",on).apply());
        LinearLayout root=column();root.setBackgroundColor(PlayerStyle.NAVY);
        if(landscape){
            stage.addView(topChrome,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
            LinearLayout actions=new LinearLayout(this);actions.setGravity(Gravity.CENTER_VERTICAL);bottomChrome.addView(actions);
            landscapeCaption=label("",13);landscapeCaption.setTextColor(PlayerStyle.MUTED);actions.addView(landscapeCaption,new LinearLayout.LayoutParams(0,-2,1));
            Button episodes=action(actions,"选集","episodes",this::chooseEpisode);
            speed=action(actions,"倍速","speed",this::chooseSpeed);
            subtitle=action(actions,"字幕","subtitle",()->{if(subtitles!=null){holdControls();subtitles.show();}});
            action(actions,"更多","more",this::showMore);
            // Compact horizontal actions leave most of the picture unobstructed.
            for(int i=1;i<actions.getChildCount();i++)actions.getChildAt(i).setLayoutParams(new LinearLayout.LayoutParams(dp(68),dp(64)));
            stage.addView(bottomChrome,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
            setContentView(stage);
        }else{
            root.addView(topChrome,new LinearLayout.LayoutParams(-1,-2));
            root.addView(stage,new LinearLayout.LayoutParams(-1,dp(Math.round(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density*9/16f))));
            ScrollView details=new ScrollView(this);details.setFillViewport(true);details.addView(bottomChrome);root.addView(details,new LinearLayout.LayoutParams(-1,0,1));
            bottomChrome.setBackgroundColor(PlayerStyle.NAVY);bottomChrome.setPadding(dp(18),dp(12),dp(18),dp(16));
            LinearLayout titleRow=new LinearLayout(this);titleRow.setGravity(Gravity.CENTER_VERTICAL);bottomChrome.addView(titleRow);
            status=label(title,26);status.setTypeface(null,Typeface.BOLD);titleRow.addView(status,new LinearLayout.LayoutParams(0,-2,1));
            favorite=PlayerStyle.action(this,"收藏","star",false);titleRow.addView(favorite,new LinearLayout.LayoutParams(dp(80),dp(48)));favorite.setCompoundDrawables(favorite.getCompoundDrawables()[1],null,null,null);favorite.setCompoundDrawablePadding(dp(5));favorite.setTextSize(12);favorite.setPadding(0,0,0,0);
            favorite.setBackground(PlayerStyle.surface(Color.TRANSPARENT,0,this));
            favorite.setTextColor(new android.content.res.ColorStateList(new int[][]{new int[]{android.R.attr.state_selected},new int[]{}},new int[]{PlayerStyle.PINK,PlayerStyle.MUTED}));
            favorite.setOnClickListener(v->toggleFavorite());refreshFavorite();
            bottomChrome.addView(episodeCaption);
            LinearLayout actions=new LinearLayout(this);LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-1,dp(68));ap.topMargin=dp(8);bottomChrome.addView(actions,ap);
            action(actions,"上一集","previous",()->switchEpisode(index-1));action(actions,"下一集","next",()->switchEpisode(index+1));
            speed=action(actions,"倍速","speed",this::chooseSpeed);
            subtitle=action(actions,"字幕","subtitle",()->{if(subtitles!=null){holdControls();subtitles.show();}});
            TextView episodes=label("选集",20);episodes.setTypeface(null,Typeface.BOLD);episodes.setPadding(0,dp(12),0,dp(8));bottomChrome.addView(episodes);
            episodeGrid=column();bottomChrome.addView(episodeGrid);
            bottomChrome.addView(autoNext,new LinearLayout.LayoutParams(-1,dp(48)));
            setContentView(root);
        }
        ViewCompat.setOnApplyWindowInsetsListener(landscape?stage:root,(v,insets)->{
            androidx.core.graphics.Insets bars=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            if(landscape){topChrome.setPadding(bars.left+dp(16),bars.top+dp(6),bars.right+dp(16),dp(12));bottomChrome.setPadding(bars.left+dp(20),dp(4),bars.right+dp(20),bars.bottom+dp(4));}
            else {root.setPadding(bars.left,bars.top,bars.right,bars.bottom);topChrome.setPadding(dp(6),0,dp(6),0);}
            return insets;
        });
        bottomChrome.addOnLayoutChangeListener((v,l,t,r,b,ol,ot,or,ob)->adjustControllerInsets());
        playerView.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener)visibility->{
            controlsVisible=visibility==View.VISIBLE;
            if(landscape){topChrome.setVisibility(visibility);bottomChrome.setVisibility(visibility);}adjustControllerInsets();
        });
        applySystemBars();ViewCompat.requestApplyInsets(landscape?stage:root);
        try {
            NasClient direct = new NasClient(this);
            player = new ExoPlayer.Builder(this).setMediaSourceFactory(new DefaultMediaSourceFactory(() -> new NasDataSource(direct))).build();
            playerView.setPlayer(player); playerView.setKeepScreenOn(true);
            stage.bind(player,playerView,topChrome,bottomChrome);stage.setSingleTapSeek(true);
            subtitles = new SubtitleMenu(this, player, subtitle, playerView.getSubtitleView(), settings, this::resumeControls, true);
            player.setPlaybackSpeed(settings.getFloat("speed", 1));
            player.addListener(new Player.Listener() {
                @Override public void onPlayerError(PlaybackException error) {
                    status.setText("播放失败，请返回选集换源（" + error.getErrorCodeName() + "）");
                    playerView.showController();
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
                        if (settings.getBoolean("autoNext",true) && index + 1 < urls.size()) switchEpisode(index + 1);
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
        status.setText(title);
        episodeCaption.setText("第 "+(index+1)+" 集 / 共 "+urls.size()+" 集");
        refreshEpisodeGrid();
        previous.setEnabled(index > 0); next.setEnabled(index + 1 < urls.size());
        speed.setText((landscape?"":"倍速 ") + settings.getFloat("speed", 1) + "×");
        if(landscapeCaption!=null)landscapeCaption.setText(episodeCaption.getText());
        if(!landscape)for(int n=0;n<2;n++) {Button b=(Button)((ViewGroup)speed.getParent()).getChildAt(n);b.setEnabled(n==0?index>0:index+1<urls.size());}
    }
    private void loadEpisode() throws Exception {
        String url = urls.get(index); LocalRepository.validate(url);
        updateHeading();
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
        if(playerView==null||bottomChrome==null)return;
        View timeline=playerView.findViewById(R.id.player_timeline);
        if(timeline!=null){FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)timeline.getLayoutParams();int margin=landscape&&controlsVisible?bottomChrome.getHeight():0;if(p.bottomMargin!=margin){p.bottomMargin=margin;timeline.setLayoutParams(p);}}
        if(playerView.getSubtitleView()!=null&&playerView.getSubtitleView().getLayoutParams() instanceof FrameLayout.LayoutParams){
            FrameLayout.LayoutParams p=(FrameLayout.LayoutParams)playerView.getSubtitleView().getLayoutParams();int margin=controlsVisible?(landscape?bottomChrome.getHeight()+dp(60):dp(48)):0;
            if(p.bottomMargin!=margin){p.bottomMargin=margin;playerView.getSubtitleView().setLayoutParams(p);}
        }
    }
    private void showMore(){
        holdControls();String[] options={player!=null&&player.getPlayerError()!=null?"重试播放":"从头播放", "自动下一集 · "+(settings.getBoolean("autoNext",true)?"开启":"关闭")};
        dialog=new AlertDialog.Builder(this).setTitle("更多播放设置").setItems(options,(d,which)->{
            if(which==0&&player!=null){if(player.getPlayerError()!=null)player.prepare();else player.seekTo(0);player.play();}else if(which==1)autoNext.setChecked(!settings.getBoolean("autoNext",true));
        }).setOnDismissListener(d->resumeControls()).show();
    }
    private void chooseEpisode(){
        holdControls();String[] labels=new String[urls.size()];for(int i=0;i<labels.length;i++)labels[i]="第 "+(i+1)+" 集";
        dialog=new AlertDialog.Builder(this).setTitle("选集").setSingleChoiceItems(labels,index,(d,which)->{switchEpisode(which);d.dismiss();}).setNegativeButton("关闭",null).setOnDismissListener(d->resumeControls()).show();
    }
    private void refreshEpisodeGrid(){
        if(episodeGrid==null)return;episodeGrid.removeAllViews();int columns=getResources().getConfiguration().fontScale>1.3f?3:4;
        for(int i=0;i<urls.size();i+=columns){LinearLayout row=new LinearLayout(this);episodeGrid.addView(row);for(int j=0;j<columns;j++){
            int target=i+j;if(target>=urls.size()){row.addView(new View(this),new LinearLayout.LayoutParams(0,dp(48),1));continue;}
            Button chip=PlayerStyle.action(this,String.format(java.util.Locale.ROOT,"第%02d集",target+1),"",false);chip.setCompoundDrawables(null,null,null,null);chip.setSelected(target==index);
            GradientDrawable idle=PlayerStyle.surface(0xff162131,10,this);idle.setStroke(dp(1),0xff344253);android.graphics.drawable.StateListDrawable bg=new android.graphics.drawable.StateListDrawable();bg.addState(new int[]{android.R.attr.state_selected},PlayerStyle.surface(PlayerStyle.PINK,10,this));bg.addState(new int[]{},idle);chip.setBackground(bg);
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(0,dp(44),1);cp.setMargins(0,dp(4),dp(j==columns-1?0:6),dp(4));row.addView(chip,cp);chip.setOnClickListener(v->switchEpisode(target));
        }}
    }
    private void refreshFavorite(){
        if(favorite==null)return;favorite.setEnabled(source!=null&&item!=null);if(source==null||item==null)return;
        try{String id=LocalRepository.key(source.getString("url")+":"+item.getString("vodId"));boolean found=false;org.json.JSONArray all=new LocalRepository(this).favorites("/api/favorites",null,"GET").getJSONArray("items");for(int i=0;i<all.length();i++)if(id.equals(all.getJSONObject(i).getString("id")))found=true;favorite.setText(found?"已收藏":"收藏");favorite.setSelected(found);}catch(Exception e){favorite.setEnabled(false);}
    }
    private void toggleFavorite(){
        if(source==null||item==null)return;
        try{LocalRepository repo=new LocalRepository(this);String id=LocalRepository.key(source.getString("url")+":"+item.getString("vodId"));if(favorite.isSelected())repo.favorites("/api/favorites/"+id,null,"DELETE");else repo.favorites("/api/favorites",new JSONObject().put("source",source).put("item",item),"POST");refreshFavorite();}catch(Exception e){Toast.makeText(this,"收藏保存失败，请重试",Toast.LENGTH_SHORT).show();}
    }
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused)applySystemBars();}
    @SuppressWarnings("deprecation") @Override public void onBackPressed(){
        if(playerView!=null&&(controlsVisible||playerView.isControllerFullyVisible())){
            playerView.hideController();controlsVisible=false;
            if(landscape){topChrome.setVisibility(View.GONE);bottomChrome.setVisibility(View.GONE);}return;
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
