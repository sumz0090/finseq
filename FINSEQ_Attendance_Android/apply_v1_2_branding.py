from pathlib import Path
p=Path('app/src/main/java/in/finseq/attendance/MainActivity.java')
s=p.read_text(encoding='utf-8')

def replace_method(src,signature,replacement):
    start=src.find(signature)
    if start<0: raise SystemExit('Missing '+signature)
    brace=src.find('{',start);depth=0
    for i in range(brace,len(src)):
        if src[i]=='{':depth+=1
        elif src[i]=='}':
            depth-=1
            if depth==0:return src[:start]+replacement+src[i+1:]
    raise SystemExit('Unclosed '+signature)

s=replace_method(s,'    LinearLayout page(String title,String sub){','''    LinearLayout page(String title,String sub){
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(20),dp(22),dp(20),dp(20));root.setBackgroundColor(Color.rgb(245,248,250));
        ImageView logo=new ImageView(this);logo.setImageResource(getResources().getIdentifier("finseq_company_logo_transparent","drawable",getPackageName()));logo.setAdjustViewBounds(true);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(logo,new LinearLayout.LayoutParams(-1,dp(82)));
        TextView h=text(title,27,true);h.setTextColor(Color.rgb(11,18,32));h.setGravity(Gravity.CENTER);root.addView(h);
        TextView st=text(sub,14,false);st.setTextColor(Color.DKGRAY);st.setGravity(Gravity.CENTER);st.setPadding(0,dp(4),0,dp(18));root.addView(st);return root;
    }''')
old='''        requestNeededPermissions();
        if(kioskToken.isEmpty()) showActivation(); else { loadCachedProfiles(); showHome(); syncProfiles(false); }'''
new='''        requestNeededPermissions();
        showWelcome();
        ui.postDelayed(()->{if(kioskToken.isEmpty()) showActivation(); else { loadCachedProfiles(); showHome(); syncProfiles(false); }},1100);'''
if old not in s: raise SystemExit('startup branding anchor')
s=s.replace(old,new,1)
anchor='    void showActivation(){'
welcome='''    void showWelcome(){
        kioskMode=false;stopCamera();LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER);root.setPadding(dp(28),dp(30),dp(28),dp(30));root.setBackgroundColor(Color.rgb(245,248,250));
        ImageView logo=new ImageView(this);logo.setImageResource(getResources().getIdentifier("finseq_company_logo_transparent","drawable",getPackageName()));logo.setAdjustViewBounds(true);logo.setScaleType(ImageView.ScaleType.FIT_CENTER);root.addView(logo,new LinearLayout.LayoutParams(-1,dp(125)));
        TextView t=text("FINSEQ Attendance",30,true);t.setGravity(Gravity.CENTER);t.setTextColor(Color.rgb(9,38,73));root.addView(t);TextView st=text("Secure Face Attendance",17,true);st.setGravity(Gravity.CENTER);st.setTextColor(Color.rgb(242,112,35));st.setPadding(0,dp(8),0,dp(8));root.addView(st);TextView i=text("Face verification • Liveness • Office controls",14,false);i.setGravity(Gravity.CENTER);i.setTextColor(Color.DKGRAY);root.addView(i);setContentView(root);
    }

'''
s=s.replace(anchor,welcome+anchor,1)
s=s.replace('Dedicated kiosk · Face Enrollment · Attendance IN / OUT','Secure kiosk · FaceNet Recognition · Liveness · Auto IN / OUT')
s=s.replace('status=text("Ready",14,false);','status=text("Ready · v1.2 FaceNet Secure",14,false);',1)
p.write_text(s,encoding='utf-8')
print('v1.2 branding patch applied')
