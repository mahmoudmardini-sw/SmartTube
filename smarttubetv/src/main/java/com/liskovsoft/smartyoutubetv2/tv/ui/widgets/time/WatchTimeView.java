package com.liskovsoft.smartyoutubetv2.tv.ui.widgets.time;

import android.annotation.SuppressLint;
import android.content.Context;
import android.util.AttributeSet;
import android.view.View;
import android.widget.TextView;

import com.liskovsoft.smartyoutubetv2.common.misc.TickleManager;
import com.liskovsoft.smartyoutubetv2.common.misc.TickleManager.TickleListener;
import com.liskovsoft.smartyoutubetv2.common.prefs.FamilyControlData;
import com.liskovsoft.smartyoutubetv2.common.prefs.TimeLimitData;

import java.util.Locale;

/**
 * Small self-updating badge inside the player controls: remaining daily watch time
 * for a family-control restricted account ("⏳ 1:20"). Hides itself when not
 * applicable (no family control, no daily limit or time already used up).
 * <p>
 * Updates itself through {@link TickleManager} (same pattern as {@link DateTimeView}),
 * so no glue/presenter wiring is needed beyond visibility mirroring.
 */
@SuppressLint("AppCompatCustomView")
public class WatchTimeView extends TextView implements TickleListener {
    private TickleManager mTickleManager;

    public WatchTimeView(Context context) {
        super(context);
        init();
    }

    public WatchTimeView(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    public WatchTimeView(Context context, AttributeSet attrs, int defStyleAttr) {
        super(context, attrs, defStyleAttr);
        init();
    }

    private void init() {
        mTickleManager = TickleManager.instance();
        updateListener();
    }

    private void updateListener() {
        if (getVisibility() == View.VISIBLE) {
            mTickleManager.addListener(this);
        } else {
            mTickleManager.removeListener(this);
        }
    }

    @Override
    public void setVisibility(int visibility) {
        super.setVisibility(visibility);

        updateListener();
    }

    @Override
    public void onTickle() {
        if (getVisibility() != View.VISIBLE) {
            return;
        }

        String text = formatRemaining();

        if (text == null) {
            // Not applicable right now: hide until the next visibility cycle.
            setVisibility(View.GONE);
            return;
        }

        setText(text);
    }

    private String formatRemaining() {
        Context context = getContext();

        if (!FamilyControlData.instance(context).isFamilyControlEnabled()) {
            return null;
        }

        long remainingSec = TimeLimitData.instance(context).getTotalRemainingSeconds();

        if (remainingSec == Long.MAX_VALUE || remainingSec <= 0) {
            return null;
        }

        long hours = remainingSec / 3600;
        long minutes = (remainingSec % 3600) / 60;

        return String.format(Locale.US, "⏳ %d:%02d", hours, minutes);
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();

        // Player has been closed
        mTickleManager.removeListener(this);
    }
}
