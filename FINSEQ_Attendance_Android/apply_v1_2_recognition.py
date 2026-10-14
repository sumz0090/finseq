from pathlib import Path
p=Path('app/src/main/java/in/finseq/attendance/MainActivity.java')
s=p.read_text(encoding='utf-8')

def replace_method(src,signature,replacement):
    start=src.find(signature)
    if start<0: raise SystemExit('Missing '+signature)
    brace=src.find('{',start); depth=0
    for i in range(brace,len(src)):
        if src[i]=='{': depth+=1
        elif src[i]=='}':
            depth-=1
            if depth==0: return src[:start]+replacement+src[i+1:]
    raise SystemExit('Unclosed '+signature)

s=s.replace('    FaceDetector detector;','    FaceDetector detector;\n    FaceEmbeddingModel embedder;',1)
old='''        requestNeededPermissions();
        if(kioskToken.isEmpty()) showActivation(); else { loadCachedProfiles(); showHome(); syncProfiles(false); }'''
new='''        try{embedder=new FaceEmbeddingModel(this);}catch(Exception e){throw new RuntimeException("Face embedding model failed to load",e);}
        requestNeededPermissions();
        if(kioskToken.isEmpty()) showActivation(); else { loadCachedProfiles(); showHome(); syncProfiles(false); }'''
if old not in s: raise SystemExit('startup anchor')
s=s.replace(old,new,1)
s=s.replace('        try{detector.close();}catch(Exception ignored){}','        try{detector.close();}catch(Exception ignored){}\n        try{if(embedder!=null)embedder.close();}catch(Exception ignored){}',1)

s=replace_method(s,'    void scheduleScan(long delay){','''    void scheduleScan(long delay){
        ui.postDelayed(()->{
            if(!kioskMode)return;
            if(captureBusy){scheduleScan(450);return;}
            captureBusy=true;
            captureFace((sample,error)->{
                captureBusy=false;
                if(!kioskMode)return;
                if(sample==null){status.setText(error==null?"Face the camera":"⚠ "+error);scheduleScan(1000);return;}
                status.setText("Live face detected\\nLiveness check…");
                startLiveness(sample);
            });
        },delay);
    }''')

s=replace_method(s,'    void startLiveness(Profile p,FaceSample initial,double score){','''    void startLiveness(FaceSample initial){
        long deadline=System.currentTimeMillis()+7000L;
        boolean canBlink=initial.leftEye>=0 && initial.rightEye>=0 && ((initial.leftEye+initial.rightEye)/2.0)>=0.55;
        if(canBlink && new Random().nextBoolean()){status.setText("Liveness: BLINK once");verifyBlink(deadline,false);}
        else{status.setText("Liveness: TURN HEAD to either side");verifyHeadTurn(deadline,false);}
    }''')

s=replace_method(s,'    void verifyBlink(Profile p,double score,long deadline,boolean sawClosed){','''    void verifyBlink(long deadline,boolean sawClosed){
        if(!kioskMode)return;
        if(System.currentTimeMillis()>deadline){livenessFailed("Blink not detected");return;}
        captureFace((sample,error)->{
            if(sample==null){ui.postDelayed(()->verifyBlink(deadline,sawClosed),400);return;}
            if(sample.leftEye<0||sample.rightEye<0){verifyHeadTurn(deadline,false);return;}
            double eye=(sample.leftEye+sample.rightEye)/2.0;
            if(!sawClosed&&eye<=0.32){status.setText("Blink detected — OPEN eyes");ui.postDelayed(()->verifyBlink(deadline,true),280);return;}
            if(sawClosed&&eye>=0.58){status.setText("✓ Liveness passed\\nConfirming identity…");startConsensus();return;}
            ui.postDelayed(()->verifyBlink(deadline,sawClosed),330);
        });
    }''')

s=replace_method(s,'    void verifyHeadTurn(Profile p,double score,long deadline,boolean moved){','''    void verifyHeadTurn(long deadline,boolean moved){
        if(!kioskMode)return;
        if(System.currentTimeMillis()>deadline){livenessFailed("Head movement not detected");return;}
        captureFace((sample,error)->{
            if(sample==null){ui.postDelayed(()->verifyHeadTurn(deadline,moved),400);return;}
            double a=Math.abs(sample.yaw);
            if(!moved&&a>=16.0){status.setText("Movement detected — FACE STRAIGHT");ui.postDelayed(()->verifyHeadTurn(deadline,true),320);return;}
            if(moved&&a<=8.0){status.setText("✓ Liveness passed\\nConfirming identity…");startConsensus();return;}
            ui.postDelayed(()->verifyHeadTurn(deadline,moved),330);
        });
    }''')

anchor='    void submitAttendance(Profile p,FaceSample sample,double score){'
block='''    static final double MIN_SECOND_BEST_MARGIN=0.08;
    static final int CONSENSUS_FRAMES=4;
    void startConsensus(){collectConsensus(new ArrayList<FaceSample>());}
    void collectConsensus(ArrayList<FaceSample> frames){
        if(!kioskMode)return;
        if(frames.size()>=CONSENSUS_FRAMES){finishConsensus(frames);return;}
        status.setText("Identity verification "+(frames.size()+1)+" / "+CONSENSUS_FRAMES+"…");
        captureFace((sample,error)->{
            if(sample==null){ui.postDelayed(()->collectConsensus(frames),420);return;}
            if(Math.abs(sample.yaw)>12){status.setText("Face straight toward camera");ui.postDelayed(()->collectConsensus(frames),420);return;}
            frames.add(sample);ui.postDelayed(()->collectConsensus(frames),260);
        });
    }
    Match matchOne(FaceSample sample){
        Profile best=null,second=null;double bs=-1,ss=-1;
        for(Profile p:profiles){double sc=FaceSignature.cosine(sample.vector,p.vector);if(sc>bs){ss=bs;second=best;bs=sc;best=p;}else if(sc>ss){ss=sc;second=p;}}
        return new Match(best,bs,second,ss);
    }
    void finishConsensus(ArrayList<FaceSample> frames){
        int winner=-1;double sum=0;Profile person=null;
        for(int i=0;i<frames.size();i++){
            Match m=matchOne(frames.get(i));
            if(m.best==null||m.bestScore<threshold||(m.bestScore-m.secondScore)<MIN_SECOND_BEST_MARGIN){status.setText("Identity not confirmed — attendance NOT recorded");scheduleScan(1800);return;}
            if(i==0){winner=m.best.id;person=m.best;}else if(m.best.id!=winner){status.setText("Face result inconsistent — attendance NOT recorded");scheduleScan(1800);return;}
            sum+=m.bestScore;
        }
        final Profile p=person;if(p==null){scheduleScan(1200);return;}
        long now=System.currentTimeMillis();
        if(p.id==lastPersonId&&now-lastSubmitAt<SAME_PERSON_COOLDOWN_MS){status.setText(p.name+"\\nAttendance already processed. Please move away.");scheduleScan(1800);return;}
        double avg=sum/frames.size();status.setText(p.name+" verified\\nRecording attendance…");submitAttendance(p,frames.get(frames.size()-1),avg);
    }

'''
if anchor not in s: raise SystemExit('submit anchor')
s=s.replace(anchor,block+anchor,1)

old='''                            Bitmap crop=Bitmap.createBitmap(bmp,safe.left,safe.top,safe.width(),safe.height());double[] sig=FaceSignature.compute(crop);String b64=android.util.Base64.encodeToString(jpeg,android.util.Base64.NO_WRAP);file.delete();'''
new='''                            Bitmap crop=Bitmap.createBitmap(bmp,safe.left,safe.top,safe.width(),safe.height());String q=qualityError(crop);if(q!=null){file.delete();ui.post(()->cb.done(null,q));return;}double[] sig=embedder.embed(crop);String b64=android.util.Base64.encodeToString(jpeg,android.util.Base64.NO_WRAP);file.delete();'''
if old not in s: raise SystemExit('capture anchor')
s=s.replace(old,new,1)

anchor='    static Rect expand(Rect r,int w,int h){'
quality='''    static String qualityError(Bitmap src){
        Bitmap b=Bitmap.createScaledBitmap(src,64,64,true);int[] px=new int[4096];b.getPixels(px,0,64,0,0,64,64);double sum=0,sq=0;
        for(int c:px){double y=(Color.red(c)*30+Color.green(c)*59+Color.blue(c)*11)/100.0;sum+=y;sq+=y*y;}
        double mean=sum/px.length,std=Math.sqrt(Math.max(0,sq/px.length-mean*mean));
        if(mean<45)return "Face is too dark";if(mean>220)return "Face is over-exposed";if(std<22)return "Image quality too low — improve light / hold steady";return null;
    }

'''
s=s.replace(anchor,quality+anchor,1)
anchor='    static class FaceSignature{'
s=s.replace(anchor,'    static class Match{Profile best,second;double bestScore,secondScore;Match(Profile b,double bs,Profile s,double ss){best=b;bestScore=bs;second=s;secondScore=ss;}}\n\n'+anchor,1)
s=s.replace('.put("liveness_passed",true).put("liveness_method","blink_or_head_turn");','.put("liveness_passed",true).put("liveness_method","blink_or_head_turn").put("recognition_model","facenet_128_v1").put("consensus_frames",CONSENSUS_FRAMES).put("second_best_margin_required",MIN_SECOND_BEST_MARGIN);',1)
p.write_text(s,encoding='utf-8')
print('v1.2 recognition patch applied')
