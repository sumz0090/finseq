package in.finseq.insurance;
import android.app.*;import android.os.*;import android.content.*;import android.provider.Settings;import android.graphics.Color;import android.text.InputType;import android.view.*;import android.widget.*;
import androidx.annotation.NonNull;
import androidx.biometric.BiometricManager;
import androidx.biometric.BiometricPrompt;
import androidx.core.content.ContextCompat;
public class SecuritySettingsActivity extends FinseqActivity {
 private static final int RC_SECURITY_SETTINGS=7102;FrameLayout credentialCover;boolean authenticating=false;
 private final int AUTHENTICATORS=BiometricManager.Authenticators.BIOMETRIC_WEAK|BiometricManager.Authenticators.DEVICE_CREDENTIAL;
 @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);render();}
 private void render(){
  LinearLayout root=Ui.column(this);Ui.appHeader(root,this,"App Security");LinearLayout body=Ui.contentColumn(this);root.addView(body);
  TextView symbol=Ui.text(this,"🔐",72,false);symbol.setGravity(Gravity.CENTER);symbol.setPadding(0,Ui.dp(this,26),0,Ui.dp(this,12));body.addView(symbol);
  TextView heading=Ui.text(this,"How would you like to\nsecure your app?",23,true);heading.setGravity(Gravity.CENTER);heading.setPadding(0,Ui.dp(this,10),0,Ui.dp(this,24));body.addView(heading);
  option(body,"Use Face / Fingerprint / Screen Lock","Use your phone Face Unlock, fingerprint or PIN/pattern/password",SecurityStore.systemLock(this),new Runnable(){public void run(){enableSystemLock();}});
  option(body,"Change PIN","Change your FINSEQ PIN. You'll need to remember this PIN at login time",SecurityStore.hasPin(this),new Runnable(){public void run(){changePin();}});
  option(body,"Skip","I would like to skip App Lock.",SecurityStore.skipped(this),new Runnable(){public void run(){confirmSkip();}});
  option(body,"Auto-lock Timeout","Lock after "+SecurityStore.timeoutMinutes(this)+" minute(s) in background",false,new Runnable(){public void run(){chooseTimeout();}});
  FrameLayout frame=new FrameLayout(this);frame.addView(Ui.scroll(this,root),new FrameLayout.LayoutParams(-1,-1));credentialCover=new FrameLayout(this);credentialCover.setBackgroundColor(Color.rgb(8,17,29));credentialCover.setVisibility(View.GONE);frame.addView(credentialCover,new FrameLayout.LayoutParams(-1,-1));setContentView(frame);
 }
 private void option(LinearLayout body,String title,String subtitle,boolean selected,final Runnable action){
  LinearLayout row=Ui.card(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);row.setMinimumHeight(Ui.dp(this,104));
  LinearLayout info=new LinearLayout(this);info.setOrientation(LinearLayout.VERTICAL);info.addView(Ui.text(this,title,17,true));info.addView(Ui.sub(this,subtitle));row.addView(info,new LinearLayout.LayoutParams(0,-2,1));
  TextView check=Ui.text(this,selected?"●":"○",26,true);check.setTextColor(selected?Ui.GREEN:Ui.mutedColor(this));check.setPadding(Ui.dp(this,12),0,0,0);row.addView(check);row.setOnClickListener(new View.OnClickListener(){public void onClick(View v){action.run();}});body.addView(row);
 }
 private void confirmSkip(){new AlertDialog.Builder(this).setTitle("Skip App Lock?").setMessage("FINSEQ will open without an additional app lock on this phone.").setNegativeButton("Cancel",null).setPositiveButton("Skip",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){SecurityStore.skip(SecuritySettingsActivity.this);openMain();}}).show();}
 private void changePin(){
  final EditText input=Ui.input(this,"New 4–6 digit PIN");input.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_VARIATION_PASSWORD);
  new AlertDialog.Builder(this).setTitle(SecurityStore.hasPin(this)?"Change PIN":"Set PIN").setView(input).setNegativeButton("Cancel",null).setPositiveButton("Save",new DialogInterface.OnClickListener(){public void onClick(DialogInterface d,int w){String value=input.getText().toString().trim();if(!value.matches("\\d{4,6}")){Ui.toast(SecuritySettingsActivity.this,"PIN must be 4–6 digits");return;}SecurityStore.choosePin(SecuritySettingsActivity.this,value);Ui.toast(SecuritySettingsActivity.this,"App PIN saved");if(getIntent().getBooleanExtra("first_setup",false))openMain();else render();}}).show();
 }
 private void enableSystemLock(){
  BiometricManager bm=BiometricManager.from(this);int state=bm.canAuthenticate(AUTHENTICATORS);
  if(state!=BiometricManager.BIOMETRIC_SUCCESS){Ui.toast(this,"Set up Face, Fingerprint or a secure phone screen lock in Android Settings first.");try{startActivityForResult(new Intent(Settings.ACTION_SECURITY_SETTINGS),RC_SECURITY_SETTINGS);}catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}return;}
  if(authenticating)return;authenticating=true;credentialCover.setVisibility(View.VISIBLE);
  BiometricPrompt prompt=new BiometricPrompt(this,ContextCompat.getMainExecutor(this),new BiometricPrompt.AuthenticationCallback(){
   @Override public void onAuthenticationSucceeded(@NonNull BiometricPrompt.AuthenticationResult result){super.onAuthenticationSucceeded(result);authenticating=false;credentialCover.setVisibility(View.GONE);SecurityStore.chooseScreen(SecuritySettingsActivity.this);DeviceAuth.registerAsync(SecuritySettingsActivity.this);Ui.toast(SecuritySettingsActivity.this,"Face / Fingerprint / Screen Lock enabled");if(getIntent().getBooleanExtra("first_setup",false))openMain();else render();}
   @Override public void onAuthenticationError(int errorCode,@NonNull CharSequence errString){super.onAuthenticationError(errorCode,errString);authenticating=false;credentialCover.setVisibility(View.GONE);if(errorCode!=BiometricPrompt.ERROR_USER_CANCELED&&errorCode!=BiometricPrompt.ERROR_CANCELED&&errorCode!=BiometricPrompt.ERROR_NEGATIVE_BUTTON)Ui.toast(SecuritySettingsActivity.this,errString.toString());}
   @Override public void onAuthenticationFailed(){super.onAuthenticationFailed();Ui.toast(SecuritySettingsActivity.this,"Face/Fingerprint not recognized. Try again or use your phone screen lock.");}
  });
  BiometricPrompt.PromptInfo info=new BiometricPrompt.PromptInfo.Builder().setTitle("Enable FINSEQ App Lock").setSubtitle("Confirm with Face, Fingerprint or phone screen lock").setAllowedAuthenticators(AUTHENTICATORS).build();
  prompt.authenticate(info);
 }
 @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(code==RC_SECURITY_SETTINGS)enableSystemLock();}
 private void openMain(){startActivity(new Intent(this,MainActivity.class));finish();}
 private void chooseTimeout(){final String[] labels={"1 minute","2 minutes","5 minutes"};final int[] values={1,2,5};new AlertDialog.Builder(this).setTitle("Auto-lock after background").setItems(labels,(d,which)->{SecurityStore.timeoutMinutes(this,values[which]);Ui.toast(this,"Auto-lock set to "+labels[which]);render();}).show();}

}
