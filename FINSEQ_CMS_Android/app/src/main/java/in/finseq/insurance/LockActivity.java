package in.finseq.insurance;

import android.app.*;import android.os.*;import android.content.*;import android.graphics.Color;import android.text.InputType;import android.view.*;import android.widget.*;
import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;

public class LockActivity extends FinseqActivity{
 FrameLayout cover;EditText pin;PatternLockView pattern;boolean authenticating=false;
 private final int AUTHENTICATORS=BiometricManager.Authenticators.BIOMETRIC_WEAK|BiometricManager.Authenticators.DEVICE_CREDENTIAL;
 @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);render();autoUnlock();}
 private void render(){LinearLayout root=Ui.column(this);Ui.title(root,this,"Unlock FINSEQ CMS","Use Face, Fingerprint, phone screen lock or FINSEQ PIN");
  if(SecurityStore.systemLock(this)){Button button=Ui.greenButton(this,"Use Face / Fingerprint / Screen Lock");button.setOnClickListener(new View.OnClickListener(){public void onClick(View v){systemAuthentication();}});root.addView(button);}
  if(SecurityStore.hasPin(this)){pin=Ui.input(this,"Enter FINSEQ CMS PIN");pin.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);Button button=Ui.button(this,"Unlock with FINSEQ PIN");button.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(SecurityStore.checkPin(LockActivity.this,pin.getText().toString()))open();else Ui.toast(LockActivity.this,"Incorrect PIN");}});root.addView(pin);root.addView(button);}
  if(SecurityStore.hasPattern(this)){root.addView(Ui.text(this,"Pattern Lock",18,true));pattern=new PatternLockView(this);pattern.setListener(new PatternLockView.Listener(){public void onPattern(String value){if(SecurityStore.checkPattern(LockActivity.this,value))open();else{Ui.toast(LockActivity.this,"Incorrect pattern");pattern.reset();}}});root.addView(pattern);}
  FrameLayout frame=new FrameLayout(this);frame.setBackgroundColor(Color.rgb(8,17,29));frame.addView(Ui.scroll(this,root),new FrameLayout.LayoutParams(-1,-1));cover=new FrameLayout(this);cover.setBackgroundColor(Color.rgb(8,17,29));cover.setVisibility(View.GONE);frame.addView(cover,new FrameLayout.LayoutParams(-1,-1));setContentView(frame);
 }
 private void autoUnlock(){if(SecurityStore.systemLock(this)||SecurityStore.biometric(this))systemAuthentication();}
 private void systemAuthentication(){
  if(authenticating)return;
  BiometricManager bm=BiometricManager.from(this);int state=bm.canAuthenticate(AUTHENTICATORS);
  if(state!=BiometricManager.BIOMETRIC_SUCCESS){SecurityStore.systemLock(this,false);Ui.toast(this,"No supported Face, Fingerprint or secure phone screen lock is available.");return;}
  authenticating=true;cover.setVisibility(View.VISIBLE);
  BiometricPrompt prompt=new BiometricPrompt(this,ContextCompat.getMainExecutor(this),new BiometricPrompt.AuthenticationCallback(){
   @Override public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result){super.onAuthenticationSucceeded(result);authenticating=false;open();}
   @Override public void onAuthenticationError(int errorCode,@NonNull CharSequence errString){super.onAuthenticationError(errorCode,errString);authenticating=false;cover.setVisibility(View.GONE);if(errorCode!=BiometricPrompt.ERROR_USER_CANCELED&&errorCode!=BiometricPrompt.ERROR_CANCELED&&errorCode!=BiometricPrompt.ERROR_NEGATIVE_BUTTON)Ui.toast(LockActivity.this,errString.toString());}
   @Override public void onAuthenticationFailed(){super.onAuthenticationFailed();Ui.toast(LockActivity.this,"Face/Fingerprint not recognized. Try again or use phone screen lock.");}
  });
  BiometricPrompt.PromptInfo info=new BiometricPrompt.PromptInfo.Builder().setTitle("Unlock FINSEQ CMS").setSubtitle("Use Face, Fingerprint or your phone screen lock").setAllowedAuthenticators(AUTHENTICATORS).build();
  prompt.authenticate(info);
 }
 private void open(){if(SecurityStore.systemLock(this)){cover.setVisibility(View.VISIBLE);DeviceAuth.onUnlock(this);return;}startActivity(new Intent(this,Session.loggedIn(this)?MainActivity.class:LoginActivity.class));finish();}
 void signInFailed(String message,boolean loginRequired){authenticating=false;cover.setVisibility(View.GONE);if(loginRequired){new AlertDialog.Builder(this).setTitle("Reconnect screen lock sign-in").setMessage(message).setPositiveButton("Staff login",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int which){startActivity(new Intent(LockActivity.this,LoginActivity.class));finish();}}).setNegativeButton("Retry screen lock",null).show();}else Ui.toast(this,message);}
 @Override public void onBackPressed(){moveTaskToBack(true);}
}
