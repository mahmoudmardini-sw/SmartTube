package com.liskovsoft.smartyoutubetv2.common.prefs;

import android.annotation.SuppressLint;
import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.manager.PlayerConstants;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.SidebarService;
import com.liskovsoft.smartyoutubetv2.common.prefs.AppPrefs.ProfileChangeListener;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

/**
 * Family control (parental restrictions) stored per account.
 * <p>
 * Unlike the master password (which is device-wide), this is stored per account so that
 * the parent account stays unrestricted while the child account is locked down.
 * <p>
 * When enabled, it hides the search, most sections, the account switcher and limits the player buttons.
 * The pre-restriction UI state is captured in a "snapshot" so it can be restored on disable.
 */
public class FamilyControlData implements ProfileChangeListener {
    private static final String FAMILY_CONTROL_DATA = "family_control_data";

    /**
     * Built-in sections toggled by family control. The order matters: bit i of the snapshot mask
     * corresponds to index i. Keep in sync with {@link BrowsePresenter#enableAllSections}.
     */
    private static final int[] RESTRICTED_SECTIONS = {
            MediaGroup.TYPE_HISTORY, MediaGroup.TYPE_USER_PLAYLISTS, MediaGroup.TYPE_SUBSCRIPTIONS,
            MediaGroup.TYPE_CHANNEL_UPLOADS, MediaGroup.TYPE_GAMING, MediaGroup.TYPE_MUSIC,
            MediaGroup.TYPE_NEWS, MediaGroup.TYPE_HOME, MediaGroup.TYPE_TRENDING, MediaGroup.TYPE_SHORTS
    };
    private static final int ALL_SECTIONS_MASK = (1 << RESTRICTED_SECTIONS.length) - 1;

    @SuppressLint("StaticFieldLeak")
    private static FamilyControlData sInstance;

    private final Context mContext;
    private final AppPrefs mPrefs;
    private final Runnable mPersistStateInt = this::persistStateInt;

    // Family control state (per account)
    private boolean mIsFamilyControlEnabled;

    // Temporary pause (parent-granted via PIN): restrictions are lifted until this timestamp
    private long mPausedUntilMs;

    // Snapshot of the normal (non-restricted) UI state, captured before enabling
    private int mSnapshotTopButtons;
    private long mSnapshotMenuItems;
    private int mSnapshotPlayerButtons;
    private boolean mSnapshotSuggestionsDisabled;
    private boolean mSnapshotPopularSearchesDisabled;
    private int mSnapshotPlaybackMode;
    private int mSnapshotEnabledSections = ALL_SECTIONS_MASK;
    private boolean mHasSnapshot;

    private FamilyControlData(Context context) {
        mContext = context.getApplicationContext();
        mPrefs = AppPrefs.instance(mContext);
        mPrefs.addListener(this);
        restoreState();
    }

    public static FamilyControlData instance(Context context) {
        if (sInstance == null) {
            sInstance = new FamilyControlData(context.getApplicationContext());
        }

        return sInstance;
    }

    /**
     * Whether family control restrictions currently apply. While a parent-granted
     * pause is active (see {@link #pauseFamilyControl}) this returns false, so all
     * gates (search, account switcher, watch-time limits...) are lifted.
     */
    public boolean isFamilyControlEnabled() {
        return mIsFamilyControlEnabled && !isPauseActive();
    }

    /**
     * Whether family control is configured for this account, regardless of an active pause.
     * Use for gates that must stay protected while paused (e.g. entering settings).
     */
    public boolean isFamilyControlConfigured() {
        return mIsFamilyControlEnabled;
    }

    public boolean isPauseActive() {
        return mPausedUntilMs > 0 && System.currentTimeMillis() < mPausedUntilMs;
    }

    public long getPausedUntilMs() {
        return mPausedUntilMs;
    }

    /**
     * Temporarily lifts all family control restrictions (parent-only, PIN-gated by callers).
     * The pre-restriction UI state is restored; restrictions re-apply automatically when
     * the pause expires (see {@link #checkPauseExpiry}).
     */
    public void pauseFamilyControl(long untilMs) {
        if (!mIsFamilyControlEnabled || untilMs <= System.currentTimeMillis()) {
            return;
        }

        restoreRestrictions();
        mPausedUntilMs = untilMs;
        persistNow();
    }

    /**
     * Ends an active pause early and re-applies the restrictions immediately.
     */
    public void cancelPause() {
        if (mPausedUntilMs == 0) {
            return;
        }

        mPausedUntilMs = 0;
        persistNow();
        applyRestrictions();
    }

    /**
     * Re-applies the restrictions once a pause has expired. Call regularly (app start,
     * browse view init, player tick) so the pause can't outlive its window.
     *
     * @return true if the pause just expired and restrictions were re-applied
     */
    public boolean checkPauseExpiry() {
        if (mIsFamilyControlEnabled && mPausedUntilMs > 0 && System.currentTimeMillis() >= mPausedUntilMs) {
            mPausedUntilMs = 0;
            persistNow();
            applyRestrictions();
            return true;
        }

        return false;
    }

    /**
     * Enables family control for the current account: captures the current UI state,
     * then applies the restrictions.
     */
    public void enableFamilyControl() {
        captureSnapshot();

        mIsFamilyControlEnabled = true;
        mPausedUntilMs = 0;
        persistState();

        applyRestrictions();
    }

    /**
     * Disables family control for the current account: restores the captured UI state
     * and clears the snapshot.
     */
    public void disableFamilyControl() {
        mIsFamilyControlEnabled = false;
        mPausedUntilMs = 0;
        persistState();

        restoreRestrictions();

        mHasSnapshot = false;
        persistState();
    }

    /**
     * Saves the current (normal) UI state so it can be restored later.
     */
    private void captureSnapshot() {
        MainUIData mainUIData = MainUIData.instance(mContext);
        PlayerTweaksData tweaksData = PlayerTweaksData.instance(mContext);
        SearchData searchData = SearchData.instance(mContext);
        PlayerData playerData = PlayerData.instance(mContext);

        mSnapshotTopButtons = mainUIData.getTopButtons();
        mSnapshotMenuItems = mainUIData.getMenuItems();
        mSnapshotPlayerButtons = tweaksData.getPlayerButtons();
        mSnapshotSuggestionsDisabled = tweaksData.isSuggestionsDisabled();
        mSnapshotPopularSearchesDisabled = searchData.isPopularSearchesDisabled();
        mSnapshotPlaybackMode = playerData.getPlaybackMode();
        mSnapshotEnabledSections = getEnabledSectionsMask();
        mHasSnapshot = true;
        persistState();
    }

    /**
     * Applies the family control restrictions: hides sections, search, account switcher
     * and limits the player buttons.
     */
    private void applyRestrictions() {
        MainUIData mainUIData = MainUIData.instance(mContext);
        PlayerTweaksData tweaksData = PlayerTweaksData.instance(mContext);
        SearchData searchData = SearchData.instance(mContext);

        int playerButtons = PlayerTweaksData.PLAYER_BUTTON_PLAY_PAUSE | PlayerTweaksData.PLAYER_BUTTON_NEXT | PlayerTweaksData.PLAYER_BUTTON_PREVIOUS |
                PlayerTweaksData.PLAYER_BUTTON_DISLIKE | PlayerTweaksData.PLAYER_BUTTON_LIKE | PlayerTweaksData.PLAYER_BUTTON_SCREEN_DIMMING |
                PlayerTweaksData.PLAYER_BUTTON_SEEK_INTERVAL | PlayerTweaksData.PLAYER_BUTTON_PLAYBACK_QUEUE | PlayerTweaksData.PLAYER_BUTTON_OPEN_CHANNEL |
                PlayerTweaksData.PLAYER_BUTTON_PIP | PlayerTweaksData.PLAYER_BUTTON_VIDEO_SPEED | PlayerTweaksData.PLAYER_BUTTON_SUBTITLES |
                PlayerTweaksData.PLAYER_BUTTON_VIDEO_ZOOM | PlayerTweaksData.PLAYER_BUTTON_ADD_TO_PLAYLIST;
        // NOTE: no accounts/menu selection here, so the child can't switch accounts.
        long menuItems = MainUIData.MENU_ITEM_SHOW_QUEUE | MainUIData.MENU_ITEM_ADD_TO_QUEUE | MainUIData.MENU_ITEM_PLAY_NEXT |
                MainUIData.MENU_ITEM_STREAM_REMINDER | MainUIData.MENU_ITEM_SAVE_REMOVE_PLAYLIST;

        // Remove all
        mainUIData.setTopButtonDisabled(Integer.MAX_VALUE);
        tweaksData.setPlayerButtonDisabled(Integer.MAX_VALUE);
        mainUIData.setMenuItemDisabled(Integer.MAX_VALUE);
        applySectionsMask(0);
        searchData.setPopularSearchesDisabled(true);

        // Apply the allowed (child-safe) subset
        mainUIData.setTopButtonEnabled(0);
        tweaksData.setPlayerButtonEnabled(playerButtons);
        mainUIData.setMenuItemEnabled(menuItems);
        PlayerData.instance(mContext).setPlaybackMode(PlayerConstants.PLAYBACK_MODE_LIST);
        BrowsePresenter.instance(mContext).enableSection(MediaGroup.TYPE_HISTORY, true);
        BrowsePresenter.instance(mContext).enableSection(MediaGroup.TYPE_USER_PLAYLISTS, true);
        BrowsePresenter.instance(mContext).enableSection(MediaGroup.TYPE_SUBSCRIPTIONS, true);
        BrowsePresenter.instance(mContext).enableSection(MediaGroup.TYPE_CHANNEL_UPLOADS, true);
    }

    /**
     * Restores the pre-restriction UI state from the snapshot (or defaults if no snapshot).
     * NOTE: section order isn't restored, only the enabled set.
     */
    private void restoreRestrictions() {
        MainUIData mainUIData = MainUIData.instance(mContext);
        PlayerTweaksData tweaksData = PlayerTweaksData.instance(mContext);
        SearchData searchData = SearchData.instance(mContext);

        if (mHasSnapshot) {
            mainUIData.setTopButtonDisabled(Integer.MAX_VALUE);
            mainUIData.setTopButtonEnabled(mSnapshotTopButtons);

            mainUIData.setMenuItemDisabled(Integer.MAX_VALUE);
            mainUIData.setMenuItemEnabled(mSnapshotMenuItems);

            tweaksData.setPlayerButtonDisabled(Integer.MAX_VALUE);
            tweaksData.setPlayerButtonEnabled(mSnapshotPlayerButtons);

            tweaksData.setSuggestionsDisabled(mSnapshotSuggestionsDisabled);
            searchData.setPopularSearchesDisabled(mSnapshotPopularSearchesDisabled);
            PlayerData.instance(mContext).setPlaybackMode(mSnapshotPlaybackMode);

            applySectionsMask(mSnapshotEnabledSections);
        } else {
            mainUIData.setTopButtonEnabled(MainUIData.TOP_BUTTON_DEFAULT);
            tweaksData.setPlayerButtonEnabled(PlayerTweaksData.PLAYER_BUTTON_DEFAULT);
            mainUIData.setMenuItemEnabled(MainUIData.MENU_ITEM_DEFAULT);
            BrowsePresenter.instance(mContext).enableAllSections(true);
            tweaksData.setSuggestionsDisabled(false);
            PlayerData.instance(mContext).setPlaybackMode(PlayerConstants.PLAYBACK_MODE_ALL);
            searchData.setPopularSearchesDisabled(false);
        }
    }

    private int getEnabledSectionsMask() {
        SidebarService sidebarService = SidebarService.instance(mContext);

        int mask = 0;

        for (int i = 0; i < RESTRICTED_SECTIONS.length; i++) {
            if (sidebarService.isSectionPinned(RESTRICTED_SECTIONS[i])) {
                mask |= 1 << i;
            }
        }

        return mask;
    }

    private void applySectionsMask(int mask) {
        BrowsePresenter presenter = BrowsePresenter.instance(mContext);

        for (int i = 0; i < RESTRICTED_SECTIONS.length; i++) {
            presenter.enableSection(RESTRICTED_SECTIONS[i], (mask & (1 << i)) != 0);
        }
    }

    private synchronized void restoreState() {
        String data = mPrefs.getFamilyControlData();

        String[] split = Helpers.splitData(data);

        mIsFamilyControlEnabled = Helpers.parseBoolean(split, 0, false);
        mSnapshotTopButtons = Helpers.parseInt(split, 1, 0);
        mSnapshotMenuItems = Helpers.parseLong(split, 2, 0);
        mSnapshotPlayerButtons = Helpers.parseInt(split, 3, 0);
        mSnapshotSuggestionsDisabled = Helpers.parseBoolean(split, 4, false);
        mSnapshotPopularSearchesDisabled = Helpers.parseBoolean(split, 5, false);
        mSnapshotPlaybackMode = Helpers.parseInt(split, 6, 0);
        mHasSnapshot = Helpers.parseBoolean(split, 7, false);
        // Index 8 added later: default to "all sections" for already-persisted data (legacy behavior).
        mSnapshotEnabledSections = Helpers.parseInt(split, 8, ALL_SECTIONS_MASK);
        // Index 9 added later: no pause for already-persisted data.
        mPausedUntilMs = Helpers.parseLong(split, 9, 0);

        if (data == null) {
            migrateLegacyData();
        }
    }

    /**
     * One-time migration from the old per-profile "child mode" fields stored in {@link GeneralData}.
     */
    private void migrateLegacyData() {
        GeneralData generalData = GeneralData.instance(mContext);

        if (!generalData.isChildModeEnabled()) {
            return;
        }

        mIsFamilyControlEnabled = true;

        if (generalData.hasChildModeSnapshot()) {
            mSnapshotTopButtons = generalData.getChildModeSnapshotTopButtons();
            mSnapshotMenuItems = generalData.getChildModeSnapshotMenuItems();
            mSnapshotPlayerButtons = generalData.getChildModeSnapshotPlayerButtons();
            mSnapshotSuggestionsDisabled = generalData.isChildModeSnapshotSuggestionsDisabled();
            mSnapshotPopularSearchesDisabled = generalData.isChildModeSnapshotPopularSearchesDisabled();
            mSnapshotPlaybackMode = generalData.getChildModeSnapshotPlaybackMode();
            // The legacy snapshot didn't capture sections: restoring enables all of them (legacy behavior).
            mSnapshotEnabledSections = ALL_SECTIONS_MASK;
            mHasSnapshot = true;
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
        mPrefs.setFamilyControlData(Helpers.mergeData(
                mIsFamilyControlEnabled, mSnapshotTopButtons, mSnapshotMenuItems, mSnapshotPlayerButtons,
                mSnapshotSuggestionsDisabled, mSnapshotPopularSearchesDisabled, mSnapshotPlaybackMode, mHasSnapshot,
                mSnapshotEnabledSections, mPausedUntilMs));
    }

    @Override
    public void onProfileChanged() {
        Utils.removeCallbacks(mPersistStateInt);
        restoreState();
    }
}
