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

    public static void showPassword(Context context, String dialogTitle, String defaultValue, OnChange onChange, Runnable onDismiss) {
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
                .setNegativeButton(android.R.string.cancel, (dialog, which) -> { })
                .create();

        if (onDismiss != null) {
            configDialog.setOnDismissListener(dialog -> onDismiss.run());
        }

        View.OnClickListener buttonListener = v -> {
            int id = v.getId();
            if (id == R.id.pin_btn_delete) {
                if (pinBuilder.length() > 0) {
                    pinBuilder.deleteCharAt(pinBuilder.length() - 1);
                }
            } else if (id == R.id.pin_btn_ok) {
                if (pinBuilder.length() > 0) {
                    String pin = pinBuilder.toString();
                    boolean dismiss = onChange.onChange(pin);
                    if (dismiss) {
                        configDialog.dismiss();
                    }
                }
            } else {
                Button btn = (Button) v;
                String digit = btn.getText().toString();
                if (digit != null && digit.length() == 1 && Character.isDigit(digit.charAt(0))) {
                    pinBuilder.append(digit);
                }
            }
            updateDots(dotsDisplay, pinBuilder.length());
        };

        int[] buttonIds = {
                R.id.pin_btn_1, R.id.pin_btn_2, R.id.pin_btn_3,
                R.id.pin_btn_4, R.id.pin_btn_5, R.id.pin_btn_6,
                R.id.pin_btn_7, R.id.pin_btn_8, R.id.pin_btn_9,
                R.id.pin_btn_delete, R.id.pin_btn_0, R.id.pin_btn_ok
        };

        for (int id : buttonIds) {
            contentView.findViewById(id).setOnClickListener(buttonListener);
        }

        contentView.setFocusable(true);
        contentView.setFocusableInTouchMode(true);
        contentView.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() == KeyEvent.ACTION_DOWN) {
                switch (keyCode) {
                    case KeyEvent.KEYCODE_0:
                    case KeyEvent.KEYCODE_NUMPAD_0:
                        pinBuilder.append('0');
                        updateDots(dotsDisplay, pinBuilder.length());
                        return true;
                    case KeyEvent.KEYCODE_1:
                    case KeyEvent.KEYCODE_NUMPAD_1:
                        pinBuilder.append('1');
                        updateDots(dotsDisplay, pinBuilder.length());
                        return true;
                    case KeyEvent.KEYCODE_2:
                    case KeyEvent.KEYCODE_NUMPAD_2:
                        pinBuilder.append('2');
                        updateDots(dotsDisplay, pinBuilder.length());
                        return true;
                    case KeyEvent.KEYCODE_3:
                    case KeyEvent.KEYCODE_NUMPAD_3:
                        pinBuilder.append('3');
                        updateDots(dotsDisplay, pinBuilder.length());
                        return true;
                    case KeyEvent.KEYCODE_4:
                    case KeyEvent.KEYCODE_NUMPAD_4:
                        pinBuilder.append('4');
                        updateDots(dotsDisplay, pinBuilder.length());
                        return true;
                    case KeyEvent.KEYCODE_5:
                    case KeyEvent.KEYCODE_NUMPAD_5:
                        pinBuilder.append('5');
                        updateDots(dotsDisplay, pinBuilder.length());
                        return true;
                    case KeyEvent.KEYCODE_6:
                    case KeyEvent.KEYCODE_NUMPAD_6:
                        pinBuilder.append('6');
                        updateDots(dotsDisplay, pinBuilder.length());
                        return true;
                    case KeyEvent.KEYCODE_7:
                    case KeyEvent.KEYCODE_NUMPAD_7:
                        pinBuilder.append('7');
                        updateDots(dotsDisplay, pinBuilder.length());
                        return true;
                    case KeyEvent.KEYCODE_8:
                    case KeyEvent.KEYCODE_NUMPAD_8:
                        pinBuilder.append('8');
                        updateDots(dotsDisplay, pinBuilder.length());
                        return true;
                    case KeyEvent.KEYCODE_9:
                    case KeyEvent.KEYCODE_NUMPAD_9:
                        pinBuilder.append('9');
                        updateDots(dotsDisplay, pinBuilder.length());
                        return true;
                    case KeyEvent.KEYCODE_DEL:
                        if (pinBuilder.length() > 0) {
                            pinBuilder.deleteCharAt(pinBuilder.length() - 1);
                        }
                        updateDots(dotsDisplay, pinBuilder.length());
                        return true;
                    case KeyEvent.KEYCODE_ENTER:
                    case KeyEvent.KEYCODE_NUMPAD_ENTER:
                        if (pinBuilder.length() > 0) {
                            String pin = pinBuilder.toString();
                            boolean dismiss = onChange.onChange(pin);
                            if (dismiss) {
                                configDialog.dismiss();
                            }
                        }
                        return true;
                }
            }
            return false;
        });

        try {
            configDialog.show();
            contentView.findViewById(R.id.pin_btn_1).requestFocus();
        } catch (RuntimeException e) {
            e.printStackTrace();
            MessageHelpers.showMessage(context, e.getMessage());
        }

        configDialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener(view -> configDialog.dismiss());
    }

    private static void updateDots(TextView dotsDisplay, int count) {
        StringBuilder dots = new StringBuilder();
        for (int i = 0; i < count; i++) {
            dots.append('\u2022').append(' ');
        }
        dotsDisplay.setText(dots.toString().trim());
    }
}
