package in.finseq.insurance;

import android.text.Editable;
import android.text.TextWatcher;

/** Applies search immediately for presets; custom searches wait for Apply. */
final class RenewalSearchWatcher implements TextWatcher {
    private final RenewalsActivity owner;

    RenewalSearchWatcher(RenewalsActivity owner) {
        this.owner = owner;
    }

    @Override public void beforeTextChanged(CharSequence text, int start, int count, int after) {}
    @Override public void onTextChanged(CharSequence text, int start, int before, int count) {}
    @Override public void afterTextChanged(Editable text) {
        if (!"custom".equals(owner.period)) owner.refresh();
    }
}
