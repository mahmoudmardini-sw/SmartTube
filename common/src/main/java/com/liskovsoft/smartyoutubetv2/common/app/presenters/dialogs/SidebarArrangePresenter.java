package com.liskovsoft.smartyoutubetv2.common.app.presenters.dialogs;

import android.content.Context;

import com.liskovsoft.mediaserviceinterfaces.data.MediaGroup;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.BrowseSection;
import com.liskovsoft.smartyoutubetv2.common.app.models.data.Video;
import com.liskovsoft.smartyoutubetv2.common.app.models.playback.ui.UiOptionItem;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.AppDialogPresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.BrowsePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.base.BasePresenter;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.service.SidebarService;
import com.liskovsoft.smartyoutubetv2.common.utils.SimpleEditDialog;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * One-stop sidebar management screen: reorder, rename, remove, hide and show sections
 * from a single numbered list (instead of one-at-a-time long-press moves or digging
 * through the General settings for section visibility).
 * <p>
 * After every change the whole dialog is closed and reopened (with a small delay to let
 * the old dialog view finish cleanly), so the list always shows the fresh order and the
 * real sidebar refreshes live behind it.
 */
public class SidebarArrangePresenter extends BasePresenter<Void> {
    private static final long REOPEN_DELAY_MS = 200;

    private SidebarArrangePresenter(Context context) {
        super(context);
    }

    public static SidebarArrangePresenter instance(Context context) {
        return new SidebarArrangePresenter(context);
    }

    public void show() {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        SidebarService sidebar = getSidebarService();
        List<Video> items = new ArrayList<>(sidebar.getPinnedItems());

        int position = 1;
        for (Video item : items) {
            Video currentItem = item;
            int currentPos = position;

            dialog.appendSingleButton(UiOptionItem.from(
                    currentPos + ". " + sidebar.getPinnedItemTitle(currentItem),
                    optionItem -> showItemActions(currentItem)));
            position++;
        }

        appendHiddenSections(dialog);

        dialog.showDialog(getContext().getString(R.string.arrange_sidebar));
    }

    private void showItemActions(Video item) {
        AppDialogPresenter dialog = AppDialogPresenter.instance(getContext());
        SidebarService sidebar = getSidebarService();
        BrowsePresenter browsePresenter = BrowsePresenter.instance(getContext());

        int id = sidebar.getSectionId(item);
        boolean isPinned = item.sectionId == -1; // pinned channel/playlist vs default section

        if (sidebar.canMoveSectionUp(id)) {
            dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.move_section_up), optionItem -> {
                browsePresenter.moveSectionUp(toSection(item));
                reopen();
            }));
        }

        if (sidebar.canMoveSectionDown(id)) {
            dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.move_section_down), optionItem -> {
                browsePresenter.moveSectionDown(toSection(item));
                reopen();
            }));
        }

        if (isPinned) {
            dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.rename_section), optionItem -> {
                dialog.closeDialog();

                SimpleEditDialog.show(
                        getContext(),
                        getContext().getString(R.string.rename_section),
                        sidebar.getPinnedItemTitle(item),
                        newValue -> {
                            item.title = newValue;
                            browsePresenter.renameSection(item);
                            Utils.postDelayed(this::show, REOPEN_DELAY_MS);
                            return true;
                        });
            }));

            dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.unpin_from_sidebar), optionItem -> {
                browsePresenter.unpinItem(item);
                reopen();
            }));
        } else {
            // Default sections can't be removed, only hidden.
            dialog.appendSingleButton(UiOptionItem.from(getContext().getString(R.string.hide_from_sidebar), optionItem -> {
                browsePresenter.enableSection(id, false);
                reopen();
            }));
        }

        dialog.showDialog(sidebar.getPinnedItemTitle(item));
    }

    /**
     * Default sections that are currently hidden, each with a one-tap "show" action.
     */
    private void appendHiddenSections(AppDialogPresenter dialog) {
        SidebarService sidebar = getSidebarService();
        List<Integer> hidden = new ArrayList<>();

        for (Map.Entry<Integer, Integer> entry : sidebar.getDefaultSections().entrySet()) {
            int sectionId = entry.getValue();

            // Settings can't be hidden (it's the only way back into the app settings).
            if (sectionId == MediaGroup.TYPE_SETTINGS) {
                continue;
            }

            if (!sidebar.isSectionPinned(sectionId)) {
                hidden.add(sectionId);
            }
        }

        if (hidden.isEmpty()) {
            return;
        }

        dialog.appendHeader(getContext().getString(R.string.hidden_sections));

        for (int sectionId : hidden) {
            String title = getDefaultSectionTitle(sectionId);

            dialog.appendSingleButton(UiOptionItem.from(
                    getContext().getString(R.string.show_section, title),
                    optionItem -> {
                        BrowsePresenter.instance(getContext()).enableSection(sectionId, true);
                        reopen();
                    }));
        }
    }

    private String getDefaultSectionTitle(int sectionId) {
        for (Map.Entry<Integer, Integer> entry : getSidebarService().getDefaultSections().entrySet()) {
            if (entry.getValue() == sectionId) {
                return getContext().getString(entry.getKey());
            }
        }

        return "";
    }

    /**
     * BrowsePresenter move methods expect a section. The sidebar item id
     * (its sectionId or its own id for pinned channels) works as that id.
     */
    private BrowseSection toSection(Video item) {
        return new BrowseSection(getSidebarService().getSectionId(item), getSidebarService().getPinnedItemTitle(item), BrowseSection.TYPE_ROW, 0);
    }

    private void reopen() {
        AppDialogPresenter.instance(getContext()).closeDialog();
        Utils.postDelayed(this::show, REOPEN_DELAY_MS);
    }
}
