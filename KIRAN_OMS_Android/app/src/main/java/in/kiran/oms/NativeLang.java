package in.kiran.oms;

import android.view.View;
import android.view.ViewGroup;
import android.widget.EditText;
import android.widget.TextView;

import java.util.HashMap;
import java.util.Map;
import java.util.WeakHashMap;

final class NativeLang {
    private static final Map<String,String> HI = new HashMap<>();
    private static final WeakHashMap<TextView,String> ORIGINAL_TEXT = new WeakHashMap<>();
    private static final WeakHashMap<EditText,String> ORIGINAL_HINT = new WeakHashMap<>();

    static {
        HI.put("Home","होम");
        HI.put("Orders","ऑर्डर");
        HI.put("Production","उत्पादन");
        HI.put("Stock","स्टॉक");
        HI.put("More","अधिक");
        HI.put("Operations Overview","ऑपरेशंस अवलोकन");
        HI.put("Pending","लंबित");
        HI.put("In Production","उत्पादन में");
        HI.put("Production","उत्पादन");
        HI.put("Ready","डिस्पैच हेतु तैयार");
        HI.put("Ready for Dispatch","डिस्पैच हेतु तैयार");
        HI.put("Dispatched","डिस्पैच किया गया");
        HI.put("On Hold","होल्ड पर");
        HI.put("HOLD","होल्ड");
        HI.put("Open PO","खुले खरीद ऑर्डर");
        HI.put("Quick Actions","त्वरित कार्रवाई");
        HI.put("Attention","ध्यान आवश्यक");
        HI.put("Recent Orders","हाल के ऑर्डर");
        HI.put("+ New Order","+ नया ऑर्डर");
        HI.put("+ New","+ नया");
        HI.put("Refresh","रिफ्रेश");
        HI.put("Pending 7+ days","7+ दिन से लंबित");
        HI.put("Orders on hold","होल्ड पर ऑर्डर");
        HI.put("Purchase orders open","खुले खरीद ऑर्डर");
        HI.put("Order Management","ऑर्डर प्रबंधन");
        HI.put("Master Orders","ऑर्डर प्रबंधन");
        HI.put("Search, filter and open native order details","ऑर्डर खोजें, फ़िल्टर करें और विवरण खोलें");
        HI.put("All","सभी");
        HI.put("Hold","होल्ड");
        HI.put("Cancelled","रद्द");
        HI.put("Production Board","उत्पादन बोर्ड");
        HI.put("Native workflow board • tap an order for actions","नेटिव वर्कफ़्लो बोर्ड • कार्रवाई के लिए ऑर्डर पर टैप करें");
        HI.put("Stock Control","स्टॉक नियंत्रण");
        HI.put("Central Sole / Box inventory overview","केंद्रीय सोल / बॉक्स इन्वेंटरी अवलोकन");
        HI.put("Sole Stock","सोल स्टॉक");
        HI.put("Box Stock","बॉक्स स्टॉक");
        HI.put("Opening","ओपनिंग");
        HI.put("Received","प्राप्त");
        HI.put("Incoming","आने वाला");
        HI.put("Demand","आवश्यकता");
        HI.put("SHORT","कमी");
        HI.put("System","सिस्टम");
        HI.put("Server","सर्वर");
        HI.put("Appearance","दिखावट");
        HI.put("Central Sync","केंद्रीय सिंक");
        HI.put("Manual Backup","मैनुअल बैकअप");
        HI.put("Change","बदलें");
        HI.put("Light","लाइट");
        HI.put("Dark","डार्क");
        HI.put("Light Mode","लाइट मोड");
        HI.put("Dark Mode","डार्क मोड");
        HI.put("Backup","बैकअप");
        HI.put("Purchase / PO","खरीद / PO");
        HI.put("About","जानकारी");
        HI.put("Native Android UI • No WebView • LAN central data","नेटिव Android UI • WebView नहीं • LAN केंद्रीय डेटा");
        HI.put("Party","पार्टी");
        HI.put("Article","आर्टिकल");
        HI.put("Factory","फैक्टरी");
        HI.put("Colour","रंग");
        HI.put("Material","सामग्री");
        HI.put("Box","बॉक्स");
        HI.put("Sole","सोल");
        HI.put("Total Pairs","कुल जोड़ी");
        HI.put("Rate","दर");
        HI.put("Order Date","ऑर्डर तिथि");
        HI.put("Production Date","उत्पादन तिथि");
        HI.put("Ready for Dispatch Date","तैयार तिथि");
        HI.put("Dispatch Date","डिस्पैच तिथि");
        HI.put("Batch No.","बैच नं.");
        HI.put("Size Wise Pairs","साइज-वार जोड़ी");
        HI.put("New Order","नया ऑर्डर");
        HI.put("Save","सेव करें");
        HI.put("Cancel","रद्द करें");
        HI.put("Close","बंद करें");
        HI.put("Continue","जारी रखें");
        HI.put("Confirm","पुष्टि करें");
        HI.put("Put On Hold","होल्ड पर रखें");
        HI.put("Resume Order","ऑर्डर पुनः शुरू करें");
        HI.put("Resume","पुनः शुरू करें");
        HI.put("Move to Production","उत्पादन में भेजें");
        HI.put("Confirm Status","स्थिति की पुष्टि करें");
        HI.put("Possible Duplicate","संभावित डुप्लिकेट");
        HI.put("Save Anyway","फिर भी सेव करें");
        HI.put("Select Factory","फैक्टरी चुनें");
        HI.put("Select Party","पार्टी चुनें");
        HI.put("Select Colour","रंग चुनें");
        HI.put("Select D/M/L","D/M/L चुनें");
        HI.put("Select Box","बॉक्स चुनें");
        HI.put("Select Sole","सोल चुनें");
        HI.put("No sole stock data","सोल स्टॉक डेटा उपलब्ध नहीं");
        HI.put("No box stock data","बॉक्स स्टॉक डेटा उपलब्ध नहीं");
        HI.put("No pending PO","कोई खुला खरीद ऑर्डर नहीं");
    }

    private NativeLang() {}

    static String status(String raw, boolean hindi) {
        if (!hindi) {
            if ("Ready".equalsIgnoreCase(raw)) return "Ready for Dispatch";
            if ("HOLD".equalsIgnoreCase(raw) || "Hold".equalsIgnoreCase(raw)) return "On Hold";
            return raw;
        }
        if ("Ready".equalsIgnoreCase(raw) || "Ready for Dispatch".equalsIgnoreCase(raw)) return "डिस्पैच हेतु तैयार";
        if ("In Production".equalsIgnoreCase(raw)) return "उत्पादन में";
        if ("Dispatched".equalsIgnoreCase(raw)) return "डिस्पैच किया गया";
        if ("Pending".equalsIgnoreCase(raw)) return "लंबित";
        if ("Cancelled".equalsIgnoreCase(raw)) return "रद्द";
        if ("HOLD".equalsIgnoreCase(raw) || "Hold".equalsIgnoreCase(raw) || "On Hold".equalsIgnoreCase(raw)) return "होल्ड पर";
        return raw;
    }

    static String text(String raw, boolean hindi) {
        if (raw == null) return "";
        String s = raw;
        if (!hindi) {
            if ("Master Orders".equals(s)) return "Order Management";
            if ("Ready".equals(s)) return "Ready for Dispatch";
            if ("HOLD".equals(s) || "Hold".equals(s)) return "On Hold";
            if ("Open PO".equals(s)) return "Open Purchase Orders";
            if ("Production".equals(s)) return "In Production";
            return s;
        }
        String exact = HI.get(s);
        if (exact != null) return exact;
        if (s.matches("\\d+ pairs")) return s.replace(" pairs", " जोड़ी");
        if (s.matches("\\d+ orders • \\d+ pairs")) return s.replace(" orders", " ऑर्डर").replace(" pairs", " जोड़ी");
        if (s.startsWith("Live central LAN data • ")) return s.replace("Live central LAN data", "लाइव केंद्रीय LAN डेटा").replace(" total orders", " कुल ऑर्डर");
        if (s.startsWith("Connected to ")) return s.replace("Connected to ", "कनेक्टेड: ");
        if (s.startsWith("State version ")) return s.replace("State version ", "स्टेट वर्ज़न ");
        if (s.startsWith("Create server-side backup now")) return "अभी सर्वर बैकअप बनाएं";
        if (s.startsWith("Pending ")) return s.replace("Pending ", "लंबित ");
        if (s.startsWith("Order #")) return s.replace("Order #", "ऑर्डर #");
        if (s.startsWith("Next: ")) return "अगला: " + status(s.substring(6), true);
        if (s.startsWith("Order ") && s.contains(" · ")) return s.replace("Order ", "ऑर्डर ").replace("Prod ", "उत्पादन ").replace("Ready ", "तैयार ").replace("Dispatch ", "डिस्पैच ");
        if (s.endsWith(" pairs")) return s.substring(0,s.length()-6)+" जोड़ी";
        return status(s,true);
    }

    static void apply(View root, boolean hindi) {
        if (root == null) return;
        if (root instanceof TextView) {
            TextView tv=(TextView)root;
            if (!ORIGINAL_TEXT.containsKey(tv)) ORIGINAL_TEXT.put(tv, String.valueOf(tv.getText()));
            tv.setText(text(ORIGINAL_TEXT.get(tv), hindi));
            if (tv instanceof EditText) {
                EditText e=(EditText)tv;
                if (!ORIGINAL_HINT.containsKey(e)) ORIGINAL_HINT.put(e, e.getHint()==null?"":String.valueOf(e.getHint()));
                String h=ORIGINAL_HINT.get(e);
                if (h!=null && !h.isEmpty()) e.setHint(text(h,hindi));
            }
        }
        if (root instanceof ViewGroup) {
            ViewGroup g=(ViewGroup)root;
            for (int i=0;i<g.getChildCount();i++) apply(g.getChildAt(i),hindi);
        }
    }
}
