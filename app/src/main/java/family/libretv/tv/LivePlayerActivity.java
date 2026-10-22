package family.libretv.tv;

import android.app.Activity;
import android.os.*;
import android.view.*;
import android.widget.*;
import androidx.core.view.*;
import androidx.media3.common.*;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import java.util.concurrent.*;
import org.json.*;

/** Live-only playback. VOD player, saved progress and cast queues are never touched. */
@androidx.media3.common.util.UnstableApi
public final class LivePlayerActivity extends Activity {
    private LiveRepository repository;
    private JSONArray channels;
    private int index,lineIndex,ticket,retries;
    private ExoPlayer player;
    private PlayerView video;
    private PlaybackGestureLayout stage;
    private LinearLayout chrome;
    private TextView title;
    private Button lines;
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Future<?> pending;
    private boolean active;
    private final Runnable retry=()->{if(active&&player!=null){player.seekToDefaultPosition();player.prepare();player.play();}};
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        if(state!=null&&state.containsKey("windowBrightness")){WindowManager.LayoutParams attrs=getWindow().getAttributes();attrs.screenBrightness=state.getFloat("windowBrightness");getWindow().setAttributes(attrs);}
        stage=new PlaybackGestureLayout(this);stage.setBackgroundColor(0xff0c141d);
        video=new PlayerView(this);video.setControllerShowTimeoutMs(3000);stage.addView(video,new FrameLayout.LayoutParams(-1,-1));
        chrome=new LinearLayout(this);chrome.setOrientation(LinearLayout.VERTICAL);chrome.setPadding(dp(10),dp(4),dp(10),dp(6));chrome.setBackgroundColor(0xd9101723);
        title=new TextView(this);title.setTextColor(0xffffffff);title.setTextSize(18);chrome.addView(title);
        LinearLayout actions=new LinearLayout(this);chrome.addView(actions);
        button(actions,"返回",this::finish);button(actions,"上一台",()->switchChannel(-1));button(actions,"下一台",()->switchChannel(1));
        button(actions,"回到直播",()->{retries=0;load();});
        lines=button(actions,"换线路",()->{lineIndex++;retries=0;load();});
        FrameLayout.LayoutParams overlay=new FrameLayout.LayoutParams(-1,-2,Gravity.TOP);stage.addView(chrome,overlay);
        video.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener)v->chrome.setVisibility(v));
        ViewCompat.setOnApplyWindowInsetsListener(chrome,(v,insets)->{androidx.core.graphics.Insets b=insets.getInsets(WindowInsetsCompat.Type.systemBars());v.setPadding(dp(10)+b.left,dp(4)+b.top,dp(10)+b.right,dp(6));return insets;});
        setContentView(stage);WindowInsetsControllerCompat bars=WindowCompat.getInsetsController(getWindow(),stage);bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);bars.hide(WindowInsetsCompat.Type.systemBars());
        try{repository=new LiveRepository(this);channels=repository.channels();if(channels.length()==0)throw new Exception("请先导入直播频道");String id=state==null?getIntent().getStringExtra("channelId"):state.getString("channelId");for(int i=0;i<channels.length();i++)if(channels.getJSONObject(i).getString("id").equals(id))index=i;lineIndex=state==null?0:state.getInt("line");}
        catch(Exception e){title.setText(e.getMessage());channels=new JSONArray();}
    }
    private Button button(LinearLayout row,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(12);b.setTextColor(0xffffffff);b.setBackgroundResource(R.drawable.focus_button);b.setPadding(dp(4),0,dp(4),0);row.addView(b,new LinearLayout.LayoutParams(0,dp(46),1));b.setOnClickListener(v->action.run());return b;}
    @Override protected void onStart(){super.onStart();active=true;retries=0;if(channels.length()==0)return;player=new ExoPlayer.Builder(this).build();video.setPlayer(player);stage.bind(player,video,chrome);video.setKeepScreenOn(true);player.setPlaybackSpeed(1f);player.addListener(new Player.Listener(){
        @Override public void onPlayerError(PlaybackException error){
            if(!active)return;
            if(retries++<3){title.setText("直播连接中断，正在重连…");main.removeCallbacks(retry);main.postDelayed(retry,Math.min(8000,1000L<<Math.min(3,retries-1)));}
            else {title.setText("频道暂不可用，可切换线路或频道");video.showController();}
        }
        @Override public void onPlaybackStateChanged(int state){if(state==Player.STATE_READY){try{title.setText(channels.getJSONObject(index).optString("name")+" · 直播");}catch(Exception ignored){}}if(state==Player.STATE_ENDED){title.setText("直播已结束，可切换频道");video.showController();}}
    });load();}
    private void switchChannel(int delta){if(channels.length()==0)return;index=(index+delta+channels.length())%channels.length();lineIndex=0;retries=0;load();}
    private void load(){
        if(!active||player==null||channels.length()==0)return;final int generation=++ticket;main.removeCallbacks(retry);if(pending!=null)pending.cancel(true);player.stop();
        try {
            JSONObject channel=channels.getJSONObject(index);JSONArray options=channel.getJSONArray("lines");lineIndex=Math.floorMod(lineIndex,options.length());JSONObject line=options.getJSONObject(lineIndex);
            lines.setEnabled(options.length()>1);lines.setText(options.length()>1?"线路 "+(lineIndex+1)+"/"+options.length():"单线路");
            repository.remember(channel.getString("id"));title.setText("正在打开 "+channel.optString("name")+"…");video.showController();
            pending=worker.submit(()->{try{
                String mime=LiveRepository.mime(line);
                main.post(()->{if(!active||generation!=ticket||player==null)return;try{
                    DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent(line.optString("userAgent").isEmpty()?LocalRepository.UA:line.optString("userAgent")).setConnectTimeoutMs(8000).setReadTimeoutMs(12000).setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(LiveRepository.headers(line));
                    MediaItem.Builder item=new MediaItem.Builder().setUri(line.getString("url"));if(mime!=null)item.setMimeType(mime);
                    player.setMediaSource(new DefaultMediaSourceFactory(http).createMediaSource(item.build()));player.prepare();player.play();
                }catch(Exception e){title.setText("频道暂不可用，请切换线路或频道");video.showController();}});
            }catch(Exception e){main.post(()->{if(active&&generation==ticket){title.setText("频道暂不可用，请重试或切换线路");video.showController();}});}});
        }catch(Exception e){title.setText("频道资料无效，请更新频道列表");}
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putFloat("windowBrightness",getWindow().getAttributes().screenBrightness);try{state.putString("channelId",channels.getJSONObject(index).getString("id"));state.putInt("line",lineIndex);}catch(Exception ignored){}super.onSaveInstanceState(state);}
    @Override protected void onStop(){stage.release();active=false;++ticket;main.removeCallbacks(retry);if(pending!=null)pending.cancel(true);if(player!=null){video.setPlayer(null);player.release();player=null;}super.onStop();}
    @Override protected void onDestroy(){worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
