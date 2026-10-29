package family.libretv.tv;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.*;
import android.graphics.drawable.*;
import android.view.Gravity;
import android.widget.Button;

/** Shared visual language; phone touch targets and TV focus remain native views. */
final class PlayerStyle {
    static final int PINK=0xfff15b9b, NAVY=0xff101723, MUTED=0xffaab5c5;
    static int dp(Context c,int n){return Math.round(n*c.getResources().getDisplayMetrics().density);}
    static GradientDrawable surface(int color,int radius,Context c){GradientDrawable d=new GradientDrawable();d.setColor(color);d.setCornerRadius(dp(c,radius));return d;}
    static Drawable background(Context c,boolean tv){
        StateListDrawable states=new StateListDrawable();
        GradientDrawable focus=surface(PINK,12,c);focus.setStroke(dp(c,2),Color.WHITE);
        states.addState(new int[]{android.R.attr.state_focused},focus);
        states.addState(new int[]{android.R.attr.state_pressed},surface(0x99f15b9b,12,c));
        states.addState(new int[]{android.R.attr.state_selected},surface(PINK,12,c));
        states.addState(new int[]{},surface(tv?0xa01c293a:Color.TRANSPARENT,12,c));return states;
    }
    static Button action(Context c,String label,String icon,boolean tv){
        Button b=new Button(c);b.setText(label);b.setAllCaps(false);b.setTextSize(tv?17:13);b.setGravity(Gravity.CENTER);
        b.setMinWidth(0);b.setMinimumWidth(0);b.setMinHeight(dp(c,48));b.setMinimumHeight(dp(c,48));
        b.setPadding(dp(c,8),dp(c,tv?12:6),dp(c,8),dp(c,tv?12:6));b.setBackground(background(c,tv));
        b.setTextColor(new ColorStateList(new int[][]{new int[]{-android.R.attr.state_enabled},new int[]{android.R.attr.state_focused},new int[]{android.R.attr.state_selected},new int[]{}},new int[]{0x55ffffff,NAVY,NAVY,Color.WHITE}));
        setIcon(b,icon,tv?26:24);b.setCompoundDrawablePadding(dp(c,tv?8:5));b.setMaxLines(1);b.setEllipsize(android.text.TextUtils.TruncateAt.END);b.setContentDescription(label);return b;
    }
    static void setIcon(Button b,String icon,int size){Icon d=new Icon(icon);int px=dp(b.getContext(),size);d.setBounds(0,0,px,px);if(b.getText().length()==0){b.setCompoundDrawables(d,null,null,null);b.setCompoundDrawablePadding(0);}else b.setCompoundDrawables(null,d,null,null);}
    static Drawable tvBackground(Context c) {
        StateListDrawable states=new StateListDrawable();
        states.addState(new int[]{android.R.attr.state_focused},surface(0xfff5f5fa,28,c));
        states.addState(new int[]{android.R.attr.state_pressed},surface(0x99f15b9b,28,c));
        states.addState(new int[]{},surface(Color.TRANSPARENT,28,c));
        return states;
    }
    static Drawable tvTileBackground(Context c) {
        StateListDrawable states=new StateListDrawable();
        GradientDrawable focus=surface(0xfff5f5fa,12,c);focus.setStroke(dp(c,2),Color.WHITE);
        states.addState(new int[]{android.R.attr.state_focused},focus);
        states.addState(new int[]{android.R.attr.state_selected},surface(PINK,12,c));
        states.addState(new int[]{},surface(0xa01c293a,12,c));return states;
    }
    static Button tvAction(Context c,String label,String icon) {
        Button b=action(c,label,icon,true);
        b.setTextSize(18);b.setPadding(dp(c,14),dp(c,8),dp(c,14),dp(c,8));
        b.setBackground(tvBackground(c));setTvIcon(b,icon,26);
        return b;
    }
    static void setTvIcon(Button b,String icon,int size) {
        Icon d=new Icon(icon);int px=dp(b.getContext(),size);d.setBounds(0,0,px,px);
        b.setCompoundDrawables(d,null,null,null);b.setCompoundDrawablePadding(dp(b.getContext(),9));
    }
    static final class Icon extends Drawable {
        private final String kind;private final Paint p=new Paint(3);
        Icon(String kind){this.kind=kind;}
        @Override public boolean isStateful(){return true;}
        @Override protected boolean onStateChange(int[] state){invalidateSelf();return true;}
        private void line(Canvas c,float... xy){Path a=new Path();a.moveTo(xy[0],xy[1]);for(int i=2;i<xy.length;i+=2)a.lineTo(xy[i],xy[i+1]);c.drawPath(a,p);}
        @Override public void draw(Canvas canvas){
            int save=canvas.save();Rect r=getBounds();canvas.translate(r.left,r.top);canvas.scale(r.width()/24f,r.height()/24f);
            int color=Color.WHITE;boolean enabled=false;for(int s:getState()){if(s==android.R.attr.state_enabled)enabled=true;if(s==android.R.attr.state_focused||s==android.R.attr.state_selected)color="star".equals(kind)?PINK:NAVY;}if(!enabled)color=0x55ffffff;
            p.setColor(color);p.setStrokeWidth(1.8f);p.setStrokeCap(Paint.Cap.ROUND);p.setStrokeJoin(Paint.Join.ROUND);p.setStyle(Paint.Style.STROKE);
            switch(kind){
                case "key-left":case "key-right":
                    canvas.drawRoundRect(2,2,22,22,4,4,p);
                    if("key-left".equals(kind)){line(canvas,17,12,7,12);line(canvas,11,8,7,12,11,16);}
                    else {line(canvas,7,12,17,12);line(canvas,13,8,17,12,13,16);}break;
                case "grid":for(int y=3;y<=14;y+=11)for(int x=3;x<=14;x+=11)canvas.drawRoundRect(x,y,x+7,y+7,1,1,p);break;
                case "close":line(canvas,6,6,18,18);line(canvas,18,6,6,18);break;
                case "tv-pause":case "tv-play":
                    p.setStyle(Paint.Style.FILL);p.setColor(PINK);canvas.drawCircle(12,12,12,p);
                    p.setStyle(Paint.Style.STROKE);p.setColor(Color.WHITE);
                    if("tv-pause".equals(kind)){line(canvas,9,7,9,17);line(canvas,15,7,15,17);}
                    else line(canvas,9,6,18,12,9,18,9,6);break;
                case "pause":line(canvas,8,5,8,19);line(canvas,16,5,16,19);break;
                case "play":line(canvas,7,4,20,12,7,20,7,4);break;
                case "previous":line(canvas,5,5,5,19);line(canvas,19,5,8,12,19,19,19,5);break;
                case "next":line(canvas,19,5,19,19);line(canvas,5,5,16,12,5,19,5,5);break;
                case "back":line(canvas,15,4,7,12,15,20);break;
                case "subtitle":canvas.drawRoundRect(3,4,21,19,3,3,p);line(canvas,7,9,17,9);line(canvas,7,14,12,14);line(canvas,15,14,17,14);break;
                case "episodes":for(int y=6;y<=18;y+=6){canvas.drawCircle(4,y,.6f,p);line(canvas,9,y,21,y);}break;
                case "more":p.setStyle(Paint.Style.FILL);for(int x=5;x<=19;x+=7)canvas.drawCircle(x,12,1.8f,p);break;
                case "star":Path star=new Path();for(int i=0;i<10;i++){double a=-Math.PI/2+i*Math.PI/5;float rad=i%2==0?10:4.6f;float x=12+(float)Math.cos(a)*rad,y=12+(float)Math.sin(a)*rad;if(i==0)star.moveTo(x,y);else star.lineTo(x,y);}star.close();canvas.drawPath(star,p);break;
                case "speed":canvas.drawArc(3,3,21,21,145,250,false,p);line(canvas,12,12,17,7);break;
                case "rotate":case "fullscreen":line(canvas,3,9,3,3,9,3);line(canvas,15,3,21,3,21,9);line(canvas,21,15,21,21,15,21);line(canvas,9,21,3,21,3,15);break;
                case "restart":canvas.drawArc(4,4,21,21,-90,300,false,p);line(canvas,5,3,4,9,10,8);break;
            }
            canvas.restoreToCount(save);
        }
        @Override public void setAlpha(int alpha){p.setAlpha(alpha);}
        @Override public void setColorFilter(ColorFilter colorFilter){p.setColorFilter(colorFilter);}
        @Override public int getOpacity(){return PixelFormat.TRANSLUCENT;}
    }
}
