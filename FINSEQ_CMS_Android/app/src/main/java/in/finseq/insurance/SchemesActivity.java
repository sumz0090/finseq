package in.finseq.insurance;

import android.app.AlertDialog;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.widget.EditText;
import android.widget.LinearLayout;
import org.json.JSONArray;
import org.json.JSONObject;
import java.net.URLEncoder;

public class SchemesActivity extends FinseqActivity {
    private EditText search;
    private LinearLayout results;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private Runnable scheduled;
    private int sequence;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        LinearLayout root = Ui.column(this);
        Ui.appHeader(root, this, "NSE Scheme Search");
        LinearLayout body = Ui.contentColumn(this);
        root.addView(body);
        search = Ui.input(this, "Search scheme name or code");
        search.setText(getIntent().getStringExtra("search") == null ? "" : getIntent().getStringExtra("search"));
        body.addView(search);
        body.addView(Ui.sub(this, "Production NSE scheme master · tap a scheme for available details"));
        results = new LinearLayout(this);
        results.setOrientation(LinearLayout.VERTICAL);
        body.addView(results);
        setContentView(Ui.scroll(this, root));
        search.addTextChangedListener(new TextWatcher() {
            public void beforeTextChanged(CharSequence s, int st, int c, int after) {}
            public void onTextChanged(CharSequence s, int st, int before, int count) { queue(); }
            public void afterTextChanged(Editable s) {}
        });
        queue();
    }

    private void queue() {
        if (scheduled != null) handler.removeCallbacks(scheduled);
        int request = ++sequence;
        String query = search.getText().toString().trim();
        scheduled = () -> load(query, request);
        handler.postDelayed(scheduled, 250);
    }

    private void load(String query, int request) {
        results.removeAllViews();
        results.addView(Ui.sub(this, "Loading schemes…"));
        Ui.run(this, () -> {
            try {
                JSONObject response = ApiClient.get(this, "/api/v2/mf/schemes?q=" + URLEncoder.encode(query, "UTF-8"));
                Ui.ui(this, () -> { if (request == sequence && !isFinishing()) render(response.optJSONArray("schemes")); });
            } catch (Exception error) {
                Ui.ui(this, () -> { if (request == sequence && !isFinishing()) { results.removeAllViews(); results.addView(Ui.sub(this, error.getMessage())); } });
            }
        });
    }

    private void render(JSONArray schemes) {
        results.removeAllViews();
        if (schemes == null || schemes.length() == 0) { results.addView(Ui.sub(this, "No matching scheme in the current NSE master.")); return; }
        for (int i = 0; i < schemes.length(); i++) {
            JSONObject scheme = schemes.optJSONObject(i);
            if (scheme == null) continue;
            LinearLayout row = Ui.compactCard(this);
            row.addView(Ui.text(this, scheme.optString("scheme_name"), 16, true));
            row.addView(Ui.sub(this, "NSE code: " + scheme.optString("scheme_code") + " · " + (scheme.optBoolean("allowed") ? "Allowed" : "Check availability")));
            row.setOnClickListener(v -> new AlertDialog.Builder(this).setTitle(scheme.optString("scheme_name"))
                    .setMessage("NSE code: " + scheme.optString("scheme_code") + "\nMinimum: " + Ui.money(scheme.optDouble("minimum"))
                            + "\nMaximum: " + Ui.money(scheme.optDouble("maximum"))
                            + "\nSIP frequency: " + scheme.optString("frequency", "—"))
                    .setPositiveButton("Close", null).show());
            results.addView(row);
        }
    }

    @Override protected void onDestroy() {
        if (scheduled != null) handler.removeCallbacks(scheduled);
        super.onDestroy();
    }
}
