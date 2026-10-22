package family.libretv.tv;

import android.content.Context;
import android.graphics.Color;
import android.text.TextUtils;
import android.view.Gravity;
import android.widget.*;

/** One remote focus target per poster; child labels/images never intercept D-pad navigation. */
final class PosterCard extends LinearLayout {
    Runnable onFocused;
    final ImageView image;
    final TextView title, source, metadata;
    PosterCard(Context context, int posterHeight) {
        super(context);
        setOrientation(VERTICAL); setPadding(dp(5), dp(5), dp(5), dp(10));
        setFocusable(true); setFocusableInTouchMode(true); setClickable(true);
        setDescendantFocusability(FOCUS_BLOCK_DESCENDANTS);
        setBackgroundResource(R.drawable.poster_card);
        image = new ImageView(context); image.setScaleType(ImageView.ScaleType.CENTER_CROP);
        image.setBackgroundColor(Color.rgb(29, 42, 58));
        addView(image, new LayoutParams(-1, posterHeight));
        title = text(18, Color.WHITE); title.setMaxLines(1); title.setEllipsize(TextUtils.TruncateAt.END);
        source = text(14, Color.rgb(107, 226, 195)); source.setMaxLines(1); source.setEllipsize(TextUtils.TruncateAt.END);
        metadata = text(13, Color.rgb(155, 173, 192)); metadata.setMaxLines(1); metadata.setEllipsize(TextUtils.TruncateAt.END);
        setOnFocusChangeListener((v, focused) -> {
            animate().scaleX(focused ? 1.025f : 1f).scaleY(focused ? 1.025f : 1f).setDuration(120).start();
            setTranslationZ(focused ? dp(8) : 0);
            if (focused && onFocused != null) onFocused.run();
        });
    }
    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private TextView text(int size, int color) {
        TextView label = new TextView(getContext()); label.setTextSize(size); label.setTextColor(color);
        label.setGravity(Gravity.START); label.setPadding(dp(7), dp(4), dp(7), 0);
        addView(label, new LayoutParams(-1, -2)); return label;
    }
}
