package family.libretv.tv;

import android.app.*;
import android.content.*;
import android.net.Uri;
import android.os.*;
import android.provider.Settings;
import android.text.InputType;
import android.view.*;
import android.widget.*;
import java.io.File;
import java.net.URI;
import java.util.concurrent.*;
import org.json.JSONObject;

/** Optional private updater for the old package identity. Playback never invokes this connection. */
public final class LegacyUpdateActivity extends Activity {
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private EditText address,secret;
    private TextView status;
    private CheckBox remember;
    private Button check,install;
    private File pending;
    private boolean busy,dead;
    private NasClient session;
    private AppUpdater updater;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        if(!BuildConfig.APPLICATION_ID.equals("family.libretv.tv")){finish();return;}
        ScrollView scroll=new ScrollView(this);LinearLayout content=new LinearLayout(this);content.setOrientation(LinearLayout.VERTICAL);
        int pad=Math.round(24*getResources().getDisplayMetrics().density);content.setPadding(pad,pad,pad,pad);scroll.addView(content);setContentView(scroll);
        TextView heading=new TextView(this);heading.setText("应用更新 · 可选 NAS");heading.setTextSize(25);content.addView(heading);
        TextView hint=new TextView(this);hint.setText("仅检查和下载更新时连接 NAS。搜索、播放、收藏和续播继续独立运行。\n当前版本 "+BuildConfig.VERSION_NAME);content.addView(hint);
        address=new EditText(this);address.setSingleLine(true);address.setHint("原 NAS 地址");content.addView(address);
        String old=getSharedPreferences("update-profile",0).getString("nas","");
        if(old.isEmpty())old=getSharedPreferences("phone-ui",0).getString("nas","");
        if(old.isEmpty())old=getSharedPreferences("MainActivity",0).getString("nas","");
        if(old.isEmpty())old=getSharedPreferences("phone-login",0).getString("origin","");
        address.setText(old);
        secret=new EditText(this);secret.setSingleLine(true);secret.setSaveEnabled(false);secret.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        secret.setHint("NAS 密码（已有保存密码可留空）");content.addView(secret);
        remember=new CheckBox(this);remember.setText("在本机加密保存本次密码");content.addView(remember);
        status=new TextView(this);content.addView(status);
        check=button(content,"检查更新",this::checkUpdate);
        install=button(content,"安装已下载更新",this::installPending);install.setVisibility(View.GONE);
        button(content,"返回",this::finish);check.requestFocus();
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
        });}catch(Exception e){main.post(()->{if(!dead){busy=false;check.setEnabled(true);status.setText("更新失败："+e.getMessage());}});}});
    }
    private void checkUpdate() {
        final URI origin;
        try{origin=NasAddress.parse(address.getText().toString());}catch(Exception e){status.setText(e.getMessage());return;}
        String entered=secret.getText().toString();boolean save=remember.isChecked();
        work("正在登录并检查更新…",()->{
            CredentialStore credentials=new CredentialStore(this);
            String password=entered.isEmpty() && credentials.hasSavedFor(origin)?credentials.load(origin):entered;
            NasClient candidate=new NasClient(origin);
            if(!candidate.request("/api/auth",new JSONObject().put("password",password)).optBoolean("success"))throw new java.io.IOException("NAS 拒绝登录");
            AppUpdater next=new AppUpdater(candidate);AppUpdater.Release release=next.check();
            if(save && !entered.isEmpty())credentials.save(origin,password);
            getSharedPreferences("update-profile",0).edit().putString("nas",origin.toString()).apply();
            session=candidate;updater=next;return release;
        },release->{
            secret.setText("");
            if(release.code<=BuildConfig.VERSION_CODE){status.setText("已经是最新版本（"+BuildConfig.VERSION_NAME+"）");return;}
            new AlertDialog.Builder(this).setTitle("发现新版 "+release.name).setMessage(release.notes)
                .setNegativeButton("稍后",null).setPositiveButton("下载并安装",(d,w)->download(release)).show();
        });
    }
    private void download(AppUpdater.Release release) {
        work("正在下载更新…",()->updater.download(this,release,percent->main.post(()->{if(!dead)status.setText("正在下载 · "+percent+"%");})),file->{
            pending=file;install.setVisibility(View.VISIBLE);status.setText("下载完成，签名和完整性校验通过");installPending();
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
    @Override protected void onDestroy() {
        dead=true;worker.shutdownNow();main.removeCallbacksAndMessages(null);if(session!=null)session.clearSession();super.onDestroy();
    }
}
