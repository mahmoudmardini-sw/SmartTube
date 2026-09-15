package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import android.content.Context;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.util.Calendar;

/**
 * Device-wide (global) master password. A single PIN that protects the app and the settings.
 * Also holds the app access window: outside this window the app is locked on launch.
 */
public class MasterPasswordData {
    private static final String MASTER_PASSWORD_DATA = "master_password_data";
    /**
     * Marker written as the first field so future field additions can tell layouts apart.
     * Data written before this marker starts directly with the PIN hash.
     */
    private static final String FORMAT_VERSION = "v2";
    /** Wrong PINs accepted before the dialog goes quiet for {@link #PIN_LOCKOUT_MS}. */
    private static final int PIN_MAX_ATTEMPTS = 5;
    private static final long PIN_LOCKOUT_MS = 60_000;
    @SuppressLint("StaticFieldLeak")
    private static MasterPasswordData sInstance;
    private final AppPrefs mPrefs;
    private final Runnable mPersistStateInt = this::persistStateInt;
    private String mPinHash;
    private boolean mLockEnabled;
    private int mLockStartMinutes;
    private int mLockEndMinutes;
    private int mFailedPinAttempts;
    private long mPinLockoutUntilMs;

    private MasterPasswordData(Context context) {
        mPrefs = AppPrefs.instance(context);
        restoreState();
    }

    public static MasterPasswordData instance(Context context) {
        if (sInstance == null) {
            sInstance = new MasterPasswordData(context.getApplicationContext());
        }

        return sInstance;
    }

    // PIN

    public boolean hasPin() {
        return mPinHash != null;
    }

    public void setPin(String pin) {
        mPinHash = Utils.hashPin(pin);
        mFailedPinAttempts = 0;
        mPinLockoutUntilMs = 0;
        persistState();
    }

    public void clearPin() {
        mPinHash = null;
        mFailedPinAttempts = 0;
        mPinLockoutUntilMs = 0;
        persistState();
    }

    /**
     * Checks a typed PIN, upgrading older hash formats and throttling guessing.
     * <p>
     * Five wrong PINs lock the check out for a minute. The counter and the lockout are persisted,
     * so restarting the app doesn't hand out a fresh set of attempts.
     */
    public boolean isPinValid(String typed) {
        if (mPinHash == null || typed == null) {
            return false;
        }

        if (getPinLockoutRemainingMs() > 0) {
            return false;
        }

        if (Utils.verifyPin(mPinHash, typed)) {
            if (Utils.isLegacyPinFormat(mPinHash)) {
                // The plaintext PIN is only available here, so re-hash it in the current format.
                mPinHash = Utils.hashPin(typed);
            }

            mFailedPinAttempts = 0;
            mPinLockoutUntilMs = 0;
            persistNow();

            return true;
        }

        mFailedPinAttempts++;

        if (mFailedPinAttempts >= PIN_MAX_ATTEMPTS) {
            mFailedPinAttempts = 0;
            mPinLockoutUntilMs = System.currentTimeMillis() + PIN_LOCKOUT_MS;
        }

        persistNow();

        return false;
    }

    /**
     * Milliseconds left before another PIN attempt is accepted, or 0 when attempts are allowed.
     */
    public long getPinLockoutRemainingMs() {
        long remaining = mPinLockoutUntilMs - System.currentTimeMillis();

        return remaining > 0 ? remaining : 0;
    }

    // App access lock

    public boolean isLockEnabled() {
        return mLockEnabled;
    }

    public void setLockEnabled(boolean enabled) {
        mLockEnabled = enabled;
        persistState();
    }

    public int getLockStartMinutes() {
        return mLockStartMinutes;
    }

    public void setLockStartMinutes(int minutes) {
        mLockStartMinutes = minutes;
        persistState();
    }

    public int getLockEndMinutes() {
        return mLockEndMinutes;
    }

    public void setLockEndMinutes(int minutes) {
        mLockEndMinutes = minutes;
        persistState();
    }

    /**
     * The app is locked (PIN required) when the current time is outside the access window.
     */
    public boolean isLockedNow() {
        if (!mLockEnabled || mPinHash == null) {
            return false;
        }

        return !isWithinWindow();
    }

    /**
     * NOTE: start == end means "always locked" (the window is empty).
     */
    private boolean isWithinWindow() {
        int now = getCurrentMinutesOfDay();

        if (mLockStartMinutes <= mLockEndMinutes) {
            return now >= mLockStartMinutes && now < mLockEndMinutes;
        } else {
            // Overnight window, e.g. 22:00 - 06:00
            return now >= mLockStartMinutes || now < mLockEndMinutes;
        }
    }

    private int getCurrentMinutesOfDay() {
        Calendar calendar = Calendar.getInstance();
        return calendar.get(Calendar.HOUR_OF_DAY) * 60 + calendar.get(Calendar.MINUTE);
    }

    private synchronized void restoreState() {
        String data = mPrefs.getData(MASTER_PASSWORD_DATA);

        String[] split = Helpers.splitData(data);

        // See FORMAT_VERSION: 0 = layout without the marker, 1 = current layout.
        int offset = FORMAT_VERSION.equals(Helpers.parseStr(split, 0)) ? 1 : 0;

        mPinHash = Helpers.parseStr(split, offset);
        mLockEnabled = Helpers.parseBoolean(split, offset + 1, false);
        mLockStartMinutes = Helpers.parseInt(split, offset + 2, 10 * 60);
        mLockEndMinutes = Helpers.parseInt(split, offset + 3, 18 * 60);
        mFailedPinAttempts = Helpers.parseInt(split, offset + 4, 0);
        mPinLockoutUntilMs = Helpers.parseLong(split, offset + 5, 0);

        if (data == null) {
            migrateLegacyData();
        } else if (mPinHash != null && !Utils.isHashedPin(mPinHash)) {
            // The earliest builds stored the PIN plaintext. Hash it, verification keeps working.
            mPinHash = Utils.hashPin(mPinHash);
            persistNow();
        }
    }

    private void migrateLegacyData() {
        GeneralData generalData = GeneralData.instance(mPrefs.getContext());

        String masterPassword = generalData.getMasterPassword();
        String password = masterPassword != null ? masterPassword : generalData.getSettingsPassword();

        if (password == null) {
            return;
        }

        // Legacy passwords were stored plaintext. Hash before storing.
        mPinHash = Utils.hashPin(password);

        if (masterPassword != null) {
            // Legacy behavior: a master password locked the app at every startup.
            // start == end means "always locked" (see isWithinWindow).
            mLockEnabled = true;
            mLockStartMinutes = 0;
            mLockEndMinutes = 0;
        }

        persistNow();
    }

    public void persistNow() {
        Utils.post(mPersistStateInt);
    }

    private void persistState() {
        Utils.postDelayed(mPersistStateInt, 10_000);
    }

    private void persistStateInt() {
        mPrefs.setData(MASTER_PASSWORD_DATA, Helpers.mergeData(
                FORMAT_VERSION, mPinHash, mLockEnabled, mLockStartMinutes, mLockEndMinutes,
                mFailedPinAttempts, mPinLockoutUntilMs));
    }
}
