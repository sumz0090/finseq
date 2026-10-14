from pathlib import Path
p=Path('app/src/main/java/in/finseq/attendance/MainActivity.java')
s=p.read_text(encoding='utf-8')

def replace_method(src,signature,replacement):
    start=src.find(signature)
    if start<0: raise SystemExit('Missing '+signature)
    brace=src.find('{',start);depth=0
    for i in range(brace,len(src)):
        if src[i]=='{': depth+=1
        elif src[i]=='}':
            depth-=1
            if depth==0:return src[:start]+replacement+src[i+1:]
    raise SystemExit('Unclosed '+signature)

s=s.replace('＋ Face Enrollment (Admin)','＋ Face Enrollment (Authorized User)')
replacement='''    void promptAdminForEnrollment(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(8),dp(18),0);
        EditText u=input("Enrollment username"),p=input("Password");p.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);box.addView(u);box.addView(p);
        new AlertDialog.Builder(this).setTitle("Face Enrollment Authorization").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Continue",(d,w)->{
            String us=u.getText().toString().trim(),pw=p.getText().toString();if(us.isEmpty()||pw.isEmpty()){toast("Enrollment credentials required");return;}
            io.execute(()->{
                try{JSONObject er=Api.post("/api/attendance/kiosk/operator-login",new JSONObject().put("username",us).put("password",pw),null,kioskToken);String auth=er.getString("enrollment_token");syncProfilesBlocking();ui.post(()->chooseEmployee(auth));}
                catch(Exception e){ui.post(()->toast("Authorization failed: "+msg(e)));}
            });
        }).show();
    }'''
s=replace_method(s,'    void promptAdminForEnrollment(){',replacement)
p.write_text(s,encoding='utf-8')
print('v1.2 operator patch applied')
