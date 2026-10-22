package family.libretv.tv;

/** Monotonic-time watchdog independent of Android; pauses/suppression never spend recovery budget. */
final class LivePlaybackWatchdog {
    static final long FIRST_FRAME_MS=30000,BUFFERING_MS=20000,FROZEN_MS=15000,STABLE_MS=30000;
    enum Signal { NONE, NO_FRAME, BUFFERING, FROZEN, STABLE }
    private long firstFrameStart,bufferingStart,progressAt,position,stableStart;
    private boolean sawFrame,stableReported;
    void begin(long now){firstFrameStart=progressAt=now;bufferingStart=stableStart=-1;position=Long.MIN_VALUE;sawFrame=stableReported=false;}
    Signal sample(long now,boolean shouldPlay,boolean buffering,boolean ready,boolean frameAvailable,long newPosition){
        if(!shouldPlay){firstFrameStart=progressAt=now;bufferingStart=stableStart=-1;position=newPosition;return Signal.NONE;}
        sawFrame|=frameAvailable;
        if(!sawFrame&&now-firstFrameStart>=FIRST_FRAME_MS)return Signal.NO_FRAME;
        if(buffering){
            stableStart=-1;
            if(bufferingStart<0)bufferingStart=now;
            if(sawFrame&&now-bufferingStart>=BUFFERING_MS)return Signal.BUFFERING;
        }else bufferingStart=-1;
        if(ready&&sawFrame&&!buffering){
            if(newPosition!=position){position=newPosition;progressAt=now;}else if(now-progressAt>=FROZEN_MS)return Signal.FROZEN;
            if(stableStart<0)stableStart=now;
            if(!stableReported&&now-stableStart>=STABLE_MS){stableReported=true;return Signal.STABLE;}
        }else {stableStart=-1;progressAt=now;position=newPosition;}
        return Signal.NONE;
    }
}
