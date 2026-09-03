package com.liskovsoft.smartyoutubetv2.common.app.presenters.settings;

import android.content.Context;
import android.util.Pair;

import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.OptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.prefs.FamilyControlData;
import com.liskovsoft.smartyoutubetv2.common.prefs.MasterPasswordData;
import com.liskovsoft.smartyoutubetv2.common.prefs.TimeLimitData;
import com.liskovsoft.smartyoutubetv2.common.utils.AppDialogUtil;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * Family control settings screen.
 * <p>
 * Sections:
 * <ul>
 *     <li>Family control — enable/disable (per account)</li>
 *     <li>Watch time limits — daily + per-channel (per account, shown only when enabled)</li>
 *     <li>Master password — PIN + app access lock (device-wide)</li>
 * </ul>
 */
public class FamilyControlSettingsPresenter extends BasePresenter<Void> {
    private FamilyControlSettingsPresenter(Context context) {
        super(context);
    }

    public static FamilyControlSettingsPresenter instance(Context context) {
        return new FamilyControlSettingsPresenter(context);
    }

    public void show() {
        AppDialogPresenter presenter = AppDialogPresenter.instance(getContext());
        FamilyControlData familyControl = FamilyControlData.instance(getContext());
        boolean configured = familyControl.isFamilyControlConfigured();

        // Section 1: Family control (per account)
        presenter.appendHeader(getContext().getString(R.string.family_control));

        if (configured && familyControl.isPauseActive()) {
            appendPauseStatus(presenter, familyControl);
        }

        appendFamilyControlButton(presenter, familyControl);

        // Section 2: Watch time limits (per account, only meaningful when enabled)
        if (configured) {
            presenter.appendHeader(getContext().getString(R.string.watch_time_limits));
            appendUsageStatus(presenter);
            appendDailyLimit(presenter);
            appendChannelLimits(presenter);
        }

        // Section 3: Master password (device-wide)
        presenter.appendHeader(getContext().getString(R.string.master_password));
        appendPinButton(presenter);
        appendAppLock(presenter);

        presenter.showDialog(getContext().getString(R.string.family_control));
    }

    /**
     * Status line + early-resume action shown while a parent-granted pause is active.
     */
    private void appendPauseStatus(AppDialogPresenter presenter, FamilyControlData data) {
        String until = new SimpleDateFormat("HH:mm", Locale.getDefault()).format(new Date(data.getPausedUntilMs()));

        presenter.appendHeader(getContext().getString(R.string.family_control_paused_until, until));

        presenter.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.cancel_pause), optionItem -> {
            data.cancelPause();
            presenter.closeDialog();
        }));
    }

    /**
     * Today's watch time vs the daily limit, so the parent knows where things stand.
     */
    private void appendUsageStatus(AppDialogPresenter presenter) {
        TimeLimitData data = TimeLimitData.instance(getContext());

        String usage = formatDuration(data.getTotalWatchSeconds());
        int dailyLimitMinutes = data.getDailyLimitMinutes();

        String status = dailyLimitMinutes > 0
                ? getContext().getString(R.string.watch_time_today, usage, formatDuration(dailyLimitMinutes * 60L))
                : getContext().getString(R.string.watch_time_today_unlimited, usage);

        presenter.appendHeader(status);
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;

        return String.format(Locale.US, "%d:%02d", hours, minutes);
    }

    private void appendFamilyControlButton(AppDialogPresenter presenter, FamilyControlData data) {
        // Use the "configured" state so the button keeps saying "Disable" while a pause is active.
        boolean enabled = data.isFamilyControlConfigured();
        int titleResId = enabled ? R.string.disable_family_control : R.string.enable_family_control;

        presenter.appendSingleButton(UiOptionItem.from(getContext().getString(titleResId), optionItem -> {
            if (enabled) {
                // Disable: restore the UI state.
                data.disableFamilyControl();
                presenter.closeDialog();
            } else {
                // Enable: warn about changed settings, then require a PIN if none is set yet.
                AppDialogUtil.showConfirmationDialog(
                        getContext(),
                        getContext().getString(R.string.lost_setting_warning),
                        () -> {
                            if (MasterPasswordData.instance(getContext()).hasPin()) {
                                data.enableFamilyControl();
                                presenter.closeDialog();
                            } else {
                                showPinDialog(presenter, data::enableFamilyControl);
                            }
                        },
                        presenter::closeDialog);
            }
        }));
    }

    private void appendPinButton(AppDialogPresenter presenter) {
        MasterPasswordData data = MasterPasswordData.instance(getContext());
        int titleResId = data.hasPin() ? R.string.change_pin : R.string.set_pin;

        presenter.appendSingleButton(UiOptionItem.from(
                getContext().getString(titleResId),
                optionItem -> showPinDialog(presenter, null)));
    }

    private void showPinDialog(AppDialogPresenter presenter, Runnable onSuccess) {
        presenter.closeDialog();

        SimpleEditDialog.showPassword(
                getContext(),
                getContext().getString(R.string.set_pin),
                null,
                newValue -> {
                    MasterPasswordData.instance(getContext()).setPin(newValue);
                    if (onSuccess != null) {
                        onSuccess.run();
                    }
                    return true;
                });
    }

    private void appendAppLock(AppDialogPresenter presenter) {
        MasterPasswordData data = MasterPasswordData.instance(getContext());

        presenter.appendSingleSwitch(UiOptionItem.from(
                getContext().getString(R.string.app_access_lock),
                option -> {
                    if (option.isSelected()) {
                        if (!data.hasPin()) {
                            // A PIN is required for the lock to have any effect.
                            showPinDialog(presenter, () -> {
                                data.setLockEnabled(true);
                                show(); // refresh: reveal the time window buttons
                            });
                        } else {
                            data.setLockEnabled(true);
                            presenter.closeDialog();
                            show(); // refresh: reveal the time window buttons
                        }
                    } else {
                        data.setLockEnabled(false);
                        presenter.closeDialog();
                        show(); // refresh: hide the time window buttons
                    }
                },
                data.isLockEnabled()));

        // Show the time window only when the lock is enabled.
        if (data.isLockEnabled()) {
            presenter.appendSingleButton(UiOptionItem.from(
                    getContext().getString(R.string.schedule_start_time) + ": " + formatMinutes(data.getLockStartMinutes()),
                    optionItem -> showTimePicker(data, true)));

            presenter.appendSingleButton(UiOptionItem.from(
                    getContext().getString(R.string.schedule_end_time) + ": " + formatMinutes(data.getLockEndMinutes()),
                    optionItem -> showTimePicker(data, false)));
        }
    }

    private void showTimePicker(MasterPasswordData data, boolean isStart) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        List<OptionItem> options = new ArrayList<>();

        int current = isStart ? data.getLockStartMinutes() : data.getLockEndMinutes();

        for (int hour = 0; hour < 24; hour++) {
            int minutes = hour * 60;
            options.add(UiOptionItem.from(
                    formatMinutes(minutes),
                    optionItem -> {
                        if (isStart) {
                            data.setLockStartMinutes(minutes);
                        } else {
                            data.setLockEndMinutes(minutes);
                        }
                        dialog.closeDialog();
                        show(); // refresh: show the updated time
                    },
                    current == minutes));
        }

        int titleResId = isStart ? R.string.schedule_start_time : R.string.schedule_end_time;

        dialog.appendRadioCategory(getContext().getString(titleResId), options);
        dialog.showDialog(getContext().getString(titleResId));
    }

    private String formatMinutes(int minutes) {
        return String.format(Locale.US, "%02d:00", minutes / 60);
    }

    private void appendDailyLimit(AppDialogPresenter presenter) {
        TimeLimitData timeLimitData = TimeLimitData.instance(getContext());
        List<OptionItem> options = new ArrayList<>();

        int current = timeLimitData.getDailyLimitMinutes();

        options.add(UiOptionItem.from(
                getContext().getString(R.string.option_never),
                option -> timeLimitData.setDailyLimitMinutes(0),
                current == 0));

        for (int minutes : getDurationOptions()) {
            options.add(UiOptionItem.from(
                    getContext().getString(R.string.time_limit_min, Helpers.toString(minutes)),
                    option -> timeLimitData.setDailyLimitMinutes(minutes),
                    current == minutes));
        }

        presenter.appendRadioCategory(getContext().getString(R.string.daily_watch_time_limit), options);
    }

    private void appendChannelLimits(AppDialogPresenter presenter) {
        presenter.appendSingleButton(UiOptionItem.from(
                getContext().getString(R.string.channel_time_limits),
                optionItem -> showChannelLimitsDialog()));
    }

    private void showChannelLimitsDialog() {
        TimeLimitData timeLimitData = TimeLimitData.instance(getContext());
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());

        List<Pair<String, String>> channels = timeLimitData.getChannelLimits();

        for (Pair<String, String> entry : channels) {
            String channelName = entry.second != null ? entry.second : entry.first;
            int limitMinutes = timeLimitData.getChannelLimitMinutes(entry.first);

            dialog.appendSingleButton(UiOptionItem.from(
                    getContext().getString(R.string.channel_time_limit_min, channelName, limitMinutes),
                    optionItem -> {
                        dialog.closeDialog();
                        showChannelLimitOptions(entry.first, entry.second);
                    }));
        }

        if (channels.isEmpty()) {
            dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.no_channel_time_limits)));
        }

        dialog.showDialog(getContext().getString(R.string.channel_time_limits));
    }

    private void showChannelLimitOptions(String channelId, String channelName) {
        TimeLimitData timeLimitData = TimeLimitData.instance(getContext());
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        List<OptionItem> options = new ArrayList<>();

        int current = timeLimitData.getChannelLimitMinutes(channelId);

        options.add(UiOptionItem.from(
                getContext().getString(R.string.option_never),
                optionItem -> {
                    timeLimitData.removeChannelLimit(channelId, channelName);
                    dialog.closeDialog();
                },
                current == 0));

        for (int minutes : getDurationOptions()) {
            options.add(UiOptionItem.from(
                    getContext().getString(R.string.time_limit_min, Helpers.toString(minutes)),
                    optionItem -> {
                        timeLimitData.setChannelLimit(channelId, channelName, minutes);
                        dialog.closeDialog();
                    },
                    current == minutes));
        }

        dialog.appendRadioCategory(getContext().getString(R.string.set_channel_time_limit), options);
        dialog.showDialog(getContext().getString(R.string.set_channel_time_limit));
    }

    private int[] getDurationOptions() {
        return new int[] {10, 15, 30, 45, 60, 90, 120, 180};
    }
}
