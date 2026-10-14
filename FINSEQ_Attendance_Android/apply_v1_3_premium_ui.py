from pathlib import Path

p=Path('app/src/main/java/in/finseq/attendance/MainActivity.java')
s=p.read_text(encoding='utf-8')

def replace_method(src,signature,replacement):
    start=src.find(signature)
    if start<0:
        raise SystemExit('Missing '+signature)
    brace=src.find('{',start);depth=0
    for i in range(brace,len(src)):
        if src[i]=='{': depth+=1
        elif src[i]=='}':
            depth-=1
            if depth==0:
                return src[:start]+replacement+src[i+1:]
    raise SystemExit('Unclosed '+signature)

# Premium helpers
anchor='    int dp(int x){ return Math.round(x*getResources().getDisplayMetrics().density); }\n'
helpers='''    android.graphics.drawable.GradientDrawable bg(int color,float radius){android.graphics.drawable.GradientDrawable g=new android.graphics.drawable.GradientDrawable();g.setColor(color);g.setCornerRadius(dp((int)radius));return g;}\n    void premiumButton(Button b,int color){b.setTextColor(Color.WHITE);b.setTypeface(null,1);b.setBackground(bg(color,16));b.setMinHeight(dp(56));}\n    void softButton(Button b){b.setTextColor(Color.rgb(9,78,139));b.setTypeface(null,1);b.setBackground(bg(Color.WHITE,16));b.setMinHeight(dp(56));}\n    ImageView finseqLogo(int height){ImageView v=new ImageView(this);v.setImageResource(getResources().getIdentifier(\"finseq_company_logo_transparent\",\"drawable\",getPackageName()));v.setAdjustViewBounds(true);v.setScaleType(ImageView.ScaleType.FIT_CENTER);v.setLayoutParams(new LinearLayout.LayoutParams(-1,dp(height)));return v;}\n'''
if helpers.strip() not in s:
    s=s.replace(anchor,anchor+helpers,1)

s=replace_method(s,'    LinearLayout page(String title,String sub){','''    LinearLayout page(String title,String sub){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(18),dp(18),dp(18),dp(20));root.setBackgroundColor(Color.rgb(247,250,252));
        LinearLayout header=new LinearLayout(this);header.setOrientation(LinearLayout.VERTICAL);header.setGravity(Gravity.CENTER);header.setPadding(dp(10),dp(12),dp(10),dp(14));header.setBackground(bg(Color.rgb(4,58,108),22));
        header.addView(finseqLogo(72));TextView h=text(title,25,true);h.setTextColor(Color.WHITE);h.setGravity(Gravity.CENTER);header.addView(h);TextView st=text(sub,13,false);st.setTextColor(Color.rgb(219,234,254));st.setGravity(Gravity.CENTER);st.setPadding(0,dp(3),0,0);header.addView(st);root.addView(header);Space sp=new Space(this);root.addView(sp,new LinearLayout.LayoutParams(1,dp(14)));return root;
    }''')

s=replace_method(s,'    void showWelcome(){','''    void showWelcome(){
        kioskMode=false;stopCamera();LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER);root.setPadding(dp(30),dp(40),dp(30),dp(34));root.setBackgroundColor(Color.rgb(242,248,253));
        Space top=new Space(this);root.addView(top,new LinearLayout.LayoutParams(1,0,1));root.addView(finseqLogo(150));TextView t=text("Attendance",31,true);t.setGravity(Gravity.CENTER);t.setTextColor(Color.rgb(4,58,108));root.addView(t);TextView tag=text("People  •  Presence  •  Progress",14,true);tag.setGravity(Gravity.CENTER);tag.setTextColor(Color.rgb(27,94,160));tag.setPadding(0,dp(10),0,0);root.addView(tag);Space mid=new Space(this);root.addView(mid,new LinearLayout.LayoutParams(1,0,1));TextView foot=text("Secure Face Attendance\nfor a Better Workplace",15,true);foot.setGravity(Gravity.CENTER);foot.setTextColor(Color.rgb(4,58,108));root.addView(foot);TextView ver=text("Version 1.3.0",12,false);ver.setGravity(Gravity.CENTER);ver.setTextColor(Color.GRAY);ver.setPadding(0,dp(8),0,0);root.addView(ver);setContentView(root);
    }''')

s=replace_method(s,'    void showActivation(){','''    void showActivation(){
        kioskMode=false;stopCamera();LinearLayout root=page("FINSEQ Attendance","One-time device activation · Admin only");
        LinearLayout card=new LinearLayout(this);card.setOrientation(LinearLayout.VERTICAL);card.setPadding(dp(18),dp(18),dp(18),dp(18));card.setBackground(bg(Color.WHITE,18));
        TextView tt=text("Activate Attendance Device",18,true);tt.setTextColor(Color.rgb(11,18,32));card.addView(tt);TextView sub=text("Enter Admin credentials to link this device with your office attendance.",13,false);sub.setTextColor(Color.DKGRAY);sub.setPadding(0,dp(5),0,dp(12));card.addView(sub);
        final EditText user=input("Admin Username");final EditText pass=input("Password");pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);final EditText label=input("Device label");label.setText(Build.MANUFACTURER+" "+Build.MODEL);Button go=button("Activate Device");premiumButton(go,Color.rgb(17,119,238));status=text("",14,false);status.setPadding(0,dp(10),0,0);card.addView(user);card.addView(pass);card.addView(label);card.addView(go);card.addView(status);root.addView(card);
        TextView info=text("▣  This device will be linked with your office location and attendance rules.",13,false);info.setPadding(dp(16),dp(16),dp(16),dp(16));info.setTextColor(Color.rgb(51,65,85));info.setBackground(bg(Color.rgb(235,245,255),16));LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(-1,-2);ip.setMargins(0,dp(12),0,0);root.addView(info,ip);
        go.setOnClickListener(v->{String u=user.getText().toString().trim(),pw=pass.getText().toString(),l=label.getText().toString().trim();if(u.isEmpty()||pw.isEmpty()){toast("Admin username/password required");return;}go.setEnabled(false);status.setText("Activating…");io.execute(()->{try{JSONObject login=Api.post("/api/v1/login",new JSONObject().put("username",u).put("password",pw),null,null);JSONObject admin=login.getJSONObject("user");if(!"admin".equalsIgnoreCase(admin.optString("role")))throw new Exception("Admin login required for device activation");String bearer=login.getString("token");JSONObject body=new JSONObject().put("device_uid",deviceUid).put("device_label",l);JSONObject r=Api.post("/api/attendance/kiosk/setup",body,bearer,null);kioskToken=r.getString("kiosk_token");prefs.edit().putString("kiosk_token",kioskToken).putString("device_label",l).apply();parseSync(r);cacheProfiles();ui.post(()->{toast("Attendance device activated");showHome();});}catch(Exception e){ui.post(()->{go.setEnabled(true);status.setText("Activation failed: "+msg(e));});}});});
        ScrollView sc=new ScrollView(this);sc.addView(root);setContentView(sc);
    }''')

s=replace_method(s,'    void showHome(){','''    void showHome(){
        kioskMode=false;stopCamera();LinearLayout root=page("FINSEQ Attendance","Secure Face Attendance Kiosk");
        TextView device=text("Device: "+prefs.getString("device_label",Build.MODEL)+"    ● Active",14,true);device.setTextColor(Color.rgb(20,83,45));device.setPadding(dp(14),dp(12),dp(14),dp(12));device.setBackground(bg(Color.rgb(236,253,245),14));root.addView(device);
        Button kiosk=button("📷  Start Face Attendance\nAutomatically detect and mark IN / OUT");premiumButton(kiosk,Color.rgb(17,119,238));LinearLayout.LayoutParams kp=new LinearLayout.LayoutParams(-1,dp(118));kp.setMargins(0,dp(12),0,0);root.addView(kiosk,kp);
        LinearLayout row=new LinearLayout(this);row.setOrientation(LinearLayout.HORIZONTAL);row.setWeightSum(2f);Button enroll=button("👤＋\nFace Enrollment\nAuthorized User");Button sync=button("↻\nSync Faces\nEmployees");softButton(enroll);softButton(sync);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,dp(112),1);rp.setMargins(0,dp(12),dp(6),0);row.addView(enroll,rp);LinearLayout.LayoutParams rp2=new LinearLayout.LayoutParams(0,dp(112),1);rp2.setMargins(dp(6),dp(12),0,0);row.addView(sync,rp2);root.addView(row);
        Button deactivate=button("Device Info / Settings");softButton(deactivate);LinearLayout.LayoutParams dpv=new LinearLayout.LayoutParams(-1,dp(58));dpv.setMargins(0,dp(12),0,0);root.addView(deactivate,dpv);status=text("Ready · FaceNet Secure · Enrolled staff: "+profiles.size(),13,false);status.setGravity(Gravity.CENTER);status.setPadding(0,dp(14),0,0);status.setTextColor(Color.DKGRAY);root.addView(status);
        kiosk.setOnClickListener(v->{if(profiles.isEmpty()){toast("No employee face enrolled yet");return;}showKiosk();});enroll.setOnClickListener(v->promptAdminForEnrollment());sync.setOnClickListener(v->syncProfiles(true));deactivate.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Attendance Device").setMessage("Device: "+prefs.getString("device_label",Build.MODEL)+"\n\nDeactivate only if this tablet should stop being used as the attendance kiosk.").setNegativeButton("Close",null).setPositiveButton("Deactivate",(d,w)->{prefs.edit().clear().apply();kioskToken="";profiles.clear();employees.clear();showActivation();}).show());setContentView(root);
    }''')

s=replace_method(s,'    void showKiosk(){','''    void showKiosk(){
        kioskMode=true;lastSubmitAt=0;lastPersonId=-1;LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(3,34,61));
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.VERTICAL);top.setGravity(Gravity.CENTER);top.setPadding(0,dp(10),0,dp(8));top.setBackgroundColor(Color.rgb(4,58,108));top.addView(finseqLogo(45));TextView head=text("Attendance",15,false);head.setTextColor(Color.WHITE);head.setGravity(Gravity.CENTER);top.addView(head);root.addView(top);
        preview=new PreviewView(this);preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));status=text("Looking for a face…\nPlease look at the camera",17,true);status.setTextColor(Color.WHITE);status.setGravity(Gravity.CENTER);status.setPadding(dp(14),dp(14),dp(14),dp(12));root.addView(status);Button stop=button("← Exit Kiosk");softButton(stop);stop.setOnClickListener(v->showHome());root.addView(stop,new LinearLayout.LayoutParams(-1,dp(56)));setContentView(root);startCamera(()->scheduleScan(900));
    }''')

s=replace_method(s,'    void showEnrollment(Employee emp,String adminToken){','''    void showEnrollment(Employee emp,String adminToken){
        kioskMode=false;ArrayList<FaceSample> samples=new ArrayList<>();LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(247,250,252));
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.VERTICAL);top.setGravity(Gravity.CENTER);top.setPadding(0,dp(10),0,dp(8));top.setBackgroundColor(Color.rgb(4,58,108));TextView h=text("Face Enrollment",20,true);h.setTextColor(Color.WHITE);h.setGravity(Gravity.CENTER);top.addView(h);TextView who=text(emp.name+(emp.code.isEmpty()?"":" · "+emp.code),13,false);who.setTextColor(Color.rgb(219,234,254));who.setGravity(Gravity.CENTER);top.addView(who);root.addView(top);
        TextView tip=text("Automatic enrollment\nLook straight, then slowly turn left and right",14,true);tip.setGravity(Gravity.CENTER);tip.setPadding(dp(12),dp(12),dp(12),dp(12));tip.setTextColor(Color.rgb(30,41,59));root.addView(tip);preview=new PreviewView(this);preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);preview.setBackgroundColor(Color.LTGRAY);LinearLayout.LayoutParams pp=new LinearLayout.LayoutParams(-1,0,1);pp.setMargins(dp(14),0,dp(14),0);root.addView(preview,pp);status=text("Keep your face inside the frame",15,true);status.setGravity(Gravity.CENTER);status.setPadding(dp(12),dp(12),dp(12),dp(10));status.setTextColor(Color.rgb(4,58,108));root.addView(status);Button cancel=button("Cancel Enrollment");softButton(cancel);root.addView(cancel,new LinearLayout.LayoutParams(-1,dp(56)));setContentView(root);cancel.setOnClickListener(v->showHome());startCamera(()->ui.postDelayed(()->autoEnroll(emp,adminToken,samples,0),700));
    }''')

# Dedicated success screen matching approved graphic, then return to kiosk automatically.
anchor='    void submitAttendance(Profile p,FaceSample sample,double score){'
if '    void showAttendanceSuccess(' not in s:
    block='''    void showAttendanceSuccess(String name,String action,String tm){
        kioskMode=false;stopCamera();LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER);root.setPadding(dp(24),dp(34),dp(24),dp(28));root.setBackgroundColor(Color.rgb(247,252,249));root.addView(finseqLogo(70));TextView tick=text("✓",58,true);tick.setGravity(Gravity.CENTER);tick.setTextColor(Color.rgb(16,185,129));root.addView(tick);TextView title=text(action+" Successful!",27,true);title.setGravity(Gravity.CENTER);title.setTextColor(Color.rgb(5,122,85));root.addView(title);TextView time=text(tm,16,true);time.setGravity(Gravity.CENTER);time.setTextColor(Color.rgb(30,41,59));time.setPadding(0,dp(8),0,dp(18));root.addView(time);TextView person=text(name,21,true);person.setGravity(Gravity.CENTER);person.setPadding(dp(16),dp(14),dp(16),dp(14));person.setBackground(bg(Color.WHITE,16));root.addView(person,new LinearLayout.LayoutParams(-1,-2));TextView rules=text("Face matched  ✓\nLiveness passed  ✓\nAttendance rules verified  ✓",14,false);rules.setGravity(Gravity.CENTER);rules.setTextColor(Color.rgb(51,65,85));rules.setPadding(0,dp(18),0,dp(18));root.addView(rules);Button next=button("Ready for next person");premiumButton(next,Color.rgb(17,119,238));root.addView(next,new LinearLayout.LayoutParams(-1,dp(58)));next.setOnClickListener(v->showKiosk());setContentView(root);ui.postDelayed(()->{if(!kioskMode)showKiosk();},3200);
    }\n\n'''
    s=s.replace(anchor,block+anchor,1)

old='ui.post(()->{status.setText("✓ "+name+"\\n"+action+" SUCCESS\\n"+tm);scheduleScan(3500);});'
new='ui.post(()->showAttendanceSuccess(name,action,tm));'
if old not in s:
    raise SystemExit('Success callback anchor not found')
s=s.replace(old,new,1)

p.write_text(s,encoding='utf-8')
print('v1.3 premium approved UI patch applied')
