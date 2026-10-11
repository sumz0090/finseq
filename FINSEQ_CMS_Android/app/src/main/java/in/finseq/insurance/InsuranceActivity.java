package in.finseq.insurance;
import android.app.*;import android.os.*;import android.content.*;
/** Legacy route retained for compatibility; v3.0.1 uses PoliciesActivity. */
public class InsuranceActivity extends FinseqActivity{public void onCreate(Bundle b){super.onCreate(b);startActivity(new Intent(this,PoliciesActivity.class));finish();}}
