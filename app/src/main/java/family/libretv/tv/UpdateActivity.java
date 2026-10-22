package family.libretv.tv;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.widget.*;
import java.io.File;
import java.util.concurrent.*;

/** Public update flow. A failed background check never interrupts playback. */
public final class UpdateActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final AppUpdater updater=new AppUpdater();
    private TextView status;
    private Button check, install;
    private File pending;
    private boolean dead,busy;

    static void checkInBackground(Activity activity) {
        android.content.SharedPreferences prefs=activity.getSharedPreferences("github-updates",0);
        long now=System.currentTimeMillis();
        if(now-prefs.getLong("lastCheck",0)<24*60*60*1000L)return;
        prefs.edit().putLong("lastCheck",now).apply();
        java.lang.ref.WeakReference<Activity> owner=new java.lang.ref.WeakReference<>(activity);
        Thread thread=new Thread(()->{
            try {
                AppUpdater.Release release=new AppUpdater().check();
                if(release.code<=BuildConfig.VERSION_CODE)return;
                Activity target=owner.get();if(target==null)return;
                target.runOnUiThread(()->{
                    Activity current=owner.get();
                    if(current==null || current.isFinishing() || current.isDestroyed() || !current.hasWindowFocus())return;
                    new AlertDialog.Builder(current).setTitle("发现新版 "+release.name)
                        .setMessage("GitHub Release 已发布新版，可在更新页下载。")
                        .setNegativeButton("稍后",null).setPositiveButton("查看更新",(d,w)->current.startActivity(new Intent(current,UpdateActivity.class))).show();
                });
            } catch(Exception ignored) { /* Offline operation is fully supported. */ }
        },"github-release-check");thread.setDaemon(true);thread.start();
    }
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll=new ScrollView(this);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(24*getResources().getDisplayMetrics().density);content.setPadding(pad,pad,pad,pad);scroll.addView(content);setContentView(scroll);
        TextView heading=new TextView(this);heading.setText("应用更新");heading.setTextSize(25);content.addView(heading);
        TextView hint=new TextView(this);hint.setText("当前版本 "+BuildConfig.VERSION_NAME+"\n从 GitHub Releases 获取更新；安装后保留本机来源、收藏和播放进度。");content.addView(hint);
        status=new TextView(this);content.addView(status);
        check=button(content,"检查更新",this::checkUpdate);
        install=button(content,"安装已下载更新",this::installPending);install.setVisibility(android.view.View.GONE);
        button(content,"返回",this::finish);check.requestFocus();checkUpdate();
    }
    private Button button(LinearLayout parent,String title,Runnable action) {
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setFocusableInTouchMode(true);b.setBackgroundResource(R.drawable.focus_button);b.setTextColor(android.graphics.Color.WHITE);
        parent.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->{if(!busy)action.run();});return b;
    }
    private interface Work<T>{T run()throws Exception;}
    private interface Done<T>{void accept(T value)throws Exception;}
    private <T> void work(String hint,Work<T> task,Done<T> done) {
        busy=true;check.setEnabled(false);status.setText(hint);
        worker.submit(()->{try{T result=task.run();main.post(()->{
            if(dead)return;busy=false;check.setEnabled(true);
            try{done.accept(result);}catch(Exception e){status.setText(e.getMessage());}
        });}catch(Exception e){main.post(()->{if(!dead){busy=false;check.setEnabled(true);status.setText("更新失败："+e.getMessage()+"\n请检查网络后重试。");}});}});
    }
    private void checkUpdate() {
        work("正在检查 GitHub Release…",updater::check,release->{
            if(release.code<=BuildConfig.VERSION_CODE){status.setText("已经是最新版本（"+BuildConfig.VERSION_NAME+"）");return;}
            status.setText("发现新版 "+release.name);
            new AlertDialog.Builder(this).setTitle("发现新版 "+release.name).setMessage(release.notes)
                .setNegativeButton("稍后",null).setPositiveButton("下载并安装",(d,w)->download(release)).show();
        });
    }
    private void download(AppUpdater.Release release) {
        work("正在下载更新…",()->updater.download(this,release,percent->main.post(()->{if(!dead)status.setText("正在下载 · "+percent+"%");})),file->{
            pending=file;install.setVisibility(android.view.View.VISIBLE);status.setText("下载完成，签名和完整性校验通过");installPending();
        });
    }
    private void installPending() {
        if(pending==null)return;
        try {
            if(Build.VERSION.SDK_INT>=26 && !getPackageManager().canRequestPackageInstalls()) {
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())),71);return;
            }
            UpdateInstaller.install(this,pending);
        }catch(Exception e){status.setText("无法打开安装器："+e.getMessage());}
    }
    @Override protected void onActivityResult(int code,int result,Intent data) {
        super.onActivityResult(code,result,data);
        if(code==71 && Build.VERSION.SDK_INT>=26 && getPackageManager().canRequestPackageInstalls())installPending();
    }
    @Override protected void onDestroy(){dead=true;worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
