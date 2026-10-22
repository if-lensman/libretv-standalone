package family.libretv.tv;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.SharedPreferences;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.TextView;
import android.view.View;
import androidx.media3.ui.SubtitleView;
import androidx.media3.common.C;
import androidx.media3.common.Format;
import androidx.media3.common.MimeTypes;
import androidx.media3.common.Player;
import androidx.media3.common.TrackSelectionOverride;
import androidx.media3.common.TrackSelectionParameters;
import androidx.media3.common.Tracks;
import java.util.ArrayList;
import java.util.Locale;

/** Text-track policy survives episodes; track-group overrides are scoped to the current item. */
@androidx.media3.common.util.UnstableApi
final class SubtitleMenu {
    private final Activity activity;
    private final Player player;
    private final Button button;
    private final SubtitleView subtitleView;
    private final SharedPreferences prefs;
    private final Runnable dismissed;
    private AlertDialog dialog;
    private String mode;
    private final boolean compact;
    private final ArrayList<Choice> choices = new ArrayList<>();
    private static final class Choice {
        final Tracks.Group group; final int index; final boolean supported; final String label;
        Choice(Tracks.Group group,int index) {
            this.group=group; this.index=index; supported=group.isTrackSupported(index);
            Format format=group.getTrackFormat(index);
            String language=format.language;
            String name=language==null || language.isEmpty() || language.equals("und") ? "未标注语言" : Locale.forLanguageTag(language).getDisplayLanguage(Locale.SIMPLIFIED_CHINESE);
            String display=format.label==null || format.label.isEmpty() ? name : format.label;
            if(MimeTypes.APPLICATION_CEA608.equals(format.sampleMimeType) || MimeTypes.APPLICATION_CEA708.equals(format.sampleMimeType)) display="内嵌 CC · "+display+"（可能为空轨）";
            label=display+(supported ? "" : "（当前格式不支持）");
        }
    }
    SubtitleMenu(Activity activity,Player player,Button button,SubtitleView subtitleView,SharedPreferences prefs,Runnable dismissed) {
        this(activity,player,button,subtitleView,prefs,dismissed,false);
    }
    SubtitleMenu(Activity activity,Player player,Button button,SubtitleView subtitleView,SharedPreferences prefs,Runnable dismissed,boolean compact) {
        this.compact=compact;
        this.activity=activity;this.player=player;this.button=button;this.subtitleView=subtitleView;this.prefs=prefs;this.dismissed=dismissed;
        mode=prefs.getString("subtitleMode","auto"); applyPolicy();refresh();
    }
    private void applyPolicy() {
        TrackSelectionParameters.Builder builder=player.getTrackSelectionParameters().buildUpon()
            .clearOverridesOfType(C.TRACK_TYPE_TEXT).setTrackTypeDisabled(C.TRACK_TYPE_TEXT,"off".equals(mode));
        if (mode.startsWith("lang:")) builder.setPreferredTextLanguages(mode.substring(5));
        else builder.setPreferredTextLanguages("zh","zh-Hans","zh-Hant","en");
        builder.setSelectUndeterminedTextLanguage(true);
        player.setTrackSelectionParameters(builder.build());
        if(subtitleView!=null) {
            boolean off="off".equals(mode);
            // Hide immediately as well as disabling decoding, including any queued/stale cues.
            subtitleView.setVisibility(off?View.GONE:View.VISIBLE);
            if(off)subtitleView.setCues(null);
        }
    }
    void refresh() {
        choices.clear();Choice selected=null;
        for (Tracks.Group group:player.getCurrentTracks().getGroups()) if(group.getType()==C.TRACK_TYPE_TEXT)
            for(int i=0;i<group.length;i++) { Choice choice=new Choice(group,i);choices.add(choice);if(group.isTrackSelected(i))selected=choice; }
        String text;
        if ("off".equals(mode)) text="关闭";
        else if (selected!=null) text=selected.label;
        else if (choices.isEmpty()) text=player.getPlaybackState()==Player.STATE_BUFFERING || player.getPlaybackState()==Player.STATE_IDLE ? "识别中" : "无可选字幕";
        else if (!hasSupported()) text="格式不支持";
        else text="自动";
        button.setText(compact ? "字幕" : "字幕："+text);
        button.setContentDescription("字幕："+text);
    }
    private boolean hasSupported() { for(Choice choice:choices) if(choice.supported)return true;return false; }
    boolean isShowing() { return dialog!=null && dialog.isShowing(); }
    void show() {
        refresh(); ArrayList<Choice> current=new ArrayList<>(choices);
        ArrayList<String> labels=new ArrayList<>();labels.add("自动（优先中文）");labels.add("关闭字幕");
        int checked="off".equals(mode)?1:0;
        for(int i=0;i<current.size();i++) {
            Choice choice=current.get(i);labels.add(choice.label);
            if (!"auto".equals(mode) && !"off".equals(mode) && choice.group.isTrackSelected(choice.index)) checked=i+2;
        }
        ArrayAdapter<String> adapter=new ArrayAdapter<String>(activity,android.R.layout.simple_list_item_single_choice,labels) {
            @Override public boolean isEnabled(int position) { return position<2 || current.get(position-2).supported; }
            @Override public boolean areAllItemsEnabled() { return false; }
        };
        String heading=choices.isEmpty()?"字幕 · 当前没有可选字幕轨":!hasSupported()?"字幕 · 当前格式不支持":"选择字幕";
        LinearLayout title=new LinearLayout(activity);title.setOrientation(LinearLayout.VERTICAL);
        int padding=(int)(20*activity.getResources().getDisplayMetrics().density);
        title.setPadding(padding,padding,padding,padding/2);
        TextView name=new TextView(activity);name.setText(heading);name.setTextSize(21);title.addView(name);
        TextView explanation=new TextView(activity);explanation.setTextSize(15);
        explanation.setText("开关仅控制软字幕。视频画面内的硬字幕无法关闭。\nCC 轨道可能没有实际字幕内容，列出轨道不代表已检测到字幕。");
        title.addView(explanation);
        dialog=new AlertDialog.Builder(activity).setCustomTitle(title).setSingleChoiceItems(adapter,checked,(d,which)->{
            if(which==0 || which==1) { mode=which==1?"off":"auto";applyPolicy(); }
            else {
                Choice choice=current.get(which-2);if(!choice.supported)return;
                String language=choice.group.getTrackFormat(choice.index).language;
                mode=language==null || language.isEmpty()?"auto":"lang:"+language;
                applyPolicy();
                player.setTrackSelectionParameters(player.getTrackSelectionParameters().buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_TEXT,false)
                    .setOverrideForType(new TrackSelectionOverride(choice.group.getMediaTrackGroup(),choice.index)).build());
            }
            prefs.edit().putString("subtitleMode",mode).apply();refresh();d.dismiss();
        }).setNegativeButton("返回",null).create();
        dialog.setOnDismissListener(d->{dialog=null;dismissed.run();});dialog.show();
    }
    void close() { if(dialog!=null) { dialog.setOnDismissListener(null);dialog.dismiss();dialog=null; } }
}
