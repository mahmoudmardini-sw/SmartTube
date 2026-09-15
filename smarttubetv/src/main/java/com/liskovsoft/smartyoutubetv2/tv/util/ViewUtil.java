package com.liskovsoft.smartyoutubetv2.tv.util;

import android.content.Context;
import android.graphics.Color;
import android.graphics.ColorMatrix;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.drawable.Drawable;
import android.graphics.drawable.GradientDrawable;
import android.graphics.drawable.StateListDrawable;
import android.os.Build.VERSION;
import android.text.Layout;
import android.text.TextUtils.TruncateAt;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewOutlineProvider;
import android.view.WindowManager;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;
import androidx.core.content.ContextCompat;
import androidx.leanback.widget.FocusHighlight;
import androidx.leanback.widget.ListRow;
import androidx.leanback.widget.RowPresenter;
import androidx.leanback.widget.VerticalGridView;
import com.bumptech.glide.load.engine.DiskCacheStrategy;
import com.bumptech.glide.request.RequestOptions;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.tv.R;
import com.liskovsoft.smartyoutubetv2.tv.adapter.VideoGroupObjectAdapter;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.marqueetextview.MarqueeTextView;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.marqueetextviewcompat.MarqueeTextViewCompat;
import com.liskovsoft.smartyoutubetv2.tv.ui.widgets.speedmarquee.SpeedMarquee;

public class ViewUtil {
    /**
     * Focused card zoom factor
     */
    public static final int FOCUS_ZOOM_FACTOR = FocusHighlight.ZOOM_FACTOR_SMALL;
    //public static final int FOCUS_ZOOM_FACTOR = FocusHighlight.ZOOM_FACTOR_NONE;
    /**
     * Dim focused card?
     */
    public static final boolean FOCUS_DIMMER_ENABLED = false;
    /**
     * Dim other rows in {@link RowPresenter}
     */
    public static final boolean ROW_SELECT_EFFECT_ENABLED = false;
    /**
     * Scroll continue threshold
     */
    public static final int GRID_SCROLL_CONTINUE_NUM = 10;
    public static final int ROW_SCROLL_CONTINUE_NUM = 4;

    /**
     * Checks whether text is truncated (e.g. has ... at the end)
     */
    public static boolean isTruncated(TextView textView) {
        Layout layout = textView.getLayout();
        if (layout != null) {
            int lines = layout.getLineCount();
            if (lines > 0) {
                int ellipsisCount = layout.getEllipsisCount(lines - 1);
                if (ellipsisCount > 0) {
                    return true;
                }
            }
        }

        return false;
    }

    public static void disableMarquee(TextView... textViews) {
        if (VERSION.SDK_INT <= 19 || textViews == null) { // Android 4: Broken grid layout fix
            return;
        }

        for (TextView textView : textViews) {
            textView.setEllipsize(TruncateAt.END);
            // Line below cause broken grid layout on Android 4 and older
            textView.setHorizontallyScrolling(false);

            applyMarqueeRtlParams(textView, false);
        }
    }

    /**
     * <a href="https://stackoverflow.com/questions/3332924/textview-marquee-not-working">More info</a>
     */
    public static void enableMarquee(TextView... textViews) {
        if (VERSION.SDK_INT <= 19 || textViews == null) { // Android 4: Broken grid layout fix
            return;
        }

        for (TextView textView : textViews) {
            if (ViewUtil.isTruncated(textView)) { // multiline scroll fix
                textView.setEllipsize(TruncateAt.MARQUEE);
                textView.setMarqueeRepeatLimit(-1);
                textView.setHorizontallyScrolling(true);

                // App dialog title fix.
                //textView.setSelected(true);

                applyMarqueeRtlParams(textView, true);
            }
        }
    }

    public static void applyMarqueeRtlParams(TextView textView, boolean scroll) {
        if (!Helpers.isTextRTL(textView.getText())) {
            // TextView may be reused from rtl context. Do reset.
            // NOTE: don't enable commented options because Setting item's text won't be centered.
            //textView.setTextAlignment(View.TEXT_ALIGNMENT_GRAVITY);
            textView.setTextDirection(View.TEXT_DIRECTION_LTR);
            textView.setLayoutDirection(View.LAYOUT_DIRECTION_LTR);
            //textView.setGravity(Gravity.TOP | Gravity.START);
            return;
        }

        if (scroll) {
            // Fix: right scrolling on rtl languages
            // Fix: text disappear on rtl languages
            textView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_END);
            textView.setTextDirection(View.TEXT_DIRECTION_RTL);
            textView.setLayoutDirection(View.LAYOUT_DIRECTION_RTL);
            textView.setGravity(Gravity.START);
        } else {
            // Fix: text disappear on rtl languages
            textView.setTextAlignment(View.TEXT_ALIGNMENT_VIEW_START);
        }
    }

    public static void setTextScrollSpeed(TextView textView, float speed) {
        if (VERSION.SDK_INT <= 19) { // Android 4: Broken grid layout fix
            return;
        }

        if (textView instanceof MarqueeTextViewCompat) {
            ((MarqueeTextViewCompat) textView).setMarqueeSpeedFactor(speed);
        } else if (textView instanceof MarqueeTextView) {
            ((MarqueeTextView) textView).setMarqueeSpeedFactor(speed);
        } else if (textView instanceof SpeedMarquee) {
            ((SpeedMarquee) textView).setSpeed(speed);
        }
    }

    public static void enableView(View view, boolean enabled) {
        if (view != null) {
            view.setVisibility(enabled ? View.VISIBLE : View.GONE);
        }
    }

    public static void setDimensions(View view, int width, int height) {
        if (view != null) {
            ViewGroup.LayoutParams lp = view.getLayoutParams();

            if (lp != null) {
                if (width > 0) {
                    lp.width = width;
                }
                if (height > 0) {
                    lp.height = height;
                }
                view.setLayoutParams(lp);
            }
        }
    }

    public static boolean isListRowEmpty(Object obj) {
        if (obj instanceof ListRow) {
            ListRow row = (ListRow) obj;
            VideoGroupObjectAdapter adapter = (VideoGroupObjectAdapter) row.getAdapter();
            return adapter == null || adapter.isEmpty();
        }

        return true;
    }

    public static RequestOptions glideOptions() {
        return new RequestOptions()
                .diskCacheStrategy(DiskCacheStrategy.NONE) // ensure start animation from beginning
                .skipMemoryCache(true); // ensure start animation from beginning
    }

    public static void enableTransparentDialog(Context context, View rootView) {
        if (context == null || rootView == null || VERSION.SDK_INT <= 19) {
            return;
        }

        // Transparent overlays sit on top of live content: no dim behind them.
        if (context instanceof android.app.Activity) {
            ((android.app.Activity) context).getWindow().clearFlags(WindowManager.LayoutParams.FLAG_DIM_BEHIND);
        }

        // Usually null. Present only on parent fragment.
        View mainContainer = rootView.findViewById(R.id.settings_preference_fragment_container);
        View mainFrame = rootView.findViewById(R.id.main_frame);
        View itemsContainer = rootView.findViewById(R.id.list);
        View title = rootView.findViewById(R.id.decor_title_container);
        int transparent = ContextCompat.getColor(context, R.color.transparent);
        int semiTransparent = ContextCompat.getColor(context, R.color.semi_grey);

        // Disable shadow outline on parent fragment
        if (mainContainer instanceof FrameLayout && VERSION.SDK_INT >= 21) {
            // ViewOutlineProvider: NoClassDefFoundError on API 19
            mainContainer.setOutlineProvider(ViewOutlineProvider.BACKGROUND);
        }
        if (mainFrame instanceof LinearLayout) {
            mainFrame.setBackgroundColor(transparent);

            // The floating rounded panel adds margins; transparent overlays should stay full-size.
            ViewGroup.LayoutParams layoutParams = mainFrame.getLayoutParams();
            if (layoutParams instanceof ViewGroup.MarginLayoutParams) {
                ((ViewGroup.MarginLayoutParams) layoutParams).setMargins(0, 0, 0, 0);
                mainFrame.setLayoutParams(layoutParams);
            }
        }
        if (itemsContainer instanceof VerticalGridView) {
            // Set background for individual buttons in the list.
            // This is the only way to do this because items haven't been added yet to the container.
            ((VerticalGridView) itemsContainer).setOnChildLaidOutListener(
                    (parent, view, position, id) -> view.setBackground(createFocusPill(context))
            );
        }
        if (title instanceof FrameLayout) {
            title.setBackgroundColor(transparent);
            title.setVisibility(View.GONE);
        }
    }

    public static void enableLeftDialog(Context context, View rootView) {
        if (context == null || rootView == null || VERSION.SDK_INT <= 19) {
            return;
        }

        // Usually null. Present only on parent fragment.
        View mainContainer = rootView.findViewById(R.id.settings_preference_fragment_container);

        if (mainContainer instanceof FrameLayout) {
            ((FrameLayout.LayoutParams) mainContainer.getLayoutParams()).gravity = Gravity.START;
        }
    }

    public static void makeMonochrome(ImageView iconView) {
        ColorMatrix colorMatrix = new ColorMatrix();
        colorMatrix.setSaturation(0);
        ColorMatrixColorFilter filter = new ColorMatrixColorFilter(colorMatrix);
        iconView.setColorFilter(filter);
    }

    public static void setGravity(View view, int gravity) {
        if (view == null) {
            return;
        }

        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp instanceof FrameLayout.LayoutParams) {
            FrameLayout.LayoutParams flp = (FrameLayout.LayoutParams) lp;
            flp.gravity = gravity;
            view.setLayoutParams(flp);
        }
    }

    public static void setWidth(View view, int width) {
        if (view == null) {
            return;
        }

        ViewGroup.LayoutParams lp = view.getLayoutParams();
        if (lp != null) {
            lp.width = width;
            view.setLayoutParams(lp);
        }
    }

    public static void setPadding(View view, int padding) {
        if (view == null) {
            return;
        }

        view.setPadding(padding, view.getPaddingTop(), padding, view.getPaddingBottom());
    }

    /**
     * Fix SDK 28+ GridLayoutManager broken navigation when using Japanese fonts
     */
    public static void fixApi28BrokenGridNavigation(TextView textView) {
        if (VERSION.SDK_INT >= 28) {
            // 1. Disable dynamic line spacing for special characters (prevents expansion for CJK glyphs)
            textView.setFallbackLineSpacing(false);
        }

        // 2. Remove system font padding to ensure consistent baseline and height
        textView.setIncludeFontPadding(false);

        // 3. Add fixed internal padding to create a "safe zone" for both Latin and Japanese text
        int paddingExtra = (int) (4 * textView.getResources().getDisplayMetrics().density);
        textView.setPadding(textView.getPaddingLeft(), paddingExtra, textView.getPaddingRight(), paddingExtra);
    }

    /**
     * Rounded focus pill tinted with the active theme accent (subtle alpha),
     * replacing the old flat grey rectangle on focused dialog items.
     */
    private static Drawable createFocusPill(Context context) {
        int radius = (int) (12 * context.getResources().getDisplayMetrics().density);

        GradientDrawable focused = new GradientDrawable();
        focused.setCornerRadius(radius);
        focused.setColor(getThemeAccent(context));
        focused.setAlpha(46); // ~18% tint: visible but not loud

        GradientDrawable normal = new GradientDrawable();
        normal.setCornerRadius(radius);
        normal.setColor(Color.TRANSPARENT);

        StateListDrawable background = new StateListDrawable();
        background.addState(new int[]{android.R.attr.state_focused}, focused);
        background.addState(new int[0], normal);

        return background;
    }

    /**
     * Accent color of the active theme (follows the selected color scheme).
     */
    public static int getThemeAccent(Context context) {
        TypedValue typedValue = new TypedValue();

        if (context.getTheme().resolveAttribute(android.R.attr.colorAccent, typedValue, true)) {
            return typedValue.data;
        }

        return ContextCompat.getColor(context, R.color.semi_grey);
    }
}
