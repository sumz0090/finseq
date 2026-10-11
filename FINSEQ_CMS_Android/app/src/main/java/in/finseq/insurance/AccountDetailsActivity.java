package in.finseq.insurance;

import android.content.Intent;
import android.os.Bundle;
import android.widget.Button;
import android.widget.LinearLayout;
import org.json.JSONArray;
import org.json.JSONObject;

public class AccountDetailsActivity extends FinseqActivity {
    private int accountId, shown;
    private JSONArray entries;
    private LinearLayout body, rows;

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        accountId = getIntent().getIntExtra("account_id", 0);
        if (accountId <= 0) { finish(); return; }
        LinearLayout root = Ui.column(this);
        Ui.appHeader(root, this, "Account Details");
        body = Ui.contentColumn(this);
        root.addView(body);
        body.addView(Ui.sub(this, "Loading account register…"));
        setContentView(Ui.scroll(this, root));
        Ui.run(this, () -> {
            try {
                JSONObject response = ApiClient.get(this, "/api/v1/accounts/" + accountId);
                Ui.ui(this, () -> render(response));
            } catch (Exception error) {
                Ui.ui(this, () -> { body.removeAllViews(); body.addView(Ui.sub(this, error.getMessage())); });
            }
        });
    }

    private void render(JSONObject response) {
        body.removeAllViews();
        JSONObject account = response.optJSONObject("account");
        JSONObject total = response.optJSONObject("summary");
        if (account == null) account = new JSONObject();
        if (total == null) total = new JSONObject();
        LinearLayout header = Ui.card(this);
        header.addView(Ui.text(this, account.optString("account_name", "Account"), 20, true));
        header.addView(Ui.sub(this, account.optString("account_type") + " · " + account.optString("institution")));
        header.addView(Ui.text(this, "Balance  " + Ui.money(total.optDouble("balance")), 18, true));
        header.addView(Ui.sub(this, "Credits " + Ui.money(total.optDouble("credits")) + " · Debits " + Ui.money(total.optDouble("debits"))));
        body.addView(header);
        entries = response.optJSONArray("entries");
        if (entries == null || entries.length() == 0) { body.addView(Ui.sub(this, "No register entries found.")); return; }
        body.addView(Ui.text(this, "Register · " + entries.length() + " entries", 18, true));
        rows = new LinearLayout(this);
        rows.setOrientation(LinearLayout.VERTICAL);
        body.addView(rows);
        shown = 0;
        appendPage();
    }

    private void appendPage() {
        if (rows.getChildCount() > 0 && rows.getChildAt(rows.getChildCount()-1) instanceof Button)
            rows.removeViewAt(rows.getChildCount()-1);
        int end = Math.min(shown + 50, entries.length());
        for (int i = shown; i < end; i++) {
            JSONObject entry = entries.optJSONObject(i);
            if (entry == null) continue;
            LinearLayout card = Ui.compactCard(this);
            card.addView(Ui.text(this, entry.optString("party", entry.optString("kind")) + " · " + Ui.money(entry.optDouble("amount")), 16, true));
            card.addView(Ui.sub(this, entry.optString("entry_date") + " · " + entry.optString("direction") + " · " + entry.optString("category")));
            String reference = entry.optString("reference");
            if (!reference.isEmpty()) card.addView(Ui.sub(this, "Reference: " + reference));
            String notes = entry.optString("notes");
            if (!notes.isEmpty()) card.addView(Ui.sub(this, notes));
            int policy = entry.optInt("policy_id"), customer = entry.optInt("customer_id");
            if (policy > 0) card.setOnClickListener(v -> startActivity(new Intent(this, PolicyDetailActivity.class).putExtra("policy_id", policy)));
            else if (customer > 0) card.setOnClickListener(v -> startActivity(new Intent(this, Customer360Activity.class).putExtra("customer_id", customer)));
            rows.addView(card);
        }
        shown = end;
        if (shown < entries.length()) {
            Button more = Ui.outlineButton(this, "Show next entries (" + (entries.length() - shown) + " remaining)");
            more.setOnClickListener(v -> appendPage());
            rows.addView(more);
        }
    }
}
