package in.kiran.oms;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Iterator;
import java.util.List;

final class AppState {
    final List<JSONObject> orders = new ArrayList<>();
    final List<JSONObject> boxes = new ArrayList<>();
    final List<JSONObject> poLog = new ArrayList<>();
    final List<JSONObject> purchaseLog = new ArrayList<>();
    final List<JSONObject> auditLog = new ArrayList<>();
    JSONObject soles = new JSONObject();
    JSONObject masters = new JSONObject();
    JSONObject rawState = new JSONObject();
    long version = 0;
    String updatedAt = "";

    static AppState fromStateResponse(JSONObject response) {
        AppState s = new AppState();
        s.rawState = response;
        s.version = response.optLong("version", 0);
        s.updatedAt = response.optString("updated_at", "");
        JSONObject values = response.optJSONObject("values");
        if (values == null) values = new JSONObject();

        addArray(s.orders, parseArray(values.optString("st_orders", "[]")));
        addArray(s.boxes, parseArray(values.optString("st_boxes", "[]")));
        addArray(s.poLog, parseArray(values.optString("st_po_log", "[]")));
        addArray(s.purchaseLog, parseArray(values.optString("st_purchase_log", "[]")));
        addArray(s.auditLog, parseArray(values.optString("st_audit_log", "[]")));
        s.soles = parseObject(values.optString("st_soles", "{}"));
        s.masters = parseObject(values.optString("st_masters", "{}"));

        Collections.sort(s.orders, new Comparator<JSONObject>() {
            @Override public int compare(JSONObject a, JSONObject b) {
                return Integer.compare(b.optInt("orderNo", 0), a.optInt("orderNo", 0));
            }
        });
        return s;
    }

    private static JSONArray parseArray(String raw) {
        try { return new JSONArray(raw == null || raw.isEmpty() ? "[]" : raw); }
        catch (Exception e) { return new JSONArray(); }
    }

    private static JSONObject parseObject(String raw) {
        try { return new JSONObject(raw == null || raw.isEmpty() ? "{}" : raw); }
        catch (Exception e) { return new JSONObject(); }
    }

    private static void addArray(List<JSONObject> out, JSONArray a) {
        for (int i = 0; i < a.length(); i++) {
            JSONObject o = a.optJSONObject(i);
            if (o != null) out.add(o);
        }
    }

    JSONArray ordersJson() {
        JSONArray a = new JSONArray();
        for (JSONObject o : orders) a.put(o);
        return a;
    }

    JSONObject findOrder(int orderNo) {
        for (JSONObject o : orders) if (o.optInt("orderNo", -1) == orderNo) return o;
        return null;
    }

    int countStatus(String status) {
        int n = 0;
        for (JSONObject o : orders) if (!o.optBoolean("hold", false) && status.equalsIgnoreCase(o.optString("status"))) n++;
        return n;
    }

    int countHold() {
        int n = 0;
        for (JSONObject o : orders) if (o.optBoolean("hold", false)) n++;
        return n;
    }

    int pairsStatus(String status) {
        int n = 0;
        for (JSONObject o : orders) if (!o.optBoolean("hold", false) && status.equalsIgnoreCase(o.optString("status"))) n += o.optInt("totalPairs", 0);
        return n;
    }

    List<String> masterNames(String key) {
        List<String> out = new ArrayList<>();
        JSONArray a = masters.optJSONArray(key);
        if (a == null) return out;
        for (int i=0;i<a.length();i++) {
            Object v=a.opt(i);
            if (v instanceof JSONObject) {
                String name=((JSONObject)v).optString("name","");
                if (!name.isEmpty()) out.add(name);
            } else if (v != null) {
                String name=String.valueOf(v);
                if (!name.isEmpty()) out.add(name);
            }
        }
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    List<String> soleNames() {
        List<String> out=new ArrayList<>();
        Iterator<String> it=soles.keys();
        while(it.hasNext()) out.add(it.next());
        for(String x:masterNames("sole")) if(!out.contains(x)) out.add(x);
        Collections.sort(out, String.CASE_INSENSITIVE_ORDER);
        return out;
    }

    int nextOrderNo() {
        int max=0;
        for(JSONObject o:orders) max=Math.max(max,o.optInt("orderNo",0));
        return max+1;
    }
}
