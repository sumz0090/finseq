from pathlib import Path

p=Path('app/src/main/java/in/finseq/attendance/MainActivity.java')
s=p.read_text(encoding='utf-8')

s=s.replace('.setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)',
            '.setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_ALL)')

old='''                status.setText(best.name+" recognized\\nRecording attendance…");
                submitAttendance(best,sample,score);'''
new='''                status.setText(best.name+" recognized\\nLiveness check…");
                startLiveness(best,sample,score);'''
if old not in s:
    raise SystemExit('Recognition handoff anchor not found')
s=s.replace(old,new,1)

anchor='''    void submitAttendance(Profile p,FaceSample sample,double score){'''
if anchor not in s:
    raise SystemExit('submitAttendance anchor not found')

liveness='''    void startLiveness(Profile p,FaceSample initial,double score){
        long deadline=System.currentTimeMillis()+7000L;
        boolean canBlink=initial.leftEye>=0 && initial.rightEye>=0 && ((initial.leftEye+initial.rightEye)/2.0)>=0.55;
        boolean blink=canBlink && new Random().nextBoolean();
        if(blink){
            status.setText(p.name+"\\nLiveness: BLINK once");
            verifyBlink(p,score,deadline,false);
        }else{
            status.setText(p.name+"\\nLiveness: TURN HEAD to either side");
            verifyHeadTurn(p,score,deadline,false);
        }
    }

    void livenessFailed(String reason){
        status.setText("Liveness failed\\n"+reason+"\\nPlease try again");
        scheduleScan(1800);
    }

    void verifyBlink(Profile p,double score,long deadline,boolean sawClosed){
        if(!kioskMode)return;
        if(System.currentTimeMillis()>deadline){livenessFailed("Blink not detected");return;}
        captureFace((sample,error)->{
            if(sample==null){ui.postDelayed(()->verifyBlink(p,score,deadline,sawClosed),450);return;}
            if(sample.leftEye<0 || sample.rightEye<0){
                status.setText(p.name+"\\nEyes not measurable — turn head to either side");
                verifyHeadTurn(p,score,deadline,false);return;
            }
            double eye=(sample.leftEye+sample.rightEye)/2.0;
            if(!sawClosed && eye<=0.32){
                status.setText(p.name+"\\nBlink detected — OPEN eyes");
                ui.postDelayed(()->verifyBlink(p,score,deadline,true),320);return;
            }
            if(sawClosed && eye>=0.58){
                status.setText(p.name+"\\n✓ Liveness passed\\nRecording attendance…");
                submitAttendance(p,sample,score);return;
            }
            ui.postDelayed(()->verifyBlink(p,score,deadline,sawClosed),380);
        });
    }

    void verifyHeadTurn(Profile p,double score,long deadline,boolean moved){
        if(!kioskMode)return;
        if(System.currentTimeMillis()>deadline){livenessFailed("Head movement not detected");return;}
        captureFace((sample,error)->{
            if(sample==null){ui.postDelayed(()->verifyHeadTurn(p,score,deadline,moved),450);return;}
            double a=Math.abs(sample.yaw);
            if(!moved && a>=16.0){
                status.setText(p.name+"\\nMovement detected — FACE STRAIGHT");
                ui.postDelayed(()->verifyHeadTurn(p,score,deadline,true),350);return;
            }
            if(moved && a<=8.0){
                status.setText(p.name+"\\n✓ Liveness passed\\nRecording attendance…");
                submitAttendance(p,sample,score);return;
            }
            ui.postDelayed(()->verifyHeadTurn(p,score,deadline,moved),380);
        });
    }

'''
s=s.replace(anchor,liveness+anchor,1)

old_body='''.put("face_signature",FaceSignature.json(sample.vector)).put("face_similarity",score).put("selfie_jpeg_base64",sample.base64);'''
new_body='''.put("face_signature",FaceSignature.json(sample.vector)).put("face_similarity",score).put("selfie_jpeg_base64",sample.base64)
                        .put("liveness_passed",true).put("liveness_method","blink_or_head_turn");'''
if old_body not in s:
    raise SystemExit('Attendance JSON anchor not found')
s=s.replace(old_body,new_body,1)

old_face='''                            Rect b=faces.get(0).getBoundingBox();Rect safe=expand(b,bmp.getWidth(),bmp.getHeight());'''
new_face='''                            Face face=faces.get(0);Rect b=face.getBoundingBox();Rect safe=expand(b,bmp.getWidth(),bmp.getHeight());'''
if old_face not in s:
    raise SystemExit('Face detection anchor not found')
s=s.replace(old_face,new_face,1)

old_sample='''                            ui.post(()->cb.done(new FaceSample(sig,b64),null));'''
new_sample='''                            Float le=face.getLeftEyeOpenProbability(),re=face.getRightEyeOpenProbability();
                            double left=le==null?-1.0:le.doubleValue(),right=re==null?-1.0:re.doubleValue();
                            double yaw=face.getHeadEulerAngleY();
                            ui.post(()->cb.done(new FaceSample(sig,b64,yaw,left,right),null));'''
if old_sample not in s:
    raise SystemExit('FaceSample creation anchor not found')
s=s.replace(old_sample,new_sample,1)

old_class='''    static class FaceSample{double[] vector;String base64;FaceSample(double[]v,String b){vector=v;base64=b;}}'''
new_class='''    static class FaceSample{double[] vector;String base64;double yaw,leftEye,rightEye;FaceSample(double[]v,String b,double y,double l,double r){vector=v;base64=b;yaw=y;leftEye=l;rightEye=r;}}'''
if old_class not in s:
    raise SystemExit('FaceSample class anchor not found')
s=s.replace(old_class,new_class,1)

p.write_text(s,encoding='utf-8')
print('FINSEQ Attendance liveness patch applied')
