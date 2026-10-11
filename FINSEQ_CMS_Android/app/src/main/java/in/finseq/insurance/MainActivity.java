package in.finseq.insurance;

import android.app.*;
import android.content.*;
import android.os.*;
import android.view.*;
import android.widget.*;
import org.json.*;
import java.text.*;
import java.util.*;

public class MainActivity extends FinseqActivity {
    LinearLayout root,body,metrics,quick; String selectedMonth=DashboardPeriod.currentMonth();long backPressedAt;
    @Override public void onCreate(Bundle b){super.onCreate(b);render();load();}
    @Override protected void onResume(){super.onResume();if(root!=null)load();}

    void render(){
        root=Ui.column(this);Ui.appHeader(root,this,"Dashboard");body=Ui.contentColumn(this);root.addView(body);
        String today=new SimpleDateFormat("EEEE, dd MMMM",Locale.ENGLISH).format(new Date());
        body.addView(Ui.text(this,"Namaste, "+Session.name(this),25,true));body.addView(Ui.sub(this,today));
        Button month=Ui.greenButton(this,"Current Month ▾");month.setOnClickListener(v->chooseMonth(month));body.addView(month);
        metrics=new LinearLayout(this);metrics.setOrientation(LinearLayout.VERTICAL);body.addView(metrics);
        body.addView(section("Quick Actions"));quick=new LinearLayout(this);quick.setOrientation(LinearLayout.VERTICAL);body.addView(quick);
        if(AccessControl.has(this,"attendance"))addAction("✓","Attendance","Check-In / Check-Out and my attendance",AttendanceActivity.class);
        if(AccessControl.has(this,"customers"))addAction("♙","Customers","Search client, family and contact details",ClientsActivity.class);
        if(AccessControl.has(this,"renewals"))addAction("⏰","Renewal Dashboard","Calls, reminders and expiring policies",RenewalsActivity.class);
        if(AccessControl.has(this,"policies"))addAction("▤","Policy Inventory","Preview, share and manage policy documents",PoliciesActivity.class);
        if(AccessControl.has(this,"account_register"))addAction("₹","Accounts","Open account and ledger details",AccountsActivity.class);
        if(AccessControl.has(this,"mf_beta"))addAction("MF","Mutual Funds · NSE Invest","Client 360, UCC, orders and SIP activity",InvestActivity.class);
        Ui.setScreen(this,root,0);
    }
    TextView section(String s){TextView t=Ui.text(this,s,18,true);t.setPadding(Ui.dp(this,12),Ui.dp(this,15),0,Ui.dp(this,4));return t;}
    void addAction(String icon,String title,String sub,Class<?> target){LinearLayout row=Ui.actionRow(this,icon,title,sub);row.setOnClickListener(v->startActivity(new Intent(this,target)));quick.addView(row);}

    void chooseMonth(Button b){DashboardPeriod.choose(this,b);}

    void load(){
        Ui.run(this,()->{try{
            String path=DashboardPeriod.path(selectedMonth);
            JSONObject ins=ApiClient.get(this,path);JSONObject mf;
            try{mf=ApiClient.get(this,"/api/v2/mf/dashboard");}
            catch(Exception unavailable){mf=new JSONObject();}
            final JSONObject mfResult=mf;
            Ui.ui(this,()->showMetrics(ins,mfResult));
        }catch(Exception e){Ui.ui(this,()->{if(e instanceof ApiClient.ApiException&&((ApiClient.ApiException)e).code==401){Session.clear(this);startActivity(new Intent(this,SecurityStore.systemLock(this)?LockActivity.class:LoginActivity.class));finish();}else Ui.toast(this,e.getMessage());});}});
    }
    void showMetrics(JSONObject ins,JSONObject mf){
        metrics.removeAllViews();JSONObject s=ins.optJSONObject("summary"),m=mf.optJSONObject("summary");if(s==null)s=new JSONObject();if(m==null)m=new JSONObject();
        addMetricRow(metric("♙",String.valueOf(s.optInt("customers")),"Customers",ClientsActivity.class,""),metric("▤",String.valueOf(s.optInt("policies")),"Policies",PoliciesActivity.class,""));
        addMetricRow(metric("⏰",String.valueOf(s.optInt("due_reminders")),"Renewal Calls",RenewalsActivity.class,""),metric("₹",Ui.money(s.optDouble("customer_due")),"Family Net Due",ClientsActivity.class,"family_due"));
        addMetricRow(metric("₹",Ui.money(s.optDouble("insurer_due")),"Insurer Due",ClientsActivity.class,"insurer_due"),metric("₹",Ui.money(s.optDouble("premium")),"Total Premium",PoliciesActivity.class,""));
        addMetricRow(metric("⌕",Ui.money(s.optDouble("inspection")),"Inspection",PoliciesActivity.class,"inspection"),metric("₹",Ui.money(s.optDouble("customer_advance")),"Family Advance",ClientsActivity.class,"family_advance"));
        LinearLayout mfCard=Ui.card(this);mfCard.addView(Ui.text(this,"Mutual Fund Snapshot",17,true));mfCard.addView(Ui.sub(this,"MF data · All Time"));
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);TextView a=Ui.text(this,"Investors\n"+m.optInt("investors"),16,true);TextView p=Ui.text(this,"NSE Profiles\n"+m.optInt("nse_profiles"),16,true);TextView sip=Ui.text(this,"SIP/XSIP\n"+m.optInt("sip"),16,true);row.addView(a,new LinearLayout.LayoutParams(0,-2,1));row.addView(p,new LinearLayout.LayoutParams(0,-2,1));row.addView(sip,new LinearLayout.LayoutParams(0,-2,1));mfCard.addView(row);mfCard.setOnClickListener(v->startActivity(new Intent(this,InvestActivity.class)));metrics.addView(mfCard);
    }
    LinearLayout metric(String icon,String value,String label,Class<?> screen,String filter){
        LinearLayout card=Ui.metric(this,icon,value,label);
        card.setClickable(true);
        card.setOnClickListener(v->{Intent next=new Intent(this,screen).putExtra("balance_filter",filter).putExtra("policy_filter",filter).putExtra("period",selectedMonth);String month=DashboardPeriod.drilldownMonth(selectedMonth);if(!month.isEmpty())next.putExtra("month",month);startActivity(next);});
        return card;
    }
    void addMetricRow(View left,View right){LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.addView(left,new LinearLayout.LayoutParams(0,-2,1));row.addView(right,new LinearLayout.LayoutParams(0,-2,1));metrics.addView(row);}
    @Override public void onBackPressed(){long now=System.currentTimeMillis();if(now-backPressedAt<2000){super.onBackPressed();return;}backPressedAt=now;Ui.toast(this,"Back again to exit FINSEQ CMS");}
}
