package in.finseq.insurance;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;
import android.widget.Toast;
import org.json.JSONObject;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.Certificate;
import java.util.UUID;

/** Screen lock unlocks a device-bound signing key; the staff password is never saved. */
public final class DeviceAuth {
    private static final String PREFS="finseq_device_auth";
    private static final String ALIAS="finseq_device_signing_v1";
    private static final String BASE="https://cms.finseq.in";

    private static String deviceId(Context c) {
        String id=c.getSharedPreferences(PREFS,0).getString("device_id","");
        if(id.isEmpty()) {
            id=UUID.randomUUID().toString();
            c.getSharedPreferences(PREFS,0).edit().putString("device_id",id).apply();
        }
        return id;
    }

    private static JSONObject post(Context c,String path,JSONObject body,boolean authorized) throws Exception {
        return post(c,path,body,authorized?Session.token(c):"");
    }

    private static JSONObject post(Context c,String path,JSONObject body,String authToken) throws Exception {
        HttpURLConnection h=(HttpURLConnection)new URL(BASE+path).openConnection();
        try {
            h.setRequestMethod("POST");h.setConnectTimeout(12000);h.setReadTimeout(15000);
            h.setDoOutput(true);h.setRequestProperty("Content-Type","application/json; charset=utf-8");
            if(!authToken.isEmpty())h.setRequestProperty("Authorization","Bearer "+authToken);
            try(OutputStream out=h.getOutputStream()) {out.write(body.toString().getBytes(StandardCharsets.UTF_8));}
            int status=h.getResponseCode();InputStream input=status<400?h.getInputStream():h.getErrorStream();
            byte[] bytes=new byte[0];if(input!=null)try(InputStream in=input){java.io.ByteArrayOutputStream result=new java.io.ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=in.read(buf))!=-1)result.write(buf,0,n);bytes=result.toByteArray();}
            JSONObject response=bytes.length==0?new JSONObject():new JSONObject(new String(bytes,StandardCharsets.UTF_8));
            if(status<200||status>=300)throw new Exception(response.optString("error","Device sign-in failed")+" (HTTP "+status+")");
            return response;
        } finally {h.disconnect();}
    }

    private static KeyStore keyStore() throws Exception {
        KeyStore store=KeyStore.getInstance("AndroidKeyStore");store.load(null);return store;
    }

    private static Certificate keyCertificate() throws Exception {
        KeyStore store=keyStore();Certificate cert=store.getCertificate(ALIAS);
        if(cert!=null)return cert;
        KeyPairGenerator generator=KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA,"AndroidKeyStore");
        KeyGenParameterSpec spec=new KeyGenParameterSpec.Builder(ALIAS,KeyProperties.PURPOSE_SIGN)
                .setKeySize(2048).setDigests(KeyProperties.DIGEST_SHA256)
                .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                .setUserAuthenticationRequired(true).setUserAuthenticationValidityDurationSeconds(30).build();
        generator.initialize(spec);generator.generateKeyPair();return keyStore().getCertificate(ALIAS);
    }

    private static void register(Context c) throws Exception {
        if(Session.token(c).isEmpty())throw new Exception("First sign in with staff ID and password once.");
        JSONObject request=new JSONObject().put("device_id",deviceId(c))
                .put("public_key",Base64.encodeToString(keyCertificate().getPublicKey().getEncoded(),Base64.NO_WRAP));
        post(c,"/api/v1/device-auth/register",request,true);
        c.getSharedPreferences(PREFS,0).edit().putBoolean("registered",true).apply();
    }

    public static void registerAsync(Activity a) {
        if(!SecurityStore.systemLock(a))return;
        new Thread(new Runnable(){@Override public void run(){
            try {register(a);} catch(Exception e) {
                final String message=e.getMessage();
                a.runOnUiThread(new Runnable(){@Override public void run(){
                    Toast.makeText(a,"Device sign-in setup: "+message,Toast.LENGTH_LONG).show();
                }});
            }
        }}).start();
    }

    /** Complete device registration before leaving a successful staff login. */
    public static void registerAfterLogin(Context c) throws Exception {
        if(SecurityStore.systemLock(c))register(c);
    }

    private static void signIn(Context c) throws Exception {
        String id=deviceId(c);
        JSONObject challenge=post(c,"/api/v1/device-auth/challenge",new JSONObject().put("device_id",id),false);
        String nonce=challenge.getString("nonce");
        KeyStore store=keyStore();PrivateKey key=(PrivateKey)store.getKey(ALIAS,null);
        if(key==null)throw new Exception("Device key is missing. Sign in with staff ID once.");
        Signature signer=Signature.getInstance("SHA256withRSA");signer.initSign(key);
        signer.update(("FINSEQ-CMS:"+id+":"+nonce).getBytes(StandardCharsets.UTF_8));
        String signature=Base64.encodeToString(signer.sign(),Base64.NO_WRAP);
        JSONObject reply=post(c,"/api/v1/device-auth/login",new JSONObject()
                .put("device_id",id).put("nonce",nonce).put("signature",signature),false);
        JSONObject user=reply.getJSONObject("user");
        Session.save(c,reply.getString("token"),user.optString("name"),user.optString("role"),true,reply.optJSONArray("features"));
    }

    public static void onUnlock(Activity a) {
        new Thread(new Runnable(){@Override public void run(){
            try {
                try {signIn(a);}
                catch(Exception first) {
                    if(Session.token(a).isEmpty())throw first;
                    register(a);
                    signIn(a);
                }
                a.runOnUiThread(new Runnable(){@Override public void run(){navigate(a,AccessControl.landing(a));}});
            } catch(Exception e) {
                final String message=e.getMessage();
                a.runOnUiThread(new Runnable(){@Override public void run(){
                    boolean loginRequired=message!=null&&(message.contains("HTTP 401")||message.contains("HTTP 403")||message.contains("key is missing")||message.contains("staff login"));
                    if(a instanceof LockActivity) ((LockActivity)a).signInFailed(loginRequired
                            ? "Screen lock sign-in needs one staff login to connect this phone again."
                            : "Could not reach device sign-in: "+message,loginRequired);
                }});
            }
        }}).start();
    }

    private static void navigate(Activity a,Class<?> screen) {
        if(a.isFinishing()||a.isDestroyed())return;
        a.startActivity(new Intent(a,screen));a.finish();
    }

    public static void forget(Context c) {
        String id=c.getSharedPreferences(PREFS,0).getString("device_id","");
        String token=Session.token(c);
        if(!id.isEmpty()&&!token.isEmpty())new Thread(new Runnable(){@Override public void run(){
            try {post(c,"/api/v1/device-auth/revoke",new JSONObject().put("device_id",id),token);}catch(Exception ignored){}
        }}).start();
        try {keyStore().deleteEntry(ALIAS);}catch(Exception ignored){}
        c.getSharedPreferences(PREFS,0).edit().clear().apply();
    }
}
