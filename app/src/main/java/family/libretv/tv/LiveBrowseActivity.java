package family.libretv.tv;

import android.app.*;
import android.os.*;
import android.content.Intent;
import android.content.ActivityNotFoundException;
import android.net.Uri;
import android.graphics.Typeface;
import android.graphics.drawable.*;
import android.view.*;
import android.widget.*;
import androidx.recyclerview.widget.*;
import java.util.*;
import java.util.concurrent.*;
import org.json.*;

/** Remote-friendly channel grid; subscription and playback data remain in LiveRepository. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class LiveBrowseActivity extends Activity {
    private static final int IMPORT_PLAYLIST=414;
    private LiveRepository repository;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private final PosterLoader logos=new PosterLoader();
    private final Map<String,View> controls=new HashMap<>();
    private final List<JSONObject> visible=new ArrayList<>();
    private TextView notice;
    private AlertDialog channelActions;
    private RecyclerView grid;
    private GridLayoutManager layout;
    private boolean refreshing,dead,restoring;
    private String group="全部",focusKey="";
    private int columns,renderGeneration;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    private GradientDrawable shape(int color,int radius,int border){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(radius));if(border!=0)d.setStroke(dp(2),border);return d;}
    private StateListDrawable focusBackground(boolean selected){StateListDrawable d=new StateListDrawable();d.addState(new int[]{android.R.attr.state_focused},shape(0xff3a243c,14,0xfff15b9b));d.addState(new int[]{},shape(selected?0xff293544:0xff1b2736,14,selected?0xff52667b:0));return d;}
    private TextView text(String value,int size,int color){TextView t=new TextView(this);t.setText(value);t.setTextSize(size);t.setTextColor(color);return t;}
    @Override public void onCreate(Bundle state){
        super.onCreate(state);
        try{repository=new LiveRepository(this);if(state!=null){group=state.getString("group","全部");focusKey=state.getString("focusKey","");}else if(!repository.lastChannel().isEmpty())focusKey="channel:"+repository.lastChannel();render();refresh(false);}
        catch(Exception e){TextView message=text("频道资料无法读取，请返回后重试",20,0xffffffff);setContentView(message);}
    }
    private Button button(LinearLayout parent,String label,String key,boolean selected,Runnable action){
        Button b=new Button(this);b.setText(label);b.setTextSize(14);b.setTextColor(selected?0xfff6c1db:0xffd7e0eb);b.setBackground(focusBackground(selected));b.setAllCaps(false);b.setFocusableInTouchMode(true);b.setMinWidth(0);b.setMinimumWidth(0);b.setPadding(dp(12),0,dp(12),0);b.setId(View.generateViewId());
        parent.addView(b,new LinearLayout.LayoutParams(-1,dp(46)));controls.put(key,b);
        b.setOnFocusChangeListener((v,focused)->{if(focused&&!restoring)focusKey=key;});b.setOnClickListener(v->action.run());return b;
    }
    private void render()throws Exception {
        String desired=focusKey;restoring=true;int generation=++renderGeneration;controls.clear();
        JSONArray channels=repository.channels();LinkedHashMap<String,Integer> groups=new LinkedHashMap<>();groups.put("全部",channels.length());groups.put("收藏",0);
        for(int i=0;i<channels.length();i++){JSONObject c=channels.getJSONObject(i);String name=c.optString("group");if(name.isEmpty())name="未分组";if(!name.equals("全部")&&!name.equals("收藏"))groups.put(name,(groups.containsKey(name)?groups.get(name):0)+1);if(repository.favorite(c.getString("id")))groups.put("收藏",groups.get("收藏")+1);}
        if(!groups.containsKey(group))group="全部";
        visible.clear();for(int i=0;i<channels.length();i++){JSONObject c=channels.getJSONObject(i);String name=c.optString("group");if(name.isEmpty())name="未分组";if(group.equals("全部")||(group.equals("收藏")&&repository.favorite(c.getString("id")))||(!group.equals("收藏")&&group.equals(name)))visible.add(c);}
        LinearLayout outer=new LinearLayout(this);outer.setOrientation(LinearLayout.VERTICAL);outer.setPadding(dp(28),dp(18),dp(28),dp(12));outer.setBackgroundColor(0xff101723);
        LinearLayout header=new LinearLayout(this);header.setGravity(Gravity.CENTER_VERTICAL);outer.addView(header,new LinearLayout.LayoutParams(-1,dp(58)));
        TextView title=text("电视直播",27,0xffffffff);title.setTypeface(null,Typeface.BOLD);header.addView(title,new LinearLayout.LayoutParams(0,-2,1));
        Button back=button(header,"返回选片","back",false,this::finish);back.setLayoutParams(new LinearLayout.LayoutParams(dp(104),dp(42)));
        Button update=button(header,"刷新频道","refresh",false,()->refresh(true));update.setLayoutParams(new LinearLayout.LayoutParams(dp(104),dp(42)));((LinearLayout.LayoutParams)update.getLayoutParams()).leftMargin=dp(10);
        Button configure=button(header,"添加直播订阅","configure",false,this::configure);configure.setLayoutParams(new LinearLayout.LayoutParams(dp(132),dp(42)));((LinearLayout.LayoutParams)configure.getLayoutParams()).leftMargin=dp(10);
        Button file=button(header,"导入 M3U","import",false,this::pickPlaylist);file.setLayoutParams(new LinearLayout.LayoutParams(dp(112),dp(42)));((LinearLayout.LayoutParams)file.getLayoutParams()).leftMargin=dp(10);
        notice=text(refreshing?"正在检查频道…":"方向键选择频道 · 确认播放 · 长按确认或菜单键收藏",13,0xff9db0c5);notice.setPadding(0,dp(3),0,dp(10));outer.addView(notice);
        LinearLayout body=new LinearLayout(this);outer.addView(body,new LinearLayout.LayoutParams(-1,0,1));
        ScrollView sidebar=new ScrollView(this);sidebar.setVerticalScrollBarEnabled(false);LinearLayout filters=new LinearLayout(this);filters.setOrientation(LinearLayout.VERTICAL);sidebar.addView(filters);body.addView(sidebar,new LinearLayout.LayoutParams(dp(154),-1));
        TextView groupTitle=text("频道分组",13,0xff9db0c5);groupTitle.setPadding(dp(12),dp(6),0,dp(12));filters.addView(groupTitle);
        for(Map.Entry<String,Integer> entry:groups.entrySet()){
            String name=entry.getKey();Button filter=button(filters,name+"  "+entry.getValue(),"group:"+name,group.equals(name),()->{group=name;focusKey="";renderSafely();});
            filter.setGravity(Gravity.CENTER_VERTICAL|Gravity.START);filter.setContentDescription("频道分组 · "+name);((LinearLayout.LayoutParams)filter.getLayoutParams()).bottomMargin=dp(8);
            filter.setOnKeyListener((v,code,event)->{if(code==KeyEvent.KEYCODE_DPAD_RIGHT&&event.getAction()==KeyEvent.ACTION_DOWN){focusChannel(0);return true;}return false;});
        }
        LinearLayout catalogue=new LinearLayout(this);catalogue.setOrientation(LinearLayout.VERTICAL);LinearLayout.LayoutParams catalogueParams=new LinearLayout.LayoutParams(0,-1,1);catalogueParams.leftMargin=dp(22);body.addView(catalogue,catalogueParams);
        TextView heading=text(group+" · "+visible.size()+" 个频道",18,0xffe8eef5);heading.setPadding(dp(6),dp(6),0,dp(10));catalogue.addView(heading);
        columns=Math.max(2,Math.min(5,(getResources().getConfiguration().screenWidthDp-240)/180));
        grid=new RecyclerView(this);grid.setId(View.generateViewId());grid.setContentDescription("直播频道卡片");grid.setClipToPadding(false);grid.setClipChildren(false);grid.setPadding(dp(2),dp(6),dp(2),dp(8));layout=new GridLayoutManager(this,columns);grid.setLayoutManager(layout);grid.setItemAnimator(null);grid.setAdapter(new ChannelAdapter());catalogue.addView(grid,new LinearLayout.LayoutParams(-1,0,1));
        if(visible.isEmpty()){TextView empty=text(group.equals("收藏")?"还没有收藏频道\n选中频道后，长按确认键即可收藏":"暂无频道\n添加直播订阅，或导入 M3U 文件",18,0xff9db0c5);empty.setGravity(Gravity.CENTER);catalogue.addView(empty,new LinearLayout.LayoutParams(-1,0,1));grid.setVisibility(View.GONE);}
        setContentView(outer);
        final String target=desired;
        grid.getViewTreeObserver().addOnGlobalLayoutListener(new android.view.ViewTreeObserver.OnGlobalLayoutListener(){public void onGlobalLayout(){if(grid.getViewTreeObserver().isAlive())grid.getViewTreeObserver().removeOnGlobalLayoutListener(this);if(dead||generation!=renderGeneration)return;View control=controls.get(target);if(control!=null){restoring=false;control.requestFocus();return;}int position=0;for(int i=0;i<visible.size();i++)if(target.equals("channel:"+visible.get(i).optString("id")))position=i;restoring=false;if(!visible.isEmpty())focusChannel(position);else controls.get("group:"+group).requestFocus();}});
    }
    private void renderSafely(){try{render();}catch(Exception e){if(notice!=null)notice.setText("频道显示失败，请返回后重试");}}
    private void focusChannel(int position){
        if(visible.isEmpty())return;int target=Math.max(0,Math.min(position,visible.size()-1)),generation=renderGeneration;RecyclerView selectedGrid=grid;
        grid.scrollToPosition(target);main.post(new Runnable(){int attempts;public void run(){if(dead||generation!=renderGeneration)return;View card=layout.findViewByPosition(target);if(card!=null){card.requestFocusFromTouch();}else if(++attempts<4)selectedGrid.postDelayed(this,50);}});
    }
    private void play(JSONObject channel){String id=channel.optString("id");focusKey="channel:"+id;startActivity(new Intent(this,LivePlayerActivity.class).putExtra("channelId",id).putExtra("tvRemote",true));}
    private void actions(JSONObject channel){
        String id=channel.optString("id");focusKey="channel:"+id;
        channelActions=new AlertDialog.Builder(this).setTitle(channel.optString("name")).setItems(new String[]{"播放频道",repository.favorite(id)?"取消收藏":"收藏频道"},(d,which)->{if(which==0)play(channel);else{repository.toggleFavorite(id);renderSafely();}}).setNegativeButton("取消",null).show();
    }
    private final class ChannelHolder extends RecyclerView.ViewHolder {
        final LinearLayout card;final ImageView logo;final TextView initials,name,meta;
        ChannelHolder(LinearLayout c,ImageView logo,TextView initials,TextView name,TextView meta){super(c);card=c;this.logo=logo;this.initials=initials;this.name=name;this.meta=meta;}
    }
    private final class ChannelAdapter extends RecyclerView.Adapter<ChannelHolder> {
        @Override public ChannelHolder onCreateViewHolder(ViewGroup parent,int type){
            LinearLayout card=new LinearLayout(LiveBrowseActivity.this);card.setOrientation(LinearLayout.VERTICAL);card.setGravity(Gravity.CENTER);card.setPadding(dp(10),dp(12),dp(10),dp(10));card.setFocusableInTouchMode(true);card.setFocusable(true);card.setClickable(true);card.setLongClickable(true);card.setBackground(focusBackground(false));
            RecyclerView.LayoutParams params=new RecyclerView.LayoutParams(-1,dp(150));params.setMargins(dp(6),dp(6),dp(6),dp(6));card.setLayoutParams(params);
            FrameLayout artwork=new FrameLayout(LiveBrowseActivity.this);artwork.setBackground(shape(0xff25364a,10,0));card.addView(artwork,new LinearLayout.LayoutParams(-1,dp(64)));
            TextView initials=text("TV",23,0xffbad4ed);initials.setGravity(Gravity.CENTER);initials.setTypeface(null,Typeface.BOLD);artwork.addView(initials,new FrameLayout.LayoutParams(-1,-1));
            ImageView logo=new ImageView(LiveBrowseActivity.this);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);logo.setPadding(dp(10),dp(8),dp(10),dp(8));logo.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);artwork.addView(logo,new FrameLayout.LayoutParams(-1,-1));
            TextView name=text("",16,0xffffffff);name.setTypeface(null,Typeface.BOLD);name.setGravity(Gravity.CENTER);name.setSingleLine(true);name.setEllipsize(android.text.TextUtils.TruncateAt.END);LinearLayout.LayoutParams nameParams=new LinearLayout.LayoutParams(-1,-2);nameParams.topMargin=dp(10);card.addView(name,nameParams);
            TextView meta=text("",12,0xff9db0c5);meta.setGravity(Gravity.CENTER);meta.setSingleLine(true);meta.setEllipsize(android.text.TextUtils.TruncateAt.END);card.addView(meta,new LinearLayout.LayoutParams(-1,-2));
            return new ChannelHolder(card,logo,initials,name,meta);
        }
        @Override public void onBindViewHolder(ChannelHolder h,int position){
            JSONObject channel=visible.get(position);String id=channel.optString("id"),name=channel.optString("name");boolean favorite=repository.favorite(id);
            h.name.setText(name);h.meta.setText((favorite?"★ 已收藏  ·  ":"")+channel.optString("group","未分组"));h.meta.setTextColor(favorite?0xfff4bbd7:0xff9db0c5);
            h.initials.setText(name.length()>9?name.substring(0,9):name);boolean hasLogo=!channel.optString("logo").isEmpty();h.initials.setVisibility(hasLogo?View.GONE:View.VISIBLE);h.logo.setVisibility(hasLogo?View.VISIBLE:View.GONE);
            // Reset binding even when there is no logo, so an old image callback cannot fill a reused tile.
            logos.loadPublic(h.logo,channel.optString("logo"),R.drawable.ic_nav_live);
            h.card.setContentDescription("播放频道 · "+name+(favorite?" · 已收藏":""));h.card.setTag("channel:"+id);
            h.card.setOnClickListener(v->play(channel));h.card.setOnLongClickListener(v->{actions(channel);return true;});
            h.card.setOnFocusChangeListener((v,focused)->{if(focused&&!restoring)focusKey="channel:"+id;v.animate().scaleX(focused?1.035f:1f).scaleY(focused?1.035f:1f).setDuration(100).start();v.setElevation(focused?dp(5):0);});
            h.card.setOnKeyListener((v,code,event)->{if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;if(code==KeyEvent.KEYCODE_MENU){if(event.getRepeatCount()==0)actions(channel);return true;}if(code==KeyEvent.KEYCODE_DPAD_LEFT&&position%columns==0){View filter=controls.get("group:"+group);if(filter!=null)filter.requestFocus();return true;}return false;});
        }
        @Override public int getItemCount(){return visible.size();}
    }
    private void pickPlaylist(){
        Intent intent=new Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*");
        try{startActivityForResult(intent,IMPORT_PLAYLIST);}
        catch(ActivityNotFoundException missing){
            try{startActivityForResult(new Intent(Intent.ACTION_GET_CONTENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*"),IMPORT_PLAYLIST);}
            catch(ActivityNotFoundException alsoMissing){notice.setText("电视没有可用的文件选择器，请先安装支持选文件的文件管理器，再导入 M3U");}
        }
    }
    @Override protected void onActivityResult(int request,int result,Intent data){
        super.onActivityResult(request,result,data);
        if(request!=IMPORT_PLAYLIST||result!=RESULT_OK||data==null||data.getData()==null)return;
        Uri uri=data.getData();notice.setText("正在导入频道…");
        worker.submit(()->{try{
            LivePlaylistFile.importInto(this,repository,uri);int count=repository.channels().length();
            main.post(()->{if(dead)return;group="全部";focusKey="import";renderSafely();notice.setText("已导入频道，当前共 "+count+" 个 · 更新时重新导入同名文件");});
        }catch(Exception e){main.post(()->{if(!dead)notice.setText("导入失败，已有频道保留 · "+(e.getMessage()==null?"请检查文件或重新选择":e.getMessage()));});}});
    }
    private void configure(){LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);EditText name=new EditText(this);name.setHint("订阅名称");fields.addView(name);EditText url=new EditText(this);url.setSingleLine(true);url.setHint("M3U订阅地址");fields.addView(url);new AlertDialog.Builder(this).setTitle("添加直播订阅").setView(fields).setNegativeButton("取消",null).setPositiveButton("添加",(d,w)->{try{repository.saveSubscription(null,name.getText().toString(),url.getText().toString());refresh(true);}catch(Exception e){notice.setText(e.getMessage());}}).show();}
    private void refresh(boolean force){if(refreshing)return;refreshing=true;if(notice!=null)notice.setText("正在检查频道…");worker.submit(()->{try{LiveRepository.Refresh result=repository.refresh(force);main.post(()->{if(dead)return;refreshing=false;renderSafely();notice.setText(result.failed>0?"部分订阅更新失败，已有频道仍可使用":result.updated>0?"频道已更新 · 长按确认或菜单键收藏":"方向键选择频道 · 确认播放 · 长按确认或菜单键收藏");});}catch(Exception e){main.post(()->{if(!dead){refreshing=false;notice.setText("更新未完成，已有频道仍可使用");}});}});}
    @Override protected void onSaveInstanceState(Bundle state){state.putString("group",group);state.putString("focusKey",focusKey);super.onSaveInstanceState(state);}
    @Override protected void onDestroy(){dead=true;if(channelActions!=null)channelActions.dismiss();logos.close();worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
