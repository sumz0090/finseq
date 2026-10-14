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

replacement='''    void showEnrollment(Employee emp,String adminToken){
        kioskMode=false;
        ArrayList<FaceSample> samples=new ArrayList<>();
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(8,17,29));
        TextView h=text("Automatic Face Enrollment",22,true);h.setTextColor(Color.WHITE);h.setGravity(Gravity.CENTER);h.setPadding(0,dp(14),0,dp(5));root.addView(h);
        TextView who=text(emp.name+(emp.code.isEmpty()?"":" · "+emp.code),16,true);who.setTextColor(Color.WHITE);who.setGravity(Gravity.CENTER);root.addView(who);
        preview=new PreviewView(this);preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        status=text("Keep your face inside the frame",16,true);status.setTextColor(Color.WHITE);status.setGravity(Gravity.CENTER);status.setPadding(dp(10),dp(10),dp(10),dp(10));root.addView(status);
        Button cancel=button("Cancel Enrollment");root.addView(cancel);setContentView(root);cancel.setOnClickListener(v->showHome());
        startCamera(()->ui.postDelayed(()->autoEnroll(emp,adminToken,samples,0),700));
    }'''
s=replace_method(s,'    void showEnrollment(Employee emp,String adminToken){',replacement)
anchor='    void startCamera(Runnable ready){'
block='''    void autoEnroll(Employee emp,String auth,ArrayList<FaceSample> samples,int attempts){
        if(attempts>80){status.setText("Enrollment timed out. Please try again in better light.");return;}
        int straight=0,pos=0,neg=0;for(FaceSample f:samples){if(Math.abs(f.yaw)<=8)straight++;else if(f.yaw>=11)pos++;else if(f.yaw<=-11)neg++;}
        if(samples.size()>=12&&straight>=4&&pos>=2&&neg>=2){finishAutoEnrollment(emp,auth,samples);return;}
        if(straight<4)status.setText("Look STRAIGHT · "+samples.size()+" / 12");
        else if(pos<2)status.setText("Slowly turn your head to one side · "+samples.size()+" / 12");
        else if(neg<2)status.setText("Now turn to the other side · "+samples.size()+" / 12");
        else status.setText("Almost done · Keep moving naturally · "+samples.size()+" / 12");
        captureFace((sample,error)->{
            if(sample!=null){boolean diverse=true;if(!samples.isEmpty()&&FaceSignature.cosine(samples.get(samples.size()-1).vector,sample.vector)>0.9995&&Math.abs(samples.get(samples.size()-1).yaw-sample.yaw)<1.5)diverse=false;if(diverse)samples.add(sample);}
            ui.postDelayed(()->autoEnroll(emp,auth,samples,attempts+1),220);
        });
    }

    void finishAutoEnrollment(Employee emp,String auth,ArrayList<FaceSample> samples){
        ArrayList<double[]> vecs=new ArrayList<>();for(FaceSample x:samples)vecs.add(x.vector);double[] avg=FaceSignature.average(vecs);
        Profile duplicate=null;double dupScore=-1;for(Profile p:profiles){if(p.id==emp.id)continue;double sc=FaceSignature.cosine(avg,p.vector);if(sc>dupScore){dupScore=sc;duplicate=p;}}
        if(duplicate!=null&&dupScore>=0.86){status.setText("Enrollment BLOCKED\\nFace already resembles "+duplicate.name+" ("+String.format(Locale.US,"%.2f",dupScore)+")");return;}
        FaceSample reference=samples.get(0);for(FaceSample x:samples)if(Math.abs(x.yaw)<Math.abs(reference.yaw))reference=x;final FaceSample ref=reference;status.setText("Saving secure face template…");
        io.execute(()->{
            try{JSONObject body=new JSONObject().put("user_id",emp.id).put("device_uid",deviceUid).put("face_signature",FaceSignature.json(avg)).put("sample_count",samples.size()).put("reference_photo_base64",ref.base64).put("recognition_model","facenet_128_v1");Api.post("/api/attendance/kiosk/enroll",body,auth,kioskToken);ui.post(()->{toast(emp.name+" face enrollment completed");syncProfiles(true);showHome();});}
            catch(Exception e){ui.post(()->status.setText("Enrollment failed: "+msg(e)));}
        });
    }

'''
if anchor not in s: raise SystemExit('Missing startCamera')
s=s.replace(anchor,block+anchor,1)
p.write_text(s,encoding='utf-8')
print('v1.2 auto enrollment patch applied')
