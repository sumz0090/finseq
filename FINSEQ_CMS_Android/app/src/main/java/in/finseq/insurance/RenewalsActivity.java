package in.finseq.insurance;

import android.app.*;import android.content.*;import android.os.*;import android.view.*;import android.widget.*;
import org.json.*;import java.time.*;import java.net.URLEncoder;

public class RenewalsActivity extends FinseqActivity {
 LinearLayout body,filters,list,dateRow,actionRow;
 EditText search;
 Button next,expired,renewed,window,fromButton,toButton,apply,reset,more;
 TextView result;
 String tab="next",period="30",from="",to="";
 int days=7,page=0,sequence=0;
 @Override public void onCreate(Bundle b){
  super.onCreate(b);
  LinearLayout root=Ui.column(this);Ui.appHeader(root,this,"Renewal Dashboard");
  body=Ui.contentColumn(this);root.addView(body);
  LinearLayout cards=new LinearLayout(this);cards.setOrientation(LinearLayout.HORIZONTAL);
  next=Ui.button(this,"Next");expired=Ui.outlineButton(this,"Expired");renewed=Ui.outlineButton(this,"Renewed");
  for(Button btn:new Button[]{next,expired,renewed})cards.addView(btn,new LinearLayout.LayoutParams(0,-2,1));
  body.addView(cards);
  next.setOnClickListener(v->select("next"));expired.setOnClickListener(v->select("expired"));renewed.setOnClickListener(v->select("renewed"));
  filters=Ui.card(this);body.addView(filters);
  search=Ui.input(this,"Search policy, customer, mobile, insurer, vehicle");filters.addView(search);
  search.addTextChangedListener(new RenewalSearchWatcher(this));
  window=Ui.outlineButton(this,"");filters.addView(window);window.setOnClickListener(v->choosePeriod());
  fromButton=Ui.outlineButton(this,"From: Any");toButton=Ui.outlineButton(this,"To: Any");
  dateRow=new LinearLayout(this);dateRow.setOrientation(LinearLayout.HORIZONTAL);filters.addView(dateRow);
  dateRow.addView(fromButton,new LinearLayout.LayoutParams(0,-2,1));
  dateRow.addView(toButton,new LinearLayout.LayoutParams(0,-2,1));
  fromButton.setOnClickListener(v->pick(true));toButton.setOnClickListener(v->pick(false));
  apply=Ui.greenButton(this,"Apply Filters");reset=Ui.outlineButton(this,"Reset Filters");
  actionRow=new LinearLayout(this);actionRow.setOrientation(LinearLayout.HORIZONTAL);filters.addView(actionRow);
  actionRow.addView(apply,new LinearLayout.LayoutParams(0,-2,1));
  actionRow.addView(reset,new LinearLayout.LayoutParams(0,-2,1));
  apply.setOnClickListener(v->{
   if(from.isEmpty()||to.isEmpty()){Ui.toast(this,"Select both From and To dates");return;}
   refresh();
  });
  reset.setOnClickListener(v->{from=to="";period="30";days=7;setControls();refresh();});
  result=Ui.sub(this,"");body.addView(result);
  list=new LinearLayout(this);list.setOrientation(LinearLayout.VERTICAL);body.addView(list);
  more=Ui.outlineButton(this,"Show next 50");more.setOnClickListener(v->{page++;load(false);});
  setContentView(Ui.scroll(this,root));setControls();load(true);
 }
 void select(String chosen){tab=chosen;period="30";days=7;from=to="";page=0;setControls();load(true);}
 void styleTab(Button button,boolean selected){
  android.graphics.drawable.GradientDrawable background=Ui.round(selected?Ui.BLUE:Ui.cardColor(this),14);
  if(!selected)background.setStroke(Ui.dp(this,1),Ui.lineColor(this));
  button.setBackground(background);
  button.setTextColor(selected?android.graphics.Color.WHITE:Ui.textColor(this));
  button.setEnabled(true);
 }
 void setControls(){
  boolean custom=period.equals("custom");
  window.setText(custom?"Custom ▾":tab.equals("next")?"Next "+days+" days ▾":period.equals("all")?"All dates ▾":"Last "+period+" days ▾");
  fromButton.setText("From: "+(from.isEmpty()?"Any":from));
  toButton.setText("To: "+(to.isEmpty()?"Any":to));
  styleTab(next,tab.equals("next"));styleTab(expired,tab.equals("expired"));styleTab(renewed,tab.equals("renewed"));
  dateRow.setVisibility(custom?View.VISIBLE:View.GONE);
  actionRow.setVisibility(custom?View.VISIBLE:View.GONE);
 }
 void choosePeriod(){
  final boolean upcoming=tab.equals("next");
  final String[] labels=upcoming?new String[]{"Next 7 days","Next 15 days","Next 30 days","Next 60 days","Custom"}:new String[]{"Last 7 days","Last 15 days","Last 30 days","Last 60 days","Last 90 days","All dates","Custom"};
  new AlertDialog.Builder(this).setTitle("Renewal period").setItems(labels,(d,which)->{
   if(which==labels.length-1){period="custom";from=to="";setControls();return;}
   if(upcoming){days=new int[]{7,15,30,60}[which];period="30";}
   else period=which==5?"all":new String[]{"7","15","30","60","90"}[which];
   from=to="";setControls();refresh();
  }).show();
 }
 void pick(boolean start){
  String current=start?from:to;LocalDate date;
  try{date=LocalDate.parse(current);}catch(Exception e){date=LocalDate.now();}
  new DatePickerDialog(this,(picker,y,m,d)->{
   String selected=LocalDate.of(y,m+1,d).toString();
   if(start)from=selected;else to=selected;
   setControls();
  },date.getYear(),date.getMonthValue()-1,date.getDayOfMonth()).show();
 }
 void refresh(){
  if(!from.isEmpty()&&!to.isEmpty()&&from.compareTo(to)>0){Ui.toast(this,"From date must be before To date");return;}
  page=0;load(true);
 }
 void load(boolean reset){final int request=++sequence;if(reset){list.removeAllViews();result.setText("Loading renewals…");}try{
  String path="/api/v1/renewals/dashboard?tab="+tab+"&days="+days+"&period="+period+"&page="+page+"&from="+from+"&to="+to+"&q="+URLEncoder.encode(search.getText().toString().trim(),"UTF-8");
  Ui.run(this,()->{try{JSONObject data=ApiClient.get(this,path);Ui.ui(this,()->{if(request==sequence&&!isFinishing())show(data,reset);});}catch(Exception e){Ui.ui(this,()->{if(request==sequence)result.setText("Renewals unavailable: "+e.getMessage());});}});
 }catch(Exception e){Ui.toast(this,e.getMessage());}}
 void show(JSONObject data,boolean reset){if(reset)list.removeAllViews();JSONObject counts=data.optJSONObject("counts");if(counts!=null){next.setText("Next\n"+counts.optInt("next"));expired.setText("Expired\n"+counts.optInt("expired"));renewed.setText("Renewed\n"+counts.optInt("renewed"));}
  int total=data.optInt("total");result.setText(total+" "+tab+" entries · Total Premium "+Ui.money(data.optDouble("listed_gross_premium"))+(total>50?" · Page "+(page+1):""));
  if(more.getParent() instanceof LinearLayout)((LinearLayout)more.getParent()).removeView(more);
  JSONArray rows=data.optJSONArray("policies");if(rows!=null)for(int i=0;i<rows.length();i++){JSONObject row=rows.optJSONObject(i);if(row!=null)addRow(row);}
  if(total==0)list.addView(Ui.sub(this,"No policies in this selection."));
  if((page+1)*50<total)list.addView(more);
 }
 void addRow(JSONObject r){LinearLayout card=Ui.card(this);String part=r.optString("renewal_component");card.addView(Ui.text(this,r.optString("customer_name")+" · "+r.optString("policy_number"),17,true));card.addView(Ui.sub(this,r.optString("insurer")+" · "+r.optString("policy_type")+(part.isEmpty()?"":" · "+part)));
  if(tab.equals("renewed")){card.addView(Ui.sub(this,"Renewal/issue date: "+r.optString("renewal_date")+" · New expiry: "+r.optString("new_expiry")));card.addView(Ui.badge(this,"RENEWED",Ui.GREEN));}
  else{card.addView(Ui.sub(this,"Expiry: "+r.optString("expiry_date")+" · "+r.optInt("days_left")+" days"));card.addView(Ui.text(this,Ui.money(r.optDouble("gross_premium")),15,true));}
  final int id=r.optInt("id");Button open=Ui.outlineButton(this,"Policy Details");card.addView(open);open.setOnClickListener(v->startActivity(new Intent(this,PolicyDetailActivity.class).putExtra("policy_id",id)));
  if(tab.equals("renewed")){int newer=r.optInt("renewed_policy_id");if(newer>0){Button newPolicy=Ui.button(this,"Open renewed policy · "+r.optString("renewed_policy_number"));card.addView(newPolicy);newPolicy.setOnClickListener(v->startActivity(new Intent(this,PolicyDetailActivity.class).putExtra("policy_id",newer)));}}
  else{Button wa=Ui.greenButton(this,"WhatsApp Reminder · Review Template");card.addView(wa);wa.setOnClickListener(v->PolicySharing.whatsapp(this,id,true));Button email=Ui.button(this,"Email Reminder · Review Template");card.addView(email);email.setOnClickListener(v->PolicySharing.email(this,id,true));Button comment=Ui.outlineButton(this,"Call Comment ("+r.optInt("comment_count")+")");card.addView(comment);comment.setOnClickListener(v->comment(id));Button reminder=Ui.outlineButton(this,"Set Follow-up Reminder");card.addView(reminder);reminder.setOnClickListener(v->reminder(id));}
  JSONArray comments=r.optJSONArray("comments"),reminders=r.optJSONArray("reminders");JSONArray waHistory=r.optJSONArray("whatsapp_history"),emailHistory=r.optJSONArray("email_history");if((comments!=null&&comments.length()>0)||(reminders!=null&&reminders.length()>0)||(waHistory!=null&&waHistory.length()>0)||(emailHistory!=null&&emailHistory.length()>0)){Button history=Ui.outlineButton(this,"Contact & Follow-up History");card.addView(history);history.setOnClickListener(v->history(r));}
  if(!tab.equals("renewed")&&reminders!=null)for(int i=0;i<reminders.length();i++){JSONObject item=reminders.optJSONObject(i);if(item!=null&&"Pending".equals(item.optString("status"))){int reminderId=item.optInt("id");Button done=Ui.outlineButton(this,"Mark reminder done · "+item.optString("remind_at"));card.addView(done);done.setOnClickListener(v->post("/api/v1/reminders/"+reminderId+"/complete",new JSONObject(),"Reminder completed",null,null));break;}}
  list.addView(card);
 }
 void comment(int id){EditText text=Ui.input(this,"Client call response / follow-up");text.setSingleLine(false);new AlertDialog.Builder(this).setTitle("Add call comment").setView(text).setPositiveButton("Save",(d,w)->{String value=text.getText().toString().trim();if(value.isEmpty()){Ui.toast(this,"Comment required");return;}post("/api/v1/renewals/"+id+"/comment",new JSONObject(),"Comment saved",value,null);}).setNegativeButton("Cancel",null).show();}
 void reminder(int id){LocalDateTime at=LocalDateTime.now().plusDays(1);new DatePickerDialog(this,(picker,y,m,d)->new TimePickerDialog(this,(tp,h,min)->{String stamp=String.format(java.util.Locale.US,"%04d-%02d-%02dT%02d:%02d",y,m+1,d,h,min);EditText note=Ui.input(this,"Follow-up note");new AlertDialog.Builder(this).setTitle("Set reminder · "+stamp).setView(note).setPositiveButton("Save",(dialog,which)->{String value=note.getText().toString().trim();if(value.isEmpty()){Ui.toast(this,"Reminder note required");return;}try{post("/api/v1/reminders",new JSONObject().put("policy_id",id).put("remind_at",stamp).put("note",value),"Reminder saved",null,null);}catch(Exception e){Ui.toast(this,e.getMessage());}}).setNegativeButton("Cancel",null).show();},at.getHour(),at.getMinute(),false).show(),at.getYear(),at.getMonthValue()-1,at.getDayOfMonth()).show();}
 void post(String path,JSONObject body,String message,String comment,Runnable after){Ui.run(this,()->{try{if(comment!=null)body.put("comment_text",comment);ApiClient.post(this,path,body);Ui.ui(this,()->{Ui.toast(this,message);if(after!=null)after.run();refresh();});}catch(Exception e){Ui.ui(this,()->Ui.toast(this,e.getMessage()));}});}
 void history(JSONObject r){StringBuilder s=new StringBuilder();JSONArray comments=r.optJSONArray("comments"),reminders=r.optJSONArray("reminders");s.append("CALL COMMENTS\n");for(int i=0;comments!=null&&i<comments.length();i++){JSONObject c=comments.optJSONObject(i);s.append(c.optString("created_at")).append(" · ").append(c.optString("actor")).append("\n").append(c.optString("comment_text")).append("\n\n");}s.append("REMINDERS\n");for(int i=0;reminders!=null&&i<reminders.length();i++){JSONObject x=reminders.optJSONObject(i);s.append(x.optString("remind_at")).append(" · ").append(x.optString("status")).append("\n").append(x.optString("note")).append("\n\n");}JSONArray wa=r.optJSONArray("whatsapp_history"),emails=r.optJSONArray("email_history");s.append("WHATSAPP OPENED\n");for(int i=0;wa!=null&&i<wa.length();i++){JSONObject x=wa.optJSONObject(i);s.append(x.optString("created_at")).append(" · ").append(x.optString("actor")).append(" · ").append(x.optString("recipient_mobile")).append("\n");}s.append("\nEMAILS\n");for(int i=0;emails!=null&&i<emails.length();i++){JSONObject x=emails.optJSONObject(i);s.append(x.optString("created_at")).append(" · ").append(x.optString("delivery_status")).append(" · ").append(x.optString("recipient_email")).append("\n");}new AlertDialog.Builder(this).setTitle(r.optString("policy_number")).setMessage(s.toString()).setPositiveButton("Close",null).show();}
}
