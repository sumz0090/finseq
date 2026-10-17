package in.finseq.insurance;

import android.app.*;import android.os.*;import android.text.InputType;import android.view.*;import android.widget.*;
import org.json.*;

public class PaymentReviewActivity extends FinseqActivity {
 LinearLayout root,body,list; TextView status;
 @Override public void onCreate(Bundle b){super.onCreate(b);render();load();}
 void render(){
  root=Ui.column(this);Ui.appHeader(root,this,"Payment Review");body=Ui.contentColumn(this);root.addView(body);
  body.addView(Ui.text(this,"Pending Payment Requests",22,true));
  body.addView(Ui.sub(this,"Review your pending payment amount/details before approval. Your own request can be edited and resubmitted; approval remains a separate permission."));
  Button refresh=Ui.outlineButton(this,"Refresh");refresh.setOnClickListener(v->load());body.addView(refresh);
  status=Ui.sub(this,"Loading…");body.addView(status);list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);body.addView(list);
  Ui.setScreen(this,root,4);
 }
 void load(){status.setText("Loading payment requests…");list.removeAllViews();Ui.run(this,()->{try{JSONObject data=ApiClient.get(this,"/api/v1/payment-requests");Ui.ui(this,()->show(data));}catch(Exception e){Ui.ui(this,()->status.setText("Payment review unavailable: "+e.getMessage()));}});}
 void show(JSONObject data){JSONArray a=data.optJSONArray("requests");int n=a==null?0:a.length();status.setText(n+" pending request(s)");if(n==0){list.addView(Ui.sub(this,"No pending payment request."));return;}for(int i=0;i<n;i++){JSONObject r=a.optJSONObject(i);if(r!=null)addRow(r);}}
 void addRow(JSONObject r){
  LinearLayout c=Ui.card(this);c.addView(Ui.text(this,r.optString("customer")+" · "+r.optString("policy_number"),17,true));
  c.addView(Ui.sub(this,"Request #"+r.optInt("id")+" · "+r.optString("request_action")+" · "+r.optString("requested_at")));
  c.addView(Ui.text(this,Ui.money(r.optDouble("amount"))+" · "+r.optString("payment_mode"),15,true));
  c.addView(Ui.sub(this,"Date: "+r.optString("payment_date")+" · Ref: "+r.optString("reference_number")));
  String notes=r.optString("notes");if(!notes.isEmpty())c.addView(Ui.sub(this,"Notes: "+notes));
  if(r.optBoolean("can_edit")){Button edit=Ui.greenButton(this,"Review / Edit & Resubmit");edit.setOnClickListener(v->edit(r));c.addView(edit);}else c.addView(Ui.badge(this,"APPROVAL VIEW",Ui.AMBER));
  list.addView(c);
 }
 EditText field(String hint,String value){EditText e=Ui.input(this,hint);e.setText(value==null?"":value);return e;}
 void edit(JSONObject r){
  LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(Ui.dp(this,12),Ui.dp(this,4),Ui.dp(this,12),0);
  EditText date=field("Payment date (YYYY-MM-DD)",r.optString("payment_date"));box.addView(date);
  EditText amount=field("Amount",String.valueOf(r.optDouble("amount")));amount.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);box.addView(amount);
  EditText mode=field("Payment mode",r.optString("payment_mode"));box.addView(mode);
  EditText ref=field("Reference number",r.optString("reference_number"));box.addView(ref);
  EditText notes=field("Notes",r.optString("notes"));notes.setSingleLine(false);notes.setMinLines(2);box.addView(notes);
  CheckBox confirm=new CheckBox(this);confirm.setText("I reviewed the amount and entry details; resubmit for approval.");confirm.setTextColor(Ui.textColor(this));box.addView(confirm);
  AlertDialog d=new AlertDialog.Builder(this).setTitle("Review Payment Request #"+r.optInt("id")).setView(box).setPositiveButton("Confirm & Resubmit",null).setNegativeButton("Cancel",null).create();
  d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{if(!confirm.isChecked()){Ui.toast(this,"Please confirm review first.");return;}double amt;try{amt=Double.parseDouble(amount.getText().toString().trim());}catch(Exception e){Ui.toast(this,"Enter a valid amount.");return;}try{JSONObject payload=new JSONObject().put("payment_date",date.getText().toString().trim()).put("amount",amt).put("payment_mode",mode.getText().toString().trim()).put("reference_number",ref.getText().toString().trim()).put("notes",notes.getText().toString().trim()).put("review_confirm",true);resubmit(r.optInt("id"),payload,d);}catch(Exception e){Ui.toast(this,e.getMessage());}}));d.show();
 }
 void resubmit(int id,JSONObject payload,AlertDialog dialog){Ui.run(this,()->{try{JSONObject out=ApiClient.post(this,"/api/v1/payment-requests/"+id+"/resubmit",payload);Ui.ui(this,()->{dialog.dismiss();Ui.toast(this,out.optString("message","Payment request resubmitted"));load();});}catch(Exception e){Ui.ui(this,()->Ui.toast(this,e.getMessage()));}});}
}
