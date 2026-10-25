package family.libretv.tv;

import android.app.*;
import android.content.*;
import android.view.*;
import android.widget.TextView;
import androidx.test.platform.app.InstrumentationRegistry;
import junit.framework.TestCase;
import java.util.*;

/** ActivityMonitor.onStartActivity interception is available on API 26+. */
public final class LivePickerFallbackTest extends TestCase {
    public void testMissingPickersShowActionableMessage() throws Exception {
        Instrumentation instrument=InstrumentationRegistry.getInstrumentation();
        Context context=instrument.getTargetContext();
        Activity activity=instrument.startActivitySync(new Intent(context,LiveBrowseActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        final List<String> requested=new ArrayList<>();
        Instrumentation.ActivityMonitor monitor=new Instrumentation.ActivityMonitor(){@Override public Instrumentation.ActivityResult onStartActivity(Intent intent){
            if(Intent.ACTION_OPEN_DOCUMENT.equals(intent.getAction())||Intent.ACTION_GET_CONTENT.equals(intent.getAction())){
                requested.add(intent.getAction());throw new ActivityNotFoundException("Simulated TV without picker");
            }
            return null;
        }};
        instrument.addMonitor(monitor);
        try {
            instrument.runOnMainSync(()->find(activity.getWindow().getDecorView(),"导入 M3U").performClick());
            assertEquals(Arrays.asList(Intent.ACTION_OPEN_DOCUMENT,Intent.ACTION_GET_CONTENT),requested);
            instrument.runOnMainSync(()->assertNotNull(find(activity.getWindow().getDecorView(),"电视没有可用的文件选择器")));
        } finally {instrument.removeMonitor(monitor);instrument.runOnMainSync(activity::finish);instrument.waitForIdleSync();}
    }
    private View find(View v,String text){
        if(v instanceof TextView&&((TextView)v).getText().toString().contains(text))return v;
        if(v instanceof ViewGroup)for(int i=0;i<((ViewGroup)v).getChildCount();i++){View found=find(((ViewGroup)v).getChildAt(i),text);if(found!=null)return found;}
        return null;
    }
}
