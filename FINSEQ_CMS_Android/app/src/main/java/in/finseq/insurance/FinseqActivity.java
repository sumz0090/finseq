package in.finseq.insurance;

import android.content.Intent;
import android.graphics.Insets;
import android.os.Build;
import android.view.View;
import android.view.WindowInsets;
import androidx.fragment.app.FragmentActivity;

/** Common safe-area and configurable resume-lock behavior for FINSEQ screens. */
public class FinseqActivity extends FragmentActivity {
    @Override public void setContentView(View content) {
        super.setContentView(content);
        if (Build.VERSION.SDK_INT < 35 || getApplicationInfo().targetSdkVersion < 35) return;
        final int left=content.getPaddingLeft(), top=content.getPaddingTop(), right=content.getPaddingRight(), bottom=content.getPaddingBottom();
        content.setOnApplyWindowInsetsListener((view,windowInsets)->{
            Insets safe=windowInsets.getInsets(WindowInsets.Type.systemBars()|WindowInsets.Type.displayCutout());
            view.setPadding(left+safe.left,top+safe.top,right+safe.right,bottom+safe.bottom); return windowInsets;
        }); content.requestApplyInsets();
    }
    private boolean lockExempt(){return this instanceof LauncherActivity||this instanceof LoginActivity||this instanceof LockActivity||this instanceof SecuritySettingsActivity;}
    @Override protected void onStop(){super.onStop(); if(!lockExempt()&&SecurityStore.configured(this)&&!isChangingConfigurations()) SecurityStore.backgroundAt(this,System.currentTimeMillis());}
    @Override protected void onResume(){super.onResume(); if(lockExempt()||!SecurityStore.configured(this)||!Session.loggedIn(this))return; long at=SecurityStore.backgroundAt(this); if(at<=0)return; long elapsed=System.currentTimeMillis()-at; long limit=SecurityStore.timeoutMinutes(this)*60_000L; if(elapsed>=limit){SecurityStore.backgroundAt(this,0); startActivity(new Intent(this,LockActivity.class).addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP));}}
}
