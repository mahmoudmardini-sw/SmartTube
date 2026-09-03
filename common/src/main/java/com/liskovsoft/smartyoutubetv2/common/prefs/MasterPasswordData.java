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
    @SuppressLint("StaticFieldLeak")
    private static MasterPasswordData sInstance;
    private final AppPrefs mPrefs;
    private final Runnable mPersistStateInt = this::persistStateInt;
    private String mPinHash;
    private boolean mLockEnabled;
    private int mLockStartMinutes;
    private int mLockEndMinutes;

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
        persistState();
    }

    public void clearPin() {
        mPinHash = null;
        persistState();
    }

    public boolean isPinValid(String typed) {
        return mPinHash != null && mPinHash.equals(Utils.hashPin(typed));
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

        mPinHash = Helpers.parseStr(split, 0);
        mLockEnabled = Helpers.parseBoolean(split, 1, false);
        mLockStartMinutes = Helpers.parseInt(split, 2, 10 * 60);
        mLockEndMinutes = Helpers.parseInt(split, 3, 18 * 60);

        if (data == null) {
            migrateLegacyData();
        } else if (mPinHash != null && !isSha256Hash(mPinHash)) {
            // Early builds stored the PIN plaintext, which makes isPinValid always fail. Normalize.
            mPinHash = Utils.hashPin(mPinHash);
            persistNow();
        }
    }

    /**
     * SHA-256 in lowercase hex is exactly 64 hex chars. Anything else is a plaintext PIN.
     */
    private boolean isSha256Hash(String value) {
        if (value.length() != 64) {
            return false;
        }

        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);

            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
                return false;
            }
        }

        return true;
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
                mPinHash, mLockEnabled, mLockStartMinutes, mLockEndMinutes));
    }
}
