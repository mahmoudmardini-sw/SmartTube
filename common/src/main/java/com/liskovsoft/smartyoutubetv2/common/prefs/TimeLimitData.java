package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.SystemClock;
import android.util.Pair;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs.ProfileChangeListener;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Daily watch time limits (only enforced inside child mode). Per-account.
 */
public class TimeLimitData implements ProfileChangeListener {
    /**
     * Marker written as the first field so future field additions can tell layouts apart.
     * Data written before it starts directly with the daily limit. A number can never be mistaken
     * for the marker, so the offset stays reliable for every layout written so far.
     */
    private static final String FORMAT_VERSION = "v2";
    private static final int FIELD_DAILY_LIMIT = 0;
    private static final int FIELD_CHANNEL_LIMITS = 1;
    private static final int FIELD_USAGE_DATE = 2;
    private static final int FIELD_TOTAL_USAGE = 3;
    private static final int FIELD_CHANNEL_USAGE = 4;
    private static final int FIELD_TRUSTED_WALL_MS = 5;
    private static final int FIELD_LAST_ELAPSED_MS = 6;
    /**
     * Wall-clock drift tolerated before a jump is treated as tampering (NTP corrections,
     * DST, the user fixing the clock by a few minutes).
     */
    private static final long MAX_CLOCK_DRIFT_MS = 5 * 60 * 1000L;
    @SuppressLint("StaticFieldLeak")
    private static volatile TimeLimitData sInstance;
    private final AppPrefs mPrefs;
    private final Runnable mPersistPendingInt = this::persistPendingInt;
    private String mPendingKey;
    private String mPendingPayload;
    private int mDailyLimitMinutes;
    private List<ChannelLimit> mChannelLimits;
    private String mUsageDate;
    private long mTotalUsageSec;
    private Map<String, Long> mChannelUsageSec;
    /** Last trusted wall-clock time (epoch ms) and the elapsedRealtime anchor it was taken at. */
    private long mTrustedWallMs;
    private long mLastElapsedMs;

    public static class ChannelLimit {
        public String channelId;
        public String channelName;
        public int limitMinutes;

        public ChannelLimit(String channelId, String channelName, int limitMinutes) {
            this.channelId = channelId;
            this.channelName = channelName;
            this.limitMinutes = limitMinutes;
        }

        public static ChannelLimit fromString(String specs) {
            String[] split = Helpers.splitObj(specs);

            if (split == null || split.length != 3) {
                return null;
            }

            return new ChannelLimit(
                    Helpers.parseStr(split[0]),
                    Helpers.parseStr(split[1]),
                    Helpers.parseInt(split[2], 0));
        }

        @Override
        public String toString() {
            return Helpers.mergeObj(channelId, channelName, limitMinutes);
        }

        @Override
        public boolean equals(@Nullable Object obj) {
            if (obj instanceof ChannelLimit) {
                ChannelLimit other = (ChannelLimit) obj;

                return Helpers.equals(channelId, other.channelId);
            }

            return super.equals(obj);
        }

        @Override
        public int hashCode() {
            return channelId != null ? channelId.hashCode() : 0;
        }
    }

    private TimeLimitData(Context context) {
        mPrefs = AppPrefs.instance(context);
        mPrefs.addListener(this);
        restoreState();
    }

    public static TimeLimitData instance(Context context) {
        if (sInstance == null) {
            synchronized (TimeLimitData.class) {
                if (sInstance == null) {
                    sInstance = new TimeLimitData(context.getApplicationContext());
                }
            }
        }

        return sInstance;
    }

    public int getDailyLimitMinutes() {
        return mDailyLimitMinutes;
    }

    public void setDailyLimitMinutes(int minutes) {
        mDailyLimitMinutes = Math.max(0, minutes);
        persistState();
    }

    public void setChannelLimit(String channelId, String channelName, int limitMinutes) {
        if (Helpers.allNulls(channelId, channelName) || containsDelimiters(channelId) || containsDelimiters(channelName)) {
            return;
        }

        ChannelLimit limit = new ChannelLimit(channelId, channelName, limitMinutes);
        mChannelLimits.remove(limit);

        if (limitMinutes > 0) {
            mChannelLimits.add(0, limit);
        }

        persistState();
    }

    public void removeChannelLimit(String channelId, String channelName) {
        if (Helpers.allNulls(channelId, channelName) || mChannelLimits.isEmpty()) {
            return;
        }

        mChannelLimits.remove(new ChannelLimit(channelId, channelName, 0));

        persistState();
    }

    public int getChannelLimitMinutes(String channelId) {
        if (channelId == null) {
            return 0;
        }

        for (ChannelLimit limit : mChannelLimits) {
            if (channelId.equals(limit.channelId)) {
                return limit.limitMinutes;
            }
        }

        return 0;
    }

    public List<Pair<String, String>> getChannelLimits() {
        List<Pair<String, String>> result = new ArrayList<>();

        for (ChannelLimit limit : mChannelLimits) {
            result.add(new Pair<>(limit.channelId, limit.channelName));
        }

        return result;
    }

    public int getChannelLimitCount() {
        return mChannelLimits.size();
    }

    public boolean isEmpty() {
        return mChannelLimits.isEmpty();
    }

    public void clearChannelLimits() {
        mChannelLimits.clear();
        persistState();
    }

    // Usage tracking

    public synchronized void addWatchSeconds(String channelId, long seconds) {
        if (seconds <= 0) {
            return;
        }

        resetIfNeeded();

        mTotalUsageSec += seconds;

        if (channelId != null) {
            Long current = mChannelUsageSec.get(channelId);
            mChannelUsageSec.put(channelId, (current != null ? current : 0L) + seconds);
        }

        persistState();
    }

    public long getTotalWatchSeconds() {
        resetIfNeeded();
        return mTotalUsageSec;
    }

    public long getChannelWatchSeconds(String channelId) {
        resetIfNeeded();

        Long current = channelId != null ? mChannelUsageSec.get(channelId) : null;
        return current != null ? current : 0L;
    }

    public boolean isTotalLimitReached() {
        int limitMinutes = getDailyLimitMinutes();
        return limitMinutes > 0 && getTotalWatchSeconds() >= limitMinutes * 60L;
    }

    public boolean isChannelLimitReached(String channelId) {
        int limitMinutes = getChannelLimitMinutes(channelId);
        return limitMinutes > 0 && getChannelWatchSeconds(channelId) >= limitMinutes * 60L;
    }

    /**
     * Seconds left before the daily total limit is reached
     * ({@link Long#MAX_VALUE} when no limit is configured).
     */
    public long getTotalRemainingSeconds() {
        int limitMinutes = getDailyLimitMinutes();

        if (limitMinutes <= 0) {
            return Long.MAX_VALUE;
        }

        return limitMinutes * 60L - getTotalWatchSeconds();
    }

    /**
     * Seconds left before the per-channel limit is reached
     * ({@link Long#MAX_VALUE} when no limit is configured for the channel).
     */
    public long getChannelRemainingSeconds(String channelId) {
        int limitMinutes = getChannelLimitMinutes(channelId);

        if (limitMinutes <= 0) {
            return Long.MAX_VALUE;
        }

        return limitMinutes * 60L - getChannelWatchSeconds(channelId);
    }

    private void resetIfNeeded() {
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date(updateTrustedClock()));

        if (!today.equals(mUsageDate)) {
            synchronized (this) {
                if (!today.equals(mUsageDate)) {
                    mUsageDate = today;
                    mTotalUsageSec = 0;
                    mChannelUsageSec = new HashMap<>();
                    persistState();
                }
            }
        }
    }

    /**
     * Wall-clock time the daily reset may trust.
     * <p>
     * The system clock is user-writable: moving it to tomorrow would otherwise reset the
     * daily limit (and moving it back would reset it again). Once anchored, time is
     * projected with {@link SystemClock#elapsedRealtime()} (which keeps counting during
     * sleep) and wall-clock jumps beyond {@link #MAX_CLOCK_DRIFT_MS} are ignored.
     * <p>
     * A reboot resets {@code elapsedRealtime}, so the first call after it re-anchors to the
     * wall clock. That still catches the common in-session tampering; a clock change made
     * while the device was off cannot be told apart from the device being off for that long.
     */
    private synchronized long updateTrustedClock() {
        long nowWallMs = System.currentTimeMillis();
        long nowElapsedMs = SystemClock.elapsedRealtime();

        if (mTrustedWallMs <= 0 || nowElapsedMs < mLastElapsedMs) {
            // First run or reboot: there is no monotonic reference to compare against.
            mTrustedWallMs = nowWallMs;
            mLastElapsedMs = nowElapsedMs;
            return mTrustedWallMs;
        }

        long projectedWallMs = mTrustedWallMs + (nowElapsedMs - mLastElapsedMs);

        if (Math.abs(nowWallMs - projectedWallMs) > MAX_CLOCK_DRIFT_MS) {
            // Wall clock was moved: keep the monotonic projection as the trusted time.
            mTrustedWallMs = projectedWallMs;
        } else {
            // Normal drift: re-anchor to the wall clock.
            mTrustedWallMs = nowWallMs;
        }

        mLastElapsedMs = nowElapsedMs;
        return mTrustedWallMs;
    }

    /**
     * Storage delimiters must not appear inside channel fields: they'd split the record
     * and shift the usage columns on the next load.
     */
    private static boolean containsDelimiters(String value) {
        return value != null && (value.contains("%OB%") || value.contains("%AR%")
                || value.contains("%PR%") || value.contains("&vi;"));
    }

    public void persistNow() {
        // Cancel a pending debounced write: it was captured with an older state and would
        // silently overwrite this one seconds later.
        Utils.removeCallbacks(mPersistPendingInt);
        mPendingKey = null;
        mPendingPayload = null;
        mPrefs.setData(mPrefs.getTimeLimitDataKey(), buildPayload());
    }

    private void persistState() {
        // Freeze the key and the payload now (see FamilyControlData).
        mPendingKey = mPrefs.getTimeLimitDataKey();
        mPendingPayload = buildPayload();
        Utils.postDelayed(mPersistPendingInt, 10_000);
    }

    private void persistPendingInt() {
        if (mPendingKey != null && mPendingPayload != null) {
            mPrefs.setData(mPendingKey, mPendingPayload);
        }
        mPendingKey = null;
        mPendingPayload = null;
    }

    private String buildPayload() {
        return Helpers.mergeData(
                FORMAT_VERSION, mDailyLimitMinutes, mChannelLimits, mUsageDate, mTotalUsageSec, mChannelUsageSec,
                mTrustedWallMs, mLastElapsedMs);
    }

    private synchronized void restoreState() {
        String data = mPrefs.getTimeLimitData();

        String[] split = Helpers.splitData(data);

        int o = FORMAT_VERSION.equals(Helpers.parseStr(split, 0)) ? 1 : 0;

        mDailyLimitMinutes = Helpers.parseInt(split, o + FIELD_DAILY_LIMIT, 0);
        mChannelLimits = Helpers.parseList(split, o + FIELD_CHANNEL_LIMITS, ChannelLimit::fromString);
        mUsageDate = Helpers.parseStr(split, o + FIELD_USAGE_DATE);
        mTotalUsageSec = Helpers.parseLong(split, o + FIELD_TOTAL_USAGE, 0);
        mChannelUsageSec = Helpers.parseMap(split, o + FIELD_CHANNEL_USAGE, Helpers::parseStr, Helpers::parseLong);
        mTrustedWallMs = Helpers.parseLong(split, o + FIELD_TRUSTED_WALL_MS, 0);
        mLastElapsedMs = Helpers.parseLong(split, o + FIELD_LAST_ELAPSED_MS, 0);

        if (mChannelLimits == null) {
            mChannelLimits = new ArrayList<>();
        }

        if (mChannelUsageSec == null) {
            mChannelUsageSec = new HashMap<>();
        }
    }

    @Override
    public void onProfileChanged() {
        // Flush the old profile's pending write first instead of dropping it.
        persistPendingInt();
        Utils.removeCallbacks(mPersistPendingInt);
        restoreState();
    }
}
