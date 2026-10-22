package family.libretv.tv;

import android.app.Activity;
import android.os.*;
import android.graphics.Color;
import android.content.res.ColorStateList;
import android.text.Html;
import android.view.inputmethod.EditorInfo;
import androidx.recyclerview.widget.RecyclerView;
import androidx.recyclerview.widget.GridLayoutManager;
import android.text.InputType;
import android.view.*;
import android.view.inputmethod.InputMethodManager;
import android.content.Context;
import android.widget.*;
import androidx.media3.common.*;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory;
import androidx.media3.ui.PlayerView;
import org.json.*;
import java.net.URI;
import java.util.*;
import java.util.concurrent.*;

@androidx.media3.common.util.UnstableApi
public final class MainActivity extends Activity {
    private enum Screen { CONFIG, LOGIN, SEARCH, FAVORITES, PLAYLIST, SETTINGS, PAIRING, DETAIL, PLAYER }
    private Screen screen = Screen.CONFIG;
    private final ExecutorService network = Executors.newSingleThreadExecutor();
    private final ExecutorService updateChecks = Executors.newSingleThreadExecutor();
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ExecutorService searches = Executors.newSingleThreadExecutor();
    private Future<?> searchFuture;
    private int searchToken, searchCompleted, searchTotal, searchFailed;
    private boolean searching;
    private TextView searchSummary;
    private Button stopSearchButton;
    private Screen detailReturn = Screen.SEARCH;
    private CheckBox autoNext;
    private long lastProgressSave;
    private int generation;
    private boolean busy, stopped;
    private PhonePairingServer pairingServer;
    private ImageView pairingQr;
    private android.app.AlertDialog pairingConfirmation;
    private Screen pairingReturn = Screen.SETTINGS;
    private final Runnable expirePairing = () -> {
        if (screen == Screen.PAIRING) { closePairing(); setNotice("二维码已过期，请重新生成。"); }
    };
    private AppUpdater.Release availableUpdate;
    private java.io.File pendingUpdate;
    private int pendingUpdateCode;
    private LinearLayout root, content;
    private TextView message;
    private NasClient client;
    private JSONArray sources = new JSONArray(), results = new JSONArray(), episodes = new JSONArray();
    private JSONObject selected;
    private JSONObject detailInfo = new JSONObject();
    private final PosterLoader posters = new PosterLoader();
    private RecyclerView resultsView;
    private GridLayoutManager resultsLayout;
    private int resultColumns, resultFocusTicket;
    private View searchTarget;
    private final Set<String> enabledSources = new HashSet<>();
    private String query = "", title = "", mime;
    private int resultIndex, episodeIndex;
    private URI playbackUri;
    private ExoPlayer player;
    private PlayerView playerView;
    private TextView playbackStatus;
    private LinearLayout playerTop, playerControls;
    private Button playToggle, subtitleButton;
    private SubtitleMenu subtitleMenu;
    private JSONObject detailSource;
    private String detailFavoriteId="";
    private boolean detailFromFavorites, favoriteAvailable;
    private Button favoriteButton;
    private ProgressBar playbackProgress;
    private boolean controlsVisible, playerHasFrame;
    private final Runnable hidePlayerControls = () -> {
        if (screen == Screen.PLAYER && player != null && player.isPlaying() && (subtitleMenu == null || !subtitleMenu.isShowing())) hideControls();
    };
    private long position;
    private boolean playWhenReady = true;
    private final Runnable ticker = new Runnable() {
        @Override public void run() {
            if (player != null && playbackStatus != null) {
                String state = player.getPlaybackState() == Player.STATE_BUFFERING ? "缓冲中" : player.isPlaying() ? "播放中" : "已暂停";
                playbackStatus.setText(state + "   " + time(player.getCurrentPosition()) + " / " + time(player.getDuration()));
                long duration = player.getDuration();
                playbackProgress.setProgress(duration > 0 ? (int) (player.getCurrentPosition() * 1000 / duration) : 0);
                playToggle.setText(player.getPlayWhenReady() ? "Ⅱ 暂停" : "▶ 播放");
                if(SystemClock.elapsedRealtime()-lastProgressSave>=5000) { saveTvProgress(); lastProgressSave=SystemClock.elapsedRealtime(); }
                main.postDelayed(this, 1000);
            }
        }
    };
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        android.app.UiModeManager modes = (android.app.UiModeManager) getSystemService(UI_MODE_SERVICE);
        if (modes.getCurrentModeType() != android.content.res.Configuration.UI_MODE_TYPE_TELEVISION
                && !getIntent().hasCategory(android.content.Intent.CATEGORY_LEANBACK_LAUNCHER)) {
            startActivity(new android.content.Intent(this, PhoneActivity.class)); finish(); return;
        }
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN, WindowManager.LayoutParams.FLAG_FULLSCREEN);
        try { client=new NasClient(this); sources=client.local.sources(); loadSourceSelection(); showSearch(); }
        catch(Exception e) { showConfig(); setNotice(e.getMessage()); }
        UpdateActivity.checkInBackground(this);
    }
    private boolean hasHouseholdPassword() {
        return !BuildConfig.DEFAULT_NAS_PASSWORD.isEmpty()
                && NasAddress.sameOrigin(NasAddress.parse(BuildConfig.DEFAULT_NAS_URL), client.origin);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView label(String text, int size) {
        TextView v = new TextView(this); v.setText(text); v.setTextSize(size); v.setTextColor(Color.rgb(228, 238, 246));
        v.setPadding(dp(6), dp(6), dp(6), dp(6)); return v;
    }
    private LinearLayout row() {
        LinearLayout v = new LinearLayout(this); v.setOrientation(LinearLayout.HORIZONTAL); return v;
    }
    private void page(Screen next, String heading) {
        closePairing();
        generation++; busy = false; screen = next;
        main.removeCallbacks(hidePlayerControls);
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_FULLSCREEN);
        root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(40), dp(18), dp(40), dp(18)); root.setBackgroundColor(Color.rgb(16, 25, 34));
        root.addView(label(heading, 25));
        message = label("", 15); message.setVisibility(View.GONE);
        message.setAccessibilityLiveRegion(View.ACCESSIBILITY_LIVE_REGION_POLITE); root.addView(message);
        ScrollView scroll = new ScrollView(this); scroll.setFillViewport(true);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(content); root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));
        setContentView(root);
    }
    private void setNotice(String text) {
        message.setText(text);
        message.setVisibility(text.isEmpty() ? View.GONE : View.VISIBLE);
    }
    private void focusLink(View from, int key, View target) {
        from.setOnKeyListener((v, code, event) -> {
            if (code == key && event.getAction() == KeyEvent.ACTION_DOWN) { target.requestFocus(); return true; }
            return false;
        });
    }
    private Button button(LinearLayout parent, String text, Runnable action) {
        Button b = new Button(this); b.setText(text); b.setTextSize(17); b.setTextColor(Color.WHITE);
        b.setFocusableInTouchMode(true);
        b.setOnFocusChangeListener((v, focused) -> {
            if (focused) { if (screen == Screen.SEARCH) resultFocusTicket++; hideKeyboard(); }
        });
        b.setAllCaps(false); b.setGravity(Gravity.CENTER_VERTICAL | Gravity.START);
        b.setPadding(dp(18), dp(10), dp(18), dp(10)); b.setMinHeight(dp(50)); b.setBackgroundResource(R.drawable.focus_button);
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(parent.getOrientation() == LinearLayout.HORIZONTAL ? -2 : -1, -2);
        lp.setMargins(dp(4), dp(4), dp(4), dp(4)); parent.addView(b, lp);
        b.setOnClickListener(v -> { if (!busy) action.run(); }); return b;
    }
    private EditText input(LinearLayout parent, String hint, String value, int type) {
        EditText edit = new EditText(this); edit.setHint(hint); edit.setText(value); edit.setSingleLine(true);
        edit.setTextSize(19); edit.setInputType(type); edit.setSaveEnabled(false);
        parent.addView(edit, new LinearLayout.LayoutParams(-1, dp(58))); return edit;
    }
    private void hideKeyboard() {
        View focused = getCurrentFocus();
        if (focused != null) ((InputMethodManager) getSystemService(Context.INPUT_METHOD_SERVICE)).hideSoftInputFromWindow(focused.getWindowToken(), 0);
    }
    private interface Work<T> { T run() throws Exception; }
    private interface Done<T> { void accept(T value) throws Exception; }
    private <T> void task(String hint, Work<T> work, Done<T> done) {
        if (busy) return;
        busy = true; setNotice(hint + " · 返回键可退出等待"); int ticket = generation;
        network.execute(() -> {
            try {
                T value = work.run();
                main.post(() -> {
                    if (ticket != generation || isFinishing() || isDestroyed()) return;
                    busy = false;
                    try { setNotice(""); done.accept(value); }
                    catch (Exception ex) { error(ex); }
                });
            } catch (Exception ex) {
                main.post(() -> {
                    if (ticket != generation || isFinishing() || isDestroyed()) return;
                    busy = false; error(ex);
                });
            }
        });
    }
    private void error(Exception ex) {
        if (ex instanceof NasClient.ApiException && ((NasClient.ApiException) ex).status == 401 && screen != Screen.LOGIN && screen != Screen.PAIRING) {
            client.clearSession(); showLogin(); setNotice("会话已过期，请重新登录"); return;
        }
        setNotice("操作失败：" + (ex.getMessage() == null ? ex.getClass().getSimpleName() : ex.getMessage()));
    }
    private void showConfig() {
        page(Screen.CONFIG, "LibreTV · 来源配置");
        content.addView(label("独立运行，无需 NAS。默认源可删除；支持粘贴订阅地址或 JSON。",17));
        EditText value=input(content,"订阅地址或 JSON","",InputType.TYPE_CLASS_TEXT);
        value.setSingleLine(false);
        button(content,"导入配置",()->task("正在导入…",()->client.local.importSources(value.getText().toString()),count->{
            sources=client.local.sources();loadSourceSelection();showSearch();setNotice("已新增 "+count+" 个来源");
        }));
        button(content,"手机扫码填写配置",this::showSourcePairing);
        button(content,"返回选片",this::showSearch);
    }
    private void showSourcePairing() {
        page(Screen.PAIRING,"扫码添加来源");
        try {
            List<String> addresses=PhonePairingServer.localAddresses();
            if(addresses.isEmpty())throw new Exception("请先连接局域网");
            pairingServer=new PhonePairingServer(addresses.get(0),(PhonePairingServer.SourceSubmission)value->main.post(()->{
                if(screen!=Screen.PAIRING)return;
                pairingConfirmation=new android.app.AlertDialog.Builder(this).setTitle("导入手机提交的来源？").setMessage("将在本机解析并保存，不需要 NAS。")
                    .setNegativeButton("取消",(d,w)->showConfig()).setPositiveButton("导入",(d,w)->task("正在导入…",()->client.local.importSources(value),count->{sources=client.local.sources();loadSourceSelection();showSearch();setNotice("已新增 "+count+" 个来源");})).show();
            }));
            pairingQr=new ImageView(this);pairingQr.setImageBitmap(PairingQr.create(pairingServer.url()));
            content.addView(pairingQr,new LinearLayout.LayoutParams(dp(260),dp(260)));
            content.addView(label("手机和电视同一局域网，扫码填写订阅或 JSON。二维码 5 分钟有效。",17));
            button(content,"返回",this::showConfig);main.postDelayed(expirePairing,PhonePairingServer.LIFETIME_MS);
        }catch(Exception e){setNotice(e.getMessage());}
    }
    private void showLogin() {
        page(Screen.LOGIN, "LibreTV · 登录 NAS");
        content.addView(label(client.origin.toString(), 17));
        EditText password = input(content, "NAS 访问密码（不保存）", "", InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        Button login = button(content, "登录", () -> {
            String secret = password.getText().toString();
            if (secret.isEmpty()) { setNotice("请输入 NAS 访问密码"); return; }
            password.setText(""); hideKeyboard();
            signIn(secret, false);
        });
        if (hasHouseholdPassword()) button(content, "使用家庭预设登录", () -> signIn(BuildConfig.DEFAULT_NAS_PASSWORD, true));
        addPhonePairingButton();
        button(content, "修改 NAS 地址", this::showConfig);
        login.requestFocus();
    }
    private void signIn(String secret, boolean automatic) {
        NasClient active = client;
        task(automatic ? "正在自动连接家庭 NAS" : "正在登录并读取点播源", () -> {
            active.clearSession();
            JSONObject auth = active.request("/api/auth", new JSONObject().put("password", secret));
            if (!auth.optBoolean("success")) throw new Exception("NAS 拒绝登录");
            return active.sources(active.request("/api/status", null));
        }, list -> {
            getPreferences(MODE_PRIVATE).edit().putString("nas", active.origin.toString()).apply();
            sources = list; results = new JSONArray(); loadSourceSelection();
            showSearch();
            checkUpdateQuietly(active);
        });
    }

    private void showSearch() {
        page(Screen.SEARCH, "LibreTV   /   独立影院");
        root.removeViewAt(root.getChildCount() - 1);
        content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL);
        root.addView(content, new LinearLayout.LayoutParams(-1, 0, 1));
        LinearLayout searchRow = row(); searchRow.setGravity(Gravity.CENTER_VERTICAL); content.addView(searchRow);
        EditText keywords = input(searchRow, "搜索电影、剧集…", query, InputType.TYPE_CLASS_TEXT);
        keywords.setId(View.generateViewId()); keywords.setImeOptions(EditorInfo.IME_ACTION_SEARCH);
        keywords.setPadding(dp(18), 0, dp(18), 0); keywords.setBackgroundResource(R.drawable.focus_button);
        LinearLayout.LayoutParams inputParams = new LinearLayout.LayoutParams(0, dp(56), 1);
        inputParams.setMargins(dp(4), dp(8), dp(12), dp(8)); keywords.setLayoutParams(inputParams);
        Button search = button(searchRow, "搜索", () -> performSearch(keywords));
        search.setId(View.generateViewId()); search.setMinWidth(dp(124)); search.setHeight(dp(56));
        stopSearchButton = button(searchRow, "停止搜索", () -> { cancelSearch(); updateSearchSummary(); });
        stopSearchButton.setEnabled(searching);
        LinearLayout navigation = row(); content.addView(navigation);
        Button library=button(navigation, "本机收藏夹", this::showFavorites);
        button(navigation, "播放列表", this::showPlaylist);
        button(navigation, "电视直播", ()->startActivity(new android.content.Intent(this,LiveBrowseActivity.class)));
        Button settings = button(navigation, "设置", this::showSettings);
        if (availableUpdate != null) settings.setText("设置 · 有更新");
        settings.setTextSize(16); settings.setHeight(dp(56));
        searchTarget = search;
        // EditText's built-in caret navigation and FocusFinder disagree on this TV layout.
        // Define both axes explicitly: UP from Search always reaches the search field.
        search.setNextFocusUpId(keywords.getId()); search.setNextFocusLeftId(keywords.getId());
        keywords.setNextFocusDownId(search.getId());
        focusLink(keywords, KeyEvent.KEYCODE_DPAD_DOWN, search);
        search.setOnKeyListener((v, code, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (code == KeyEvent.KEYCODE_DPAD_UP || code == KeyEvent.KEYCODE_DPAD_LEFT) { keywords.requestFocus(); return true; }
            if (code == KeyEvent.KEYCODE_DPAD_RIGHT) {
                if(searching)stopSearchButton.requestFocus();else library.requestFocus();return true;
            }
            if (code == KeyEvent.KEYCODE_DPAD_DOWN) {
                if (results.length() > 0) focusResult(0, 0);else library.requestFocus();
                return true;
            }
            return false;
        });
        stopSearchButton.setOnKeyListener((v,code,event)->{
            if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
            if(code==KeyEvent.KEYCODE_DPAD_LEFT){search.requestFocus();return true;}
            if(code==KeyEvent.KEYCODE_DPAD_DOWN){library.requestFocus();return true;}
            return false;
        });
        for(int n=0;n<navigation.getChildCount();n++) {
            View link=navigation.getChildAt(n);
            link.setOnKeyListener((v,code,event)->{
                if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
                if(code==KeyEvent.KEYCODE_DPAD_UP){search.requestFocus();return true;}
                if(code==KeyEvent.KEYCODE_DPAD_LEFT && v==library){search.requestFocus();return true;}
                if(code==KeyEvent.KEYCODE_DPAD_DOWN && results.length()>0){focusResult(0,0);return true;}
                return false;
            });
        }
        keywords.setOnEditorActionListener((v, action, event) -> {
            if (action == EditorInfo.IME_ACTION_SEARCH || action == EditorInfo.IME_ACTION_DONE
                    || event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER && event.getAction() == KeyEvent.ACTION_DOWN) {
                search.performClick(); return true;
            }
            return false;
        });
        searchSummary=label("",17); content.addView(searchSummary); updateSearchSummary();
            int width = getResources().getConfiguration().screenWidthDp;
            resultColumns = width >= 1000 ? 6 : width >= 800 ? 5 : width >= 600 ? 4 : 3;
            int posterHeight = dp((int) (((width - 80f) / resultColumns - 22) * 1.5f));
            resultsView = new RecyclerView(this); resultsView.setFocusable(false); resultsView.setClipToPadding(false);
            resultsView.setPadding(0, dp(8), 0, dp(10)); resultsView.setItemAnimator(null);
            resultsLayout = new GridLayoutManager(this, resultColumns); resultsView.setLayoutManager(resultsLayout);
            resultsView.setAdapter(new RecyclerView.Adapter<PosterHolder>() {
                @Override public PosterHolder onCreateViewHolder(ViewGroup parent, int type) {
                    PosterCard card = new PosterCard(MainActivity.this, fitPosterHeight(posterHeight));
                    RecyclerView.LayoutParams params = new RecyclerView.LayoutParams(-1, -2);
                    params.setMargins(dp(5), dp(5), dp(5), dp(12)); card.setLayoutParams(params);
                    return new PosterHolder(card);
                }
                @Override public void onBindViewHolder(PosterHolder holder, int position) {
                    JSONObject item = results.optJSONObject(position); PosterCard card = holder.card;
                    card.title.setText(item.optString("name")); card.source.setText(item.optString("sourceName"));
                    String extra = item.optString("year"); String remarks = item.optString("remarks");
                    card.metadata.setText(extra.isEmpty() ? remarks : remarks.isEmpty() ? extra : extra + "  ·  " + remarks);
                    card.setContentDescription(item.optString("name") + "，" + item.optString("sourceName") + "，" + remarks);
                    posters.load(card.image, client, item.optString("pic"));
                    card.onFocused = () -> { int current = holder.getBindingAdapterPosition(); if (current != RecyclerView.NO_POSITION) resultIndex = current; };
                    card.setOnClickListener(v -> {
                        int current = holder.getBindingAdapterPosition();
                        if (!busy && current != RecyclerView.NO_POSITION) loadDetail(results.optJSONObject(current), current);
                    });
                    card.setOnKeyListener((v, key, event) -> {
                        if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
                        int index = holder.getBindingAdapterPosition();
                        if (index == RecyclerView.NO_POSITION) return false;
                        if (key == KeyEvent.KEYCODE_DPAD_UP) { if (index < resultColumns) search.requestFocus(); else focusResult(index - resultColumns, 0); return true; }
                        if (key == KeyEvent.KEYCODE_DPAD_DOWN) { if (index + resultColumns < results.length()) focusResult(index + resultColumns, 0); return true; }
                        if (key == KeyEvent.KEYCODE_DPAD_LEFT) { if (index % resultColumns > 0) focusResult(index - 1, 0); return true; }
                        if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { if (index % resultColumns < resultColumns - 1 && index + 1 < results.length()) focusResult(index + 1, 0); return true; }
                        return false;
                    });
                }
                @Override public int getItemCount() { return results.length(); }
            });
            resultsView.addOnLayoutChangeListener((v,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{
                if(bottom-top==oldBottom-oldTop)return;
                int height=fitPosterHeight(posterHeight);
                for(int i=0;i<resultsView.getChildCount();i++) {
                    View child=resultsView.getChildAt(i);
                    if(child instanceof PosterCard) {
                        ImageView image=((PosterCard)child).image;
                        if(image.getLayoutParams().height!=height){ViewGroup.LayoutParams params=image.getLayoutParams();params.height=height;image.setLayoutParams(params);}
                    }
                }
            });
            content.addView(resultsView, new LinearLayout.LayoutParams(-1, 0, 1));
        if (sources.length() == 0) setNotice("暂无可用来源，请在设置中导入来源。");
        if (results.length() > 0) focusResult(Math.min(resultIndex, results.length() - 1), 0); else search.requestFocus();
    }
    private int fitPosterHeight(int preferred) {
        if(resultsView==null || resultsView.getHeight()==0)return preferred;
        android.graphics.Paint paint=new android.graphics.Paint();int labels=0;
        for(int size:new int[]{18,14,13}) {
            paint.setTextSize(size*getResources().getDisplayMetrics().scaledDensity);
            android.graphics.Paint.FontMetricsInt metrics=paint.getFontMetricsInt();labels+=metrics.bottom-metrics.top;
        }
        int available=resultsView.getHeight()-resultsView.getPaddingTop()-resultsView.getPaddingBottom()-labels-dp(15+12+17+8);
        return Math.min(preferred,Math.max(dp(48),available));
    }
    private String sourcePreferenceKey() { return "disabledSources@" + client.origin; }
    private void loadSourceSelection() throws Exception {
        if(getPreferences(MODE_PRIVATE).contains(sourcePreferenceKey())) {
            Set<String> disabled=getPreferences(MODE_PRIVATE).getStringSet(sourcePreferenceKey(),Collections.emptySet());
            for(int i=0;i<sources.length();i++) {
                JSONObject item=sources.getJSONObject(i);item.put("enabled",!disabled.contains(item.optString("url")));
            }
            client.local.saveSources(sources);
            getPreferences(MODE_PRIVATE).edit().remove(sourcePreferenceKey()).apply();
        }
        enabledSources.clear();
        for(int i=0;i<sources.length();i++) if(sources.getJSONObject(i).optBoolean("enabled",true)) enabledSources.add(sources.getJSONObject(i).getString("key"));
    }
    private void saveSourceSelection() {
        try {
            for(int i=0;i<sources.length();i++) sources.getJSONObject(i).put("enabled",enabledSources.contains(sources.getJSONObject(i).getString("key")));
            client.local.saveSources(sources);
        } catch(Exception e) { error(e); }
    }
    private void showSettings() {
        page(Screen.SETTINGS,"LibreTV · 来源设置");
        button(content,"返回选片",this::showSearch);
        button(content,"添加来源 / 扫码导入",this::showConfig);
        button(content,"检查更新",()->startActivity(new android.content.Intent(this,UpdateActivity.class)));
        content.addView(label("共 "+sources.length()+" 个来源，收藏只保存在本机。",17));
        for(int i=0;i<sources.length();i++){
            JSONObject source=sources.optJSONObject(i);String key=source.optString("key");
            CheckBox choice=new CheckBox(this);choice.setText(source.optString("name"));choice.setTextColor(Color.WHITE);choice.setChecked(enabledSources.contains(key));
            choice.setOnCheckedChangeListener((v,on)->{if(on)enabledSources.add(key);else enabledSources.remove(key);saveSourceSelection();});content.addView(choice);
            button(content,"删除 · "+source.optString("name"),()->new android.app.AlertDialog.Builder(this).setMessage("删除后不会在重启时补回，是否删除？")
                .setNegativeButton("取消",null).setPositiveButton("删除",(d,w)->task("正在删除…",()->{client.local.removeSource(key);return client.local.sources();},list->{sources=list;results=new JSONArray();loadSourceSelection();showSettings();})).show());
        }
        content.addView(label("版本 "+BuildConfig.VERSION_NAME+" · 独立测试版",16));
    }
    private void addPhonePairingButton() {
        Button scan = button(content, "手机扫码配置 NAS", () -> showPairing(0));
        // Let first-time users leave an unreachable default NAS without waiting for its timeout.
        scan.setOnClickListener(view -> showPairing(0));
    }
    private void closePairing() {
        main.removeCallbacks(expirePairing);
        if (pairingServer != null) { pairingServer.close(); pairingServer = null; }
        if (pairingQr != null) { pairingQr.setImageDrawable(null); pairingQr = null; }
        if (pairingConfirmation != null) { pairingConfirmation.dismiss(); pairingConfirmation = null; }
    }
    private void returnFromPairing() {
        if (pairingReturn == Screen.SETTINGS) showSettings();
        else if (pairingReturn == Screen.LOGIN) showLogin();
        else showConfig();
    }
    private void showPairing(int addressIndex) {
        if (screen != Screen.PAIRING) pairingReturn = screen;
        page(Screen.PAIRING, "LibreTV   /   手机扫码配置");
        content.addView(label("手机与电视连接同一网络，扫码填写 NAS 地址和密码。", 18));
        try {
            List<String> addresses = PhonePairingServer.localAddresses();
            if (addresses.isEmpty()) throw new Exception("未找到局域网地址，请先连接 Wi-Fi 或网线。");
            int selectedAddress = addressIndex % addresses.size(); int ticket = generation;
            pairingServer = new PhonePairingServer(addresses.get(selectedAddress), client == null ? "" : client.origin.toString(), (origin, password) ->
                main.post(() -> {
                    if (screen != Screen.PAIRING || generation != ticket || pairingServer == null || stopped || isFinishing()) return;
                    closePairing(); setNotice("手机已提交配置，请确认连接。");
                    pairingConfirmation = new android.app.AlertDialog.Builder(this).setTitle("连接并保存这个 NAS？")
                        .setMessage(origin + "\n\n密码已填写，将在登录验证成功后加密保存，以后自动登录。")
                        .setNegativeButton("取消", (dialog, which) -> setNotice("已取消，原来的连接没有更改。可重新生成二维码。"))
                        .setPositiveButton("连接并保存", (dialog, which) -> connectFromPhone(origin, password)).show();
                    pairingConfirmation.setOnCancelListener(dialog -> setNotice("已取消，可重新生成二维码。"));
                    pairingConfirmation.setOnDismissListener(dialog -> pairingConfirmation = null);
                }));
            LinearLayout layout = row(); layout.setGravity(Gravity.CENTER_VERTICAL); content.addView(layout);
            pairingQr = new ImageView(this); pairingQr.setImageBitmap(PairingQr.create(pairingServer.url()));
            pairingQr.setContentDescription("手机配置二维码"); layout.addView(pairingQr, new LinearLayout.LayoutParams(dp(300),dp(300)));
            LinearLayout instructions = new LinearLayout(this); instructions.setOrientation(LinearLayout.VERTICAL);
            LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0,-2,1); params.setMargins(dp(24),0,0,0); layout.addView(instructions,params);
            instructions.addView(label("1  手机相机扫码，或用浏览器打开下方地址\n2  填写 NAS 地址与密码，发送到电视\n3  用遥控器确认连接并保存", 20));
            instructions.addView(label("二维码 5 分钟内有效。退出此页面即失效。\n电视有多个网络地址时，可以切换后重新扫码。", 16));
            TextView address = label(pairingServer.url(),14); address.setContentDescription("手机配置地址"); instructions.addView(address);
            main.postDelayed(expirePairing, PhonePairingServer.LIFETIME_MS);
            button(content, "重新生成二维码", () -> showPairing(selectedAddress));
            if (addresses.size()>1) button(content, "切换网络地址", () -> showPairing(selectedAddress+1));
        } catch (Exception ex) { closePairing(); setNotice("无法开启手机配置：" + ex.getMessage()); button(content,"重试", () -> showPairing(0)); }
        Button back = button(content, "‹ 返回", this::returnFromPairing); back.requestFocus();
    }
    private void connectFromPhone(URI origin, String password) {
        NasClient candidate = new NasClient(origin);
        task("正在验证手机填写的 NAS", () -> {
            JSONObject auth = candidate.request("/api/auth", new JSONObject().put("password",password));
            if (!auth.optBoolean("success")) throw new Exception("NAS 拒绝登录，请重新扫码填写密码");
            JSONObject status = candidate.request("/api/status",null);
            if (!status.optBoolean("passwordRequired")) throw new Exception("NAS 未启用密码，请先配置 NAS 认证");
            return candidate.sources(status);
        }, list -> {
            new CredentialStore(this).save(origin,password);
            if (client != null) client.clearSession(); client = candidate; availableUpdate = null;
            getPreferences(MODE_PRIVATE).edit().putString("nas",origin.toString()).putBoolean("autoLogin",true).apply();
            sources = list; results = new JSONArray(); loadSourceSelection(); showSearch();
            setNotice("手机配置已保存，以后会自动登录。"); checkUpdateQuietly(candidate);
        });
    }
    private void checkUpdateQuietly(NasClient active) {
        updateChecks.execute(() -> {
            try {
                AppUpdater.Release release = new AppUpdater(active).check();
                main.post(() -> {
                    if (client != active || isFinishing() || isDestroyed()) return;
                    availableUpdate = release.code > BuildConfig.VERSION_CODE ? release : null;
                    if (availableUpdate != null && screen == Screen.SEARCH && !busy)
                        setNotice("发现新版 " + release.name + "，可在设置中下载安装。");
                });
            } catch (Exception ignored) { /* An unavailable updater must never block watching. */ }
        });
    }
    private void offerUpdate(AppUpdater.Release release) {
        new android.app.AlertDialog.Builder(this).setTitle("升级到 " + release.name)
                .setMessage(release.notes + "\n\n下载完成后将打开系统安装界面，请用遥控器确认安装。")
                .setNegativeButton("稍后", null).setPositiveButton("下载并安装", (dialog, which) -> {
                    NasClient active = client; int ticket = generation;
                    task("正在下载更新", () -> new AppUpdater(active).download(this, release, percent ->
                        main.post(() -> { if (ticket == generation) setNotice("正在下载更新 " + percent + "%"); })), file -> {
                            pendingUpdate = file; pendingUpdateCode = release.code; installUpdate();
                        });
                }).show();
    }
    private void installUpdate() {
        if (pendingUpdate == null) return;
        try {
            AppUpdater.validateApk(this, pendingUpdate, pendingUpdateCode);
            if (Build.VERSION.SDK_INT >= 26 && !getPackageManager().canRequestPackageInstalls()) {
                new android.app.AlertDialog.Builder(this).setTitle("允许安装应用更新")
                        .setMessage("请在接下来的系统页面允许 LibreTV 安装应用，然后返回继续安装。")
                        .setNegativeButton("稍后", null).setPositiveButton("前往设置", (d, w) -> {
                            try { startActivityForResult(new android.content.Intent(android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                android.net.Uri.parse("package:" + getPackageName())), 901); }
                            catch (Exception ex) { error(ex); }
                        }).show();
                return;
            }
            UpdateInstaller.install(this, pendingUpdate);
        } catch (Exception ex) { error(ex); }
    }
    @Override protected void onActivityResult(int request, int result, android.content.Intent data) {
        super.onActivityResult(request, result, data);
        if (request == 901 && Build.VERSION.SDK_INT >= 26) {
            if (getPackageManager().canRequestPackageInstalls()) installUpdate();
            else setNotice("尚未允许安装，请再次检查更新后重试。");
        }
    }
    private static final class PosterHolder extends RecyclerView.ViewHolder {
        final PosterCard card;
        PosterHolder(PosterCard card) { super(card); this.card = card; }
    }
    private void focusResult(int index, int attempt) {
        if (screen != Screen.SEARCH || resultsView == null || index < 0 || index >= results.length()) return;
        if (attempt == 0) { resultIndex = index; resultFocusTicket++; }
        View item = resultsLayout.findViewByPosition(index);
        if (item != null) { item.requestFocus(); return; }
        if (attempt >= 8) return;
        resultsView.scrollToPosition(index); int ticket = generation, focusTicket = resultFocusTicket;
        resultsView.postDelayed(() -> { if (ticket == generation && resultIndex == index && focusTicket == resultFocusTicket) focusResult(index, attempt + 1); }, 40);
    }
    private void cancelSearch() {
        searchToken++; searching=false;
        if(searchFuture!=null) searchFuture.cancel(true);
    }
    private void updateSearchSummary() {
        if(screen!=Screen.SEARCH || searchSummary==null)return;
        searchSummary.setText(query.isEmpty()?"输入片名开始搜索 · 收藏和进度保存在本机":
            "「"+query+"」 · "+results.length()+" 个结果 · "+searchCompleted+" / "+searchTotal+" 个来源完成 · "+searchFailed+" 个不可用"+(searching?" · 正在搜索…":" · 已结束"));
        if(stopSearchButton!=null)stopSearchButton.setEnabled(searching);
    }
    private void performSearch(EditText keywords) {
        String keyword=keywords.getText().toString().trim();
        if(keyword.isEmpty() || keyword.length()>100) {setNotice("请输入 1–100 字的片名");return;}
        try {
            JSONArray chosen=new JSONArray();
            for(int i=0;i<sources.length();i++) if(enabledSources.contains(sources.getJSONObject(i).getString("key"))) chosen.put(new JSONObject(sources.getJSONObject(i).toString()));
            if(chosen.length()==0){setNotice("请在设置中启用至少一个来源");return;}
            hideKeyboard(); cancelSearch(); final int token=searchToken;
            query=keyword; results=new JSONArray(); resultIndex=0; searchCompleted=0; searchFailed=0; searchTotal=chosen.length(); searching=true;
            showSearch();
            searchFuture=searches.submit(()->{
                try {
                    client.local.search(keyword,chosen,(added,completed,total,failed)->main.post(()->{
                        if(token!=searchToken || isDestroyed())return;
                        int start=results.length();
                        for(int i=0;i<added.length();i++)results.put(added.optJSONObject(i));
                        searchCompleted=completed;searchTotal=total;searchFailed=failed;
                        if(screen==Screen.SEARCH) {
                            if(added.length()>0)resultsView.getAdapter().notifyItemRangeInserted(start,added.length());
                            updateSearchSummary();
                        }
                    }));
                } catch(Exception e) {main.post(()->{if(token==searchToken && screen==Screen.SEARCH)error(e);});}
                finally {main.post(()->{if(token==searchToken){searching=false;updateSearchSummary();}});}
            });
        }catch(Exception e){error(e);}
    }
    private void loadDetail(JSONObject item, int index) { loadDetail(item,index,false); }
    private void loadDetail(JSONObject item, int index, boolean continuePlaying) {
        Screen returnTo=screen==Screen.PLAYLIST?Screen.PLAYLIST:screen==Screen.FAVORITES?Screen.FAVORITES:Screen.SEARCH;
        JSONObject source = item.optJSONObject("favoriteSource");
        for (int i = 0; source == null && i < sources.length(); i++) {
            JSONObject candidate = sources.optJSONObject(i);
            if (candidate.optString("key").equals(item.optString("sourceKey"))) { source = candidate; break; }
        }
        if (source == null) { setNotice("源配置已失效，请重新搜索"); return; }
        String path = "/api/detail?id=" + NasClient.encode(item.optString("vodId")) + "&source=" + NasClient.encode(source.toString());
        JSONObject savedSource = source;
        NasClient active = client;
        task("正在获取详情和剧集", () -> active.request(path, null), detail -> {
            selected = item; resultIndex = index; detailSource=savedSource; detailReturn=returnTo;
            detailFromFavorites=item.has("favoriteSource");detailFavoriteId="";favoriteAvailable=false;
            episodes = detail.optJSONArray("episodes");
            if (episodes == null || episodes.length() == 0) throw new Exception("此来源没有可播放集数");
            JSONObject info = detail.optJSONObject("videoInfo");
            detailInfo = info == null ? new JSONObject() : info;
            title = info == null ? item.optString("name") : info.optString("title", item.optString("name"));
            JSONObject saved=new PlaybackStore(this).find(detailSource,selected);
            episodeIndex=saved==null?0:saved.optInt("index");
            if(episodeIndex<0 || episodeIndex>=episodes.length())episodeIndex=0;
            showDetail();
            if(continuePlaying)preparePlayback(episodeIndex);
        });
    }
    @SuppressWarnings("deprecation")
    private void showDetail() {
        page(Screen.DETAIL, "LibreTV   /   影片详情");
        button(content, detailReturn==Screen.PLAYLIST?"‹ 返回播放列表":detailReturn==Screen.FAVORITES?"‹ 返回本机收藏夹":"‹ 返回搜索结果 / 选择其他来源", this::returnFromDetail);
        LinearLayout hero = row(); hero.setPadding(dp(4), dp(16), dp(4), dp(20)); content.addView(hero);
        ImageView cover = new ImageView(this); cover.setScaleType(ImageView.ScaleType.CENTER_CROP);
        hero.addView(cover, new LinearLayout.LayoutParams(dp(146), dp(204)));
        String coverUrl = detailInfo.optString("cover");
        posters.load(cover, client, coverUrl.isEmpty() ? selected.optString("pic") : coverUrl);
        LinearLayout info = new LinearLayout(this); info.setOrientation(LinearLayout.VERTICAL); info.setPadding(dp(22), 0, 0, 0);
        hero.addView(info, new LinearLayout.LayoutParams(0, -2, 1));
        TextView name = label(title, 29); name.setMaxLines(2); info.addView(name);
        String summary = detailInfo.optString("year") + "  " + detailInfo.optString("area") + "  " + detailInfo.optString("typeName");
        if (!summary.trim().isEmpty()) info.addView(label(summary.trim(), 16));
        TextView source = label(selected.optString("sourceName") + "   ·   共 " + episodes.length() + " 集", 16);
        source.setTextColor(Color.rgb(107, 226, 195)); info.addView(source);
        TextView description = label("", 16); description.setMaxLines(3);
        String desc = detailInfo.optString("desc");
        description.setText(desc.isEmpty() ? "选好剧集，开始观看。" : Html.fromHtml(desc));
        description.setTextColor(Color.rgb(175, 190, 207)); info.addView(description);
        favoriteButton=button(content,"正在读取收藏状态…",()->toggleFavorite());favoriteButton.setEnabled(false);
        readFavoriteState();
        PlaybackStore store=new PlaybackStore(this);
        JSONObject saved=store.find(detailSource,selected);
        if(saved!=null && saved.optInt("index")>=0 && saved.optInt("index")<episodes.length()) {
            int savedIndex=saved.optInt("index");
            button(content,"继续播放 · 第 "+(savedIndex+1)+" 集 · "+time(saved.optLong("position")),()->preparePlayback(savedIndex));
        }
        boolean listed=store.contains(detailSource,selected);
        button(content,listed?"移出播放列表":"加入播放列表",()->task("正在保存播放列表",()->{
            if(listed)store.remove(PlaybackStore.identity(detailSource,selected));else store.add(detailSource,selected,title);return true;
        },ok->showDetail()));
        content.addView(label("选择剧集", 21));
        int columns = getResources().getConfiguration().screenWidthDp >= 900 ? 8 : 6;
        GridLayout grid = new GridLayout(this); grid.setColumnCount(columns); content.addView(grid);
        ArrayList<Button> buttons = new ArrayList<>();
        for (int i = 0; i < episodes.length(); i++) {
            int index = i;
            Button episode = new Button(this); episode.setText("第 " + (i + 1) + " 集"); episode.setTextSize(17);
            episode.setFocusableInTouchMode(true); episode.setAllCaps(false); episode.setTextColor(Color.WHITE); episode.setBackgroundResource(R.drawable.focus_button);
            int width = (getResources().getConfiguration().screenWidthDp - 80) / columns;
            GridLayout.LayoutParams params = new GridLayout.LayoutParams(); params.width = dp(width - 10); params.height = dp(50);
            params.setMargins(dp(4), dp(5), dp(4), dp(5)); grid.addView(episode, params);
            episode.setOnClickListener(v -> { if (!busy) preparePlayback(index); }); buttons.add(episode);
        }
        if (!buttons.isEmpty()) buttons.get(Math.min(episodeIndex, buttons.size() - 1)).requestFocus();
    }
    private void showFavorites() {
        NasClient active=client;
        task("正在读取本机收藏夹",()->active.request("/api/favorites",null),response->{
            page(Screen.FAVORITES,"LibreTV   /   本机收藏夹");
            content.addView(label("收藏仅保存在本机，无需 NAS、账号或服务器。",17));
            button(content,"‹ 返回首页",this::showSearch);
            button(content,"刷新收藏夹",this::showFavorites);
            JSONArray list=response.optJSONArray("items");
            if(list==null || list.length()==0)content.addView(label("还没有收藏。进入影片详情，点击收藏即可。",19));
            if(list!=null)for(int i=0;i<list.length();i++) {
                JSONObject favorite=list.getJSONObject(i),item=new JSONObject(favorite.getJSONObject("item").toString());
                JSONObject source=favorite.getJSONObject("source");
                item.put("favoriteSource",source);item.put("favoriteId",favorite.optString("id"));
                button(content,item.optString("name")+"  ·  "+source.optString("name"),()->loadDetail(item,0));
                JSONObject progress=new PlaybackStore(this).find(source,item);
                if(progress!=null)content.addView(label("上次看到第 "+(progress.optInt("index")+1)+" 集 · "+time(progress.optLong("position")),17));
                LinearLayout actions=row();content.addView(actions);
                button(actions,"加入播放列表",()->task("正在加入播放列表",()->{new PlaybackStore(this).add(source,item,item.optString("name"));return true;},ok->setNotice("已加入播放列表")));
                button(actions,"移除收藏",()->task("正在移除收藏",()->{client.local.favorites("/api/favorites/"+favorite.getString("id"),null,"DELETE");return true;},ok->showFavorites()));
            }
        });
    }
    private void returnFromDetail() {
        if(detailReturn==Screen.PLAYLIST)showPlaylist();else if(detailReturn==Screen.FAVORITES)showFavorites();else showSearch();
    }
    private void showPlaylist() {
        page(Screen.PLAYLIST,"LibreTV   /   播放列表");
        Button back=button(content,"‹ 返回首页",this::showSearch);
        content.addView(label("本机保存 · 自动记录已播放影片，也可在详情手动加入 · 移除后保留观看历史",17));
        PlaybackStore store=new PlaybackStore(this);JSONArray list=store.playlist();Button first=null;
        try {
            if(list.length()==0)content.addView(label("播放列表为空，先搜索一部想看的影片吧",21));
            for(int i=0;i<list.length();i++) {
                JSONObject entry=list.getJSONObject(i),source=entry.getJSONObject("source"),item=new JSONObject(entry.getJSONObject("item").toString()).put("favoriteSource",source);
                String name=entry.optString("title",item.optString("name"));JSONObject saved=store.find(source,item);
                content.addView(label(name+" · "+source.optString("name")+(saved==null?" · 尚未播放":" · 第 "+(saved.optInt("index")+1)+" 集 · "+time(saved.optLong("position"))+" / "+time(saved.optLong("duration"))),20));
                LinearLayout actions=row();content.addView(actions);
                Button play=button(actions,(saved==null?"开始播放 · ":"继续播放 · ")+name,()->loadDetail(item,0,true));if(first==null)first=play;
                play.setSingleLine(true);play.setEllipsize(android.text.TextUtils.TruncateAt.END);
                LinearLayout.LayoutParams playParams=new LinearLayout.LayoutParams(0,-2,1);playParams.setMargins(dp(4),dp(4),dp(4),dp(4));play.setLayoutParams(playParams);
                button(actions,"查看剧集",()->loadDetail(item,0));
                button(actions,"移出列表",()->task("正在移除",()->{store.remove(entry.getString("id"));return true;},ok->showPlaylist()));
            }
        }catch(Exception e){error(e);}
        if(first!=null)first.requestFocus();else back.requestFocus();
    }
    private void readFavoriteState() {
        NasClient active=client;JSONObject source=detailSource,item=selected;int ticket=generation;
        network.execute(()->{
            try {
                JSONArray list=active.request("/api/favorites",null).optJSONArray("items");String id="";
                if(list!=null)for(int i=0;i<list.length();i++) {
                    JSONObject entry=list.getJSONObject(i);
                    if(entry.getJSONObject("item").optString("vodId").equals(item.optString("vodId"))
                        && entry.getJSONObject("source").optString("url").replaceAll("/+$","").equals(source.optString("url").replaceAll("/+$","")))id=entry.optString("id");
                }
                String found=id;
                main.post(()->{if(ticket!=generation || (screen!=Screen.DETAIL && screen!=Screen.PLAYER))return;
                    detailFavoriteId=found;favoriteAvailable=true;favoriteButton.setEnabled(true);favoriteButton.setText(found.isEmpty()?"收藏":"取消收藏");});
            } catch(Exception ex) { main.post(()->{if(ticket==generation && (screen==Screen.DETAIL || screen==Screen.PLAYER)) {favoriteButton.setText("收藏暂不可用");setNotice("无法读取本机收藏，请返回重试。");}}); }
        });
    }
    private void toggleFavorite() {
        if(!favoriteAvailable)return;
        NasClient active=client;String id=detailFavoriteId;JSONObject source=detailSource,item=selected;
        task(id.isEmpty()?"正在加入本机收藏夹":"正在取消收藏",()->{
            if(!id.isEmpty()) { active.request("/api/favorites/"+NasClient.encode(id),null,"DELETE");return ""; }
            JSONObject payload=new JSONObject().put("source",source).put("item",new JSONObject(item.toString()));
            payload.getJSONObject("item").put("pic",detailInfo.optString("cover",item.optString("pic")));
            return active.request("/api/favorites",payload).getJSONObject("item").getString("id");
        },saved->{detailFavoriteId=saved;favoriteButton.setText(saved.isEmpty()?"收藏":"取消收藏");setNotice(saved.isEmpty()?"已取消收藏":"已收藏，保存在此设备");});
    }
    private void preparePlayback(int index) { preparePlayback(index,true); }
    private void preparePlayback(int index, boolean restore) {
        String upstream = episodes.optString(index); NasClient active = client;
        task("正在连接视频源", () -> {
            URI uri = active.proxy(upstream);
            return new Object[] { uri, active.playbackMime(uri, upstream) };
        }, info -> {
            episodeIndex = index; playbackUri = (URI) info[0]; mime = (String) info[1]; position = 0; playWhenReady = true;
            JSONObject saved=new PlaybackStore(this).find(detailSource,selected);
            if(restore && saved!=null && saved.optInt("index")==index)position=PlaybackStore.resumePosition(saved.optLong("position"),saved.optLong("duration"));
            showPlayer();
        });
    }
    private void showPlayer() {
        generation++; busy = false; screen = Screen.PLAYER; playerHasFrame = false;
        getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
                | View.SYSTEM_UI_FLAG_FULLSCREEN | View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                | View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN | View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION | View.SYSTEM_UI_FLAG_LAYOUT_STABLE);
        FrameLayout stage = new FrameLayout(this); stage.setBackgroundColor(Color.BLACK);
        playerView = new PlayerView(this); playerView.setUseController(false); playerView.setFocusable(true);
        playerView.setFocusableInTouchMode(true); stage.addView(playerView, new FrameLayout.LayoutParams(-1, -1));
        playerTop = new LinearLayout(this); playerTop.setOrientation(LinearLayout.VERTICAL);
        playerTop.setPadding(dp(40), dp(20), dp(40), dp(34)); playerTop.setBackgroundResource(R.drawable.player_top);
        LinearLayout heading=row();playerTop.addView(heading);
        heading.addView(label(title + "   ·   第 " + (episodeIndex + 1) + " 集",24),new LinearLayout.LayoutParams(0,-2,1));
        favoriteButton=button(heading,favoriteAvailable?(detailFavoriteId.isEmpty()?"收藏":"取消收藏"):"收藏暂不可用",()->toggleFavorite());
        favoriteButton.setEnabled(favoriteAvailable);
        if(!favoriteAvailable)readFavoriteState();
        message = label("正在连接…", 15); playerTop.addView(message);
        stage.addView(playerTop, new FrameLayout.LayoutParams(-1, -2, Gravity.TOP));
        playerControls = new LinearLayout(this); playerControls.setOrientation(LinearLayout.VERTICAL);
        playerControls.setPadding(dp(36), dp(36), dp(36), dp(24)); playerControls.setBackgroundResource(R.drawable.player_bottom);
        playbackStatus = label("正在缓冲", 16); playerControls.addView(playbackStatus);
        playbackProgress = new ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal);
        playbackProgress.setMax(1000); playbackProgress.setProgressTintList(ColorStateList.valueOf(Color.rgb(107, 226, 195)));
        LinearLayout.LayoutParams progressParams = new LinearLayout.LayoutParams(-1, dp(5)); progressParams.setMargins(dp(5), dp(4), dp(5), dp(16));
        playerControls.addView(playbackProgress, progressParams);
        LinearLayout controls = row(); playerControls.addView(controls);
        playToggle = button(controls, "Ⅱ 暂停", () -> { togglePlay(); showControls(false); });
        button(controls, "−10 秒", () -> { seek(-10000); showControls(false); });
        button(controls, "+10 秒", () -> { seek(10000); showControls(false); });
        subtitleButton = button(controls, "字幕：识别中", () -> {
            if(subtitleMenu!=null) { main.removeCallbacks(hidePlayerControls);subtitleMenu.show(); }
        });
        subtitleButton.setTextSize(15);
        button(controls, "全屏观看", this::hideControls);
        button(controls, "返回选集", this::leavePlayer);
        LinearLayout episodesRow=row(); playerControls.addView(episodesRow);
        Button previous=button(episodesRow,"上一集",()->switchTvEpisode(episodeIndex-1));previous.setEnabled(episodeIndex>0);
        Button next=button(episodesRow,"下一集",()->switchTvEpisode(episodeIndex+1));next.setEnabled(episodeIndex+1<episodes.length());
        button(episodesRow,"从头播放",()->{if(player!=null){player.seekTo(0);player.play();saveTvProgress();}});
        autoNext=new CheckBox(this);autoNext.setText("自动下一集");autoNext.setTextColor(Color.WHITE);
        autoNext.setChecked(getPreferences(MODE_PRIVATE).getBoolean("autoNext",true));
        autoNext.setOnCheckedChangeListener((v,on)->getPreferences(MODE_PRIVATE).edit().putBoolean("autoNext",on).apply());episodesRow.addView(autoNext);
        stage.addView(playerControls, new FrameLayout.LayoutParams(-1, -2, Gravity.BOTTOM));
        setContentView(stage); controlsVisible = true;
        playerView.setOnClickListener(v -> { if (controlsVisible) hideControls(); else showControls(true); });
        playerView.setOnKeyListener((v, key, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (key == KeyEvent.KEYCODE_DPAD_CENTER || key == KeyEvent.KEYCODE_ENTER) { togglePlay(); showControls(false); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_LEFT) { seek(-10000); showControls(false); return true; }
            if (key == KeyEvent.KEYCODE_DPAD_RIGHT) { seek(10000); showControls(false); return true; }
            return false;
        });
        playerView.requestFocus();
        if (!stopped) startPlayer();
    }
    private void showControls(boolean focus) {
        if (screen != Screen.PLAYER) return;
        controlsVisible = true; playerTop.setVisibility(View.VISIBLE); playerControls.setVisibility(View.VISIBLE);
        setSubtitleInset(true);
        if (focus) playToggle.requestFocus();
        main.removeCallbacks(hidePlayerControls); main.postDelayed(hidePlayerControls, 3500);
    }
    private void hideControls() {
        main.removeCallbacks(hidePlayerControls);
        if (screen != Screen.PLAYER) return;
        controlsVisible = false; playerTop.setVisibility(View.GONE); playerControls.setVisibility(View.GONE);
        setSubtitleInset(false);
        playerView.requestFocus();
    }
    private void setSubtitleInset(boolean controls) {
        if(playerView==null || playerView.getSubtitleView()==null)return;
        View subtitles=playerView.getSubtitleView();
        ViewGroup.LayoutParams current=subtitles.getLayoutParams();
        if(current instanceof ViewGroup.MarginLayoutParams) {
            ViewGroup.MarginLayoutParams params=(ViewGroup.MarginLayoutParams)current;
            params.bottomMargin=controls?Math.max(dp(170),playerControls.getHeight()):0;
            subtitles.setLayoutParams(params);
        }
        playerView.getSubtitleView().setBottomPaddingFraction(0.06f);
    }
    private void startPlayer() {
        if (player != null || playbackUri == null) return;
        playerHasFrame = false;
        DefaultMediaSourceFactory factory = new DefaultMediaSourceFactory(() -> new NasDataSource(client));
        player = new ExoPlayer.Builder(this).setMediaSourceFactory(factory).build();
        player.setAudioAttributes(new AudioAttributes.Builder().setUsage(C.USAGE_MEDIA).setContentType(C.AUDIO_CONTENT_TYPE_MOVIE).build(), true);
        playerView.setPlayer(player);
        if(playerView.getSubtitleView()!=null) playerView.getSubtitleView().setFixedTextSize(android.util.TypedValue.COMPLEX_UNIT_SP,24);
        subtitleMenu = new SubtitleMenu(this,player,subtitleButton,playerView.getSubtitleView(),getPreferences(MODE_PRIVATE),()->{
            if(screen==Screen.PLAYER && player!=null) { showControls(false);subtitleButton.requestFocus(); }
        });
        MediaItem.Builder item = new MediaItem.Builder().setUri(playbackUri.toString());
        if (mime != null) item.setMimeType(mime);
        player.addListener(new Player.Listener() {
            @Override public void onTracksChanged(Tracks tracks) { if(subtitleMenu!=null)subtitleMenu.refresh(); }
            @Override public void onPlayerError(PlaybackException ex) {
                main.removeCallbacks(ticker);
                Throwable cause = ex;
                while (cause != null) {
                    if (cause instanceof NasClient.ApiException && ((NasClient.ApiException) cause).status == 401) {
                        releasePlayer(); client.clearSession(); showLogin(); setNotice("播放会话过期，请重新登录"); return;
                    }
                    cause = cause.getCause();
                }
                setNotice("暂时无法播放，请返回选集重试，或选择其他来源。");
                playbackStatus.setText("播放已停止"); showControls(true); main.removeCallbacks(hidePlayerControls);
            }
            @Override public void onRenderedFirstFrame() {
                playerHasFrame = true; setNotice("左右快进退 · 上下键打开菜单 · 返回关闭菜单 / 选集"); showControls(false);
            }
            @Override public void onIsPlayingChanged(boolean playing) {
                if (playing) { main.removeCallbacks(hidePlayerControls); main.postDelayed(hidePlayerControls, 3500); }
            }
            @Override public void onPlaybackStateChanged(int state) {
                if(subtitleMenu!=null)subtitleMenu.refresh();
                if(state==Player.STATE_READY)try{new PlaybackStore(MainActivity.this).add(detailSource,selected,title);}catch(Exception ignored){}
                if (state == Player.STATE_ENDED) {
                    saveTvProgress();
                    if(autoNext.isChecked() && episodeIndex+1<episodes.length()) main.post(()->{
                        if(screen==Screen.PLAYER && player!=null && player.getPlaybackState()==Player.STATE_ENDED)switchTvEpisode(episodeIndex+1);
                    });
                    else {setNotice("本集已播完，可返回选集继续观看。");showControls(true);main.removeCallbacks(hidePlayerControls);}
                }
            }
        });
        player.setMediaItem(item.build(), position); player.prepare(); player.setPlayWhenReady(playWhenReady);
        playerView.setKeepScreenOn(true); main.post(ticker);
    }
    private void togglePlay() { if (player != null) { if (player.getPlayWhenReady()) player.pause(); else player.play(); } }
    private void seek(long delta) {
        if (player == null || !player.isCurrentMediaItemSeekable()) return;
        long end = player.getDuration(); long next = Math.max(0, player.getCurrentPosition() + delta);
        player.seekTo(end > 0 ? Math.min(end, next) : next);
    }
    private void switchTvEpisode(int target) {
        if(target<0 || target>=episodes.length() || busy)return;
        releasePlayer();preparePlayback(target,false);
    }
    private void saveTvProgress() {
        if(player!=null)new PlaybackStore(this).save(detailSource,selected,title,episodeIndex,player.getCurrentPosition(),player.getDuration());
    }
    private void releasePlayer() {
        if(subtitleMenu!=null) { subtitleMenu.close();subtitleMenu=null; }
        main.removeCallbacks(ticker); main.removeCallbacks(hidePlayerControls);
        if (player != null) {
            saveTvProgress(); position = player.getCurrentPosition(); playWhenReady = player.getPlayWhenReady();
            playerView.setPlayer(null); player.release(); player = null; playerView.setKeepScreenOn(false);
        }
    }
    private void leavePlayer() { releasePlayer(); showDetail(); }
    @Override public boolean dispatchKeyEvent(KeyEvent event) {
        if (screen == Screen.PLAYER && player != null && event.getAction() == KeyEvent.ACTION_DOWN) {
            if (controlsVisible) { main.removeCallbacks(hidePlayerControls); main.postDelayed(hidePlayerControls, 3500); }
            if (event.getKeyCode() == KeyEvent.KEYCODE_MENU
                    || !controlsVisible && (event.getKeyCode() == KeyEvent.KEYCODE_DPAD_UP || event.getKeyCode() == KeyEvent.KEYCODE_DPAD_DOWN)) {
                showControls(true); return true;
            }
            if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE) { togglePlay(); showControls(false); return true; }
            if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PLAY) { player.play(); return true; }
            if (event.getKeyCode() == KeyEvent.KEYCODE_MEDIA_PAUSE) { player.pause(); return true; }
        }
        return super.dispatchKeyEvent(event);
    }
    @SuppressWarnings("deprecation") @Override public void onBackPressed() {
        if (busy) { generation++; busy = false; setNotice("已取消等待，可重试"); return; }
        switch (screen) {
            case PLAYER: if (controlsVisible) hideControls(); else leavePlayer(); break;
            case DETAIL: returnFromDetail(); break;
            case PLAYLIST: showSearch(); break;
            case FAVORITES: showSearch(); break;
            case SETTINGS: showSearch(); break;
            case PAIRING: returnFromPairing(); break;
            case LOGIN: showConfig(); break;
            case SEARCH: if(searching){cancelSearch();updateSearchSummary();}else super.onBackPressed(); break;
            default: super.onBackPressed();
        }
    }
    @Override protected void onStart() { super.onStart(); stopped = false; if (screen == Screen.PLAYER) startPlayer(); }
    @Override protected void onPause() { saveTvProgress(); super.onPause(); }
    @Override protected void onStop() { stopped = true; if (screen == Screen.PAIRING) { closePairing(); setNotice("配置已暂停，请重新生成二维码。"); } releasePlayer(); super.onStop(); }
    @Override protected void onDestroy() { closePairing(); cancelSearch(); searches.shutdownNow(); generation++; network.shutdownNow(); updateChecks.shutdownNow(); posters.close(); main.removeCallbacksAndMessages(null); super.onDestroy(); }
    private static String time(long ms) {
        if (ms < 0) return "--:--";
        return String.format(Locale.ROOT, "%02d:%02d", ms / 60000, ms / 1000 % 60);
    }
}
