package in.finseq.insurance;
import android.app.*;import android.os.*;import android.content.*;
/** Legacy route retained for compatibility; v3.0.1 uses InvestActivity. */
public class MutualFundActivity extends FinseqActivity{public void onCreate(Bundle b){super.onCreate(b);startActivity(new Intent(this,InvestActivity.class));finish();}}
