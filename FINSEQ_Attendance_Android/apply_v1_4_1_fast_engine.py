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

# Imports for live CameraX analysis.
if 'import java.nio.ByteBuffer;' not in s:
    s=s.replace('import java.nio.charset.StandardCharsets;','import java.nio.charset.StandardCharsets;\nimport java.nio.ByteBuffer;')

# Dedicated analyzer executor and fast-engine state. Network IO stays separate.
old='final ExecutorService io=Executors.newSingleThreadExecutor();'
new='''final ExecutorService io=Executors.newSingleThreadExecutor();
    final ExecutorService analysisIo=Executors.newSingleThreadExecutor();'''
if old in s:s=s.replace(old,new,1)

old='''    ImageCapture imageCapture;
    ProcessCameraProvider cameraProvider;'''
new='''    ImageCapture imageCapture;
    ImageAnalysis imageAnalysis;
    ProcessCameraProvider cameraProvider;'''
if old in s:s=s.replace(old,new,1)

old='''    FaceDetector detector;
    FaceEmbeddingModel embedder;'''
new='''    FaceDetector detector;
    FaceEmbeddingModel embedder;
    final ArrayDeque<FastObservation> fastWindow=new ArrayDeque<>();
    volatile boolean fastSubmitting=false;
    long fastCandidateSince=0L,fastLastAcceptedAt=0L;
    int fastCandidateId=-1;
    boolean fastBlinkChallenge=false,fastBlinkClosed=false;
    long fastBlinkDeadline=0L;'''
if old not in s: raise SystemExit('Fast-engine field anchor missing')
s=s.replace(old,new,1)

# Close analysis executor as well.
s=s.replace('        io.shutdownNow();','        analysisIo.shutdownNow();\n        io.shutdownNow();',1)

# Premium kiosk now starts continuous analysis immediately; no photo-by-photo schedule loop.
s=replace_method(s,'    void showKiosk(){','''    void showKiosk(){
        kioskMode=true;fastSubmitting=false;resetFastEngine();
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(3,34,61));
        LinearLayout top=new LinearLayout(this);top.setOrientation(LinearLayout.VERTICAL);top.setGravity(Gravity.CENTER);top.setPadding(0,dp(10),0,dp(8));top.setBackgroundColor(Color.rgb(4,58,108));top.addView(finseqLogo(45));TextView head=text("Attendance · Fast Scan",15,false);head.setTextColor(Color.WHITE);head.setGravity(Gravity.CENTER);top.addView(head);root.addView(top);
        preview=new PreviewView(this);preview.setImplementationMode(PreviewView.ImplementationMode.PERFORMANCE);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        status=text("Ready · look at the camera",17,true);status.setTextColor(Color.WHITE);status.setGravity(Gravity.CENTER);status.setPadding(dp(14),dp(14),dp(14),dp(12));root.addView(status);
        Button stop=button("← Exit Kiosk");softButton(stop);stop.setOnClickListener(v->showHome());root.addView(stop,new LinearLayout.LayoutParams(-1,dp(56)));setContentView(root);
        startCamera(()->status.setText("Ready · live face matching active"));
    }''')

# Bind Preview + ImageCapture (enrollment compatibility) + ImageAnalysis (fast attendance).
s=replace_method(s,'    void startCamera(Runnable ready){','''    void startCamera(Runnable ready){
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestNeededPermissions();showPermissionHelp();return;}
        ListenableFuture<ProcessCameraProvider> f=ProcessCameraProvider.getInstance(this);
        f.addListener(()->{
            try{
                cameraProvider=f.get();cameraProvider.unbindAll();
                int rotation=getWindowManager().getDefaultDisplay().getRotation();
                Preview pv=new Preview.Builder().setTargetRotation(rotation).build();pv.setSurfaceProvider(preview.getSurfaceProvider());
                imageCapture=new ImageCapture.Builder().setTargetRotation(rotation).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setJpegQuality(86).build();
                imageAnalysis=new ImageAnalysis.Builder().setTargetResolution(new android.util.Size(640,480)).setTargetRotation(rotation).setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST).build();
                imageAnalysis.setAnalyzer(analysisIo,this::analyzeFastFrame);
                CameraSelector selector=new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_FRONT).build();
                cameraProvider.bindToLifecycle(this,selector,pv,imageCapture,imageAnalysis);
                ready.run();
            }catch(Exception e){if(status!=null)status.setText("Front camera unavailable");toast("Front camera is required for secure face attendance: "+msg(e));}
        },ContextCompat.getMainExecutor(this));
    }''')

s=replace_method(s,'    void stopCamera(){','''    void stopCamera(){
        resetFastEngine();
        try{if(imageAnalysis!=null)imageAnalysis.clearAnalyzer();}catch(Exception ignored){}
        if(cameraProvider!=null)cameraProvider.unbindAll();
        imageAnalysis=null;imageCapture=null;
    }''')

# Keep target rotation correct for all three CameraX use cases.
old='''@Override public void onConfigurationChanged(Configuration newConfig){super.onConfigurationChanged(newConfig);int rotation=getWindowManager().getDefaultDisplay().getRotation();if(imageCapture!=null)imageCapture.setTargetRotation(rotation);}'''
new='''@Override public void onConfigurationChanged(Configuration newConfig){super.onConfigurationChanged(newConfig);int rotation=getWindowManager().getDefaultDisplay().getRotation();if(imageCapture!=null)imageCapture.setTargetRotation(rotation);if(imageAnalysis!=null)imageAnalysis.setTargetRotation(rotation);}'''
if old in s:s=s.replace(old,new,1)

# Fast scan engine helpers are inserted before the old scheduleScan() implementation.
anchor='    void scheduleScan(long delay){'
if anchor not in s: raise SystemExit('scheduleScan anchor missing')
fast=r'''    void resetFastEngine(){
        synchronized(fastWindow){fastWindow.clear();}
        fastCandidateSince=0L;fastLastAcceptedAt=0L;fastCandidateId=-1;fastBlinkChallenge=false;fastBlinkClosed=false;fastBlinkDeadline=0L;
    }

    @androidx.camera.core.ExperimentalGetImage
    void analyzeFastFrame(ImageProxy proxy){
        if(!kioskMode||fastSubmitting){proxy.close();return;}
        long now=SystemClock.elapsedRealtime();
        if(now-fastLastAcceptedAt<105L){proxy.close();return;}
        android.media.Image media=proxy.getImage();
        if(media==null){proxy.close();return;}
        int rotation=proxy.getImageInfo().getRotationDegrees();
        InputImage input=InputImage.fromMediaImage(media,rotation);
        detector.process(input).addOnSuccessListener(faces->{
            try{
                if(!kioskMode||fastSubmitting)return;
                if(faces.size()!=1){
                    if(faces.isEmpty()){if(SystemClock.elapsedRealtime()-fastLastAcceptedAt>600L)resetFastEngine();ui.post(()->{if(kioskMode&&!fastSubmitting)status.setText("Ready · look at the camera");});}
                    else ui.post(()->{if(kioskMode)status.setText("Only one face should be visible");});
                    return;
                }
                Face face=faces.get(0);
                Bitmap raw=imageProxyToBitmap(proxy);if(raw==null)return;
                Bitmap upright=rotateBitmap(raw,rotation);
                Rect b=face.getBoundingBox();Rect safe=expand(b,upright.getWidth(),upright.getHeight());
                if(safe.width()<105||safe.height()<105){ui.post(()->status.setText("Move a little closer"));return;}
                Bitmap crop=Bitmap.createBitmap(upright,safe.left,safe.top,safe.width(),safe.height());
                String q=qualityError(crop);if(q!=null){ui.post(()->status.setText(q));return;}
                double[] vector=embedder.embed(crop);Match m=matchOne(new FaceSample(vector,"",face.getHeadEulerAngleY(),eye(face.getLeftEyeOpenProbability()),eye(face.getRightEyeOpenProbability())));
                if(m.best==null||m.bestScore<threshold||(m.bestScore-m.secondScore)<MIN_SECOND_BEST_MARGIN){resetFastEngine();ui.post(()->status.setText("Face not confidently recognized"));return;}
                long wall=System.currentTimeMillis();
                if(m.best.id==lastPersonId&&wall-lastSubmitAt<SAME_PERSON_COOLDOWN_MS){resetFastEngine();ui.post(()->status.setText(m.best.name+"\nAttendance already processed · please move away"));return;}
                String proof=encodeProof(crop);
                FaceSample sample=new FaceSample(vector,proof,face.getHeadEulerAngleY(),eye(face.getLeftEyeOpenProbability()),eye(face.getRightEyeOpenProbability()));
                FastObservation obs=new FastObservation(m.best,m.bestScore,m.secondScore,sample,SystemClock.elapsedRealtime());
                handleFastObservation(obs);
            }catch(Exception e){ui.post(()->{if(kioskMode&&!fastSubmitting)status.setText("Scan retry · "+msg(e));});}
            finally{proxy.close();}
        }).addOnFailureListener(e->{proxy.close();ui.post(()->{if(kioskMode&&!fastSubmitting)status.setText("Face scan retry");});});
    }

    static double eye(Float v){return v==null?-1.0:v.doubleValue();}

    void handleFastObservation(FastObservation o){
        fastLastAcceptedAt=SystemClock.elapsedRealtime();
        if(fastCandidateId!=o.person.id){
            synchronized(fastWindow){fastWindow.clear();}
            fastCandidateId=o.person.id;fastCandidateSince=o.when;fastBlinkChallenge=false;fastBlinkClosed=false;
        }
        if(fastBlinkChallenge){
            handleBlinkChallenge(o);return;
        }
        synchronized(fastWindow){
            fastWindow.addLast(o);while(fastWindow.size()>4)fastWindow.removeFirst();
            if(fastWindow.size()<4){int n=fastWindow.size();ui.post(()->status.setText(o.person.name+"\nFast verification "+n+" / 4"));return;}
            int id=o.person.id;double sum=0,minMargin=9,yawMin=999,yawMax=-999,eyeMin=9,eyeMax=-9;boolean eyeSeen=false;
            for(FastObservation x:fastWindow){
                if(x.person.id!=id){resetFastEngine();return;}
                sum+=x.bestScore;minMargin=Math.min(minMargin,x.bestScore-x.secondScore);yawMin=Math.min(yawMin,x.sample.yaw);yawMax=Math.max(yawMax,x.sample.yaw);
                double ev=(x.sample.leftEye>=0&&x.sample.rightEye>=0)?(x.sample.leftEye+x.sample.rightEye)/2.0:-1;
                if(ev>=0){eyeSeen=true;eyeMin=Math.min(eyeMin,ev);eyeMax=Math.max(eyeMax,ev);}
            }
            double avg=sum/fastWindow.size();boolean passive=((yawMax-yawMin)>=1.2)||(eyeSeen&&(eyeMax-eyeMin)>=0.045);
            if(avg<Math.max(threshold,0.82)||minMargin<MIN_SECOND_BEST_MARGIN){resetFastEngine();ui.post(()->status.setText("Identity not confirmed"));return;}
            if(passive){FastObservation last=fastWindow.peekLast();fastWindow.clear();submitFastObservation(last,avg,"live_multiframe_motion");return;}
            if(o.when-fastCandidateSince<900L){ui.post(()->status.setText(o.person.name+"\nHold steady · confirming live face"));return;}
            fastBlinkChallenge=true;fastBlinkClosed=false;fastBlinkDeadline=SystemClock.elapsedRealtime()+4500L;
            ui.post(()->status.setText(o.person.name+"\nBlink once"));
        }
    }

    void handleBlinkChallenge(FastObservation o){
        if(SystemClock.elapsedRealtime()>fastBlinkDeadline){resetFastEngine();ui.post(()->status.setText("Liveness retry · look at camera"));return;}
        double ev=(o.sample.leftEye>=0&&o.sample.rightEye>=0)?(o.sample.leftEye+o.sample.rightEye)/2.0:-1;
        if(ev<0){if(Math.abs(o.sample.yaw)>=10.0){submitFastObservation(o,o.bestScore,"live_head_motion");}else ui.post(()->status.setText(o.person.name+"\nTurn head slightly"));return;}
        if(!fastBlinkClosed&&ev<=0.30){fastBlinkClosed=true;ui.post(()->status.setText(o.person.name+"\nBlink detected · open eyes"));return;}
        if(fastBlinkClosed&&ev>=0.58){submitFastObservation(o,o.bestScore,"live_blink");return;}
        ui.post(()->status.setText(o.person.name+"\nBlink once"));
    }

    void submitFastObservation(FastObservation o,double score,String liveMethod){
        if(fastSubmitting||!kioskMode)return;fastSubmitting=true;
        ui.post(()->status.setText(o.person.name+" verified\nRecording attendance…"));
        io.execute(()->{
            try{
                JSONObject body=new JSONObject().put("user_id",o.person.id).put("device_uid",deviceUid).put("device_label",prefs.getString("device_label",Build.MODEL))
                    .put("face_signature",FaceSignature.json(o.sample.vector)).put("face_similarity",score).put("selfie_jpeg_base64",o.sample.base64)
                    .put("liveness_passed",true).put("liveness_method",liveMethod).put("recognition_model","facenet_128_v1").put("consensus_frames",4).put("second_best_margin_required",MIN_SECOND_BEST_MARGIN);
                Location l=lastLocation();if(l!=null){body.put("latitude",l.getLatitude()).put("longitude",l.getLongitude()).put("gps_accuracy",l.getAccuracy());}
                JSONObject r=Api.post("/api/attendance/secure/submit",body,null,kioskToken);
                String action=r.optString("action","Attendance"),name=r.optString("employee_name",o.person.name),tm=r.optString("server_time_ist","");
                lastPersonId=o.person.id;lastSubmitAt=System.currentTimeMillis();prefs.edit().putInt("last_person_id",lastPersonId).putLong("last_submit_at",lastSubmitAt).apply();
                ui.post(()->{fastSubmitting=false;resetFastEngine();showAttendanceSuccess(name,action,tm);});
            }catch(Exception e){ui.post(()->{fastSubmitting=false;resetFastEngine();status.setText("Attendance blocked\n"+msg(e));});}
        });
    }

    static Bitmap rotateBitmap(Bitmap src,int degrees){if(degrees%360==0)return src;Matrix m=new Matrix();m.postRotate(degrees);return Bitmap.createBitmap(src,0,0,src.getWidth(),src.getHeight(),m,true);}

    static Bitmap imageProxyToBitmap(ImageProxy image)throws Exception{
        int w=image.getWidth(),h=image.getHeight();ImageProxy.PlaneProxy[] planes=image.getPlanes();if(planes==null||planes.length<3)return null;
        byte[] nv21=new byte[w*h+w*h/2];int pos=0;
        ByteBuffer y=planes[0].getBuffer();int yp=y.position(),yrs=planes[0].getRowStride(),yps=planes[0].getPixelStride();
        for(int r=0;r<h;r++)for(int c=0;c<w;c++)nv21[pos++]=y.get(yp+r*yrs+c*yps);
        ByteBuffer u=planes[1].getBuffer(),v=planes[2].getBuffer();int up=u.position(),vp=v.position(),urs=planes[1].getRowStride(),vrs=planes[2].getRowStride(),ups=planes[1].getPixelStride(),vps=planes[2].getPixelStride();
        for(int r=0;r<h/2;r++)for(int c=0;c<w/2;c++){nv21[pos++]=v.get(vp+r*vrs+c*vps);nv21[pos++]=u.get(up+r*urs+c*ups);}
        YuvImage yuv=new YuvImage(nv21,ImageFormat.NV21,w,h,null);ByteArrayOutputStream out=new ByteArrayOutputStream();if(!yuv.compressToJpeg(new Rect(0,0,w,h),88,out))return null;byte[] b=out.toByteArray();return BitmapFactory.decodeByteArray(b,0,b.length);
    }

    static String encodeProof(Bitmap src){
        int max=420,w=src.getWidth(),h=src.getHeight();Bitmap b=src;if(Math.max(w,h)>max){double sc=max/(double)Math.max(w,h);b=Bitmap.createScaledBitmap(src,Math.max(1,(int)(w*sc)),Math.max(1,(int)(h*sc)),true);}ByteArrayOutputStream out=new ByteArrayOutputStream();b.compress(Bitmap.CompressFormat.JPEG,72,out);return android.util.Base64.encodeToString(out.toByteArray(),android.util.Base64.NO_WRAP);
    }

    static class FastObservation{Profile person;double bestScore,secondScore;FaceSample sample;long when;FastObservation(Profile p,double b,double s,FaceSample f,long w){person=p;bestScore=b;secondScore=s;sample=f;when=w;}}

'''
s=s.replace(anchor,fast+anchor,1)

# Old photo-capture path remains for enrollment, but kiosk will no longer call scheduleScan/startLiveness.
# Keep liveness method label accurate if the fallback code is ever reused.
s=s.replace('.put("liveness_method","blink_or_head_turn")','.put("liveness_method","active_blink_or_head_turn")')

p.write_text(s,encoding='utf-8')
print('FINSEQ Attendance v1.4.1 continuous fast face engine applied')
