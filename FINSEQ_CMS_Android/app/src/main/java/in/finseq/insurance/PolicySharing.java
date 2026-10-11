package in.finseq.insurance;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Intent;
import android.net.Uri;
import android.widget.EditText;
import android.widget.LinearLayout;
import org.json.JSONObject;
import java.net.URLEncoder;

/** Uses the Windows server's saved Motor/Other and email templates. */
public final class PolicySharing {
 private PolicySharing(){}
 public static void whatsapp(Activity a,int policyId,boolean renewal){Ui.run(a,()->{try{
  JSONObject preview=ApiClient.get(a,"/api/v1/policies/"+policyId+"/message-preview?purpose="+(renewal?"renewal":"copy"));
  String message=preview.optString("message_text");
  if(!renewal){JSONObject link=ApiClient.post(a,"/api/v1/policies/"+policyId+"/share-link",new JSONObject());String url=link.optString("url");if(url.isEmpty())throw new Exception("Policy copy link unavailable");message=message.trim()+"\n\nPolicy copy कभी भी यहाँ से download करें:\n"+url;}
  final String initial=message,phone=preview.optString("mobile");Ui.ui(a,()->{
   EditText edit=Ui.input(a,"Customize WhatsApp message");edit.setSingleLine(false);edit.setMinLines(6);edit.setText(initial);
   new AlertDialog.Builder(a).setTitle(renewal?"Renewal reminder · WhatsApp":"Policy copy · WhatsApp")
    .setMessage("Windows template तैयार है। भेजने से पहले message बदल सकते हैं।")
    .setView(edit).setPositiveButton("Open WhatsApp",(d,w)->openWhatsapp(a,policyId,renewal,phone,edit.getText().toString().trim()))
    .setNegativeButton("Cancel",null).show();
  });
 }catch(Exception e){Ui.ui(a,()->Ui.toast(a,e.getMessage()));}});}
 private static void openWhatsapp(Activity a,int id,boolean renewal,String phone,String message){
  if(message.isEmpty()){Ui.toast(a,"Message खाली नहीं हो सकता");return;}
  if(message.length()>5000){Ui.toast(a,"Message 5000 characters से छोटा रखें");return;}
  Ui.run(a,()->{try{
   ApiClient.post(a,"/api/v1/whatsapp-log",new JSONObject().put("policy_id",id).put("purpose",renewal?"Renewal Reminder":"Policy Copy Link").put("message_text",message));
   Ui.ui(a,()->{try{
    String digits=phone.replaceAll("\\D","");if(digits.length()==10)digits="91"+digits;
    Intent send;
    if(digits.isEmpty()){send=new Intent(Intent.ACTION_SEND);send.setType("text/plain");send.putExtra(Intent.EXTRA_TEXT,message);a.startActivity(Intent.createChooser(send,"Share message"));}
    else{send=new Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/"+digits+"?text="+URLEncoder.encode(message,"UTF-8")));a.startActivity(send);}
   }catch(Exception error){Ui.toast(a,error.getMessage());}});
  }catch(Exception e){Ui.ui(a,()->Ui.toast(a,e.getMessage()));}});
 }
 public static void email(Activity a,int policyId,boolean renewal){Ui.run(a,()->{try{
  JSONObject preview=ApiClient.get(a,"/api/v1/policies/"+policyId+"/message-preview?purpose="+(renewal?"renewal":"copy"));
  Ui.ui(a,()->{
   String recipient=preview.optString("email");if(recipient.isEmpty()){Ui.toast(a,"Customer Master में registered email नहीं है");return;}
   LinearLayout fields=new LinearLayout(a);fields.setOrientation(LinearLayout.VERTICAL);fields.setPadding(Ui.dp(a,12),0,Ui.dp(a,12),0);
   EditText subject=Ui.input(a,"Subject");subject.setText(preview.optString("email_subject"));fields.addView(subject);
   EditText body=Ui.input(a,"Message");body.setSingleLine(false);body.setMinLines(8);body.setText(preview.optString("email_body"));fields.addView(body);
   new AlertDialog.Builder(a).setTitle((renewal?"Renewal reminder":"Policy copy PDF")+" · "+recipient)
    .setView(fields).setPositiveButton("Send Email",(d,w)->sendEmail(a,policyId,renewal,subject.getText().toString().trim(),body.getText().toString().trim()))
    .setNegativeButton("Cancel",null).show();
  });
 }catch(Exception e){Ui.ui(a,()->Ui.toast(a,e.getMessage()));}});}
 private static void sendEmail(Activity a,int id,boolean renewal,String subject,String body){
  if(subject.isEmpty()||body.isEmpty()){Ui.toast(a,"Subject और message दोनों भरें");return;}
  Ui.run(a,()->{try{JSONObject result=ApiClient.post(a,"/api/v1/policies/"+id+"/email",new JSONObject()
   .put("purpose",renewal?"Renewal Reminder":"Policy Copy").put("subject",subject).put("message_text",body));
   Ui.ui(a,()->Ui.toast(a,result.optString("message","Email sent")));
  }catch(Exception e){Ui.ui(a,()->Ui.toast(a,e.getMessage()));}});
 }
}
