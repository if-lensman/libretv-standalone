package family.libretv.tv;

import android.app.*;
import android.os.*;
import android.content.Intent;
import android.widget.*;
import java.util.concurrent.*;
import org.json.*;

/** Remote-friendly live channel entrance; leaves the existing TV VOD screens intact. */
@androidx.annotation.OptIn(markerClass = androidx.media3.common.util.UnstableApi.class)
public final class LiveBrowseActivity extends Activity {
    private LiveRepository repository;
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final Handler main=new Handler(Looper.getMainLooper());
    private TextView notice;
    private boolean refreshing,dead;
    private String group="全部";
    private int shown=40;
    private int dp(int n){return Math.round(n*getResources().getDisplayMetrics().density);}
    @Override public void onCreate(Bundle state){super.onCreate(state);try{repository=new LiveRepository(this);render();refresh(false);}catch(Exception e){TextView message=new TextView(this);message.setText(e.getMessage());setContentView(message);}}
    private Button button(LinearLayout parent,String label,Runnable action){Button b=new Button(this);b.setText(label);b.setTextSize(17);b.setTextColor(0xffffffff);b.setBackgroundResource(R.drawable.focus_button);b.setAllCaps(false);parent.addView(b,new LinearLayout.LayoutParams(-1,-2));b.setOnClickListener(v->action.run());return b;}
    private void render()throws Exception {
        LinearLayout outer=new LinearLayout(this);outer.setOrientation(LinearLayout.VERTICAL);outer.setPadding(dp(24),dp(18),dp(24),dp(16));outer.setBackgroundColor(0xff101723);setContentView(outer);
        TextView title=new TextView(this);title.setText("电视直播");title.setTextColor(0xffffffff);title.setTextSize(25);outer.addView(title);
        LinearLayout actions=new LinearLayout(this);outer.addView(actions);
        Button back=button(actions,"返回选片",this::finish);back.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        Button update=button(actions,"刷新频道",()->refresh(true));update.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        Button configure=button(actions,"添加直播订阅",this::configure);configure.setLayoutParams(new LinearLayout.LayoutParams(0,-2,1));
        notice=new TextView(this);notice.setTextColor(0xffe891b5);notice.setTextSize(15);outer.addView(notice);
        JSONArray channels=repository.channels();java.util.LinkedHashSet<String> groups=new java.util.LinkedHashSet<>();groups.add("全部");groups.add("收藏");for(int i=0;i<channels.length();i++)groups.add(channels.getJSONObject(i).optString("group"));
        Button filter=button(outer,"频道分组 · "+group,()->new AlertDialog.Builder(this).setTitle("频道分组").setItems(groups.toArray(new String[0]),(d,which)->{group=groups.toArray(new String[0])[which];shown=40;try{render();}catch(Exception ignored){}}).show());
        ScrollView scroll=new ScrollView(this);LinearLayout list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);scroll.addView(list);outer.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        int count=0;for(int i=0;i<channels.length();i++){JSONObject channel=channels.getJSONObject(i);String id=channel.getString("id");if(!"全部".equals(group)&&!("收藏".equals(group)&&repository.favorite(id))&&!group.equals(channel.optString("group")))continue;count++;if(count>shown)continue;
            LinearLayout row=new LinearLayout(this);list.addView(row);Button play=button(row,channel.optString("name"),()->startActivity(new Intent(this,LivePlayerActivity.class).putExtra("channelId",id).putExtra("tvRemote",true)));play.setLayoutParams(new LinearLayout.LayoutParams(0,dp(58),4));
            Button favorite=button(row,repository.favorite(id)?"取消收藏":"收藏",()->{repository.toggleFavorite(id);try{render();}catch(Exception ignored){}});favorite.setLayoutParams(new LinearLayout.LayoutParams(0,dp(58),1));
        }
        if(count>shown)button(list,"更多频道",()->{shown+=40;try{render();}catch(Exception ignored){}});
        if(channels.length()==0)notice.setText("暂无频道，可添加订阅后刷新。");filter.requestFocus();
    }
    private void configure(){LinearLayout fields=new LinearLayout(this);fields.setOrientation(LinearLayout.VERTICAL);EditText name=new EditText(this);name.setHint("订阅名称");fields.addView(name);EditText url=new EditText(this);url.setSingleLine(true);url.setHint("M3U订阅地址");fields.addView(url);new AlertDialog.Builder(this).setTitle("添加直播订阅").setView(fields).setNegativeButton("取消",null).setPositiveButton("添加",(d,w)->{try{repository.saveSubscription(null,name.getText().toString(),url.getText().toString());refresh(true);}catch(Exception e){notice.setText(e.getMessage());}}).show();}
    private void refresh(boolean force){if(refreshing)return;refreshing=true;if(notice!=null)notice.setText("正在检查频道…");worker.submit(()->{try{LiveRepository.Refresh result=repository.refresh(force);main.post(()->{if(dead)return;refreshing=false;try{render();notice.setText(result.failed>0?"部分订阅更新失败，已有频道仍可使用":result.updated>0?"频道已更新":"使用本机已保存的频道");}catch(Exception e){notice.setText(e.getMessage());}});}catch(Exception e){main.post(()->{if(!dead){refreshing=false;notice.setText("更新未完成，已有频道仍可使用");}});}});}
    @Override protected void onDestroy(){dead=true;worker.shutdownNow();main.removeCallbacksAndMessages(null);super.onDestroy();}
}
