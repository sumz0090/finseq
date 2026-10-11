package in.finseq.insurance;

import android.content.Context;
import android.content.SharedPreferences;

public final class AppPrefs {
    private static final String P="finseq_v301_display";
    private AppPrefs(){}
    private static SharedPreferences prefs(Context c){String user=Session.name(c);return c.getSharedPreferences(P+(user.isEmpty()?"_legacy":"_"+Integer.toHexString(user.toLowerCase(java.util.Locale.ROOT).hashCode())),0);}
    public static String theme(Context c){return prefs(c).getString("theme",prefs(c).getBoolean("dark",false)?"dark":"system");}
    public static void theme(Context c,String v){prefs(c).edit().putString("theme",v).apply();}
    public static boolean dark(Context c){String mode=theme(c);return "dark".equals(mode)||("system".equals(mode)&&((c.getResources().getConfiguration().uiMode&android.content.res.Configuration.UI_MODE_NIGHT_MASK)==android.content.res.Configuration.UI_MODE_NIGHT_YES));}
    public static void dark(Context c,boolean v){theme(c,v?"dark":"light");}
    public static float fontScale(Context c){return prefs(c).getFloat("font_scale",1.0f);}
    public static void fontScale(Context c,float v){prefs(c).edit().putFloat("font_scale",v).apply();}
    public static String language(Context c){return prefs(c).getString("language","en");}
    public static void language(Context c,String v){prefs(c).edit().putString("language",v).apply();}
}
