package in.finseq.insurance;

import android.content.Context;
import org.json.JSONArray;
import java.util.HashSet;
import java.util.Set;

/** Server-supplied module permissions. UI hiding is convenience; server must still enforce each API. */
public final class AccessControl {
    private AccessControl(){}
    private static final String PREFS="finseq_access";

    public static void save(Context c, JSONArray features){
        if(features==null)return;
        c.getSharedPreferences(PREFS,0).edit().putString("features",features.toString()).apply();
    }
    public static void clear(Context c){c.getSharedPreferences(PREFS,0).edit().clear().apply();}
    public static Set<String> features(Context c){
        Set<String> out=new HashSet<>();
        String raw=c.getSharedPreferences(PREFS,0).getString("features","");
        if(raw.isEmpty())return out;
        try{JSONArray a=new JSONArray(raw);for(int i=0;i<a.length();i++)out.add(a.optString(i));}catch(Exception ignored){}
        return out;
    }
    public static boolean known(Context c){return !c.getSharedPreferences(PREFS,0).getString("features","").isEmpty();}
    public static boolean has(Context c,String feature){
        if("admin".equalsIgnoreCase(Session.role(c)))return true;
        Set<String> f=features(c);
        return !known(c) || f.contains(feature);
    }
    public static boolean attendanceOnly(Context c){
        if("admin".equalsIgnoreCase(Session.role(c))||!known(c))return false;
        Set<String> f=features(c); if(!f.contains("attendance"))return false;
        for(String x:f)if(!x.equals("attendance")&&!x.equals("dashboard"))return false;
        return true;
    }
    public static Class<?> landing(Context c){return attendanceOnly(c)?AttendanceActivity.class:MainActivity.class;}
}
