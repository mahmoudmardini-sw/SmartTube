package com.liskovsoft.smartyoutubetv2.common.utils;

import android.content.Context;
import android.text.InputType;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;
import androidx.appcompat.app.AlertDialog;
import com.liskovsoft.sharedutils.helpers.KeyHelpers;
import com.liskovsoft.sharedutils.helpers.MessageHelpers;
import com.liskovsoft.smartyoutubetv2.common.R;

public class SimpleEditDialog {
    public interface OnChange {
        boolean onChange(String newValue);
    }

    public static void show(Context context, String dialogTitle, String defaultValue, OnChange onChange) {
        show(context, dialogTitle, dialogTitle, defaultValue, onChange, null);
    }

    public static void show(Context context, String dialogTitle, String dialogHint, String defaultValue, OnChange onChange) {
        show(context, dialogTitle, dialogHint, defaultValue, onChange, null);
    }

    public static void show(Context context, String dialogTitle, String dialogHint, String defaultValue, OnChange onChange, Runnable onDismiss) {
        show(context, dialogTitle, dialogHint, defaultValue, onChange, onDismiss, false);
    }

    public static void showPassword(Context context, String dialogTitle, String defaultValue, OnChange onChange) {
        showPassword(context, dialogTitle, defaultValue, onChange, null);
    }

    /**
     * Alphanumeric password entry (account passwords). Uses the soft keyboard / remote text
     * entry; numeric PINs go through {@link #showPin}.
     */
    public static void showPassword(Context context, String dialogTitle, String defaultValue, OnChange onChange, Runnable onDismiss) {
        show(context, dialogTitle, dialogTitle, defaultValue, onChange, onDismiss, true);
    }

    /**
     * Numeric PIN entry with an on-screen keypad (works on remotes without number keys).
     */
    public static void showPin(Context context, String dialogTitle, OnChange onChange) {
        showPin(context, dialogTitle, onChange, null);
    }

    public static void showPin(Context context, String dialogTitle, OnChange onChange, Runnable onDismiss) {
        showPinEntry(context, dialogTitle, onChange, onDismiss);
    }

    private static void show(Context context, String dialogTitle, String dialogHint, String defaultValue, OnChange onChange, Runnable onDismiss, boolean isPassword) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context, R.style.AppDialog);
        LayoutInflater inflater = LayoutInflater.from(context);
        View contentView = inflater.inflate(R.layout.simple_edit_dialog, null);

        EditText editField = contentView.findViewById(R.id.simple_edit_value);
        if (isPassword) {
            editField.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD);
        }
        KeyHelpers.fixShowKeyboard(editField);

        editField.setText(defaultValue);
        editField.setHint(dialogHint);
        editField.setNextFocusDownId(android.R.id.button1); // OK button

        if (defaultValue != null) { // move cursor to the end
            editField.setSelection(defaultValue.length());
        }

        // keep empty, will override below.
        // https://stackoverflow.com/a/15619098/5379584
        AlertDialog configDialog = builder
                .setTitle(dialogTitle)
                .setView(contentView)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> { })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> { })
                .create();

        if (onDismiss != null) {
            configDialog.setOnDismissListener(dialog -> onDismiss.run());
        }

        editField.setOnEditorActionListener((v, actionId, event) -> {
            switch (actionId) {
                case EditorInfo.IME_ACTION_NEXT:
                    configDialog.getButton(AlertDialog.BUTTON_POSITIVE).requestFocus();
                    return true;
                case EditorInfo.IME_ACTION_DONE:
                    configDialog.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
                    return true;
            }
            return false;
        });

        try {
            configDialog.show();
        } catch (RuntimeException e) {
            // BadTokenException: Unable to add window -- token null is not for an application
            // RuntimeException: InputChannel is not initialized
            e.printStackTrace();
            MessageHelpers.showMessage(context, e.getMessage());
            return;
        }

        configDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener((view) -> {
            String newValue = editField.getText().toString();

            if (newValue.isEmpty()) {
                // Empty fields not allowed
                editField.setHint(R.string.enter_value);
                return;
            }

            boolean dismiss = onChange.onChange(newValue);

            if (dismiss) {
                configDialog.dismiss();
            }
        });

        configDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener((view) -> configDialog.dismiss());

        //editField.setNextFocusDownId(configDialog.getButton(AlertDialog.BUTTON_POSITIVE).getId()); // OK button
    }

    private static void showPinEntry(Context context, String dialogTitle, OnChange onChange, Runnable onDismiss) {
        AlertDialog.Builder builder = new AlertDialog.Builder(context, R.style.AppDialog);
        LayoutInflater inflater = LayoutInflater.from(context);
        View contentView = inflater.inflate(R.layout.simple_pin_dialog, null);

        final StringBuilder pinBuilder = new StringBuilder();
        final TextView dotsDisplay = contentView.findViewById(R.id.pin_dots_display);
        dotsDisplay.setText("");

        AlertDialog configDialog = builder
                .setTitle(dialogTitle)
                .setView(contentView)
                .setPositiveButton(android.R.string.ok, (dialog, which) -> { })
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> { })
                .create();

        if (onDismiss != null) {
            configDialog.setOnDismissListener(dialog -> onDismiss.run());
        }

        final Runnable submitPin = () -> {
            if (pinBuilder.length() > 0) {
                String pin = pinBuilder.toString();
                boolean dismiss = onChange.onChange(pin);
                if (dismiss) {
                    configDialog.dismiss();
                } else {
                    // Wrong PIN: reset the entry so the user can retry right away.
                    pinBuilder.setLength(0);
                    updateDots(dotsDisplay, 0);
                    MessageHelpers.showMessage(context, R.string.wrong_pin);
                }
            }
        };

        // On-screen keypad: remotes without number keys can still enter the PIN.
        int[] digitIds = {
                R.id.pin_key_0, R.id.pin_key_1, R.id.pin_key_2, R.id.pin_key_3, R.id.pin_key_4,
                R.id.pin_key_5, R.id.pin_key_6, R.id.pin_key_7, R.id.pin_key_8, R.id.pin_key_9};
        for (int i = 0; i < digitIds.length; i++) {
            final char digit = (char) ('0' + i);
            contentView.findViewById(digitIds[i]).setOnClickListener(view -> {
                pinBuilder.append(digit);
                updateDots(dotsDisplay, pinBuilder.length());
            });
        }
        contentView.findViewById(R.id.pin_key_delete).setOnClickListener(view -> {
            if (pinBuilder.length() > 0) {
                pinBuilder.deleteCharAt(pinBuilder.length() - 1);
            }
            updateDots(dotsDisplay, pinBuilder.length());
        });

        contentView.setFocusable(true);
        contentView.setFocusableInTouchMode(true);

        // Attach to the dialog, not the root: digits typed while a keypad button has focus
        // must still reach the PIN buffer.
        configDialog.setOnKeyListener((dialog, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) {
                return false;
            }

            int digit = keyCodeToDigit(keyCode);

            if (digit >= 0) {
                pinBuilder.append((char) ('0' + digit));
                updateDots(dotsDisplay, pinBuilder.length());
                return true;
            }

            switch (keyCode) {
                case KeyEvent.KEYCODE_DEL:
                    if (pinBuilder.length() > 0) {
                        pinBuilder.deleteCharAt(pinBuilder.length() - 1);
                    }
                    updateDots(dotsDisplay, pinBuilder.length());
                    return true;
                case KeyEvent.KEYCODE_ENTER:
                case KeyEvent.KEYCODE_NUMPAD_ENTER:
                    submitPin.run();
                    return true;
            }

            return false;
        });

        try {
            configDialog.show();
            contentView.requestFocus();
        } catch (RuntimeException e) {
            e.printStackTrace();
            MessageHelpers.showMessage(context, e.getMessage());
        }

        configDialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(view -> submitPin.run());

        configDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> configDialog.dismiss());
    }

    /**
     * Maps the remote's number keys to 0-9, or -1 for any other key.
     */
    private static int keyCodeToDigit(int keyCode) {
        if (keyCode >= KeyEvent.KEYCODE_0 && keyCode <= KeyEvent.KEYCODE_9) {
            return keyCode - KeyEvent.KEYCODE_0;
        }

        if (keyCode >= KeyEvent.KEYCODE_NUMPAD_0 && keyCode <= KeyEvent.KEYCODE_NUMPAD_9) {
            return keyCode - KeyEvent.KEYCODE_NUMPAD_0;
        }

        return -1;
    }

    private static void updateDots(TextView dotsDisplay, int count) {
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < count; i++) {
            dots.append('\u2022').append(' ');
        }
        dotsDisplay.setText(dots.toString().trim());
    }
}
