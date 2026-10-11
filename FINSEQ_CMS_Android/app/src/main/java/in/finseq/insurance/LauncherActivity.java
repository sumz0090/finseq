package in.finseq.insurance;

import android.app.AlertDialog;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

public class LauncherActivity extends FinseqActivity {
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout screen = new LinearLayout(this);
        screen.setOrientation(LinearLayout.VERTICAL);
        screen.setGravity(Gravity.CENTER);
        screen.setPadding(Ui.dp(this, 28), Ui.dp(this, 28), Ui.dp(this, 28), Ui.dp(this, 28));
        screen.setBackgroundColor(Ui.bg(this));
        ImageView logo = new ImageView(this);
        logo.setImageResource(getResources().getIdentifier("finseq_company_logo_transparent", "drawable", getPackageName()));
        logo.setScaleType(ImageView.ScaleType.FIT_CENTER);
        logo.setAdjustViewBounds(true);
        screen.addView(logo, new LinearLayout.LayoutParams(-1, Ui.dp(this, 190)));
        TextView name = Ui.text(this, "FINSEQ CMS", 25, true);
        name.setGravity(Gravity.CENTER);
        screen.addView(name);
        TextView sub = Ui.sub(this, "Client Management System");
        sub.setGravity(Gravity.CENTER);
        screen.addView(sub);
        setContentView(screen);
        new Handler(Looper.getMainLooper()).postDelayed(this::nextScreen, 1200);
    }

    private void nextScreen() {
        if (isFinishing() || isDestroyed()) return;
        String crash = getSharedPreferences("finseq_diagnostics", MODE_PRIVATE).getString("last_crash", "");
        if (!crash.isEmpty()) {
            getSharedPreferences("finseq_diagnostics", MODE_PRIVATE).edit().remove("last_crash").apply();
            new AlertDialog.Builder(this).setTitle("FINSEQ CMS unexpectedly closed")
                    .setMessage("Please send this error code so we can trace the crash:\n" + crash)
                    .setPositiveButton("Continue", (dialog, which) -> openNext()).show();
            return;
        }
        openNext();
    }

    private void openNext() {
        if (isFinishing() || isDestroyed()) return;
        Class<?> target = SecurityStore.configured(this) ? LockActivity.class
                : Session.loggedIn(this) ? AccessControl.landing(this) : LoginActivity.class;
        startActivity(new Intent(this, target));
        finish();
    }
}
