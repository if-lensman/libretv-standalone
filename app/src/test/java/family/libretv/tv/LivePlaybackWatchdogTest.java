package family.libretv.tv;
import org.junit.Test;
import static org.junit.Assert.*;
public class LivePlaybackWatchdogTest {
    @Test public void startupWaitsForFrameInsteadOfMistakingReadyForSuccess(){
        LivePlaybackWatchdog p=new LivePlaybackWatchdog();p.begin(0);assertEquals(LivePlaybackWatchdog.Signal.NONE,p.sample(21000,true,true,false,false,0));assertEquals(LivePlaybackWatchdog.Signal.NO_FRAME,p.sample(30000,true,false,true,false,100));
    }
    @Test public void progressingAudioWithoutVideoDoesNotHideMissingFirstFrame(){
        LivePlaybackWatchdog p=new LivePlaybackWatchdog();p.begin(0);p.sample(10000,true,false,true,false,10000);assertEquals(LivePlaybackWatchdog.Signal.NO_FRAME,p.sample(30000,true,false,true,false,30000));
    }
    @Test public void userPauseDoesNotExpireAndResumeGetsFreshTime(){
        LivePlaybackWatchdog p=new LivePlaybackWatchdog();p.begin(0);assertEquals(LivePlaybackWatchdog.Signal.NONE,p.sample(120000,false,true,false,false,0));assertEquals(LivePlaybackWatchdog.Signal.NONE,p.sample(130000,true,true,false,false,0));assertEquals(LivePlaybackWatchdog.Signal.NO_FRAME,p.sample(150000,true,true,false,false,0));
    }
    @Test public void bufferingAfterFirstFrameAndFrozenPositionTriggerRecovery(){
        LivePlaybackWatchdog p=new LivePlaybackWatchdog();p.begin(0);p.sample(1000,true,false,true,true,1000);p.sample(2000,true,true,false,true,1000);assertEquals(LivePlaybackWatchdog.Signal.BUFFERING,p.sample(22000,true,true,false,true,1000));
        p.begin(0);p.sample(1000,true,false,true,true,1000);assertEquals(LivePlaybackWatchdog.Signal.FROZEN,p.sample(16000,true,false,true,true,1000));
    }
    @Test public void stableBudgetResetNeedsThirtyContinuousSecondsNotReady(){
        LivePlaybackWatchdog p=new LivePlaybackWatchdog();p.begin(0);p.sample(1000,true,false,true,true,1000);assertEquals(LivePlaybackWatchdog.Signal.NONE,p.sample(20000,true,false,true,true,20000));p.sample(21000,true,true,false,true,20000);p.sample(22000,true,false,true,true,21000);assertEquals(LivePlaybackWatchdog.Signal.NONE,p.sample(51000,true,false,true,true,50000));assertEquals(LivePlaybackWatchdog.Signal.STABLE,p.sample(52000,true,false,true,true,51000));assertEquals(LivePlaybackWatchdog.Signal.NONE,p.sample(53000,true,false,true,true,52000));
    }
}
