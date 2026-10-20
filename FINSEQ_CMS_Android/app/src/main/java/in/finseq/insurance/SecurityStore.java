package in.finseq.insurance;

import android.content.*;
import android.util.Base64;
import java.security.*;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.PBEKeySpec;

public final class SecurityStore {
    private static final String P="finseq_lock";

    static boolean hasPin(Context c){return !prefs(c).getString("pin_hash","").isEmpty();}
    static boolean hasPattern(Context c){return !prefs(c).getString("pattern_hash","").isEmpty();}
    static boolean biometric(Context c){return prefs(c).getBoolean("biometric",false);}
    static boolean systemLock(Context c){return prefs(c).getBoolean("system_lock",false);}
    static int timeoutMinutes(Context c){return prefs(c).getInt("lock_timeout_minutes",2);}
    static void timeoutMinutes(Context c,int v){prefs(c).edit().putInt("lock_timeout_minutes",v).apply();}
    static long backgroundAt(Context c){return prefs(c).getLong("background_at",0L);}
    static void backgroundAt(Context c,long v){prefs(c).edit().putLong("background_at",v).apply();}

    static void biometric(Context c,boolean v){prefs(c).edit().putBoolean("biometric",v).apply();}
    static void systemLock(Context c,boolean v){prefs(c).edit().putBoolean("system_lock",v).apply();}

    static void setPin(Context c,String v){save(c,"pin",v);}
    static void setPattern(Context c,String v){save(c,"pattern",v);}
    static boolean checkPin(Context c,String v){return check(c,"pin",v);}
    static boolean checkPattern(Context c,String v){return check(c,"pattern",v);}

    static void clearPin(Context c){prefs(c).edit().remove("pin_hash").remove("pin_salt").apply();}
    static void clearPattern(Context c){prefs(c).edit().remove("pattern_hash").remove("pattern_salt").apply();}
    static void clearAll(Context c){prefs(c).edit().clear().apply();}
    static void chooseScreen(Context c){clearAll(c);systemLock(c,true);}
    static void choosePin(Context c,String pin){clearAll(c);setPin(c,pin);}
    static void skip(Context c){clearAll(c);prefs(c).edit().putBoolean("skipped",true).apply();}
    static boolean skipped(Context c){return prefs(c).getBoolean("skipped",false);}

    private static android.content.SharedPreferences prefs(Context c){return c.getSharedPreferences(P,Context.MODE_PRIVATE);}

    private static void save(Context c,String key,String value){
        try{
            byte[] salt=new byte[16];new SecureRandom().nextBytes(salt);
            byte[] h=hash(value,salt);
            prefs(c).edit()
                    .putString(key+"_salt",Base64.encodeToString(salt,Base64.NO_WRAP))
                    .putString(key+"_hash",Base64.encodeToString(h,Base64.NO_WRAP)).apply();
        }catch(Exception e){throw new RuntimeException(e);}
    }

    private static boolean check(Context c,String key,String value){
        try{
            String ss=prefs(c).getString(key+"_salt","");
            String hh=prefs(c).getString(key+"_hash","");
            if(ss.isEmpty()||hh.isEmpty())return false;
            byte[] actual=hash(value,Base64.decode(ss,Base64.NO_WRAP));
            return MessageDigest.isEqual(actual,Base64.decode(hh,Base64.NO_WRAP));
        }catch(Exception e){return false;}
    }

    private static byte[] hash(String v,byte[] salt)throws Exception{
        return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
                .generateSecret(new PBEKeySpec(v.toCharArray(),salt,120000,256)).getEncoded();
    }

    static boolean configured(Context c){
        return systemLock(c)||hasPin(c)||hasPattern(c)||biometric(c);
    }
}
