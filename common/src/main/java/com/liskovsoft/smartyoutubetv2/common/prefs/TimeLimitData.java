package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
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
    @SuppressLint("StaticFieldLeak")
    private static TimeLimitData sInstance;
    private final AppPrefs mPrefs;
    private final Runnable mPersistStateInt = this::persistStateInt;
    private int mDailyLimitMinutes;
    private List<ChannelLimit> mChannelLimits;
    private String mUsageDate;
    private long mTotalUsageSec;
    private Map<String, Long> mChannelUsageSec;

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
    }

    private TimeLimitData(Context context) {
        mPrefs = AppPrefs.instance(context);
        mPrefs.addListener(this);
        restoreState();
    }

    public static TimeLimitData instance(Context context) {
        if (sInstance == null) {
            sInstance = new TimeLimitData(context.getApplicationContext());
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
        if (Helpers.allNulls(channelId, channelName)) {
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
        String today = new SimpleDateFormat("yyyyMMdd", Locale.US).format(new Date());

        if (!today.equals(mUsageDate)) {
            mUsageDate = today;
            mTotalUsageSec = 0;
            mChannelUsageSec = new HashMap<>();
            persistState();
        }
    }

    public void persistNow() {
        Utils.post(mPersistStateInt);
    }

    private void persistState() {
        Utils.postDelayed(mPersistStateInt, 10_000);
    }

    private void persistStateInt() {
        mPrefs.setTimeLimitData(Helpers.mergeData(
                mDailyLimitMinutes, mChannelLimits, mUsageDate, mTotalUsageSec, mChannelUsageSec));
    }

    private synchronized void restoreState() {
        String data = mPrefs.getTimeLimitData();

        String[] split = Helpers.splitData(data);

        mDailyLimitMinutes = Helpers.parseInt(split, 0, 0);
        mChannelLimits = Helpers.parseList(split, 1, ChannelLimit::fromString);
        mUsageDate = Helpers.parseStr(split, 2);
        mTotalUsageSec = Helpers.parseLong(split, 3, 0);
        mChannelUsageSec = Helpers.parseMap(split, 4, Helpers::parseStr, Helpers::parseLong);

        if (mChannelLimits == null) {
            mChannelLimits = new ArrayList<>();
        }

        if (mChannelUsageSec == null) {
            mChannelUsageSec = new HashMap<>();
        }
    }

    @Override
    public void onProfileChanged() {
        Utils.removeCallbacks(mPersistStateInt);
        restoreState();
    }
}
