package com.liskovsoft.smartyoutubetv2.common.app.models.errors;

import android.content.Context;

import androidx.annotation.Nullable;

import com.liskovsoft.sharedutils.helpers.Helpers;
import com.liskovsoft.sharedutils.mylogger.Log;
import com.liskovsoft.smartyoutubetv2.common.R;
import com.liskovsoft.smartyoutubetv2.common.app.presenters.YTSignInPresenter;
import com.liskovsoft.smartyoutubetv2.common.utils.Utils;

public class CategoryEmptyError implements ErrorFragmentData {
    private static final String TAG = CategoryEmptyError.class.getSimpleName();

    private final Context mContext;
    private final Throwable mError;
    private final Runnable mRetryAction;

    /**
     * @param retryAction section refresh to run from the "Try again" button (may be null)
     */
    public CategoryEmptyError(Context context, @Nullable Throwable error, @Nullable Runnable retryAction) {
        mContext = context;
        mError = error;
        mRetryAction = retryAction;
    }

    @Override
    public void onAction() {
        if (isAuthError()) {
            YTSignInPresenter.instance(mContext).start();
        } else if (mRetryAction != null) {
            mRetryAction.run();
        }
    }

    @Override
    public String getMessage() {
        // Show the localized human-readable message; keep technical details in the log only.
        if (mError != null && !Helpers.containsAny(mError.getMessage(), "fromNullable result is null")) {
            Log.e(TAG, "Can't load content: %s", Utils.getStackTraceAsString(mError));
        }

        return mContext.getString(R.string.msg_cant_load_content);
    }

    @Override
    public String getActionText() {
        if (isAuthError()) {
            return mContext.getString(R.string.action_signin);
        }

        return mRetryAction != null ? mContext.getString(R.string.try_again) : null;
    }

    private boolean isAuthError() {
        return mError != null && Helpers.startsWith(mError.getMessage(), "AuthError");
    }
}
