package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.os.SystemClock;

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
    /** Minimum accepted PIN length: shorter PINs are trivially brute-forced. */
    private static final int PIN_MIN_LENGTH = 4;
    @SuppressLint("StaticFieldLeak")
    private static volatile MasterPasswordData sInstance;
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
            synchronized (MasterPasswordData.class) {
                if (sInstance == null) {
                    sInstance = new MasterPasswordData(context.getApplicationContext());
                }
            }
        }

        return sInstance;
    }

    // PIN

    public boolean hasPin() {
        return mPinHash != null && !mPinHash.isEmpty();
    }

    /**
     * Whether a candidate PIN is acceptable as a new PIN: long enough and not trivially guessable.
     */
    public static boolean isPinAcceptable(String pin) {
        if (pin == null || pin.length() < PIN_MIN_LENGTH) {
            return false;
        }

        // Reject runs of one repeated digit ("0000", "7777"...).
        boolean allSame = true;
        for (int i = 1; i < pin.length(); i++) {
            if (pin.charAt(i) != pin.charAt(0)) {
                allSame = false;
                break;
            }
        }

        if (allSame) {
            return false;
        }

        return !"1234".equals(pin) && !"4321".equals(pin);
    }

    public void setPin(String pin) {
        if (!isPinAcceptable(pin)) {
            return;
        }

        mPinHash = Utils.hashPin(pin);

        if (mPinHash == null) {
            // Hashing failed: fail closed instead of storing a weak value.
            return;
        }

        mFailedPinAttempts = 0;
        mPinLockoutUntilMs = 0;
        persistNow();
    }

    public void clearPin() {
        mPinHash = null;
        mFailedPinAttempts = 0;
        mPinLockoutUntilMs = 0;
        persistNow();
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
                // Also upgrades older iteration counts.
                String upgraded = Utils.hashPin(typed);
                if (upgraded != null) {
                    mPinHash = upgraded;
                }
            }

            mFailedPinAttempts = 0;
            mPinLockoutUntilMs = 0;
            persistNow();

            return true;
        }

        mFailedPinAttempts++;

        if (mFailedPinAttempts >= PIN_MAX_ATTEMPTS) {
            mFailedPinAttempts = 0;
            // Monotonic clock: changing the system clock can't shorten or skip the lockout.
            mPinLockoutUntilMs = SystemClock.elapsedRealtime() + PIN_LOCKOUT_MS;
        }

        persistNow();

        return false;
    }

    /**
     * Milliseconds left before another PIN attempt is accepted, or 0 when attempts are allowed.
     */
    public long getPinLockoutRemainingMs() {
        long remaining = mPinLockoutUntilMs - SystemClock.elapsedRealtime();

        if (remaining > PIN_LOCKOUT_MS) {
            // Corrupt or pre-fix wall-clock value: clamp instead of locking out for ages.
            remaining = PIN_LOCKOUT_MS;
        }

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

        // See FORMAT_VERSION: 1 for the versioned layout, 0 for data written before it.
        // The marker alone isn't enough: field 0 of legacy data is a free-form PIN that could
        // literally be "v2", so also require the full versioned field count.
        int offset = FORMAT_VERSION.equals(Helpers.parseStr(split, 0))
                && split != null && split.length >= 7 ? 1 : 0;

        mPinHash = Helpers.parseStr(split, offset);
        if (mPinHash != null && mPinHash.isEmpty()) {
            // Pre-hardening builds could store "": treat as "no PIN", never as a locked state.
            mPinHash = null;
        }
        mLockEnabled = Helpers.parseBoolean(split, offset + 1, false);
        mLockStartMinutes = Helpers.parseInt(split, offset + 2, 10 * 60);
        mLockEndMinutes = Helpers.parseInt(split, offset + 3, 18 * 60);
        mFailedPinAttempts = Helpers.parseInt(split, offset + 4, 0);
        mPinLockoutUntilMs = Helpers.parseLong(split, offset + 5, 0);

        if (data == null || data.isEmpty()) {
            migrateLegacyData();
        } else if (mPinHash != null && !Utils.isHashedPin(mPinHash)) {
            // The earliest builds stored the PIN plaintext. Hash it, verification keeps working.
            String hashed = Utils.hashPin(mPinHash);
            if (hashed != null) {
                mPinHash = hashed;
                persistNow();
            }
        }
    }

    private void migrateLegacyData() {
        GeneralData generalData = GeneralData.instance(mPrefs.getContext());

        String masterPassword = generalData.getMasterPassword();
        String password = masterPassword != null ? masterPassword : generalData.getSettingsPassword();

        if (password == null) {
            return;
        }

        if (Utils.isHashedPin(password)) {
            // Already a hash (e.g. SHA-256 from GeneralData): adopt it, don't hash the hash.
            mPinHash = password;
        } else {
            // Legacy passwords were stored plaintext. Hash before storing.
            mPinHash = Utils.hashPin(password);
        }

        if (mPinHash == null) {
            return;
        }

        if (masterPassword != null) {
            // Legacy behavior: a master password locked the app at every startup.
            // start == end means "always locked" (see isWithinWindow).
            mLockEnabled = true;
            mLockStartMinutes = 0;
            mLockEndMinutes = 0;
        }

        persistNow();
    }

    /**
     * Immediate, synchronous write. Used for security state (PIN, lockout counter) so a
     * force-kill can't roll it back.
     */
    public void persistNow() {
        persistStateInt();
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
