package com.omarchytv.home;

import android.app.AlertDialog;
import android.view.KeyEvent;
import android.view.inputmethod.EditorInfo;
import android.widget.Button;
import android.widget.EditText;

/**
 * A single-line {@link EditText} inside an {@link AlertDialog}, reachable with a D-pad.
 *
 * On some TV keyboards (the TCL one, for instance) D-pad UP from the keyboard's top row never leaves the IME, so
 * OK and Cancel cannot be reached once the field has focus. The keyboard's Enter key is not a confirm either.
 * Setting the DONE action makes Enter/OK on the IME press the dialog's positive button, and ▼ from the field
 * points at that same button, so the text can be accepted without hiding the keyboard first.
 */
final class DialogInput {
    private DialogInput() {
    }

    /** Wire {@code field} to {@code dialog} before {@code dialog.show()}. */
    static void wire(EditText field, AlertDialog dialog) {
        field.setSingleLine(true);
        field.setImeOptions(EditorInfo.IME_ACTION_DONE | EditorInfo.IME_FLAG_NO_EXTRACT_UI);
        field.setNextFocusDownId(android.R.id.button1);   // AlertDialog's positive button
        field.setOnEditorActionListener((v, actionId, event) -> {
            boolean enter = event != null && event.getKeyCode() == KeyEvent.KEYCODE_ENTER
                    && event.getAction() == KeyEvent.ACTION_DOWN;
            if (actionId == EditorInfo.IME_ACTION_DONE || actionId == EditorInfo.IME_ACTION_GO || enter) {
                Button ok = dialog.getButton(AlertDialog.BUTTON_POSITIVE);
                if (ok != null && ok.isEnabled()) ok.performClick();
                return true;
            }
            return false;
        });
    }
}
