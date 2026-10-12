package in.finseq.insurance;

import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;

import androidx.fragment.app.FragmentActivity;

/** Keeps each screen clear of Android's status bar, camera cutout and navigation bar. */
public class FinseqActivity extends FragmentActivity {
    @Override public void setContentView(View content) {
        super.setContentView(content);
        if (Build.VERSION.SDK_INT < 35 || getApplicationInfo().targetSdkVersion < 35) return;
        final int left = content.getPaddingLeft();
        final int top = content.getPaddingTop();
        final int right = content.getPaddingRight();
        final int bottom = content.getPaddingBottom();
        content.setOnApplyWindowInsetsListener((view, windowInsets) -> {
            Insets safe = windowInsets.getInsets(
                    WindowInsets.Type.systemBars() | WindowInsets.Type.displayCutout());
            view.setPadding(left + safe.left, top + safe.top,
                    right + safe.right, bottom + safe.bottom);
            return windowInsets;
        });
        content.requestApplyInsets();
    }
}
