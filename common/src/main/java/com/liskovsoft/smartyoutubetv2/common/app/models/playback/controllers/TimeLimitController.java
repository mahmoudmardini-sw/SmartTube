package com.liskovsoft.smartyoutubetv2.common.app.models.playback.controllers;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.BasePlayerController;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.FamilyControlData;
import com.liskovsoft.smartyoutubetv2.common.prefs.MasterPasswordData;
import com.liskovsoft.smartyoutubetv2.common.prefs.TimeLimitData;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Date;
import java.util.Locale;

/**
 * Enforces daily watch time limits (total and per-channel) inside family control only.
 * <p>
 * Tracks the actual playback time via {@link #onPlay}/{@link #onPause}/{@link #onTickle} and
 * stops playback with a one-time notification when a limit is reached. At that moment it also
 * offers the parent a PIN-gated way to grant a one-time time bonus or to temporarily pause
 * family control.
 */
public class TimeLimitController extends BasePlayerController {
    private static final long LOW_TIME_WARNING_SEC = 5 * 60L;

    private long mPlayStartMs;
    private String mTrackingChannelId;
    private boolean mWasLimitReached;
    private boolean mWasLowTimeWarningShown;

    @Override
    public void onPlay() {
        if (!isActive() || getPlayer() == null) {
            return;
        }

        if (isLimitReached()) {
            stopPlayback();
            return;
        }

        if (mPlayStartMs <= 0) {
            mPlayStartMs = System.currentTimeMillis();
            mTrackingChannelId = getChannelId();
        }
    }

    @Override
    public void onPause() {
        stopTracking();
    }

    @Override
    public void onPlayEnd() {
        stopTracking();

        if (isActive() && isLimitReached()) {
            stopPlayback();
        }
    }

    @Override
    public void onEngineReleased() {
        stopTracking();
    }

    @Override
    public void onVideoLoaded(Video item) {
        stopTracking();

        if (isActive() && isLimitReached()) {
            stopPlayback();
        }
    }

    @Override
    public void onTickle() {
        // A pause may have expired mid-playback: re-apply the restrictions right away.
        FamilyControlData.instance(getContext()).checkPauseExpiry();

        if (!isActive() || getPlayer() == null) {
            return;
        }

        flushUsage();

        // Detect the transition from "not blocked" to "blocked" and notify only once.
        boolean reached = isLimitReached();
        if (reached) {
            boolean firstTime = !mWasLimitReached;
            mWasLimitReached = true;
            stopPlayback();

            if (firstTime) {
                MessageHelpers.showLongMessage(getContext(), R.string.time_limit_reached);
                showSnoozeDialog();
            }
        } else {
            mWasLimitReached = false;
            warnOnLowTimeIfNeeded();
        }
    }

    /**
     * Gentle heads-up shortly before the closest limit (total or per-channel) hits,
     * instead of an abrupt cutoff. Shown once per window.
     */
    private void warnOnLowTimeIfNeeded() {
        TimeLimitData data = TimeLimitData.instance(getContext());

        long remainingSec = Math.min(data.getTotalRemainingSeconds(), data.getChannelRemainingSeconds(getChannelId()));

        if (remainingSec == Long.MAX_VALUE || remainingSec > LOW_TIME_WARNING_SEC) {
            mWasLowTimeWarningShown = false;
            return;
        }

        if (!mWasLowTimeWarningShown) {
            mWasLowTimeWarningShown = true;
            MessageHelpers.showLongMessage(getContext(), getContext().getString(
                    R.string.time_limit_ending_soon, Math.max(1, (int) Math.ceil(remainingSec / 60.0))));
        }
    }

    private boolean isActive() {
        return FamilyControlData.instance(getContext()).isFamilyControlEnabled();
    }

    private boolean isLimitReached() {
        TimeLimitData data = TimeLimitData.instance(getContext());

        return data.isTotalLimitReached() || data.isChannelLimitReached(getChannelId());
    }

    private void stopPlayback() {
        if (getPlayer() == null) {
            return;
        }

        stopTracking();
        getPlayer().setPlayWhenReady(false);
        getPlayer().setTitle(getContext().getString(R.string.time_limit_reached));
        getPlayer().showOverlay(true);
    }

    /**
     * Offers the parent a PIN-gated way to extend the watch time or to pause family control.
     * Shown once per blocking episode (the transition detected in {@link #onTickle}).
     */
    private void showSnoozeDialog() {
        if (!MasterPasswordData.instance(getContext()).hasPin()) {
            return; // nothing to verify against, so don't tease the option
        }

        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());

        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.extend_with_password), optionItem -> {
            dialog.closeDialog();
            askPinAndShowOptions();
        }));

        dialog.showDialog(getContext().getString(R.string.time_limit_reached));
    }

    private void askPinAndShowOptions() {
        SimpleEditDialog.showPassword(
                getContext(),
                getContext().getString(R.string.enter_pin),
                null,
                pin -> {
                    if (!MasterPasswordData.instance(getContext()).isPinValid(pin)) {
                        return false; // the dialog shows "wrong PIN" and allows a retry
                    }

                    showSnoozeOptions();
                    return true;
                });
    }

    private void showSnoozeOptions() {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());

        appendPauseButton(dialog, R.string.pause_family_control_hour, 1);
        appendPauseButton(dialog, R.string.pause_family_control_3hours, 3);
        appendPauseButton(dialog, R.string.pause_family_control_until_midnight, -1);
        appendPauseButton(dialog, R.string.pause_family_control_day, 24);

        dialog.showDialog(getContext().getString(R.string.time_limit_reached));
    }

    private void appendPauseButton(AppDialogPresenter dialog, int titleResId, int hours) {
        dialog.appendSingleButton(UiOptionItem.from(getContext().getString(titleResId), optionItem -> {
            dialog.closeDialog();

            long untilMs = hours > 0
                    ? System.currentTimeMillis() + hours * 3_600_000L
                    : untilMidnightMs();

            FamilyControlData.instance(getContext()).pauseFamilyControl(untilMs);
            MessageHelpers.showLongMessage(getContext(), getContext().getString(
                    R.string.family_control_paused_until, formatTime(untilMs)));
            resumePlayback();
        }));
    }

    private void resumePlayback() {
        if (getPlayer() == null) {
            return;
        }

        mWasLimitReached = false;
        mWasLowTimeWarningShown = false; // fresh window after a snooze/pause grant
        getPlayer().setPlayWhenReady(true);
    }

    private static long untilMidnightMs() {
        Calendar calendar = Calendar.getInstance();
        calendar.add(Calendar.DAY_OF_MONTH, 1);
        calendar.set(Calendar.HOUR_OF_DAY, 0);
        calendar.set(Calendar.MINUTE, 0);
        calendar.set(Calendar.SECOND, 0);
        calendar.set(Calendar.MILLISECOND, 0);

        return calendar.getTimeInMillis();
    }

    private static String formatTime(long timeMs) {
        return new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(timeMs));
    }

    private String getChannelId() {
        Video video = getVideo();
        return video != null ? video.channelId : null;
    }

    private void flushUsage() {
        if (mPlayStartMs <= 0) {
            return;
        }

        long now = System.currentTimeMillis();
        long deltaMs = now - mPlayStartMs;
        mPlayStartMs = now;

        if (deltaMs > 0) {
            TimeLimitData.instance(getContext()).addWatchSeconds(mTrackingChannelId, deltaMs / 1_000);
        }
    }

    private void stopTracking() {
        flushUsage();
        mPlayStartMs = 0;
        mTrackingChannelId = null;
    }
}
