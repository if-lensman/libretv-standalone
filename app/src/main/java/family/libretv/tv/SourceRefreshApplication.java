package family.libretv.tv;

import android.app.*;
import android.os.*;
import java.util.concurrent.*;

/** Checks subscriptions on foreground entry; never schedules work while the app is closed. */
public final class SourceRefreshApplication extends Application implements Application.ActivityLifecycleCallbacks {
    private final ExecutorService worker=Executors.newFixedThreadPool(2);
    private final Handler main=new Handler(Looper.getMainLooper());
    private int started;
    private boolean foreground;
    private final Runnable background=()->foreground=false;
    @Override public void onCreate(){super.onCreate();registerActivityLifecycleCallbacks(this);}
    @Override public void onActivityStarted(Activity activity){
        started++;main.removeCallbacks(background);
        if(!foreground){
            foreground=true;SourceSubscriptions.beginOpen();LiveRepository.beginOpen();
            worker.execute(()->{
                try{new SourceSubscriptions(new LocalRepository(this)).refresh(false);}catch(Exception ignored){/* Status retained per subscription. */}
            });
            worker.execute(()->{
                try{new LiveRepository(this).refresh(false);}catch(Exception ignored){/* Existing channels remain available. */}
            });
        }
    }
    @Override public void onActivityStopped(Activity activity){if(--started==0)main.postDelayed(background,1000);}
    @Override public void onActivityCreated(Activity activity,Bundle state){}
    @Override public void onActivityResumed(Activity activity){}
    @Override public void onActivityPaused(Activity activity){}
    @Override public void onActivitySaveInstanceState(Activity activity,Bundle state){}
    @Override public void onActivityDestroyed(Activity activity){}
}
