package family.libretv.tv;

import android.app.Activity;
import android.content.Intent;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** TV-only presentation of the shared phone recommendation and on-device viewing repositories. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class TvHomeActivity extends Activity {
    private final Handler main=new Handler(Looper.getMainLooper());
    private final ExecutorService recommendationsWorker=Executors.newFixedThreadPool(2);
    private final PosterLoader posters=new PosterLoader();
    private RecommendationRepository recommendations;
    private PlaybackStore history;
    private LinearLayout root,body,recommendationCards;
    private TextView recommendationStatus;
    private FrameLayout hero;
    private JSONObject recent;
    private String category="movie",page="home",focusKey="hero";
    private JSONArray items=new JSONArray();
    private final Map<String,View> targets=new HashMap<>();
    private int generation;
    private boolean dead,started,restoring;
    private Future<?> pending;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable rounded(int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));return d;}
    private StateListDrawable selectable(int normal){StateListDrawable d=new StateListDrawable();GradientDrawable f=rounded(0xff3c293e,14);f.setStroke(dp(3),0xfff15b9b);d.addState(new int[]{android.R.attr.state_focused},f);d.addState(new int[]{},rounded(normal,14));return d;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextColor(color);t.setTextSize(size);return t;}
    private TextView heading(String value,int size){TextView t=text(value,size,0xfff1f5fa);t.setTypeface(null,Typeface.BOLD);return t;}
    @Override public void onCreate(Bundle state){super.onCreate(state);getWindow().setFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN,WindowManager.LayoutParams.FLAG_FULLSCREEN);recommendations=new RecommendationRepository(this);history=new PlaybackStore(this);if(state!=null){category=state.getString("category","movie");page=state.getString("page","home");focusKey=state.getString("focusKey","hero");}UpdateActivity.checkInBackground(this);}
    @Override protected void onResume(){super.onResume();started=true;render();}
    private void register(View view,String key,Runnable action){
        StateListDrawable ring=new StateListDrawable();GradientDrawable outline=rounded(0x00000000,14);outline.setStroke(dp(3),0xfff15b9b);ring.addState(new int[]{android.R.attr.state_focused},outline);ring.addState(new int[]{},rounded(0x00000000,14));view.setForeground(ring);
        targets.put(key,view);view.setId(View.generateViewId());view.setFocusableInTouchMode(true);view.setFocusable(true);view.setClickable(true);view.setBackground(selectable(0xff1b2736));view.setOnClickListener(v->{focusKey=key;action.run();});view.setOnFocusChangeListener((v,focused)->{if(focused&&!restoring)focusKey=key;v.setElevation(focused?dp(4):0);});
    }
    private Button button(LinearLayout parent,String label,String key,Runnable action){Button b=new Button(this);b.setText(label);b.setAllCaps(false);b.setTextColor(0xffedf2f8);b.setTextSize(15);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(14),0,dp(14),0);register(b,key,action);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-2,dp(46));p.setMargins(dp(4),dp(4),dp(4),dp(4));parent.addView(b,p);return b;}
    private LinearLayout row(LinearLayout parent){LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.HORIZONTAL);parent.addView(r,new LinearLayout.LayoutParams(-1,-2));return r;}
    private void open(String destination){startActivity(new Intent(this,MainActivity.class).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).putExtra("tvDestination",destination));}
    private void search(String title){startActivity(new Intent(this,MainActivity.class).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).putExtra("tvDestination","search").putExtra("query",title));}
    private void resume(JSONObject entry){startActivity(new Intent(this,MainActivity.class).addCategory(Intent.CATEGORY_LEANBACK_LAUNCHER).putExtra("tvDestination","resume").putExtra("resumeEntry",entry.toString()));}
    private void render(){
        if(dead)return;int ticket=++generation;if(pending!=null)pending.cancel(true);String restore=focusKey;restoring=true;targets.clear();recent=history.all().optJSONObject(0);items=recommendations.cached(category);
        root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(0xff101723);root.setPadding(dp(30),dp(16),dp(30),dp(12));
        LinearLayout header=row(root);header.setGravity(Gravity.CENTER_VERTICAL);TextView brand=heading("LibreTV",27);brand.setPadding(dp(4),0,dp(26),0);brand.getPaint().setShader(new android.graphics.LinearGradient(0,0,dp(140),0,new int[]{0xff24d0e7,0xfff15b9b},null,android.graphics.Shader.TileMode.CLAMP));header.addView(brand);
        button(header,"首页","nav-home",()->{page="home";focusKey="hero";render();});button(header,"搜索","nav-search",()->open("search"));button(header,"直播","nav-live",()->startActivity(new Intent(this,LiveBrowseActivity.class)));button(header,"收藏","nav-favorites",()->open("favorites"));button(header,"历史","nav-history",()->{page="history";focusKey="history-0";render();});button(header,"设置","nav-settings",()->open("settings"));
        TextView time=text(new java.text.SimpleDateFormat("HH:mm",Locale.getDefault()).format(new Date()),15,0xffa9b8c9);time.setGravity(Gravity.END);header.addView(time,new LinearLayout.LayoutParams(0,-2,1));
        ScrollView scroll=new ScrollView(this);scroll.setFillViewport(true);scroll.setClipToPadding(false);body=new LinearLayout(this);body.setOrientation(LinearLayout.VERTICAL);body.setPadding(dp(5),dp(16),dp(5),dp(6));scroll.addView(body);root.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        if(page.equals("history"))renderHistory();else if(page.equals("more"))renderMore();else renderHome();
        if(page.equals("home"))wireHomeFocus();
        TextView help=text("方向键选择  ·  确认进入  ·  返回键返回",12,0xff8fa4b8);help.setPadding(dp(5),dp(10),0,0);root.addView(help);setContentView(root);
        root.post(()->{if(dead||ticket!=generation)return;restoring=false;View target=targets.get(restore);if(target==null)target=targets.get("hero");if(target==null)target=targets.get("nav-home");target.requestFocusFromTouch();});
        if(!page.equals("history")&&recommendations.enabled())loadRecommendations(ticket);
    }
    private void renderHome(){
        LinearLayout heroRow=row(body);LinearLayout.LayoutParams heroParams=new LinearLayout.LayoutParams(0,dp(224),1.75f);heroParams.setMargins(0,dp(4),dp(16),dp(4));hero=new FrameLayout(this);hero.setClipToOutline(true);heroRow.addView(hero,heroParams);renderHero();
        LinearLayout side=new LinearLayout(this);side.setOrientation(LinearLayout.VERTICAL);heroRow.addView(side,new LinearLayout.LayoutParams(0,dp(232),1));
        Button search=button(side,"⌕  搜索电影、电视剧","quick-search",()->open("search"));search.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);search.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(62)));
        LinearLayout live=new LinearLayout(this);live.setOrientation(LinearLayout.VERTICAL);live.setGravity(Gravity.CENTER_VERTICAL);live.setPadding(dp(24),dp(16),dp(24),dp(16));register(live,"quick-live",()->startActivity(new Intent(this,LiveBrowseActivity.class)));LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,0,1);lp.topMargin=dp(14);side.addView(live,lp);live.setContentDescription("电视直播");live.addView(text("● LIVE",12,0xffa7e5ce));TextView liveTitle=heading("电视直播",25);liveTitle.setPadding(0,dp(7),0,dp(5));live.addView(liveTitle);live.addView(text("新闻、体育，还有熟悉的频道",14,0xffa9becb));
        LinearLayout titleRow=row(body);titleRow.setPadding(0,dp(14),0,dp(5));titleRow.setGravity(Gravity.CENTER_VERTICAL);titleRow.addView(heading("今晚看什么",20),new LinearLayout.LayoutParams(0,-2,1));button(titleRow,"更多片单 →","more",()->{page="more";focusKey="film-0";render();});
        recommendationStatus=text("",13,0xffa9b8c9);body.addView(recommendationStatus);recommendationCards=row(body);renderCards(false);
    }
    private void renderHero(){
        // Fill once per home render. Background catalogue updates never replace the focused hero.
        hero.removeAllViews();JSONObject boundRecent=recent,recommended=items.optJSONObject(0);register(hero,"hero",()->{if(boundRecent!=null)resume(boundRecent);else if(recommended!=null)search(recommended.optString("title"));else open("search");});
        String cover=recent!=null?recent.optJSONObject("item")==null?"":recent.optJSONObject("item").optString("pic"):recommended==null?"":recommended.optString("cover");
        if(!cover.isEmpty()){ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);FrameLayout.LayoutParams imageParams=new FrameLayout.LayoutParams(dp(310),-1,Gravity.END);hero.addView(image,imageParams);posters.loadPublic(image,cover,R.drawable.poster_placeholder);View veil=new View(this);veil.setBackground(new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,new int[]{0xff16263a,0xff16263a,0x2016263a}));hero.addView(veil,new FrameLayout.LayoutParams(-1,-1));}
        LinearLayout copy=new LinearLayout(this);copy.setOrientation(LinearLayout.VERTICAL);copy.setGravity(Gravity.CENTER_VERTICAL);copy.setPadding(dp(28),dp(18),dp(26),dp(18));hero.addView(copy,new FrameLayout.LayoutParams(-1,-1));
        copy.addView(text(recent!=null?"继续观看":recommended!=null?"今晚推荐":"你的独立影院",13,0xffafc6d9));String title=recent!=null?recent.optString("title"):recommended!=null?recommended.optString("title"):"今晚，想看点什么？";TextView name=heading(title,30);name.setMaxLines(1);name.setEllipsize(android.text.TextUtils.TruncateAt.END);name.setPadding(0,dp(12),dp(110),dp(6));copy.addView(name);
        copy.addView(text(recent!=null?"第 "+(recent.optInt("index")+1)+" 集  ·  已看到 "+clock(recent.optLong("position")):"找一部好电影，或打开熟悉的频道。",14,0xffa9b8c9));
        TextView action=heading(recent!=null?"▶ 继续播放":recommended!=null?"⌕ 搜索这部":"⌕ 搜索影视",16);action.setBackground(rounded(0xfff15b9b,10));action.setTextColor(0xff281524);action.setPadding(dp(18),dp(10),dp(18),dp(10));LinearLayout.LayoutParams ap=new LinearLayout.LayoutParams(-2,-2);ap.topMargin=dp(20);copy.addView(action,ap);hero.setContentDescription((recent!=null?"继续播放 · ":"首页推荐 · ")+title);
    }
    private void renderMore(){
        LinearLayout title=row(body);title.addView(heading("推荐片单",23),new LinearLayout.LayoutParams(0,-2,1));button(title,"返回首页","back-home",()->{page="home";focusKey="more";render();});
        LinearLayout filters=row(body);for(String key:new String[]{"movie","tv","show","anime","top"})button(filters,RecommendationRepository.label(key),"category-"+key,()->{category=key;focusKey="category-"+key;render();});
        recommendationStatus=text("",14,0xffa9b8c9);recommendationStatus.setPadding(0,dp(12),0,dp(8));body.addView(recommendationStatus);recommendationCards=new LinearLayout(this);recommendationCards.setOrientation(LinearLayout.VERTICAL);body.addView(recommendationCards);renderCards(true);
    }
    private void renderCards(boolean full){
        if(!recommendations.enabled()){recommendationStatus.setText("推荐已关闭，可在设置中开启；搜索和直播仍可使用。");return;}
        recommendationStatus.setText(items.length()==0?"正在找好片…":"");recommendationStatus.setVisibility(items.length()==0?View.VISIBLE:View.GONE);
        int columns=getResources().getConfiguration().screenWidthDp>=1000?5:4,count=full?items.length():Math.min(columns,items.length());int width=(getResources().getConfiguration().screenWidthDp-74)/columns-12;
        LinearLayout current=recommendationCards;
        for(int i=0;i<count;i++){
            if(full&&i%columns==0)current=row(recommendationCards);JSONObject item=items.optJSONObject(i);if(item==null)continue;
            LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(3),dp(3),dp(3),dp(7));register(card,"film-"+i,()->search(item.optString("title")));card.setContentDescription("搜索影片 · "+item.optString("title"));LinearLayout.LayoutParams params=new LinearLayout.LayoutParams(dp(width),-2);params.setMargins(dp(3),dp(5),dp(7),dp(5));current.addView(card,params);
            FrameLayout art=new FrameLayout(this);card.addView(art,new LinearLayout.LayoutParams(-1,dp(full?190:116)));ImageView image=new ImageView(this);image.setScaleType(ImageView.ScaleType.CENTER_CROP);art.addView(image,new FrameLayout.LayoutParams(-1,-1));posters.loadPublic(image,item.optString("cover"),R.drawable.poster_placeholder);String rating=item.optString("rating");if(!rating.isEmpty()){TextView score=text("★ "+rating,12,0xffffd66a);score.setPadding(dp(6),dp(2),dp(6),dp(2));score.setBackgroundColor(0xbb000000);art.addView(score,new FrameLayout.LayoutParams(-2,-2,Gravity.BOTTOM|Gravity.START));}
            TextView title=text(item.optString("title"),15,0xfff1f5fa);title.setSingleLine(true);title.setEllipsize(android.text.TextUtils.TruncateAt.END);title.setPadding(dp(8),dp(8),dp(5),0);card.addView(title);
        }
    }
    private void loadRecommendations(int ticket){String selectedCategory=category;JSONArray shown=items;
        pending=recommendationsWorker.submit(()->{try{JSONArray fresh=recommendations.load(selectedCategory);main.post(()->{
            if(dead||ticket!=generation||!started)return;if(fresh.toString().equals(shown.toString()))return;
            // A recommendation refresh must not move a remote target while someone is browsing it.
            if(focusKey.startsWith("film-")&&shown.length()>0)return;
            String previous=focusKey;items=fresh;targets.keySet().removeIf(key->key.startsWith("film-"));recommendationCards.removeAllViews();renderCards(page.equals("more"));if(page.equals("home"))wireHomeFocus();View target=targets.get(previous);if(previous.startsWith("film-")&&target!=null)target.requestFocusFromTouch();
        });}catch(Exception e){main.post(()->{if(!dead&&ticket==generation){recommendationStatus.setVisibility(View.VISIBLE);recommendationStatus.setText(shown.length()>0?"暂时无法更新，显示上次片单":"片单暂时无法加载，仍可使用搜索和直播。");}});}});
    }
    private void link(String from,String to,int direction){View a=targets.get(from),b=targets.get(to);if(a==null||b==null)return;switch(direction){case View.FOCUS_UP:a.setNextFocusUpId(b.getId());break;case View.FOCUS_DOWN:a.setNextFocusDownId(b.getId());break;case View.FOCUS_LEFT:a.setNextFocusLeftId(b.getId());break;case View.FOCUS_RIGHT:a.setNextFocusRightId(b.getId());break;default:break;}}
    private void wireHomeFocus(){
        link("hero","nav-home",View.FOCUS_UP);link("hero","quick-search",View.FOCUS_RIGHT);link("hero","film-0",View.FOCUS_DOWN);
        link("quick-search","hero",View.FOCUS_LEFT);link("quick-search","nav-search",View.FOCUS_UP);link("quick-search","quick-live",View.FOCUS_DOWN);
        link("quick-live","hero",View.FOCUS_LEFT);link("quick-live","quick-search",View.FOCUS_UP);link("quick-live","more",View.FOCUS_DOWN);link("more","quick-live",View.FOCUS_UP);
        for(String key:new String[]{"nav-home","nav-favorites","nav-history","nav-settings"})link(key,"hero",View.FOCUS_DOWN);
        link("nav-search","quick-search",View.FOCUS_DOWN);link("nav-live","quick-live",View.FOCUS_DOWN);
        int count=Math.min(getResources().getConfiguration().screenWidthDp>=1000?5:4,items.length());
        for(int i=0;i<count;i++){link("film-"+i,i<count-2?"hero":"quick-live",View.FOCUS_UP);if(i>0)link("film-"+i,"film-"+(i-1),View.FOCUS_LEFT);if(i<count-1)link("film-"+i,"film-"+(i+1),View.FOCUS_RIGHT);}
        link("more","film-"+(count-1),View.FOCUS_DOWN);
    }
    private void renderHistory(){
        LinearLayout title=row(body);title.addView(heading("观看历史",24),new LinearLayout.LayoutParams(0,-2,1));button(title,"播放列表","playlist",()->open("playlist"));button(title,"返回首页","back-home",()->{page="home";focusKey="hero";render();});JSONArray all=history.all();if(all.length()==0){body.addView(text("还没有观看记录，先找一部想看的。",18,0xffa9b8c9));return;}
        for(int i=0;i<all.length();i++){JSONObject entry=all.optJSONObject(i);if(entry==null)continue;Button b=button(body,entry.optString("title")+"  ·  第 "+(entry.optInt("index")+1)+" 集  ·  "+clock(entry.optLong("position")),"history-"+i,()->resume(entry));b.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);b.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(58)));}
    }
    private static String clock(long ms){return String.format(Locale.ROOT,"%02d:%02d",ms/60000,ms/1000%60);}
    @SuppressWarnings("deprecation") @Override public void onBackPressed(){if(!page.equals("home")){page="home";focusKey="hero";render();}else super.onBackPressed();}
    @Override protected void onSaveInstanceState(Bundle out){out.putString("category",category);out.putString("page",page);out.putString("focusKey",focusKey);super.onSaveInstanceState(out);}
    @Override protected void onStop(){started=false;generation++;if(pending!=null)pending.cancel(true);super.onStop();}
    @Override protected void onDestroy(){dead=true;recommendationsWorker.shutdownNow();posters.close();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
