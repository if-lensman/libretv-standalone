package family.libretv.tv;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.view.Gravity;
import android.view.View;
import android.widget.*;
import org.json.JSONObject;

/** Shared native presentation; no data, navigation or playback state lives here. */
final class PiankeUi {
    static final int BACKGROUND=0xff101723,PANEL=0xff192535,PINK=0xfff15b9b,SECONDARY=0xffa9b8c9;
    static final String SLOGAN="留点片刻，看点喜欢的";
    static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable surface(Context c,int color,int radius){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;}
    static StateListDrawable focus(Context c,boolean primary){
        StateListDrawable states=new StateListDrawable();
        GradientDrawable focused=surface(c,primary?PINK:0xff304052,12);focused.setStroke(dp(c,2),0xfff5f7fa);
        states.addState(new int[]{android.R.attr.state_focused},focused);
        states.addState(new int[]{android.R.attr.state_pressed},surface(c,0xff493044,12));
        states.addState(new int[]{},surface(c,primary?PINK:PANEL,12));return states;
    }
    static TextView text(Context c,String value,int size,boolean bold){TextView t=new TextView(c);t.setText(value);t.setTextSize(size);t.setTextColor(bold?0xfff1f5fa:SECONDARY);if(bold)t.setTypeface(null,Typeface.BOLD);return t;}
    static ImageView brand(Context c,int width,int height){ImageView v=new ImageView(c);v.setImageResource(R.drawable.pianke_wordmark);v.setScaleType(ImageView.ScaleType.FIT_CENTER);v.setContentDescription("片刻");v.setLayoutParams(new LinearLayout.LayoutParams(dp(c,width),dp(c,height)));return v;}
    static void progress(LinearLayout parent,JSONObject saved){
        if(saved==null||saved.optLong("duration")<=0)return;
        Context c=parent.getContext();ProgressBar bar=new ProgressBar(c,null,android.R.attr.progressBarStyleHorizontal);bar.setMax(1000);
        bar.setProgress((int)Math.min(1000,Math.max(0,(double)saved.optLong("position")/saved.optLong("duration")*1000)));
        bar.setProgressTintList(ColorStateList.valueOf(PINK));bar.setProgressBackgroundTintList(ColorStateList.valueOf(0xff354559));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,dp(c,4));p.topMargin=dp(c,8);p.bottomMargin=dp(c,6);parent.addView(bar,p);
    }
    static String position(JSONObject saved){
        if(saved==null||!saved.has("position"))return "尚未播放";
        long seconds=Math.max(0,saved.optLong("position"))/1000;
        return "第 "+(saved.optInt("index")+1)+" 集 · 已看 "+(seconds/60)+"分"+(seconds%60)+"秒";
    }
    static Button entry(LinearLayout parent,String title,String subtitle,Runnable action){
        Context c=parent.getContext();Button b=new Button(c);b.setAllCaps(false);b.setText(subtitle.isEmpty()?title+"    ›":title+"    ›\n"+subtitle);
        android.text.SpannableString styled=new android.text.SpannableString(b.getText());
        int split=b.getText().toString().indexOf('\n');if(split>=0){styled.setSpan(new android.text.style.RelativeSizeSpan(0.80f),split+1,styled.length(),0);styled.setSpan(new android.text.style.ForegroundColorSpan(SECONDARY),split+1,styled.length(),0);}b.setText(styled);
        b.setTextSize(c.getResources().getConfiguration().screenWidthDp>=900?20:16);b.setTextColor(0xffedf2f8);b.setGravity(Gravity.START|Gravity.CENTER_VERTICAL);b.setPadding(dp(c,16),dp(c,12),dp(c,16),dp(c,12));
        int icon=title.contains("来源")?R.drawable.ic_setting_sources:title.contains("直播")?R.drawable.ic_nav_live:title.contains("投屏")?R.drawable.ic_setting_cast:title.contains("更新")?R.drawable.ic_setting_update:R.drawable.ic_setting_info;
        android.graphics.drawable.Drawable glyph=c.getDrawable(icon);if(glyph!=null){glyph.setBounds(0,0,dp(c,24),dp(c,24));b.setCompoundDrawables(glyph,null,null,null);b.setCompoundDrawablePadding(dp(c,12));}
        b.setBackground(focus(c,false));b.setFocusableInTouchMode(true);b.setMinHeight(dp(c,58));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.topMargin=dp(c,4);p.bottomMargin=dp(c,4);parent.addView(b,p);b.setOnClickListener(v->action.run());return b;
    }
    static void about(Context c){new android.app.AlertDialog.Builder(c).setTitle("关于片刻").setMessage(SLOGAN+"\n\n版本 "+BuildConfig.VERSION_NAME+"\n手机与电视上的原生影音客户端。\n\n部分业务设计与解析逻辑参考 LibreTV，非官方发行版。项目沿用 AGPL-3.0-or-later；UPnPCast 使用 MIT 许可。完整许可与第三方声明见原仓库 README / NOTICE；仓库地址保持不变。")
        .setPositiveButton("知道了",null).show();}
    private PiankeUi(){}
}
