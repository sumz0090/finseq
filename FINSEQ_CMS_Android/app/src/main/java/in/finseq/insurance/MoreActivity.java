package in.finseq.insurance;
import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;
public class MoreActivity extends FinseqActivity {
 LinearLayout root,body;
 @Override public void onCreate(Bundle b){super.onCreate(b);render();}
 void render(){root=Ui.column(this);Ui.appHeader(root,this,"More");body=Ui.contentColumn(this);root.addView(body);
  if(AccessControl.has(this,"attendance"))add("✓","Attendance","Check-In / Check-Out, leave and attendance",AttendanceActivity.class);
  add("🔒","App Lock","Screen lock, change PIN or skip",SecuritySettingsActivity.class);
  if(!AccessControl.attendanceOnly(this)){
   if(AccessControl.has(this,"mf_beta"))add("MF","Mutual Funds · NSE Invest","Central UCC, investors, profiles and activity",InvestActivity.class);
   if(AccessControl.has(this,"customers"))add("♙","Customers","Search clients and Customer 360°",ClientsActivity.class);
   add("↻","Sync Latest Data","Refresh the latest permitted data",MainActivity.class);
   if(AccessControl.has(this,"policies"))add("＋","Upload Policy","PDF first · auto extraction · client linking",UploadPolicyActivity.class);
   if(AccessControl.has(this,"policies"))add("⚠","Unassigned Policies","Policies awaiting customer mapping",UnassignedPoliciesActivity.class);
   if(AccessControl.has(this,"customers"))add("👪","Family & Merge Tools","Family mapping, internal transfer and duplicate merge",FamilyToolsActivity.class);
   if(AccessControl.has(this,"renewals"))add("⏰","Renewals","Calls, WhatsApp and reminders",RenewalsActivity.class);
   if(AccessControl.has(this,"payment_review"))add("₹","Payment Review","Review/edit pending payment requests before approval",PaymentReviewActivity.class);
  }
  add("⚙","Display Settings","System Default, Light, Dark, language and font size",DisplaySettingsActivity.class);
  body.addView(Ui.sub(this,"Version "+BuildConfig.VERSION_NAME+" · Build "+BuildConfig.VERSION_CODE));
  LinearLayout logout=Ui.actionRow(this,"→","Logout","Sign out from this device");logout.setOnClickListener(new View.OnClickListener(){public void onClick(View v){DeviceAuth.forget(MoreActivity.this);Session.clear(MoreActivity.this);startActivity(new Intent(MoreActivity.this,LoginActivity.class));finishAffinity();}});body.addView(logout);
  Ui.setScreen(this,root,4);
 }
 void add(String icon,String title,String sub,final Class<?> target){LinearLayout row=Ui.actionRow(this,icon,title,sub);row.setOnClickListener(new View.OnClickListener(){public void onClick(View v){startActivity(new Intent(MoreActivity.this,target));}});body.addView(row);}
}
