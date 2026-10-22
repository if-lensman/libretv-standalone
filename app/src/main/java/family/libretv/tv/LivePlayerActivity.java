package family.libretv.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.net.ConnectivityManager;
import android.os.*;
import android.util.Log;
import android.view.*;
import android.widget.*;
import androidx.core.view.*;
import androidx.media3.common.*;
import androidx.media3.datasource.DefaultHttpDataSource;
import androidx.media3.datasource.HttpDataSource;
import androidx.media3.exoplayer.DefaultLoadControl;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy;
import androidx.media3.ui.PlayerView;
import java.util.concurrent.*;
import org.json.*;

/** Live-only playback with bounded recovery. Never writes VOD progress or cast queues. */
@androidx.media3.common.util.UnstableApi
public final class LivePlayerActivity extends Activity {
    private LiveRepository repository;
    private JSONArray channels=new JSONArray();
    private int index,lineIndex,ticket;
    private ExoPlayer player;
    private PlayerView video;
    private PlaybackGestureLayout stage;
    private LinearLayout chrome,statusPanel;
    private TextView title,statusText;
    private Button lines,retryButton,statusNext;
    private ProgressBar spinner;
    private final LiveRecoveryPolicy recovery=new LiveRecoveryPolicy();
    private final LivePlaybackWatchdog monitor=new LivePlaybackWatchdog();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private Future<?> pending;
    private boolean active,recovering,terminal,waitingNetwork,renderedFrame,started,restoreLine,playAfterRecovery=true;
    private long attemptStarted,firstFrameMs;
    private Runnable retryTask;
    private boolean tvRemote;
    private String initialError="请先导入直播频道";
    private final Runnable watchdog=new Runnable(){
        @Override public void run(){if(!active)return;samplePlayback();main.postDelayed(this,1000);}
    };
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        tvRemote=getIntent().getBooleanExtra("tvRemote",false)||((android.app.UiModeManager)getSystemService(UI_MODE_SERVICE)).getCurrentModeType()==android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        if(state!=null&&state.containsKey("windowBrightness")){WindowManager.LayoutParams attrs=getWindow().getAttributes();attrs.screenBrightness=state.getFloat("windowBrightness");getWindow().setAttributes(attrs);}
        stage=new PlaybackGestureLayout(this);stage.setBackgroundColor(0xff0c141d);
        video=new PlayerView(this);video.setControllerShowTimeoutMs(3000);stage.addView(video,new FrameLayout.LayoutParams(-1,-1));
        if(tvRemote){video.setShowRewindButton(false);video.setShowFastForwardButton(false);}
        chrome=new LinearLayout(this);chrome.setOrientation(LinearLayout.VERTICAL);chrome.setPadding(dp(10),dp(4),dp(10),dp(6));chrome.setBackgroundColor(0xd9101723);
        title=new TextView(this);title.setTextColor(0xffffffff);title.setTextSize(18);chrome.addView(title);
        LinearLayout actions=new LinearLayout(this);chrome.addView(actions);
        button(actions,"返回",this::finish);button(actions,"上一台",()->switchChannel(-1));button(actions,"下一台",()->switchChannel(1));
        button(actions,"回到直播",()->beginRound(lineIndex,"正在回到直播位置…",true));
        lines=button(actions,"换线路",this::manualNextLine);
        stage.addView(chrome,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        video.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener)v->chrome.setVisibility(v));
        ViewCompat.setOnApplyWindowInsetsListener(chrome,(v,insets)->{androidx.core.graphics.Insets b=insets.getInsets(WindowInsetsCompat.Type.systemBars());v.setPadding(dp(10)+b.left,dp(4)+b.top,dp(10)+b.right,dp(6));return insets;});
        buildStatusPanel();
        setContentView(stage);WindowInsetsControllerCompat bars=WindowCompat.getInsetsController(getWindow(),stage);bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);bars.hide(WindowInsetsCompat.Type.systemBars());
        try{
            repository=new LiveRepository(this);channels=repository.channels();
            String id=state==null?getIntent().getStringExtra("channelId"):state.getString("channelId");
            for(int i=0;i<channels.length();i++)if(channels.getJSONObject(i).getString("id").equals(id))index=i;
            restoreLine=state!=null;lineIndex=state==null?0:state.getInt("line");playAfterRecovery=state==null||state.getBoolean("resume",true);
        }catch(Exception e){initialError="频道资料无法读取，请返回更新频道列表";}
    }
    private Button button(LinearLayout row,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(12);b.setTextColor(0xffffffff);b.setBackgroundResource(R.drawable.focus_button);b.setPadding(dp(4),0,dp(4),0);row.addView(b,new LinearLayout.LayoutParams(0,dp(46),1));b.setOnClickListener(v->action.run());return b;}
    private void buildStatusPanel(){
        statusPanel=new LinearLayout(this);statusPanel.setOrientation(LinearLayout.VERTICAL);statusPanel.setGravity(Gravity.CENTER);statusPanel.setPadding(dp(18),dp(16),dp(18),dp(12));
        GradientDrawable bg=new GradientDrawable();bg.setColor(0xee182532);bg.setCornerRadius(dp(12));statusPanel.setBackground(bg);
        spinner=new ProgressBar(this);statusPanel.addView(spinner,new LinearLayout.LayoutParams(dp(32),dp(32)));
        statusText=new TextView(this);statusText.setTextColor(0xffffffff);statusText.setTextSize(16);statusText.setGravity(Gravity.CENTER);statusText.setPadding(0,dp(10),0,dp(12));statusText.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE);statusPanel.addView(statusText,new LinearLayout.LayoutParams(-1,-2));
        LinearLayout row=new LinearLayout(this);statusPanel.addView(row,new LinearLayout.LayoutParams(-1,-2));
        retryButton=button(row,"重试播放",()->beginRound(lineIndex,"正在重新连接…",true));statusNext=button(row,"换线路",this::manualNextLine);
        FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(dp(300),-2,Gravity.CENTER);params.setMargins(dp(12),dp(12),dp(12),dp(12));stage.addView(statusPanel,params);
    }
    private void showStatus(String text,boolean busy,boolean allowRetry){
        boolean newlyRetryable=allowRetry&&retryButton.getVisibility()!=View.VISIBLE;
        if(!text.contentEquals(statusText.getText()))statusText.setText(text);
        spinner.setVisibility(busy?View.VISIBLE:View.GONE);retryButton.setVisibility(allowRetry?View.VISIBLE:View.GONE);statusNext.setVisibility(lineCount()>1?View.VISIBLE:View.GONE);statusPanel.setVisibility(View.VISIBLE);
        if(newlyRetryable)retryButton.requestFocus();
    }
    private int lineCount(){try{return channels.getJSONObject(index).getJSONArray("lines").length();}catch(Exception ignored){return 0;}}
    private JSONObject channel()throws Exception{return channels.getJSONObject(index);}
    private String label(){return "线路 "+(lineIndex+1)+"/"+Math.max(1,lineCount());}
    private boolean online(){ConnectivityManager net=(ConnectivityManager)getSystemService(Context.CONNECTIVITY_SERVICE);return net.getActiveNetwork()!=null;}
    private boolean current(int generation,ExoPlayer opened){return active&&generation==ticket&&player==opened;}
    private void cancelRetry(){if(retryTask!=null)main.removeCallbacks(retryTask);retryTask=null;}

    @Override protected void onStart(){
        super.onStart();active=true;
        if(channels.length()==0){title.setText(initialError);showStatus(initialError,false,false);return;}
        try{if(!started&&!restoreLine)lineIndex=repository.preferredLine(channel(),System.currentTimeMillis());started=true;restoreLine=false;beginRound(lineIndex,"正在连接直播…",playAfterRecovery);}
        catch(Exception e){showStatus("频道资料无效，请返回更新列表",false,false);}
        main.post(watchdog);
    }
    private void switchChannel(int delta){
        if(channels.length()==0)return;
        index=(index+delta+channels.length())%channels.length();
        try{beginRound(repository.preferredLine(channel(),System.currentTimeMillis()),"正在切换频道…",true);}catch(Exception e){showStatus("频道资料无效，请更新列表",false,true);}
    }
    private void manualNextLine(){beginRound(lineIndex+1,"正在切换线路…",true);}
    private void beginRound(int first,String message,boolean resume){
        if(!active)return;
        if(lineCount()==0){showStatus("频道资料无效，请返回更新列表",false,false);return;}
        recovery.begin(lineCount(),first);lineIndex=recovery.current();startAttempt(message,resume);
    }
    private void startAttempt(String message,boolean resume){
        if(!active)return;
        int generation=++ticket;cancelRetry();if(pending!=null)pending.cancel(true);
        stage.release();if(player!=null){video.setPlayer(null);player.release();player=null;}
        recovering=terminal=waitingNetwork=renderedFrame=false;playAfterRecovery=resume;
        attemptStarted=SystemClock.elapsedRealtime();firstFrameMs=-1;monitor.begin(attemptStarted);
        try{
            JSONObject selected=channel(),line=selected.getJSONArray("lines").getJSONObject(lineIndex);
            repository.remember(selected.getString("id"));title.setText(selected.optString("name")+" · 直播");
            lines.setEnabled(lineCount()>1);lines.setText(lineCount()>1?label():"单线路");showStatus(message+"\n"+label(),true,false);video.showController();
            DefaultLoadControl load=new DefaultLoadControl.Builder().setBufferDurationsMs(10000,25000,1500,4000).build();
            player=new ExoPlayer.Builder(this).setLoadControl(load).build();ExoPlayer opened=player;
            video.setPlayer(opened);stage.bind(opened,video,chrome,statusPanel);video.setKeepScreenOn(true);opened.setPlaybackSpeed(1f);opened.setPlayWhenReady(resume);
            opened.addListener(new Player.Listener(){
                @Override public void onPlayerError(PlaybackException error){if(current(generation,opened))fail(classify(error));}
                @Override public void onRenderedFirstFrame(){if(current(generation,opened)){renderedFrame=true;if(firstFrameMs<0)firstFrameMs=SystemClock.elapsedRealtime()-attemptStarted;if(!recovering&&!terminal)statusPanel.setVisibility(View.GONE);}}
                @Override public void onPlaybackStateChanged(int state){
                    if(!current(generation,opened)||recovering||terminal)return;
                    if(state==Player.STATE_BUFFERING)showStatus(renderedFrame?"正在缓冲…\n"+label():"正在等待画面…\n"+label(),true,false);
                    if(state==Player.STATE_ENDED)fail(new Reason("直播已结束",false,true));
                }
            });
            pending=worker.submit(()->{
                try{
                    String mime=LiveRepository.mime(line);
                    main.post(()->{if(!current(generation,opened)||recovering||terminal)return;try{
                        DefaultHttpDataSource.Factory http=new DefaultHttpDataSource.Factory().setUserAgent(line.optString("userAgent").isEmpty()?LocalRepository.UA:line.optString("userAgent")).setConnectTimeoutMs(8000).setReadTimeoutMs(12000).setAllowCrossProtocolRedirects(true).setDefaultRequestProperties(LiveRepository.headers(line));
                        MediaItem.Builder item=new MediaItem.Builder().setUri(line.getString("url"));if(mime!=null)item.setMimeType(mime);
                        // Keep recovery bounded across both HTTP loading and application retries.
                        // Media3 constrains this live offset to the stream's available window.
                        DefaultMediaSourceFactory factory=new DefaultMediaSourceFactory(http).setLoadErrorHandlingPolicy(new DefaultLoadErrorHandlingPolicy(1)).setLiveTargetOffsetMs(5000);
                        opened.setMediaSource(factory.createMediaSource(item.build()));opened.seekToDefaultPosition();opened.prepare();
                    }catch(Exception e){fail(classify(e));}});
                }catch(Exception e){main.post(()->{if(current(generation,opened)&&!recovering&&!terminal)fail(classify(e));});}
            });
        }catch(Exception e){fail(new Reason("频道资料无效",false,true));}
    }
    private void fail(Reason reason){
        if(!active||recovering||terminal)return;
        playAfterRecovery=player==null||player.getPlayWhenReady();
        if(!online()){
            waitingNetwork=recovering=true;cancelRetry();if(pending!=null)pending.cancel(true);if(player!=null)player.stop();
            showStatus("网络已断开，连接恢复后会重新播放",false,true);return;
        }
        int oldLine=lineIndex;LiveRecoveryPolicy.Action action=recovery.fail(reason.window,reason.hard);
        Log.w("LibreTVLive","channel="+index+" line="+(oldLine+1)+" reason="+reason.text+" action="+action.kind);
        if(action.kind==LiveRecoveryPolicy.Kind.STOP||action.kind==LiveRecoveryPolicy.Kind.SWITCH)try{repository.markLineFailed(channel(),oldLine,System.currentTimeMillis());}catch(Exception ignored){}
        if(action.kind==LiveRecoveryPolicy.Kind.STOP){
            terminal=true;if(player!=null)player.stop();showStatus(reason.text+"\n频道暂不可用，已停止自动重试",false,true);video.showController();return;
        }
        recovering=true;
        String message=action.kind==LiveRecoveryPolicy.Kind.SWITCH?"正在切换线路 "+(oldLine+1)+" → "+(action.line+1):action.kind==LiveRecoveryPolicy.Kind.EDGE?"正在回到最新直播位置…":"正在重新连接…";
        showStatus(reason.text+"\n"+message,true,false);
        final int expected=ticket;
        retryTask=()->{if(!active||expected!=ticket||terminal)return;boolean resume=player==null?playAfterRecovery:player.getPlayWhenReady();lineIndex=action.line;startAttempt(message,resume);};
        main.postDelayed(retryTask,action.delayMs);
    }
    private void samplePlayback(){
        if(waitingNetwork){if(online()){waitingNetwork=false;startAttempt("网络已恢复，正在连接…",player==null?playAfterRecovery:player.getPlayWhenReady());}return;}
        if(player==null||terminal||recovering)return;
        long now=SystemClock.elapsedRealtime();int state=player.getPlaybackState();
        boolean shouldPlay=player.getPlayWhenReady()&&player.getPlaybackSuppressionReason()==Player.PLAYBACK_SUPPRESSION_REASON_NONE;
        boolean audioOnly=player.isPlaying()&&player.getVideoFormat()==null&&player.getAudioFormat()!=null;
        if(audioOnly&&firstFrameMs<0)firstFrameMs=now-attemptStarted;
        boolean frame=renderedFrame||audioOnly;
        LivePlaybackWatchdog.Signal signal=monitor.sample(now,shouldPlay,state==Player.STATE_BUFFERING,state==Player.STATE_READY,frame,player.getCurrentPosition());
        switch(signal){
            case NO_FRAME: fail(new Reason("等待画面超时",false,false));return;
            case BUFFERING: fail(new Reason("缓冲时间过长",false,false));return;
            case FROZEN: fail(new Reason("播放已卡住",false,false));return;
            case STABLE:
                recovery.stable();try{repository.markLineStable(channel(),lineIndex,firstFrameMs<0?now-attemptStarted:firstFrameMs);}catch(Exception ignored){}break;
            default:break;
        }
        if(audioOnly)showStatus("当前线路只有音频，没有视频画面",false,false);
        else if(renderedFrame&&state==Player.STATE_READY)statusPanel.setVisibility(View.GONE);
    }
    private static final class Reason {
        final String text;final boolean window,hard;
        Reason(String text,boolean window,boolean hard){this.text=text;this.window=window;this.hard=hard;}
    }
    private static Reason classify(Throwable error){
        if(error instanceof PlaybackException){
            int code=((PlaybackException)error).errorCode;
            if(code==PlaybackException.ERROR_CODE_BEHIND_LIVE_WINDOW)return new Reason("播放已落后于直播窗口",true,false);
            if(code>=3000&&code<6000)return new Reason(code>=4000?"当前线路的音视频暂时无法解码或输出":"当前线路的播放数据无法解析",false,true);
        }
        Throwable cause=error;
        for(int i=0;cause!=null&&i<12;i++,cause=cause.getCause()){
            int status=cause instanceof HttpDataSource.InvalidResponseCodeException?((HttpDataSource.InvalidResponseCodeException)cause).responseCode:cause instanceof LiveRepository.StreamHttpException?((LiveRepository.StreamHttpException)cause).status:0;
            if(status>0)return new Reason("频道请求失败（HTTP "+status+"）",false,status>=400&&status<500&&status!=408&&status!=429);
            if(cause instanceof java.net.SocketTimeoutException)return new Reason("频道连接超时",false,false);
            if(cause instanceof java.net.UnknownHostException)return new Reason("频道地址无法解析",false,false);
            if(cause instanceof javax.net.ssl.SSLException)return new Reason("频道安全连接失败",false,true);
        }
        return new Reason("频道连接中断",false,false);
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int code=event.getKeyCode();
        if(tvRemote&&(code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT)){
            // Consume DOWN, repeated DOWN and UP before PlayerView can seek or move its time bar.
            if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0&&!event.isCanceled())switchChannel(code==KeyEvent.KEYCODE_DPAD_LEFT?-1:1);
            return true;
        }
        return super.dispatchKeyEvent(event);
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putFloat("windowBrightness",getWindow().getAttributes().screenBrightness);state.putBoolean("resume",player==null?playAfterRecovery:player.getPlayWhenReady());try{state.putString("channelId",channel().getString("id"));state.putInt("line",lineIndex);}catch(Exception ignored){}super.onSaveInstanceState(state);}
    @Override protected void onStop(){active=false;++ticket;cancelRetry();main.removeCallbacks(watchdog);stage.release();if(pending!=null)pending.cancel(true);if(player!=null){playAfterRecovery=player.getPlayWhenReady();video.setPlayer(null);player.release();player=null;}super.onStop();}
    @Override protected void onDestroy(){worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
