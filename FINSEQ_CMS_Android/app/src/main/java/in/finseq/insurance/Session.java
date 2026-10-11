package in.finseq.insurance;
import android.content.*;
import org.json.JSONArray;
public final class Session {
 private static final String P="finseq_session";
 static void save(Context c,String token,String name,String role,boolean remember){c.getSharedPreferences(P,0).edit().putString("token",token).putString("name",name).putString("role",role).putBoolean("remember",remember).apply();}
 static void save(Context c,String token,String name,String role,boolean remember,JSONArray features){save(c,token,name,role,remember);AccessControl.save(c,features);}
 static void username(Context c,String v){c.getSharedPreferences(P,0).edit().putString("username",v).apply();}
 static String username(Context c){return c.getSharedPreferences(P,0).getString("username","");}
 static String token(Context c){return c.getSharedPreferences(P,0).getString("token","");}
 static String name(Context c){return c.getSharedPreferences(P,0).getString("name","");}
 static String role(Context c){return c.getSharedPreferences(P,0).getString("role","");}
 static void clear(Context c){c.getSharedPreferences(P,0).edit().clear().apply();AccessControl.clear(c);}
 static boolean loggedIn(Context c){return !token(c).isEmpty();}
}
