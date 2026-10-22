package family.libretv.tv;
import org.junit.Test;
import static org.junit.Assert.*;

public class LiveRecoveryPolicyTest {
    @Test public void windowExpiryRecoversImmediatelyThenSwitchesAndStops(){
        LiveRecoveryPolicy p=new LiveRecoveryPolicy();p.begin(2,0);
        LiveRecoveryPolicy.Action a=p.fail(true,false);assertEquals(LiveRecoveryPolicy.Kind.EDGE,a.kind);assertEquals(0,a.delayMs);
        assertEquals(LiveRecoveryPolicy.Kind.EDGE,p.fail(true,false).kind);
        a=p.fail(true,false);assertEquals(LiveRecoveryPolicy.Kind.SWITCH,a.kind);assertEquals(1,a.line);
        p.fail(true,false);p.fail(true,false);assertEquals(LiveRecoveryPolicy.Kind.STOP,p.fail(true,false).kind);
    }
    @Test public void transientErrorHasGraceForShortOutagesAndNeverLoops(){
        LiveRecoveryPolicy p=new LiveRecoveryPolicy();p.begin(2,1);
        assertEquals(LiveRecoveryPolicy.Kind.RELOAD,p.fail(false,false).kind);
        assertEquals(6000,p.fail(false,false).delayMs);
        assertEquals(0,p.fail(false,false).line);
        assertEquals(LiveRecoveryPolicy.Kind.RELOAD,p.fail(false,false).kind);
        assertEquals(LiveRecoveryPolicy.Kind.RELOAD,p.fail(false,false).kind);
        assertEquals(LiveRecoveryPolicy.Kind.STOP,p.fail(false,false).kind);
    }
    @Test public void terminalHttpSkipsStraightToNextAndSingleLineStops(){
        LiveRecoveryPolicy p=new LiveRecoveryPolicy();p.begin(2,0);assertEquals(LiveRecoveryPolicy.Kind.SWITCH,p.fail(false,true).kind);assertEquals(LiveRecoveryPolicy.Kind.STOP,p.fail(false,true).kind);
        p.begin(1,0);assertEquals(LiveRecoveryPolicy.Kind.STOP,p.fail(false,true).kind);
    }
    @Test public void stablePlaybackRestoresBudgetOnCurrentLine(){
        LiveRecoveryPolicy p=new LiveRecoveryPolicy();p.begin(2,0);assertEquals(1,p.fail(false,true).line);p.stable();assertEquals(LiveRecoveryPolicy.Kind.RELOAD,p.fail(false,false).kind);assertEquals(0,p.fail(false,true).line);
    }
    @Test public void globalBudgetCapsEvenHugeCatalogAndManualActionResets(){
        LiveRecoveryPolicy p=new LiveRecoveryPolicy();p.begin(5000,0);for(int i=0;i<5;i++)assertEquals(LiveRecoveryPolicy.Kind.SWITCH,p.fail(false,true).kind);assertEquals(LiveRecoveryPolicy.Kind.STOP,p.fail(false,true).kind);p.begin(2,1);assertEquals(0,p.fail(false,true).line);
    }
}
