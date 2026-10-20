package in.finseq.insurance;

import android.content.Context;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import java.nio.charset.StandardCharsets;
import java.security.KeyStore;
import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;

/** Small Android-Keystore backed secret store for authentication material. */
public final class SecurePrefs {
    private static final String PREFS="finseq_secure_auth";
    private static final String ALIAS="finseq_auth_aes_v1";
    private SecurePrefs(){}

    private static SecretKey key() throws Exception {
        KeyStore ks=KeyStore.getInstance("AndroidKeyStore"); ks.load(null);
        if(ks.containsAlias(ALIAS)) return (SecretKey)ks.getKey(ALIAS,null);
        KeyGenerator kg=KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore");
        kg.init(new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_ENCRYPT|KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true).build());
        return kg.generateKey();
    }

    public static void put(Context c,String name,String value){
        try{
            if(value==null||value.isEmpty()){remove(c,name);return;}
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding"); cipher.init(Cipher.ENCRYPT_MODE,key());
            byte[] encrypted=cipher.doFinal(value.getBytes(StandardCharsets.UTF_8));
            String packed=Base64.encodeToString(cipher.getIV(),Base64.NO_WRAP)+":"+Base64.encodeToString(encrypted,Base64.NO_WRAP);
            c.getSharedPreferences(PREFS,0).edit().putString(name,packed).apply();
        }catch(Exception e){throw new IllegalStateException("Secure session storage unavailable",e);}
    }
    public static String get(Context c,String name){
        String packed=c.getSharedPreferences(PREFS,0).getString(name,""); if(packed.isEmpty())return "";
        try{
            String[] p=packed.split(":",2); if(p.length!=2)return "";
            Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key(),new GCMParameterSpec(128,Base64.decode(p[0],Base64.NO_WRAP)));
            return new String(cipher.doFinal(Base64.decode(p[1],Base64.NO_WRAP)),StandardCharsets.UTF_8);
        }catch(Exception e){remove(c,name);return "";}
    }
    public static void remove(Context c,String name){c.getSharedPreferences(PREFS,0).edit().remove(name).apply();}
    public static void clear(Context c){c.getSharedPreferences(PREFS,0).edit().clear().apply();}
}
