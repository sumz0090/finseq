package in.finseq.attendance;

import android.Manifest;
import android.app.*;
import android.os.*;
import android.provider.Settings;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.*;
import android.location.*;
import android.text.InputType;
import android.view.*;
import android.widget.*;

import androidx.activity.ComponentActivity;
import androidx.camera.core.*;
import androidx.camera.lifecycle.ProcessCameraProvider;
import androidx.camera.view.PreviewView;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.common.util.concurrent.ListenableFuture;
import com.google.mlkit.vision.common.InputImage;
import com.google.mlkit.vision.face.*;

import org.json.*;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;

public class MainActivity extends ComponentActivity {
    static final String BASE="https://cms.finseq.in";
    static final int RC_PERM=5001;
    static final long SAME_PERSON_COOLDOWN_MS=120000L;
    final ExecutorService io=Executors.newSingleThreadExecutor();
    final Handler ui=new Handler(Looper.getMainLooper());
    SharedPreferences prefs;
    String deviceUid,kioskToken="";
    PreviewView preview;
    ImageCapture imageCapture;
    ProcessCameraProvider cameraProvider;
    TextView status;
    boolean kioskMode=false,captureBusy=false;
    long lastSubmitAt=0; int lastPersonId=-1;
    double threshold=.90;
    final ArrayList<Profile> profiles=new ArrayList<>();
    final ArrayList<Employee> employees=new ArrayList<>();
    FaceDetector detector;

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE,WindowManager.LayoutParams.FLAG_SECURE);
        prefs=getSharedPreferences("finseq_attendance",MODE_PRIVATE);
        deviceUid=Settings.Secure.getString(getContentResolver(),Settings.Secure.ANDROID_ID);
        kioskToken=prefs.getString("kiosk_token","");
        detector=FaceDetection.getClient(new FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setLandmarkMode(FaceDetectorOptions.LANDMARK_MODE_ALL)
                .setClassificationMode(FaceDetectorOptions.CLASSIFICATION_MODE_NONE)
                .setMinFaceSize(.22f).build());
        requestNeededPermissions();
        if(kioskToken.isEmpty()) showActivation(); else { loadCachedProfiles(); showHome(); syncProfiles(false); }
    }

    @Override protected void onDestroy(){
        kioskMode=false;
        if(cameraProvider!=null) cameraProvider.unbindAll();
        try{detector.close();}catch(Exception ignored){}
        io.shutdownNow();
        super.onDestroy();
    }

    void requestNeededPermissions(){
        ArrayList<String> p=new ArrayList<>();
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.CAMERA);
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)p.add(Manifest.permission.ACCESS_FINE_LOCATION);
        if(!p.isEmpty()) ActivityCompat.requestPermissions(this,p.toArray(new String[0]),RC_PERM);
    }

    LinearLayout page(String title,String sub){
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(dp(20),dp(24),dp(20),dp(20)); root.setBackgroundColor(Color.rgb(245,248,250));
        TextView h=text(title,27,true); h.setTextColor(Color.rgb(11,18,32)); root.addView(h);
        TextView s=text(sub,14,false); s.setTextColor(Color.DKGRAY); s.setPadding(0,dp(4),0,dp(18)); root.addView(s);
        return root;
    }
    TextView text(String t,int sp,boolean bold){ TextView v=new TextView(this);v.setText(t);v.setTextSize(sp);v.setTextColor(Color.rgb(31,41,55));if(bold)v.setTypeface(null,1);return v; }
    EditText input(String hint){ EditText e=new EditText(this);e.setHint(hint);e.setTextSize(16);e.setPadding(dp(12),dp(10),dp(12),dp(10));return e; }
    Button button(String t){ Button b=new Button(this);b.setText(t);b.setAllCaps(false);b.setTextSize(16);b.setPadding(dp(10),dp(10),dp(10),dp(10));return b; }
    int dp(int x){ return Math.round(x*getResources().getDisplayMetrics().density); }
    void toast(String s){ Toast.makeText(this,s,Toast.LENGTH_LONG).show(); }

    void showActivation(){
        kioskMode=false; stopCamera();
        LinearLayout root=page("FINSEQ Attendance","One-time Attendance Device activation · Admin only");
        final EditText user=input("Admin username");
        final EditText pass=input("Admin password"); pass.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        final EditText label=input("Device label (e.g. Reception Tablet)"); label.setText(Build.MANUFACTURER+" "+Build.MODEL);
        Button go=button("Activate Attendance Device");
        status=text("",14,false);status.setPadding(0,dp(12),0,0);
        root.addView(user);root.addView(pass);root.addView(label);root.addView(go);root.addView(status);
        go.setOnClickListener(v->{
            String u=user.getText().toString().trim(),p=pass.getText().toString(),l=label.getText().toString().trim();
            if(u.isEmpty()||p.isEmpty()){toast("Admin username/password required");return;}
            go.setEnabled(false);status.setText("Activating…");
            io.execute(()->{
                try{
                    JSONObject login=Api.post("/api/v1/login",new JSONObject().put("username",u).put("password",p),null,null);
                    JSONObject admin=login.getJSONObject("user");
                    if(!"admin".equalsIgnoreCase(admin.optString("role"))) throw new Exception("Admin login required for device activation");
                    String bearer=login.getString("token");
                    JSONObject body=new JSONObject().put("device_uid",deviceUid).put("device_label",l);
                    JSONObject r=Api.post("/api/attendance/kiosk/setup",body,bearer,null);
                    kioskToken=r.getString("kiosk_token");prefs.edit().putString("kiosk_token",kioskToken).putString("device_label",l).apply();
                    parseSync(r);cacheProfiles();
                    ui.post(()->{toast("Attendance device activated");showHome();});
                }catch(Exception e){ui.post(()->{go.setEnabled(true);status.setText("Activation failed: "+msg(e));});}
            });
        });
        ScrollView sc=new ScrollView(this);sc.addView(root);setContentView(sc);
    }

    void showHome(){
        kioskMode=false;stopCamera();
        LinearLayout root=page("FINSEQ Attendance","Dedicated kiosk · Face Enrollment · Attendance IN / OUT");
        TextView badge=text("Device: "+prefs.getString("device_label",Build.MODEL)+"\nEnrolled staff: "+profiles.size(),15,true); badge.setPadding(0,0,0,dp(14));root.addView(badge);
        Button kiosk=button("▶ Start Face Attendance");
        Button enroll=button("＋ Face Enrollment (Admin)");
        Button sync=button("↻ Sync Employees / Faces");
        Button deactivate=button("Device Settings / Deactivate");
        root.addView(kiosk);root.addView(enroll);root.addView(sync);root.addView(deactivate);
        status=text("Ready",14,false);status.setPadding(0,dp(16),0,0);root.addView(status);
        kiosk.setOnClickListener(v->{ if(profiles.isEmpty()){toast("No employee face enrolled yet");return;} showKiosk(); });
        enroll.setOnClickListener(v->promptAdminForEnrollment());
        sync.setOnClickListener(v->syncProfiles(true));
        deactivate.setOnClickListener(v->new AlertDialog.Builder(this).setTitle("Deactivate this app?").setMessage("This only removes the local kiosk token. Server device can also be disabled from Attendance Kiosk Admin.").setNegativeButton("Cancel",null).setPositiveButton("Deactivate",(d,w)->{prefs.edit().clear().apply();kioskToken="";profiles.clear();employees.clear();showActivation();}).show());
        setContentView(root);
    }

    void showKiosk(){
        kioskMode=true;lastSubmitAt=0;lastPersonId=-1;
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(8,17,29));
        TextView head=text("FINSEQ ATTENDANCE",22,true);head.setTextColor(Color.WHITE);head.setGravity(Gravity.CENTER);head.setPadding(0,dp(16),0,dp(10));root.addView(head);
        preview=new PreviewView(this);preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        status=text("Starting camera…",18,true);status.setTextColor(Color.WHITE);status.setGravity(Gravity.CENTER);status.setPadding(dp(12),dp(14),dp(12),dp(10));root.addView(status);
        Button stop=button("Exit Kiosk");stop.setOnClickListener(v->showHome());root.addView(stop);
        setContentView(root);
        startCamera(()->scheduleScan(1000));
    }

    void scheduleScan(long delay){
        ui.postDelayed(()->{
            if(!kioskMode)return;
            if(captureBusy){scheduleScan(500);return;}
            captureBusy=true;
            captureFace((sample,error)->{
                captureBusy=false;
                if(!kioskMode)return;
                if(sample==null){status.setText(error==null?"Face the camera":"⚠ "+error);scheduleScan(1200);return;}
                Profile best=null;double score=-1;
                for(Profile p:profiles){double s=FaceSignature.cosine(sample.vector,p.vector);if(s>score){score=s;best=p;}}
                if(best==null||score<threshold){status.setText(String.format(Locale.US,"Face not recognized (%.2f)",Math.max(0,score)));scheduleScan(1200);return;}
                long now=System.currentTimeMillis();
                if(best.id==lastPersonId && now-lastSubmitAt<SAME_PERSON_COOLDOWN_MS){status.setText(best.name+"\nAttendance already processed. Please move away.");scheduleScan(1800);return;}
                status.setText(best.name+" recognized\nRecording attendance…");
                submitAttendance(best,sample,score);
            });
        },delay);
    }

    void submitAttendance(Profile p,FaceSample sample,double score){
        io.execute(()->{
            try{
                JSONObject body=new JSONObject().put("user_id",p.id).put("device_uid",deviceUid).put("device_label",prefs.getString("device_label",Build.MODEL))
                        .put("face_signature",FaceSignature.json(sample.vector)).put("face_similarity",score).put("selfie_jpeg_base64",sample.base64);
                Location l=lastLocation(); if(l!=null){body.put("latitude",l.getLatitude()).put("longitude",l.getLongitude()).put("gps_accuracy",l.getAccuracy());}
                JSONObject r=Api.post("/api/attendance/kiosk/submit",body,null,kioskToken);
                String action=r.optString("action","Attendance");String name=r.optString("employee_name",p.name);String tm=r.optString("server_time_ist","");
                lastPersonId=p.id;lastSubmitAt=System.currentTimeMillis();
                ui.post(()->{status.setText("✓ "+name+"\n"+action+" SUCCESS\n"+tm);scheduleScan(3500);});
            }catch(Exception e){ui.post(()->{status.setText("Attendance blocked\n"+msg(e));scheduleScan(2500);});}
        });
    }

    void promptAdminForEnrollment(){
        LinearLayout box=new LinearLayout(this);box.setOrientation(LinearLayout.VERTICAL);box.setPadding(dp(18),dp(8),dp(18),0);
        EditText u=input("Admin username"),p=input("Admin password");p.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);box.addView(u);box.addView(p);
        new AlertDialog.Builder(this).setTitle("Admin authorization").setView(box).setNegativeButton("Cancel",null).setPositiveButton("Continue",(d,w)->{
            String us=u.getText().toString().trim(),pw=p.getText().toString();if(us.isEmpty()||pw.isEmpty()){toast("Admin credentials required");return;}
            io.execute(()->{
                try{
                    JSONObject r=Api.post("/api/v1/login",new JSONObject().put("username",us).put("password",pw),null,null);
                    if(!"admin".equalsIgnoreCase(r.getJSONObject("user").optString("role")))throw new Exception("Admin login required");
                    String token=r.getString("token");syncProfilesBlocking();ui.post(()->chooseEmployee(token));
                }catch(Exception e){ui.post(()->toast("Authorization failed: "+msg(e)));}
            });
        }).show();
    }

    void chooseEmployee(String adminToken){
        if(employees.isEmpty()){toast("No active employees returned by server");return;}
        String[] names=new String[employees.size()];for(int i=0;i<names.length;i++)names[i]=employees.get(i).name+(employees.get(i).code.isEmpty()?"":" · "+employees.get(i).code);
        new AlertDialog.Builder(this).setTitle("Select employee to enroll").setItems(names,(d,which)->showEnrollment(employees.get(which),adminToken)).setNegativeButton("Cancel",null).show();
    }

    void showEnrollment(Employee emp,String adminToken){
        kioskMode=false;
        final ArrayList<double[]> samples=new ArrayList<>();
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setBackgroundColor(Color.rgb(8,17,29));
        TextView h=text("Face Enrollment",22,true);h.setTextColor(Color.WHITE);h.setGravity(Gravity.CENTER);h.setPadding(0,dp(14),0,dp(5));root.addView(h);
        TextView who=text(emp.name+(emp.code.isEmpty()?"":" · "+emp.code),16,true);who.setTextColor(Color.WHITE);who.setGravity(Gravity.CENTER);root.addView(who);
        preview=new PreviewView(this);preview.setImplementationMode(PreviewView.ImplementationMode.COMPATIBLE);root.addView(preview,new LinearLayout.LayoutParams(-1,0,1));
        status=text("Capture 3 clear face samples: straight, slight left, slight right",15,false);status.setTextColor(Color.WHITE);status.setGravity(Gravity.CENTER);status.setPadding(dp(10),dp(10),dp(10),dp(8));root.addView(status);
        Button capture=button("Capture 1 / 3");Button cancel=button("Cancel");root.addView(capture);root.addView(cancel);setContentView(root);
        cancel.setOnClickListener(v->showHome());
        capture.setEnabled(false);startCamera(()->capture.setEnabled(true));
        capture.setOnClickListener(v->{
            capture.setEnabled(false);status.setText("Checking face…");
            captureFace((sample,error)->{
                if(sample==null){status.setText(error);capture.setEnabled(true);return;}
                samples.add(sample.vector);int n=samples.size();
                if(n<3){status.setText("Sample "+n+" saved. Change head angle slightly.");capture.setText("Capture "+(n+1)+" / 3");capture.setEnabled(true);return;}
                double[] avg=FaceSignature.average(samples);status.setText("Saving enrollment…");
                io.execute(()->{
                    try{
                        JSONObject body=new JSONObject().put("user_id",emp.id).put("device_uid",deviceUid).put("face_signature",FaceSignature.json(avg)).put("sample_count",3);
                        JSONObject r=Api.post("/api/attendance/kiosk/enroll",body,adminToken,kioskToken);
                        ui.post(()->{toast(emp.name+" face enrolled");syncProfiles(true);showHome();});
                    }catch(Exception e){ui.post(()->{status.setText("Enrollment failed: "+msg(e));capture.setEnabled(true);});}
                });
            });
        });
    }

    void startCamera(Runnable ready){
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestNeededPermissions();toast("Camera permission required");return;}
        ListenableFuture<ProcessCameraProvider> f=ProcessCameraProvider.getInstance(this);
        f.addListener(()->{
            try{
                cameraProvider=f.get();cameraProvider.unbindAll();
                Preview pv=new Preview.Builder().build();pv.setSurfaceProvider(preview.getSurfaceProvider());
                imageCapture=new ImageCapture.Builder().setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setJpegQuality(88).build();
                CameraSelector selector=new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_FRONT).build();
                try{cameraProvider.bindToLifecycle(this,selector,pv,imageCapture);}catch(Exception front){selector=new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_BACK).build();cameraProvider.bindToLifecycle(this,selector,pv,imageCapture);}
                ready.run();
            }catch(Exception e){toast("Camera start failed: "+msg(e));}
        },ContextCompat.getMainExecutor(this));
    }
    void stopCamera(){ if(cameraProvider!=null)cameraProvider.unbindAll(); imageCapture=null; }

    interface FaceCallback{void done(FaceSample sample,String error);}
    void captureFace(FaceCallback cb){
        if(imageCapture==null){cb.done(null,"Camera not ready");return;}
        try{
            File file=new File(getCacheDir(),"face_"+System.nanoTime()+".jpg");
            ImageCapture.OutputFileOptions opt=new ImageCapture.OutputFileOptions.Builder(file).build();
            imageCapture.takePicture(opt,io,new ImageCapture.OnImageSavedCallback(){
                public void onImageSaved(ImageCapture.OutputFileResults r){
                    try{
                        byte[] jpeg=readAll(file);Bitmap bmp=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);if(bmp==null)throw new Exception("Camera image decode failed");
                        InputImage image=InputImage.fromBitmap(bmp,0);
                        detector.process(image).addOnSuccessListener(faces->{
                            if(faces.size()!=1){file.delete();ui.post(()->cb.done(null,faces.isEmpty()?"No face detected":"Only one face should be in camera"));return;}
                            Rect b=faces.get(0).getBoundingBox();Rect safe=expand(b,bmp.getWidth(),bmp.getHeight());
                            if(safe.width()<80||safe.height()<80){file.delete();ui.post(()->cb.done(null,"Move closer to camera"));return;}
                            Bitmap crop=Bitmap.createBitmap(bmp,safe.left,safe.top,safe.width(),safe.height());double[] sig=FaceSignature.compute(crop);String b64=android.util.Base64.encodeToString(jpeg,android.util.Base64.NO_WRAP);file.delete();
                            ui.post(()->cb.done(new FaceSample(sig,b64),null));
                        }).addOnFailureListener(e->{file.delete();ui.post(()->cb.done(null,"Face detection failed: "+msg(e)));});
                    }catch(Exception e){file.delete();ui.post(()->cb.done(null,msg(e)));}
                }
                public void onError(ImageCaptureException e){ui.post(()->cb.done(null,"Camera capture failed: "+msg(e)));}
            });
        }catch(Exception e){cb.done(null,msg(e));}
    }

    static Rect expand(Rect r,int w,int h){int dx=(int)(r.width()*.12),dy=(int)(r.height()*.18);return new Rect(Math.max(0,r.left-dx),Math.max(0,r.top-dy),Math.min(w,r.right+dx),Math.min(h,r.bottom+dy));}
    static byte[] readAll(File f)throws Exception{try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n;while((n=in.read(b))>0)out.write(b,0,n);return out.toByteArray();}}

    Location lastLocation(){
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return null;
        try{
            LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);Location a=null,b=null;
            try{a=lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);}catch(Exception ignored){}
            try{b=lm.getLastKnownLocation(LocationManager.NETWORK_PROVIDER);}catch(Exception ignored){}
            if(a==null)return b;if(b==null)return a;return a.getTime()>=b.getTime()?a:b;
        }catch(Exception e){return null;}
    }

    void syncProfiles(boolean notify){io.execute(()->{try{syncProfilesBlocking();ui.post(()->{if(notify)toast("Attendance profiles synced: "+profiles.size());if(status!=null)status.setText("Synced · "+profiles.size()+" enrolled");});}catch(Exception e){ui.post(()->{if(notify)toast("Sync failed: "+msg(e));});}});}
    void syncProfilesBlocking()throws Exception{JSONObject r=Api.get("/api/attendance/kiosk/sync",null,kioskToken);parseSync(r);cacheProfiles();}
    synchronized void parseSync(JSONObject r)throws Exception{
        threshold=r.optDouble("threshold",.90);employees.clear();profiles.clear();
        JSONArray es=r.optJSONArray("employees");if(es!=null)for(int i=0;i<es.length();i++){JSONObject e=es.getJSONObject(i);employees.add(new Employee(e.getInt("id"),e.optString("name"),e.optString("employee_code")));}
        JSONArray ps=r.optJSONArray("profiles");if(ps!=null)for(int i=0;i<ps.length();i++){JSONObject p=ps.getJSONObject(i);profiles.add(new Profile(p.getInt("user_id"),p.optString("name"),p.optString("employee_code"),FaceSignature.fromJson(p.getJSONArray("face_signature"))));}
    }
    void cacheProfiles(){try{JSONObject x=new JSONObject().put("threshold",threshold);JSONArray es=new JSONArray(),ps=new JSONArray();for(Employee e:employees)es.put(new JSONObject().put("id",e.id).put("name",e.name).put("employee_code",e.code));for(Profile p:profiles)ps.put(new JSONObject().put("user_id",p.id).put("name",p.name).put("employee_code",p.code).put("face_signature",FaceSignature.json(p.vector)));x.put("employees",es).put("profiles",ps);prefs.edit().putString("profiles_json",x.toString()).apply();}catch(Exception ignored){}}
    void loadCachedProfiles(){try{String s=prefs.getString("profiles_json","");if(!s.isEmpty())parseSync(new JSONObject(s));}catch(Exception ignored){}}

    static String msg(Throwable e){String m=e.getMessage();return m==null?e.getClass().getSimpleName():m;}

    static class Employee{int id;String name,code;Employee(int i,String n,String c){id=i;name=n;code=c==null?"":c;}}
    static class Profile extends Employee{double[] vector;Profile(int i,String n,String c,double[]v){super(i,n,c);vector=v;}}
    static class FaceSample{double[] vector;String base64;FaceSample(double[]v,String b){vector=v;base64=b;}}

    static class FaceSignature{
        static double[] compute(Bitmap src){
            Bitmap b=Bitmap.createScaledBitmap(src,64,64,true);int cells=4,bins=16,cell=16;double[] out=new double[cells*cells*bins];
            int[] px=new int[64*64];b.getPixels(px,0,64,0,0,64,64);
            int[] g=new int[px.length];for(int i=0;i<px.length;i++){int c=px[i];g[i]=(Color.red(c)*30+Color.green(c)*59+Color.blue(c)*11)/100;}
            for(int y=1;y<63;y++)for(int x=1;x<63;x++){
                int c=g[y*64+x],code=0;
                if(g[(y-1)*64+(x-1)]>=c)code|=1;if(g[(y-1)*64+x]>=c)code|=2;if(g[(y-1)*64+(x+1)]>=c)code|=4;if(g[y*64+(x+1)]>=c)code|=8;
                if(g[(y+1)*64+(x+1)]>=c)code|=16;if(g[(y+1)*64+x]>=c)code|=32;if(g[(y+1)*64+(x-1)]>=c)code|=64;if(g[y*64+(x-1)]>=c)code|=128;
                int cx=Math.min(cells-1,x/cell),cy=Math.min(cells-1,y/cell),bin=code>>4;out[(cy*cells+cx)*bins+bin]++;
            }
            normalize(out);return out;
        }
        static void normalize(double[]v){double n=0;for(double x:v)n+=x*x;n=Math.sqrt(n);if(n>0)for(int i=0;i<v.length;i++)v[i]/=n;}
        static double cosine(double[]a,double[]b){if(a==null||b==null||a.length!=b.length)return -1;double s=0;for(int i=0;i<a.length;i++)s+=a[i]*b[i];return s;}
        static double[] average(List<double[]> xs){double[]a=new double[xs.get(0).length];for(double[]x:xs)for(int i=0;i<a.length;i++)a[i]+=x[i];for(int i=0;i<a.length;i++)a[i]/=xs.size();normalize(a);return a;}
        static JSONArray json(double[]v){JSONArray a=new JSONArray();for(double x:v)a.put(x);return a;}
        static double[] fromJson(JSONArray a)throws JSONException{double[]v=new double[a.length()];for(int i=0;i<v.length;i++)v[i]=a.getDouble(i);normalize(v);return v;}
    }

    static class Api{
        static JSONObject post(String path,JSONObject body,String bearer,String kiosk)throws Exception{return request("POST",path,body,bearer,kiosk);}
        static JSONObject get(String path,String bearer,String kiosk)throws Exception{return request("GET",path,null,bearer,kiosk);}
        static JSONObject request(String method,String path,JSONObject body,String bearer,String kiosk)throws Exception{
            HttpURLConnection h=(HttpURLConnection)new URL(BASE+path).openConnection();h.setRequestMethod(method);h.setConnectTimeout(12000);h.setReadTimeout(18000);h.setRequestProperty("Accept","application/json");
            if(bearer!=null&&!bearer.isEmpty())h.setRequestProperty("Authorization","Bearer "+bearer);if(kiosk!=null&&!kiosk.isEmpty())h.setRequestProperty("X-FINSEQ-Kiosk-Token",kiosk);
            if(body!=null){h.setDoOutput(true);h.setRequestProperty("Content-Type","application/json; charset=utf-8");try(OutputStream out=h.getOutputStream()){out.write(body.toString().getBytes(StandardCharsets.UTF_8));}}
            int code=h.getResponseCode();InputStream in=code<400?h.getInputStream():h.getErrorStream();String text="";if(in!=null)try(BufferedReader r=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8))){StringBuilder s=new StringBuilder();String line;while((line=r.readLine())!=null)s.append(line);text=s.toString();}h.disconnect();
            JSONObject j=text.isEmpty()?new JSONObject():new JSONObject(text);if(code<200||code>=300)throw new Exception(j.optString("error","HTTP "+code));return j;
        }
    }
}
