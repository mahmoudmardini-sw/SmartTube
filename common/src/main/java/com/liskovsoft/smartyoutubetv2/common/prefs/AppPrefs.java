package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import android.content.Context;
import android.text.TextUtils;

import com.liskovsoft.mediaserviceinterfaces.oauth.Account;
import com.liskovsoft.sharedutils.misc.WeakHashSet;
import com.liskovsoft.sharedutils.prefs.SharedPreferencesBase;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.SidebarService;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager;
import com.liskovsoft.smartyoutubetv2.common.misc.MediaServiceManager.AccountChangeListener;

public class AppPrefs extends SharedPreferencesBase implements AccountChangeListener {
    private static final String TAG = AppPrefs.class.getSimpleName();
    private static final String PREFS_DIR = "app_prefs";
    @SuppressLint("StaticFieldLeak")
    private static AppPrefs sInstance;
    private static final String ANONYMOUS_PROFILE_NAME = "anonymous";
    private static final String MULTI_PROFILES = "multi_profiles";
    private static final String STATE_UPDATER_DATA = "state_updater_data";
    private static final String CHANNEL_GROUP_DATA = "channel_group_data";
    private static final String SIDEBAR_DATA = "sidebar_data";
    private static final String FAMILY_CONTROL_DATA = "family_control_data";
    private static final String TIME_LIMIT_DATA = "time_limit_data";
    private static final String VIEW_MANAGER_DATA = "view_manager_data";
    private static final String WEB_PROXY_URI = "web_proxy_uri";
    private static final String WEB_PROXY_ENABLED = "web_proxy_enabled";
    private static final String LAST_PROFILE_NAME = "last_profile_name";
    private String mBootResolution;
    private final WeakHashSet<ProfileChangeListener> mListeners = new WeakHashSet<>();

    public interface ProfileChangeListener {
        void onProfileChanged();
    }

    private AppPrefs(Context context) {
        //super(context, R.xml.app_prefs); // IndexOutOfBoundsException on Google Streamer
        super(context);

        initProfiles();
    }

    private void initProfiles() {
        MediaServiceManager.instance().addAccountListener(this);
    }

    @Override
    public void onAccountChanged(Account account) {
        selectProfile(account);
        onProfileChanged();
    }

    public static AppPrefs instance(Context context) {
        if (sInstance == null) {
            sInstance = new AppPrefs(context);
        }

        return sInstance;
    }

    public boolean isMultiProfilesEnabled() {
        return getBoolean(MULTI_PROFILES, false);
    }

    public void enableMultiProfiles(boolean enabled) {
        if (isMultiProfilesEnabled() == enabled) {
            return;
        }

        migrateProfileData(enabled);

        putBoolean(MULTI_PROFILES, enabled);
        onProfileChanged();
    }

    private static final String[] PROFILE_DATA_KEYS = {
            "general_data",
            "main_ui_data2",
            "video_player_data",
            "video_player_tweaks_data",
            "blocked_channel_data",
            "family_control_data",
            "time_limit_data"
    };

    private void migrateProfileData(boolean toMultiProfiles) {
        String profileName = getProfileName();
        if (TextUtils.isEmpty(profileName)) {
            return;
        }

        for (String key : PROFILE_DATA_KEYS) {
            String oldKey;
            String newKey;

            if (toMultiProfiles) {
                oldKey = key;
                newKey = profileName + "_" + key;
            } else {
                oldKey = profileName + "_" + key;
                newKey = key;
            }

            String data = getData(oldKey);
            if (data != null) {
                setData(newKey, data);
                // Drop the source copy: otherwise a later delete resurrects stale data.
                deleteData(oldKey);
            }
        }
    }

    public String getBootResolution() {
        return mBootResolution;
    }

    public void setBootResolution(String resolution) {
        mBootResolution = resolution;
    }

    public String getStateUpdaterData() {
        // Always use multiple profiles for the history
        return getData(getProfileKey(STATE_UPDATER_DATA, true));
    }

    public void setStateUpdaterData(String data) {
        // Always use multiple profiles for the history
        setData(getProfileKey(STATE_UPDATER_DATA, true), data);
    }

    public String getChannelGroupData() {
        // Always use multiple profiles
        return getData(getProfileKey(CHANNEL_GROUP_DATA,  true));
    }

    public void setChannelGroupData(String data) {
        // Always use multiple profiles
        setData(getProfileKey(CHANNEL_GROUP_DATA,  true), data);
    }

    public String getSidebarData() {
        // Always use multiple profiles
        return getData(getProfileKey(SIDEBAR_DATA,  true));
    }

    public void setSidebarData(String data) {
        // Always use multiple profiles
        setData(getProfileKey(SIDEBAR_DATA,  true), data);
    }

    public String getFamilyControlData() {
        // Always use multiple profiles
        return getData(getFamilyControlDataKey());
    }

    public void setFamilyControlData(String data) {
        // Always use multiple profiles
        setData(getFamilyControlDataKey(), data);
    }

    /**
     * Profile key resolved right now. Capture it at mutation time when the write is
     * debounced: an account switch before the delay must not retarget the payload.
     */
    public String getFamilyControlDataKey() {
        return getProfileKey(FAMILY_CONTROL_DATA, true);
    }

    public String getTimeLimitData() {
        // Always use multiple profiles
        return getData(getTimeLimitDataKey());
    }

    public void setTimeLimitData(String data) {
        // Always use multiple profiles
        setData(getTimeLimitDataKey(), data);
    }

    /**
     * Profile key resolved right now. See {@link #getFamilyControlDataKey}.
     */
    public String getTimeLimitDataKey() {
        return getProfileKey(TIME_LIMIT_DATA, true);
    }

    public String getProfileData(String key) {
        String data = getData(getProfileKey(key, isMultiProfilesEnabled()));

        if (data == null && isMultiProfilesEnabled()) {
            data = getData(key);
        }

        return data;
    }

    public void setProfileData(String key, String data) {
        setData(getProfileKey(key, isMultiProfilesEnabled()), data);
    }

    //public String getData(String key) {
    //    // Don't sync hash here. Hashes won't match.
    //    return getString(key, null);
    //}
    //
    //public void setData(String key, String data) {
    //    if (checkData(key, data)) {
    //        putString(key, data);
    //    }
    //}

    public String getWebProxyUri() {
        return getString(WEB_PROXY_URI, "");
    }

    public void setWebProxyUri(String uri) {
        putString(WEB_PROXY_URI, uri);
    }

    public boolean isWebProxyEnabled() {
        return getBoolean(WEB_PROXY_ENABLED, false);
    }

    public void setWebProxyEnabled(boolean enabled) {
        putBoolean(WEB_PROXY_ENABLED, enabled);
    }

    private String getProfileName() {
        return getString(LAST_PROFILE_NAME, null);
    }

    private void setProfileName(String profileName) {
        putString(LAST_PROFILE_NAME, profileName);
    }

    private void selectProfile(Account account) {
        String profileName;

        if (account != null) {
            if (account.getEmail() != null) {
                profileName = sanitizeProfileName(account.getEmail());
            } else if (account.getName() != null) {
                profileName = sanitizeProfileName(account.getName());
            } else {
                profileName = ANONYMOUS_PROFILE_NAME;
            }
        } else {
            profileName = ANONYMOUS_PROFILE_NAME;
        }

        migrateProfileKeyScheme(legacyProfileName(account), profileName);
        setProfileName(profileName);
    }

    /**
     * Old naming scheme (no hash suffix), kept only to migrate existing installs once.
     */
    private static String legacyProfileName(Account account) {
        if (account != null) {
            if (account.getEmail() != null) {
                return account.getEmail().replace("@", "_at_").replace(".", "_");
            } else if (account.getName() != null) {
                return account.getName().replace(" ", "_");
            }
        }
        return ANONYMOUS_PROFILE_NAME;
    }

    /**
     * One-time move of per-profile files from the legacy key scheme to the hashed one.
     * Without this, existing users would lose their per-account settings on update.
     */
    private void migrateProfileKeyScheme(String oldName, String newName) {
        if (oldName == null || oldName.equals(newName)) {
            return;
        }

        String[] keys = {
                FAMILY_CONTROL_DATA, TIME_LIMIT_DATA, SIDEBAR_DATA, CHANNEL_GROUP_DATA,
                STATE_UPDATER_DATA, "general_data", "main_ui_data2", "video_player_data",
                "video_player_tweaks_data", "blocked_channel_data"
        };

        for (String key : keys) {
            String oldKey = oldName + "_" + key;
            String newKey = newName + "_" + key;
            String oldData = getData(oldKey);
            if (oldData != null && getData(newKey) == null) {
                setData(newKey, oldData);
                deleteData(oldKey);
            }
        }
    }

    /**
     * Filesystem-safe profile name. A hash suffix keeps names distinct when sanitizing
     * collides ("a.b@c.d" vs "a_b@c.d", "a b" vs "a_b").
     */
    private static String sanitizeProfileName(String raw) {
        String safe = raw.replace("@", "_at_").replace(".", "_").replace(" ", "_");
        return safe + "_" + sha1Hex(raw);
    }

    private static String sha1Hex(String value) {
        try {
            byte[] hash = java.security.MessageDigest.getInstance("SHA-1")
                    .digest(value.getBytes("UTF-8"));
            StringBuilder sb = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                sb.append(String.format("%02x", b));
            }
            return sb.toString();
        } catch (Exception e) {
            return Integer.toHexString(value.hashCode());
        }
    }

    private void onProfileChanged() {
        mListeners.forEach(ProfileChangeListener::onProfileChanged);
    }

    public void addListener(ProfileChangeListener listener) {
        if (!mListeners.contains(listener)) {
            if (listener instanceof GeneralData) {
                mListeners.add(0, listener); // data classes should be called before regular listeners
            } else if (listener instanceof SidebarService) {
                mListeners.add(mListeners.isEmpty() ? 0 : 1, listener); // data classes should be called before regular listeners
            } else {
                mListeners.add(listener);
            }
        }
    }

    public void removeListener(ProfileChangeListener listener) {
        mListeners.remove(listener);
    }

    //private String getProfileKey(String key) {
    //    String profileName = getProfileName();
    //    if (!TextUtils.isEmpty(profileName)) {
    //        key = profileName + "_" + key;
    //    }
    //
    //    return key;
    //}

    private String getProfileKey(String key, boolean isMultiProfilesEnabled) {
        String profileName = getProfileName();
        if (TextUtils.isEmpty(profileName) && isMultiProfilesEnabled) {
            // Pre-login writes used to land on the bare key and get orphaned after login.
            profileName = ANONYMOUS_PROFILE_NAME;
        }
        if (!TextUtils.isEmpty(profileName) && isMultiProfilesEnabled) {
            key = profileName + "_" + key;
        }

        return key;
    }
    
    @Override
    protected String getPrefsDir() {
        return PREFS_DIR;
    }
}
