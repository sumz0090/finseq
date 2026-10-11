package in.finseq.insurance;

import android.app.AlertDialog;
import android.os.Bundle;
import android.view.View;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;

public class MfListActivity extends FinseqActivity {
    private LinearLayout list;
    private EditText search;
    private String mode;
    private JSONArray records = new JSONArray();
    private int shown, sequence;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable pending;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        mode = getIntent().getStringExtra("mode");
        if (!"profiles".equals(mode)) mode = "investors";
        LinearLayout root = Ui.column(this);
        Ui.appHeader(root, this, mode.equals("profiles") ? "NSE Profiles" : "MF Investors");
        LinearLayout body = Ui.contentColumn(this);
        root.addView(body);
        search = Ui.input(this, "Search name, PAN or UCC");
        body.addView(search);
        list = new LinearLayout(this);
        list.setOrientation(LinearLayout.VERTICAL);
        body.addView(list);
        setContentView(Ui.scroll(this, root));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            public void onTextChanged(CharSequence s, int start, int before, int count) { queueLoad(); }
            public void afterTextChanged(Editable s) {}
        });
        queueLoad();
    }

    private void queueLoad() {
        if (pending != null) handler.removeCallbacks(pending);
        final int current = ++sequence;
        final String query = search.getText().toString().trim();
        pending = new Runnable(){public void run(){load(query,current);}};
        handler.postDelayed(pending, 250);
    }

    private void load(String query, final int request) {
        list.removeAllViews();
        list.addView(Ui.sub(this, "Loading…"));
        Ui.run(this,new Runnable(){public void run(){try{
            String path=mode.equals("profiles")?"/api/v2/mf/ucc":"/api/v2/mf/investors";
            if(!query.isEmpty())path+="?q="+URLEncoder.encode(query,"UTF-8");
            JSONObject response=ApiClient.get(MfListActivity.this,path);
            final JSONArray rows=response.optJSONArray(mode.equals("profiles")?"profiles":"investors");
            Ui.ui(MfListActivity.this,new Runnable(){public void run(){if(request==sequence&&!isFinishing()){records=rows==null?new JSONArray():rows;render();}}});
        }catch(final Exception error){Ui.ui(MfListActivity.this,new Runnable(){public void run(){if(request==sequence&&!isFinishing()){list.removeAllViews();list.addView(Ui.sub(MfListActivity.this,error.getMessage()));}}});}}});
    }

    private void render() {
        list.removeAllViews();
        shown = 0;
        if (records.length() == 0) { list.addView(Ui.sub(this, "No record found.")); return; }
        list.addView(Ui.sub(this, records.length() + " record(s) · tap a row for details"));
        appendPage();
    }

    private void appendPage() {
        if (list.getChildCount() > 1 && list.getChildAt(list.getChildCount()-1) instanceof Button)
            list.removeViewAt(list.getChildCount()-1);
        int end = Math.min(shown + 50, records.length());
        for (int i = shown; i < end; i++) {
            final JSONObject record = records.optJSONObject(i);
            if (record == null) continue;
            LinearLayout card = Ui.compactCard(this);
            String name = record.optString("name", record.optString("ucc", "Profile"));
            card.addView(Ui.text(this, name, 17, true));
            String sub = record.optString("pan");
            if (!record.optString("ucc").isEmpty()) sub += " · " + record.optString("ucc");
            JSONArray uccs = record.optJSONArray("uccs");
            if (uccs != null && uccs.length() > 0) sub += " · " + uccs.length() + " UCC";
            card.addView(Ui.sub(this, sub));
            String status = record.optString("status", record.optString("kyc_status"));
            if (!status.isEmpty()) card.addView(Ui.badge(this, status, Ui.GREEN));
            card.setOnClickListener(new View.OnClickListener(){public void onClick(View v){open(record);}});
            list.addView(card);
        }
        shown = end;
        if (shown < records.length()) {
            Button more = Ui.outlineButton(this, "Show next 50 (" + (records.length() - shown) + " remaining)");
            more.setOnClickListener(new View.OnClickListener(){public void onClick(View v){appendPage();}});
            list.addView(more);
        }
    }

    private void open(JSONObject record) {
        if ("profiles".equals(mode)) { openFullProfile(record); return; }
        StringBuilder detail = new StringBuilder();
        for (String[] field : new String[][]{{"PAN", "pan"}, {"Mobile", "mobile"}, {"Email", "email"},
                {"Tax status", "tax_status"}, {"KYC", "kyc_status"}, {"Consent", "consent_status"},
                {"UCC", "ucc"}, {"Exchange status", "status"}, {"Holding nature", "holding_nature"},
                {"Last fetched", "fetched_at"}}) {
            String value = record.optString(field[1]);
            if (!value.isEmpty()) detail.append(field[0]).append(": ").append(value).append("\n");
        }
        JSONArray linked = record.optJSONArray("uccs");
        if (linked != null && linked.length() > 0) detail.append("Linked UCCs: ").append(linked.toString());
        new AlertDialog.Builder(this).setTitle(record.optString("name", record.optString("ucc", "Details")))
                .setMessage(detail.length() == 0 ? "No additional details returned by the server." : detail.toString())
                .setPositiveButton("Close", null).show();
    }

    private void openFullProfile(final JSONObject record) {
        final int id=record.optInt("id");if(id<=0)return;
        Ui.run(this,new Runnable(){public void run(){try{
            JSONObject reply=ApiClient.get(MfListActivity.this,"/api/v2/mf/ucc/"+id+"/detail");
            JSONObject profile=reply.optJSONObject("profile"),fields=reply.optJSONObject("nse_fields");final StringBuilder detail=new StringBuilder();
            if(profile!=null)appendFields(detail,profile);if(fields!=null)appendFields(detail,fields);
            Ui.ui(MfListActivity.this,new Runnable(){public void run(){new AlertDialog.Builder(MfListActivity.this).setTitle(record.optString("name","NSE Client")).setMessage(detail.length()==0?"NSE returned no profile fields.":detail.toString()).setPositiveButton("Close",null).show();}});
        }catch(final Exception error){Ui.ui(MfListActivity.this,new Runnable(){public void run(){Ui.toast(MfListActivity.this,error.getMessage());}});}}});
    }
    private void appendFields(StringBuilder detail,JSONObject values){java.util.Iterator<String> keys=values.keys();while(keys.hasNext()){
        String key=keys.next();if(key.equals("raw_profile_json"))continue;
        Object value=values.opt(key);detail.append(key.replace('_',' ')).append(": ").append(value==null||value==JSONObject.NULL?"—":value.toString()).append("\n\n");
    }}
    @Override protected void onDestroy() {
        if (pending != null) handler.removeCallbacks(pending);
        super.onDestroy();
    }
}
