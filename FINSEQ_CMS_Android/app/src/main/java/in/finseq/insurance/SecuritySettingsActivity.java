package in.finseq.insurance;
import android.app.*;import android.os.*;import android.content.*;import android.provider.Settings;import android.graphics.Color;import android.text.InputType;import android.view.*;import android.widget.*;
public class SecuritySettingsActivity extends FinseqActivity {
 private static final int RC_CONFIRM_DEVICE=7101,RC_SECURITY_SETTINGS=7102;FrameLayout credentialCover;
 @Override public void onCreate(Bundle b){super.onCreate(b);getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);render();}
 private void render(){
  LinearLayout root=Ui.column(this);Ui.appHeader(root,this,"App Security");LinearLayout body=Ui.contentColumn(this);root.addView(body);
  TextView symbol=Ui.text(this,"🔐",72,false);symbol.setGravity(Gravity.CENTER);symbol.setPadding(0,Ui.dp(this,26),0,Ui.dp(this,12));body.addView(symbol);
  TextView heading=Ui.text(this,"How would you like to\nsecure your app?",23,true);heading.setGravity(Gravity.CENTER);heading.setPadding(0,Ui.dp(this,10),0,Ui.dp(this,24));body.addView(heading);
  option(body,"Use Your Screen Lock","Protect this app by using your existing phone screen lock",SecurityStore.systemLock(this),new Runnable(){public void run(){enableSystemLock();}});
  option(body,"Change PIN","Change your PIN. You'll need to remember this PIN at login time",SecurityStore.hasPin(this),new Runnable(){public void run(){changePin();}});
  option(body,"Skip","I would like to skip App Lock.",SecurityStore.skipped(this),new Runnable(){public void run(){confirmSkip();}});
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
 private void enableSystemLock(){KeyguardManager km=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);if(km==null||!km.isDeviceSecure()){Ui.toast(this,"Set a phone PIN, pattern or password in Android Settings first.");try{startActivityForResult(new Intent(Settings.ACTION_SECURITY_SETTINGS),RC_SECURITY_SETTINGS);}catch(Exception e){startActivity(new Intent(Settings.ACTION_SETTINGS));}return;}Intent confirm=km.createConfirmDeviceCredentialIntent("Enable FINSEQ App Lock","Confirm your phone screen lock");if(confirm==null){Ui.toast(this,"Android could not confirm phone screen lock");return;}credentialCover.setVisibility(View.VISIBLE);try{startActivityForResult(confirm,RC_CONFIRM_DEVICE);}catch(Exception error){credentialCover.setVisibility(View.GONE);Ui.toast(this,"Android could not open the screen-lock prompt");}}
 @Override protected void onActivityResult(int code,int result,Intent data){super.onActivityResult(code,result,data);if(code==RC_CONFIRM_DEVICE){credentialCover.setVisibility(View.GONE);if(result==RESULT_OK){SecurityStore.chooseScreen(this);DeviceAuth.registerAsync(this);Ui.toast(this,"Screen lock enabled");if(getIntent().getBooleanExtra("first_setup",false))openMain();else render();}else Ui.toast(this,"Screen lock was not confirmed");}else if(code==RC_SECURITY_SETTINGS){KeyguardManager km=(KeyguardManager)getSystemService(KEYGUARD_SERVICE);if(km!=null&&km.isDeviceSecure())enableSystemLock();}}
 private void openMain(){startActivity(new Intent(this,MainActivity.class));finish();}
}
