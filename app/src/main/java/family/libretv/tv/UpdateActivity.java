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
    private AppUpdater updater;
    private Button routeButton;
    private String activeRoute="";
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
        AppUpdater backgroundUpdater=new AppUpdater(activity);
        Thread thread=new Thread(()->{
            try {
                AppUpdater.Release release=backgroundUpdater.check();
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
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setBackgroundColor(PiankeUi.BACKGROUND);
        LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        int pad=PiankeUi.dp(this,24);content.setPadding(pad,pad,pad,pad);scroll.addView(content);setContentView(scroll);
        button(content,"‹ 返回",this::finish);
        LinearLayout identity=new LinearLayout(this);identity.setOrientation(LinearLayout.VERTICAL);identity.setGravity(android.view.Gravity.CENTER);identity.setPadding(0,pad,0,pad);content.addView(identity);
        identity.addView(PiankeUi.brand(this,120,120));
        TextView slogan=PiankeUi.text(this,PiankeUi.SLOGAN,17,false);slogan.setPadding(0,0,0,pad/2);identity.addView(slogan);
        identity.addView(PiankeUi.text(this,"当前版本 "+BuildConfig.VERSION_NAME,15,false));
        status=PiankeUi.text(this,"",15,false);status.setPadding(0,pad/2,0,pad/2);status.setAccessibilityLiveRegion(android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE);content.addView(status);
        check=button(content,"检查更新",this::checkUpdate);check.setBackground(PiankeUi.focus(this,true));
        install=button(content,"安装已下载更新",this::installPending);install.setVisibility(android.view.View.GONE);
        TextView hint=PiankeUi.text(this,"更新后保留来源与观看记录。",14,false);hint.setPadding(0,pad/2,0,pad/2);content.addView(hint);
        routeButton=button(content,"",this::chooseRoute);updateRouteLabel();configureUpdater();
        Button about=button(content,"关于片刻",()->PiankeUi.about(this));android.graphics.drawable.Drawable icon=getDrawable(R.drawable.pianke_art);icon.setBounds(0,0,PiankeUi.dp(this,28),PiankeUi.dp(this,28));about.setCompoundDrawables(icon,null,null,null);about.setCompoundDrawablePadding(PiankeUi.dp(this,12));check.requestFocus();
        if(restorePending()) {
            if(getPreferences(0).getBoolean("awaitingPermission",false) && (Build.VERSION.SDK_INT<26 || getPackageManager().canRequestPackageInstalls()))installPending();
        } else checkUpdate();
    }
    private android.content.SharedPreferences routePrefs(){return getSharedPreferences("github-updates",0);}
    private void configureUpdater(){
        updater=new AppUpdater(this);
        updater.routeStatus=text->main.post(()->{if(!dead){activeRoute=text;status.setText("正在连接 · "+text);}});
    }
    private void updateRouteLabel(){
        String mode=routePrefs().getString("mode","auto");
        routeButton.setText("更新通道："+("direct".equals(mode)?"GitHub 直连":"proxy".equals(mode)?"加速":"gitee".equals(mode)?"Gitee 镜像":"自动切换"));
    }
    private void chooseRoute(){
        new AlertDialog.Builder(this).setTitle("更新通道").setItems(new String[]{"自动：直连 → Gitee → 加速","仅 GitHub 直连","仅 Gitee 镜像","仅加速通道","自定义加速地址"},(d,which)->{
            if(which==4){editProxy();return;}
            routePrefs().edit().putString("mode",new String[]{"auto","direct","gitee","proxy"}[which]).apply();
            updateRouteLabel();configureUpdater();status.setText("通道已切换，请检查更新");
        }).setNegativeButton("取消",null).show();
    }
    private void editProxy(){
        EditText input=new EditText(this);input.setSingleLine(true);input.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI);
        input.setHint("留空使用内置通道");input.setText(routePrefs().getString("proxy",""));
        AlertDialog dialog=new AlertDialog.Builder(this).setTitle("自定义 HTTPS 加速前缀").setMessage("只用于公开更新文件。填写前缀，不需要拼接 GitHub 链接；留空恢复内置通道。").setView(input)
            .setNegativeButton("取消",null).setPositiveButton("保存",null).create();
        dialog.setOnShowListener(d->dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String value=input.getText().toString().trim();
            try{if(!value.isEmpty())value=AppUpdater.validateProxy(value).toString();}
            catch(Exception e){input.setError(e.getMessage());return;}
            routePrefs().edit().putString("proxy",value).apply();configureUpdater();status.setText("加速地址已保存");dialog.dismiss();
        }));dialog.show();
    }
    private Button button(LinearLayout parent,String title,Runnable action) {
        Button b=new Button(this);b.setText(title);b.setAllCaps(false);b.setFocusableInTouchMode(true);b.setBackground(PiankeUi.focus(this,false));b.setTextSize(16);b.setMinHeight(PiankeUi.dp(this,52));b.setPadding(PiankeUi.dp(this,16),PiankeUi.dp(this,10),PiankeUi.dp(this,16),PiankeUi.dp(this,10));b.setTextColor(android.graphics.Color.WHITE);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=PiankeUi.dp(this,8);parent.addView(b,p);b.setOnClickListener(v->{if(!busy)action.run();});return b;
    }
    private interface Work<T>{T run()throws Exception;}
    private interface Done<T>{void accept(T value)throws Exception;}
    private <T> void work(String hint,Work<T> task,Done<T> done) {
        busy=true;check.setEnabled(false);routeButton.setEnabled(false);status.setText(hint);
        worker.submit(()->{try{T result=task.run();main.post(()->{
            if(dead)return;busy=false;check.setEnabled(true);routeButton.setEnabled(true);
            try{done.accept(result);}catch(Exception e){status.setText(e.getMessage());}
        });}catch(Exception e){main.post(()->{if(!dead){busy=false;check.setEnabled(true);routeButton.setEnabled(true);status.setText("更新失败："+e.getMessage()+"\n请检查网络后重试。");}});}});
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
        work("正在下载更新…",()->updater.download(this,release,percent->main.post(()->{if(!dead)status.setText("正在下载 · "+percent+"%\n"+activeRoute);})),file->{
            getPreferences(0).edit().putInt("pendingCode",release.code).putString("pendingSha",release.sha256).putLong("pendingSize",release.size).commit();
            pending=file;install.setVisibility(android.view.View.VISIBLE);status.setText("下载完成，签名和完整性校验通过");installPending();
        });
    }
    private boolean restorePending() {
        android.content.SharedPreferences prefs=getPreferences(0);
        File file=new File(getCacheDir(),"updates/update.apk");
        try {
            if(!file.isFile() || file.length()!=prefs.getLong("pendingSize",-1))throw new java.io.IOException();
            java.security.MessageDigest digest=java.security.MessageDigest.getInstance("SHA-256");
            try(java.io.InputStream in=new java.io.FileInputStream(file)) {
                byte[] bytes=new byte[32768];int n;while((n=in.read(bytes))!=-1)digest.update(bytes,0,n);
            }
            if(!AppUpdater.hex(digest.digest()).equals(prefs.getString("pendingSha","")))throw new java.io.IOException();
            AppUpdater.validateApk(this,file,prefs.getInt("pendingCode",-1));
            pending=file;install.setVisibility(android.view.View.VISIBLE);status.setText("已恢复校验通过的安装包，可继续安装");install.requestFocus();return true;
        } catch(Exception ignored) {prefs.edit().clear().apply();return false;}
    }
    private void installPending() {
        if(pending==null)return;
        try {
            if(Build.VERSION.SDK_INT>=26 && !getPackageManager().canRequestPackageInstalls()) {
                getPreferences(0).edit().putBoolean("awaitingPermission",true).commit();
                startActivityForResult(new Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:"+getPackageName())),71);return;
            }
            getPreferences(0).edit().putBoolean("awaitingPermission",false).commit();
            UpdateInstaller.install(this,pending);
        }catch(Exception e){status.setText("无法打开安装器："+e.getMessage());}
    }
    @Override protected void onActivityResult(int code,int result,Intent data) {
        super.onActivityResult(code,result,data);
        if(code==71 && Build.VERSION.SDK_INT>=26 && getPackageManager().canRequestPackageInstalls())installPending();
    }
    @Override protected void onDestroy(){dead=true;worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
