package family.libretv.tv;

import android.app.Activity;
import android.app.UiModeManager;
import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.media.AudioManager;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.*;
import android.view.accessibility.AccessibilityManager;
import android.widget.*;
import androidx.media3.common.C;
import androidx.media3.common.Player;
import androidx.media3.ui.PlayerView;
import androidx.media3.ui.TimeBar;
import java.util.Locale;

/** Phone video-surface gestures. Buttons/time bars and TV key events retain their own handling. */
@androidx.media3.common.util.UnstableApi
final class PlaybackGestureLayout extends FrameLayout {
    private final Activity activity;
    private final AudioManager audio;
    private final GestureDetector taps;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final int threshold;
    private Player player;
    private PlayerView video;
    private View[] excluded=new View[0];
    private TextView feedback;
    private float startX,startY,startBrightness;
    private int startVolume,mode;
    private long startPosition,targetPosition;
    private Object mediaId;
    private boolean canceled,doubleTap,vodGestures;
    private final Runnable dismiss=()->{if(feedback!=null)feedback.setVisibility(GONE);};

    PlaybackGestureLayout(Activity activity) {
        super(activity);this.activity=activity;
        audio=(AudioManager)activity.getSystemService(Context.AUDIO_SERVICE);
        threshold=Math.max(ViewConfiguration.get(activity).getScaledTouchSlop(),dp(12));
        taps=new GestureDetector(activity,new GestureDetector.SimpleOnGestureListener(){
            @Override public boolean onDown(MotionEvent e){return true;}
            @Override public boolean onSingleTapConfirmed(MotionEvent e){
                if(!canceled&&video!=null){if(video.isControllerFullyVisible())video.hideController();else video.showController();}
                return true;
            }
            @Override public boolean onDoubleTap(MotionEvent e){
                doubleTap=true;
                float fraction=e.getX()/Math.max(1,getWidth());
                if(vodGestures&&fraction>=.33f&&fraction<=.67f)return true;
                if(sameMedia()&&canSeek()) {
                    long step=vodGestures?10000:5000;
                    long delta=fraction<.5f?-step:step;
                    long target=clamp(player.getCurrentPosition()+delta,player.getDuration());
                    player.seekTo(target);show((delta<0?"后退 ":"前进 ")+(step/1000)+" 秒\n"+time(target));
                } else show(vodGestures?"当前视频暂不支持调整进度":"这个频道暂不支持调整进度");
                hideLater();return true;
            }
        },handler);
        taps.setIsLongpressEnabled(false);
    }
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    void bind(Player player,PlayerView video,View... excluded){this.player=player;this.video=video;this.excluded=excluded;}
    void setVodSeekGestures(boolean enabled){vodGestures=enabled;}
    void release(){cancelDetector();handler.removeCallbacksAndMessages(null);player=null;video=null;if(feedback!=null)feedback.setVisibility(GONE);}
    private boolean enabledFor(MotionEvent e){
        UiModeManager ui=(UiModeManager)getContext().getSystemService(Context.UI_MODE_SERVICE);
        AccessibilityManager accessibility=(AccessibilityManager)getContext().getSystemService(Context.ACCESSIBILITY_SERVICE);
        if(player==null||ui.getCurrentModeType()==Configuration.UI_MODE_TYPE_TELEVISION||accessibility.isTouchExplorationEnabled())return false;
        if(e.isFromSource(android.view.InputDevice.SOURCE_MOUSE))return false;
        for(View v:excluded)if(contains(v,e.getRawX(),e.getRawY()))return false;
        return !controlAt(this,e.getRawX(),e.getRawY());
    }
    private static boolean contains(View v,float x,float y){Rect r=new Rect();return v!=null&&v.isShown()&&v.getGlobalVisibleRect(r)&&r.contains((int)x,(int)y);}
    private boolean controlAt(View v,float x,float y){
        if(!contains(v,x,y))return false;
        if(v instanceof Button||v instanceof ImageButton||v instanceof SeekBar||v instanceof TimeBar)return true;
        if(v instanceof ViewGroup){ViewGroup g=(ViewGroup)v;for(int i=g.getChildCount()-1;i>=0;i--)if(controlAt(g.getChildAt(i),x,y))return true;}
        return false;
    }
    @Override public boolean onInterceptTouchEvent(MotionEvent e){
        return e.getActionMasked()==MotionEvent.ACTION_DOWN&&enabledFor(e);
    }
    @Override public boolean onTouchEvent(MotionEvent e){
        if(player==null)return false;
        int action=e.getActionMasked();
        if(action==MotionEvent.ACTION_DOWN){
            canceled=false;doubleTap=false;mode=0;startX=e.getX();startY=e.getY();
            startPosition=targetPosition=player.getCurrentPosition();mediaId=player.getCurrentMediaItem();
            startVolume=audio.getStreamVolume(AudioManager.STREAM_MUSIC);startBrightness=brightness();
            handler.removeCallbacks(dismiss);
        }
        if(action==MotionEvent.ACTION_CANCEL||action==MotionEvent.ACTION_POINTER_DOWN){
            canceled=true;mode=0;cancelDetector();if(feedback!=null)feedback.setVisibility(GONE);return true;
        }
        if(canceled)return true;
        taps.onTouchEvent(e);
        if(action==MotionEvent.ACTION_MOVE&&!doubleTap){
            float dx=e.getX()-startX,dy=e.getY()-startY;
            if(mode==0&&Math.max(Math.abs(dx),Math.abs(dy))>threshold){
                if(Math.abs(dx)>Math.abs(dy)*1.2f)mode=1;
                else if(Math.abs(dy)>Math.abs(dx)*1.2f)mode=startX<getWidth()/2f?2:3;
            }
            if(mode==1){
                if(!sameMedia()||!canSeek()){show("这个频道暂不支持调整进度");return true;}
                long span=Math.min(120000,player.getDuration());
                targetPosition=clamp(startPosition+Math.round(dx/Math.max(1,getWidth())*span),player.getDuration());
                long delta=targetPosition-startPosition;
                show("进度 "+time(targetPosition)+" / "+time(player.getDuration())+"\n"+(delta<0?"−":"+")+time(Math.abs(delta)));
            }else if(mode==2){
                float value=Math.max(.02f,Math.min(1f,startBrightness-dy/Math.max(1,getHeight())*1.5f));
                WindowManager.LayoutParams attrs=activity.getWindow().getAttributes();attrs.screenBrightness=value;activity.getWindow().setAttributes(attrs);
                show("亮度 "+Math.round(value*100)+"%");
            }else if(mode==3){
                if(audio.isVolumeFixed()){show("当前设备不支持调节媒体音量");return true;}
                int max=audio.getStreamMaxVolume(AudioManager.STREAM_MUSIC);
                int value=Math.max(0,Math.min(max,Math.round(startVolume-dy/Math.max(1,getHeight())*max*1.5f)));
                try{if(value!=audio.getStreamVolume(AudioManager.STREAM_MUSIC))audio.setStreamVolume(AudioManager.STREAM_MUSIC,value,0);show("音量 "+Math.round(value*100f/Math.max(1,max))+"%");}
                catch(SecurityException denied){show("请使用设备音量键调节音量");}
            }
        }
        if(action==MotionEvent.ACTION_UP){
            if(mode==1&&sameMedia()&&canSeek())player.seekTo(clamp(targetPosition,player.getDuration()));
            if(mode!=0)hideLater();mode=0;performClick();
        }
        return true;
    }
    @Override public boolean performClick(){super.performClick();return true;}
    private boolean sameMedia(){return mediaId!=null&&mediaId.equals(player.getCurrentMediaItem());}
    private boolean canSeek(){return player!=null&&player.isCurrentMediaItemSeekable()&&player.isCommandAvailable(Player.COMMAND_SEEK_IN_CURRENT_MEDIA_ITEM)&&player.getDuration()!=C.TIME_UNSET&&player.getDuration()>0;}
    private static long clamp(long value,long duration){return Math.max(0,Math.min(duration,value));}
    private float brightness(){
        float value=activity.getWindow().getAttributes().screenBrightness;
        if(value<0)value=Settings.System.getInt(activity.getContentResolver(),Settings.System.SCREEN_BRIGHTNESS,128)/255f;
        return Math.max(.02f,Math.min(1f,value));
    }
    private static String time(long ms){long seconds=Math.max(0,ms)/1000;return seconds>=3600?String.format(Locale.ROOT,"%d:%02d:%02d",seconds/3600,seconds/60%60,seconds%60):String.format(Locale.ROOT,"%d:%02d",seconds/60,seconds%60);}
    private void show(String message){
        handler.removeCallbacks(dismiss);
        if(feedback==null){feedback=new TextView(getContext());feedback.setTextColor(Color.WHITE);feedback.setTextSize(18);feedback.setGravity(Gravity.CENTER);feedback.setPadding(dp(20),dp(12),dp(20),dp(12));GradientDrawable bg=new GradientDrawable();bg.setColor(0xdf182532);bg.setCornerRadius(dp(12));feedback.setBackground(bg);feedback.setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);FrameLayout.LayoutParams params=new FrameLayout.LayoutParams(-2,-2,Gravity.CENTER);params.setMargins(dp(16),dp(16),dp(16),dp(16));addView(feedback,params);}
        feedback.bringToFront();feedback.setText(message);feedback.setVisibility(VISIBLE);
    }
    private void hideLater(){handler.removeCallbacks(dismiss);handler.postDelayed(dismiss,800);}
    private void cancelDetector(){MotionEvent e=MotionEvent.obtain(0,0,MotionEvent.ACTION_CANCEL,0,0,0);taps.onTouchEvent(e);e.recycle();}
}
