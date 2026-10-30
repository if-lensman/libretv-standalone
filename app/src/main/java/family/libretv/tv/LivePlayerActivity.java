package family.libretv.tv;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
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
import java.util.*;
import android.graphics.Color;
import android.content.res.ColorStateList;
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
    private LinearLayout chrome,statusPanel,headingChrome,channelPanel,actionRow;
    private Button previousChannel,nextChannel,channelButton,playButton,favoriteButton;
    private android.app.AlertDialog liveOptions;
    private TextView channelMeta;
    private String pickerGroup="全部";
    private boolean pickerOpen,controlsShown=true,silentChannelSwitch;
    private TextView title,statusText;
    private Button lines,retryButton,statusNext,orientationButton;
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
    private int entryOrientation;
    private String initialError="请先导入直播频道";
    private final Runnable watchdog=new Runnable(){
        @Override public void run(){if(!active)return;samplePlayback();main.postDelayed(this,1000);}
    };
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}

    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        entryOrientation=state==null?getResources().getConfiguration().orientation:state.getInt("entryOrientation",getResources().getConfiguration().orientation);
        WindowCompat.setDecorFitsSystemWindows(getWindow(),false);
        tvRemote=getIntent().getBooleanExtra("tvRemote",false)||((android.app.UiModeManager)getSystemService(UI_MODE_SERVICE)).getCurrentModeType()==android.content.res.Configuration.UI_MODE_TYPE_TELEVISION;
        setVolumeControlStream(android.media.AudioManager.STREAM_MUSIC);
        if(state!=null&&state.containsKey("windowBrightness")){WindowManager.LayoutParams attrs=getWindow().getAttributes();attrs.screenBrightness=state.getFloat("windowBrightness");getWindow().setAttributes(attrs);}
        stage=new PlaybackGestureLayout(this);stage.setBackgroundColor(0xff0c141d);
        video=(PlayerView)LayoutInflater.from(this).inflate(R.layout.player_live_view,stage,false);
        video.setControllerShowTimeoutMs(3000);video.setControllerAutoShow(!tvRemote);stage.addView(video,new FrameLayout.LayoutParams(-1,-1));
        headingChrome=new LinearLayout(this);headingChrome.setOrientation(LinearLayout.VERTICAL);headingChrome.setBackgroundResource(R.drawable.player_top);
        LinearLayout heading=new LinearLayout(this);heading.setGravity(Gravity.CENTER_VERTICAL);headingChrome.addView(heading);
        Button back=PlayerStyle.action(this,"","back",false);back.setContentDescription("返回频道列表");heading.addView(back,new LinearLayout.LayoutParams(dp(42),dp(48)));back.setOnClickListener(v->finish());
        LinearLayout titles=new LinearLayout(this);titles.setOrientation(LinearLayout.VERTICAL);heading.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        title=new TextView(this);title.setTextColor(Color.WHITE);title.setTextSize(tvRemote?26:20);title.setMaxLines(1);title.setEllipsize(android.text.TextUtils.TruncateAt.END);titles.addView(title);
        channelMeta=new TextView(this);channelMeta.setTextColor(PlayerStyle.MUTED);channelMeta.setTextSize(tvRemote?15:12);titles.addView(channelMeta);
        favoriteButton=PlayerStyle.action(this,"","star",false);favoriteButton.setContentDescription("收藏频道");heading.addView(favoriteButton,new LinearLayout.LayoutParams(dp(48),dp(48)));favoriteButton.setOnClickListener(v->toggleChannelFavorite());
        if(!tvRemote){orientationButton=PlayerStyle.action(this,"横屏","rotate",false);heading.addView(orientationButton,new LinearLayout.LayoutParams(dp(52),dp(48)));orientationButton.setCompoundDrawables(null,null,null,null);orientationButton.setTextSize(14);orientationButton.setOnClickListener(v->toggleOrientation());updateOrientationButton();}
        stage.addView(headingChrome,new FrameLayout.LayoutParams(-1,-2,Gravity.TOP));
        chrome=new LinearLayout(this);chrome.setOrientation(LinearLayout.VERTICAL);chrome.setBackgroundResource(R.drawable.player_bottom);
        TextView live=new TextView(this);live.setText("● 直播");live.setTextColor(PlayerStyle.PINK);live.setTextSize(tvRemote?16:12);live.setPadding(dp(10),0,0,dp(8));chrome.addView(live);
        actionRow=new LinearLayout(this);actionRow.setGravity(Gravity.CENTER_VERTICAL);chrome.addView(actionRow);
        playButton=liveAction(actionRow,"暂停","tv-pause",()->{if(player!=null){if(player.getPlayWhenReady())player.pause();else player.play();updatePlayButton();video.showController();}});
        previousChannel=liveAction(actionRow,"上一个","key-left",()->switchChannel(-1));
        nextChannel=liveAction(actionRow,"下一个","key-right",()->switchChannel(1));
        channelButton=liveAction(actionRow,"选台","grid",()->showChannelPicker(true));
        liveAction(actionRow,"回到直播","restart",()->beginRound(lineIndex,"正在回到直播位置…",true));
        lines=liveAction(actionRow,"换线路","more",this::manualNextLine);
        if(tvRemote){TextView hint=new TextView(this);hint.setText("方向键选择 · 确认执行 · 返回收起后，左右键静默切台");hint.setTextSize(13);hint.setTextColor(PlayerStyle.MUTED);hint.setGravity(Gravity.CENTER);hint.setPadding(0,dp(12),0,0);chrome.addView(hint);}
        stage.addView(chrome,new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM));
        video.setControllerVisibilityListener((PlayerView.ControllerVisibilityListener)v->{controlsShown=v==View.VISIBLE;updateLiveVisibility();});
        ViewCompat.setOnApplyWindowInsetsListener(stage,(v,insets)->{androidx.core.graphics.Insets b=insets.getInsets(WindowInsetsCompat.Type.systemBars()|WindowInsetsCompat.Type.displayCutout());
            headingChrome.setPadding(dp(tvRemote?32:8)+b.left,dp(tvRemote?18:4)+b.top,dp(tvRemote?32:8)+b.right,dp(16));
            chrome.setPadding(dp(tvRemote?36:10)+b.left,dp(tvRemote?32:14),dp(tvRemote?36:10)+b.right,dp(tvRemote?20:8)+b.bottom);return insets;});
        buildStatusPanel();
        setContentView(stage);hideSystemBars();
        try{
            repository=new LiveRepository(this);channels=repository.channels();
            String id=state==null?getIntent().getStringExtra("channelId"):state.getString("channelId");
            for(int i=0;i<channels.length();i++)if(channels.getJSONObject(i).getString("id").equals(id))index=i;
            pickerGroup=state==null?"全部":state.getString("pickerGroup","全部");
            restoreLine=state!=null;lineIndex=state==null?0:state.getInt("line");playAfterRecovery=state==null||state.getBoolean("resume",true);
        }catch(Exception e){initialError="频道资料无法读取，请返回更新频道列表";}
        layoutLiveUi();
        if(tvRemote){video.setFocusable(true);video.setFocusableInTouchMode(true);video.requestFocus();}
    }
    private boolean landscape(){return getResources().getConfiguration().orientation==Configuration.ORIENTATION_LANDSCAPE;}
    private void toggleOrientation(){setRequestedOrientation(landscape()?ActivityInfo.SCREEN_ORIENTATION_PORTRAIT:ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE);}
    private void updateOrientationButton(){if(orientationButton!=null)orientationButton.setText(landscape()?"竖屏":"横屏");}
    private void hideSystemBars(){
        WindowInsetsControllerCompat bars=WindowCompat.getInsetsController(getWindow(),stage);
        bars.setSystemBarsBehavior(WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE);bars.hide(WindowInsetsCompat.Type.systemBars());
    }
    // Rotation only relayouts the live surface; it must not reconnect or restart recovery timers.
    @Override public void onConfigurationChanged(Configuration configuration){super.onConfigurationChanged(configuration);updateOrientationButton();hideSystemBars();layoutLiveUi();ViewCompat.requestApplyInsets(stage);}
    @Override public void onWindowFocusChanged(boolean focused){super.onWindowFocusChanged(focused);if(focused&&stage!=null)hideSystemBars();}
    private Button button(LinearLayout row,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextSize(tvRemote?16:12);b.setTextColor(0xffffffff);b.setBackgroundResource(R.drawable.focus_button);b.setPadding(dp(4),0,dp(4),0);row.addView(b,new LinearLayout.LayoutParams(0,dp(46),1));b.setOnClickListener(v->action.run());return b;}
    private boolean phonePortrait(){return !tvRemote&&!landscape();}
    private Button liveAction(LinearLayout row,String text,String icon,Runnable action){
        LinearLayout cell=new LinearLayout(this);cell.setOrientation(LinearLayout.VERTICAL);cell.setGravity(Gravity.CENTER_HORIZONTAL);
        row.addView(cell,new LinearLayout.LayoutParams(0,-2,1));
        Button b=tvRemote?PlayerStyle.tvAction(this,text,icon):PlayerStyle.action(this,text,icon,false);
        if(!tvRemote){b.setTextSize(11);b.setPadding(dp(2),dp(4),dp(2),dp(4));}
        cell.addView(b,new LinearLayout.LayoutParams(tvRemote?-2:-1,dp(tvRemote?50:58)));b.setOnClickListener(v->action.run());
        if(tvRemote)b.setOnFocusChangeListener((v,focused)->{if(focused&&!pickerOpen)video.showController();});
        if(tvRemote){String key="key-left".equals(icon)?"方向键左":"key-right".equals(icon)?"方向键右":"";TextView hint=new TextView(this);hint.setText(key);hint.setTextColor(PlayerStyle.MUTED);hint.setTextSize(13);hint.setGravity(Gravity.CENTER);cell.addView(hint,new LinearLayout.LayoutParams(-1,dp(23)));}
        return b;
    }
    private void layoutLiveUi(){
        boolean portrait=phonePortrait();
        FrameLayout.LayoutParams videoParams=new FrameLayout.LayoutParams(-1,portrait?dp(Math.round(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density*9/16f)):-1,Gravity.TOP);
        videoParams.topMargin=portrait?dp(76):0;video.setLayoutParams(videoParams);
        FrameLayout.LayoutParams statusParams=(FrameLayout.LayoutParams)statusPanel.getLayoutParams();statusParams.gravity=portrait?Gravity.TOP|Gravity.CENTER_HORIZONTAL:Gravity.CENTER;statusParams.topMargin=portrait?videoParams.topMargin+Math.max(0,videoParams.height/2-dp(70)):dp(12);statusPanel.setLayoutParams(statusParams);
        FrameLayout.LayoutParams actions=new FrameLayout.LayoutParams(-1,-2,portrait?Gravity.TOP:Gravity.BOTTOM);actions.topMargin=portrait?videoParams.topMargin+videoParams.height:0;chrome.setLayoutParams(actions);
        if(channelPanel!=null){stage.removeView(channelPanel);channelPanel=null;}
        if(portrait||pickerOpen)showChannelPicker(false);
        updateLiveVisibility();
        if(player!=null)stage.bind(player,video,chrome,headingChrome,statusPanel,channelPanel);
    }
    private void updateLiveVisibility(){
        if(chrome==null)return;
        boolean show=phonePortrait()||controlsShown;
        chrome.setVisibility(!phonePortrait()&&pickerOpen?View.GONE:show?View.VISIBLE:View.GONE);
        headingChrome.setVisibility(!phonePortrait()&&pickerOpen?View.GONE:show?View.VISIBLE:View.GONE);
    }
    private String groupOf(JSONObject c){String group=c.optString("group");return group.isEmpty()?"未分组":group;}
    private List<Integer> visibleChannels(){
        ArrayList<Integer> result=new ArrayList<>();
        for(int i=0;i<channels.length();i++){JSONObject c=channels.optJSONObject(i);if(c==null)continue;if("全部".equals(pickerGroup)||"收藏".equals(pickerGroup)&&repository.favorite(c.optString("id"))||!"收藏".equals(pickerGroup)&&pickerGroup.equals(groupOf(c)))result.add(i);}return result;
    }
    private void showChannelPicker(boolean focus){
        if(repository==null)return;
        if(!phonePortrait())pickerOpen=true;
        if(tvRemote)video.setFocusable(!pickerOpen);
        if(channelPanel!=null)stage.removeView(channelPanel);
        channelPanel=new LinearLayout(this);channelPanel.setOrientation(LinearLayout.VERTICAL);channelPanel.setBackgroundColor(0xf5101723);channelPanel.setPadding(dp(16),dp(phonePortrait()?8:20),dp(16),dp(12));
        int panelWidth=phonePortrait()?getResources().getDisplayMetrics().widthPixels:Math.min(getResources().getDisplayMetrics().widthPixels,dp(tvRemote?490:340));
        FrameLayout.LayoutParams panelParams=new FrameLayout.LayoutParams(panelWidth,-1,phonePortrait()?Gravity.TOP:Gravity.RIGHT);
        if(phonePortrait())panelParams.topMargin=dp(76)+dp(Math.round(getResources().getDisplayMetrics().widthPixels/getResources().getDisplayMetrics().density*9/16f))+dp(106);
        channelPanel.setLayoutParams(panelParams);stage.addView(channelPanel);
        if(!phonePortrait()){LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);channelPanel.addView(header);TextView heading=new TextView(this);heading.setText("频道分类");heading.setTextSize(tvRemote?24:18);heading.setTextColor(Color.WHITE);header.addView(heading,new LinearLayout.LayoutParams(0,-2,1));Button close=PlayerStyle.action(this,"关闭","close",false);close.setText("");close.setContentDescription("关闭频道分类");header.addView(close,new LinearLayout.LayoutParams(dp(48),dp(48)));close.setOnClickListener(v->closeChannelPicker());}
        LinkedHashSet<String> groups=new LinkedHashSet<>();groups.add("全部");groups.add("收藏");for(int i=0;i<channels.length();i++)groups.add(groupOf(channels.optJSONObject(i)));
        if(!groups.contains(pickerGroup))pickerGroup="全部";
        HorizontalScrollView tabsScroll=new HorizontalScrollView(this);tabsScroll.setHorizontalScrollBarEnabled(false);channelPanel.addView(tabsScroll,new LinearLayout.LayoutParams(-1,dp(50)));
        LinearLayout tabs=new LinearLayout(this);tabsScroll.addView(tabs);
        Button selectedTab=null;
        for(String group:groups){Button tab=PlayerStyle.action(this,group,"",false);tab.setCompoundDrawables(null,null,null,null);tab.setSelected(group.equals(pickerGroup));if(group.equals(pickerGroup))selectedTab=tab;tab.setContentDescription("频道分类 · "+group);tab.setTextSize(tvRemote?17:14);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(44));p.setMargins(0,0,dp(6),0);tabs.addView(tab,p);tab.setOnClickListener(v->{pickerGroup=group;showChannelPicker(false);});}
        TextView count=new TextView(this);List<Integer> visible=visibleChannels();count.setText(pickerGroup+" · "+visible.size()+" 个频道");count.setTextColor(PlayerStyle.MUTED);count.setTextSize(tvRemote?16:12);count.setPadding(dp(4),dp(8),0,dp(10));channelPanel.addView(count);
        ScrollView scroll=new ScrollView(this);channelPanel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        GridLayout grid=new GridLayout(this);grid.setColumnCount(2);scroll.addView(grid);
        Button current=null,first=null;
        ArrayList<Button> tiles=new ArrayList<>();
        for(int target:visible){JSONObject c=channels.optJSONObject(target);Button tile=PlayerStyle.action(this,c.optString("name"),"",false);tile.setId(View.generateViewId());tile.setCompoundDrawables(null,null,null,null);tile.setTextSize(tvRemote?18:13);tile.setMaxLines(2);tile.setPadding(dp(7),dp(4),dp(7),dp(4));tile.setSelected(target==index);tile.setBackground(tvRemote?PlayerStyle.tvTileBackground(this):PlayerStyle.background(this,true));tile.setContentDescription("播放频道 · "+c.optString("name"));GridLayout.LayoutParams p=new GridLayout.LayoutParams();p.width=(panelWidth-dp(32))/2-dp(8);p.height=dp(tvRemote?58:50);p.setMargins(dp(4),dp(4),dp(4),dp(4));grid.addView(tile,p);tiles.add(tile);if(first==null)first=tile;if(target==index)current=tile;
            tile.setOnClickListener(v->{silentChannelSwitch=false;if(target!=index){index=target;try{beginRound(repository.preferredLine(channel(),System.currentTimeMillis()),"正在切换频道…",true);}catch(Exception e){showStatus("频道资料无效，请更新列表",false,true);}}if(phonePortrait())showChannelPicker(false);else closeChannelPicker();});}
        for(int i=0;i<tiles.size();i++){Button b=tiles.get(i);b.setNextFocusLeftId(tiles.get(i%2==0?i:i-1).getId());b.setNextFocusRightId(tiles.get(i%2==1||i+1==tiles.size()?i:i+1).getId());}
        if(visible.isEmpty()){TextView empty=new TextView(this);empty.setText("收藏".equals(pickerGroup)?"还没有收藏频道":"暂无频道");empty.setTextColor(PlayerStyle.MUTED);grid.addView(empty);}
        if(!phonePortrait()){
            LinearLayout footer=new LinearLayout(this);channelPanel.addView(footer);
            Button favorite=button(footer,repository.favorite(channels.optJSONObject(index)==null?"":channels.optJSONObject(index).optString("id"))?"取消收藏":"收藏频道",this::toggleChannelFavorite);
            Button options=button(footer,"更多操作",this::showLiveOptions);favorite.setBackground(PlayerStyle.background(this,true));options.setBackground(PlayerStyle.background(this,true));
        }
        updateLiveVisibility();if(player!=null)stage.bind(player,video,chrome,headingChrome,statusPanel,channelPanel);
        if(focus){Button target=current!=null?current:first;if(target!=null)target.requestFocus();}else if(tvRemote&&selectedTab!=null)selectedTab.requestFocus();
    }
    private void closeChannelPicker(){
        if(phonePortrait())return;
        pickerOpen=false;video.setFocusable(true);if(channelPanel!=null){stage.removeView(channelPanel);channelPanel=null;}
        video.showController();controlsShown=true;updateLiveVisibility();if(player!=null)stage.bind(player,video,chrome,headingChrome,statusPanel);if(tvRemote)channelButton.requestFocus();
    }
    private void showLiveOptions(){
        liveOptions=new android.app.AlertDialog.Builder(this).setTitle("直播操作").setItems(new String[]{"换线路","回到直播","返回频道列表"},(d,which)->{if(which==0)manualNextLine();else if(which==1)beginRound(lineIndex,"正在回到直播位置…",true);else finish();}).setNegativeButton("关闭",null).show();
    }
    private void updateChannelHeading(){
        try{JSONObject c=channel();title.setText(c.optString("name"));channelMeta.setText("直播 · "+groupOf(c)+" · "+label());favoriteButton.setSelected(repository.favorite(c.optString("id")));favoriteButton.setContentDescription(repository.favorite(c.optString("id"))?"取消收藏频道":"收藏频道");}catch(Exception ignored){}
    }
    private void toggleChannelFavorite(){try{repository.toggleFavorite(channel().getString("id"));updateChannelHeading();if(channelPanel!=null)showChannelPicker(false);}catch(Exception ignored){}}
    private void updatePlayButton(){if(playButton==null||player==null)return;playButton.setText(player.getPlayWhenReady()?"暂停":"播放");if(tvRemote)PlayerStyle.setTvIcon(playButton,player.getPlayWhenReady()?"tv-pause":"tv-play",28);else PlayerStyle.setIcon(playButton,player.getPlayWhenReady()?"tv-pause":"tv-play",24);}
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
    private void switchChannel(int delta){switchChannel(delta,false);}
    private void switchChannel(int delta,boolean silent){
        if(channels.length()==0)return;
        silentChannelSwitch=silent;
        List<Integer> visible=visibleChannels();if(visible.isEmpty())return;int current=visible.indexOf(index);index=visible.get(current<0?(delta<0?visible.size()-1:0):(current+delta+visible.size())%visible.size());
        try{beginRound(repository.preferredLine(channel(),System.currentTimeMillis()),"正在切换频道…",true);}catch(Exception e){showStatus("频道资料无效，请更新列表",false,true);}
        if(phonePortrait())showChannelPicker(false);
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
            repository.remember(selected.getString("id"));updateChannelHeading();
            lines.setEnabled(lineCount()>1);lines.setText(lineCount()>1?label():"单线路");showStatus(message+"\n"+label(),true,false);
            DefaultLoadControl load=new DefaultLoadControl.Builder().setBufferDurationsMs(10000,25000,1500,4000).build();
            player=new ExoPlayer.Builder(this).setLoadControl(load).build();ExoPlayer opened=player;
            video.setPlayer(opened);stage.bind(opened,video,chrome,headingChrome,statusPanel,channelPanel);video.setKeepScreenOn(true);opened.setPlaybackSpeed(1f);opened.setPlayWhenReady(resume);
            if(tvRemote&&silentChannelSwitch)video.hideController();else video.showController();
            updatePlayButton();
            opened.addListener(new Player.Listener(){
                @Override public void onPlayWhenReadyChanged(boolean play,int reason){updatePlayButton();}
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
    private void showLiveControls(){
        silentChannelSwitch=false;video.showController();controlsShown=true;updateLiveVisibility();playButton.requestFocus();
    }
    @Override public boolean dispatchKeyEvent(KeyEvent event){
        int code=event.getKeyCode();
        if(tvRemote&&!pickerOpen){
            boolean horizontal=code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT;
            boolean direction=horizontal||code==KeyEvent.KEYCODE_DPAD_UP||code==KeyEvent.KEYCODE_DPAD_DOWN;
            // Only hidden controls permit one direct channel change per press.
            if(!controlsShown&&horizontal){
                if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0&&!event.isCanceled())switchChannel(code==KeyEvent.KEYCODE_DPAD_LEFT?-1:1,true);return true;
            }
            if((direction&&(!controlsShown||!(getCurrentFocus() instanceof Button)))||code==KeyEvent.KEYCODE_MENU){
                if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)showLiveControls();return true;
            }
            if((code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER)&&!(getCurrentFocus() instanceof Button)){
                if(event.getAction()==KeyEvent.ACTION_DOWN&&event.getRepeatCount()==0)showLiveControls();return true;
            }
        }
        return super.dispatchKeyEvent(event);
    }
    @SuppressWarnings("deprecation") @Override public void onBackPressed(){
        if(pickerOpen&&!phonePortrait()){closeChannelPicker();return;}
        // Touch navigation matches the on-screen Back button, including while buffering.
        // A car head unit may ignore portrait requests, so Back must not depend on rotation.
        if(!tvRemote){finish();return;}
        if(video!=null&&(video.isControllerFullyVisible()||!phonePortrait()&&chrome.getVisibility()==View.VISIBLE)){
            video.hideController();controlsShown=false;updateLiveVisibility();return;
        }
        super.onBackPressed();
    }
    @Override protected void onSaveInstanceState(Bundle state){state.putInt("entryOrientation",entryOrientation);state.putString("pickerGroup",pickerGroup);state.putFloat("windowBrightness",getWindow().getAttributes().screenBrightness);state.putBoolean("resume",player==null?playAfterRecovery:player.getPlayWhenReady());try{state.putString("channelId",channel().getString("id"));state.putInt("line",lineIndex);}catch(Exception ignored){}super.onSaveInstanceState(state);}
    @Override protected void onStop(){if(liveOptions!=null){liveOptions.dismiss();liveOptions=null;}active=false;++ticket;cancelRetry();main.removeCallbacks(watchdog);stage.release();if(pending!=null)pending.cancel(true);if(player!=null){playAfterRecovery=player.getPlayWhenReady();video.setPlayer(null);player.release();player=null;}super.onStop();}
    @Override protected void onDestroy(){worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
