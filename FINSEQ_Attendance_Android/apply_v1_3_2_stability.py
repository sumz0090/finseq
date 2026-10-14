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

# Imports needed for robust orientation + EXIF handling.
if 'import android.content.res.Configuration;' not in s:
    s=s.replace('import android.content.pm.PackageManager;','import android.content.pm.PackageManager;\nimport android.content.res.Configuration;\nimport android.media.ExifInterface;')

# Security / runtime defaults.
s=s.replace('double threshold=.90;','double threshold=.82;')
s=s.replace('threshold=r.optDouble("threshold",.90);','threshold=r.optDouble("threshold",.82);')

# Enrollment session guard fields.
old='boolean kioskMode=false,captureBusy=false;'
new='boolean kioskMode=false,captureBusy=false,enrollmentActive=false;\n    int activeEnrollmentUserId=-1;'
if old in s:s=s.replace(old,new,1)

# Persist same-person cooldown across success screen, rotation and process restart.
old='prefs=getSharedPreferences("finseq_attendance",MODE_PRIVATE);'
new='prefs=getSharedPreferences("finseq_attendance",MODE_PRIVATE);\n        lastPersonId=prefs.getInt("last_person_id",-1);\n        lastSubmitAt=prefs.getLong("last_submit_at",0L);'
if old in s:s=s.replace(old,new,1)

# Dynamic visible app version.
s=s.replace('TextView ver=text("Version 1.3.0",12,false);','TextView ver=text("Version "+BuildConfig.VERSION_NAME,12,false);')

# Home cancels any pending enrollment capture loop.
old='''    void showHome(){
        kioskMode=false;stopCamera();'''
new='''    void showHome(){
        enrollmentActive=false;activeEnrollmentUserId=-1;
        kioskMode=false;stopCamera();'''
if old in s:s=s.replace(old,new,1)

# Never reset same-person cooldown when returning from success screen.
s=s.replace('kioskMode=true;lastSubmitAt=0;lastPersonId=-1;','kioskMode=true;')

# Force a fresh profile sync before starting attendance; stale/offline biometric cache cannot start kiosk.
old='''kiosk.setOnClickListener(v->{if(profiles.isEmpty()){toast("No employee face enrolled yet");return;}showKiosk();});'''
new='''kiosk.setOnClickListener(v->{
            if(profiles.isEmpty()){toast("No employee face enrolled yet");return;}
            kiosk.setEnabled(false);status.setText("Refreshing authorized face profiles…");
            io.execute(()->{try{syncProfilesBlocking();ui.post(()->{kiosk.setEnabled(true);showKiosk();});}
                catch(Exception e){ui.post(()->{kiosk.setEnabled(true);status.setText("Cannot start attendance until face profiles sync successfully");toast("Sync required: "+msg(e));});}});
        });'''
if old in s:s=s.replace(old,new,1)

# Make Home scroll-safe in short landscape windows.
needle='''        kiosk.setOnClickListener(v->'''
# Replace only the final setContentView(root) inside showHome with a ScrollView.
home_start=s.find('    void showHome(){')
home_end=s.find('    void showKiosk(){',home_start)
home=s[home_start:home_end]
if 'ScrollView sc=new ScrollView(this);sc.addView(root);setContentView(sc);' not in home:
    home=home.replace('setContentView(root);\n    }','ScrollView sc=new ScrollView(this);sc.addView(root);setContentView(sc);\n    }',1)
    s=s[:home_start]+home+s[home_end:]

# Enrollment becomes an explicit cancellable session.
old='''    void showEnrollment(Employee emp,String adminToken){
        kioskMode=false;ArrayList<FaceSample> samples=new ArrayList<>();'''
new='''    void showEnrollment(Employee emp,String adminToken){
        enrollmentActive=true;activeEnrollmentUserId=emp.id;
        kioskMode=false;ArrayList<FaceSample> samples=new ArrayList<>();'''
if old in s:s=s.replace(old,new,1)
s=s.replace('cancel.setOnClickListener(v->showHome());startCamera(', 'cancel.setOnClickListener(v->{enrollmentActive=false;activeEnrollmentUserId=-1;showHome();});startCamera(',1)

# Stop pending async enrollment callbacks after cancel/navigation.
old='''    void autoEnroll(Employee emp,String auth,ArrayList<FaceSample> samples,int attempts){
        if(attempts>80){'''
new='''    void autoEnroll(Employee emp,String auth,ArrayList<FaceSample> samples,int attempts){
        if(!enrollmentActive || activeEnrollmentUserId!=emp.id)return;
        if(attempts>80){'''
if old in s:s=s.replace(old,new,1)
old='''    void finishAutoEnrollment(Employee emp,String auth,ArrayList<FaceSample> samples){
        ArrayList<double[]>'''
new='''    void finishAutoEnrollment(Employee emp,String auth,ArrayList<FaceSample> samples){
        if(!enrollmentActive || activeEnrollmentUserId!=emp.id)return;
        ArrayList<double[]>'''
if old in s:s=s.replace(old,new,1)
# Successful enrollment closes the session before navigation.
s=s.replace('toast(emp.name+" face enrollment completed");syncProfiles(true);showHome();','enrollmentActive=false;activeEnrollmentUserId=-1;toast(emp.name+" face enrollment completed");syncProfiles(true);showHome();')

# Store cooldown after successful submit.
old='''                lastPersonId=p.id;lastSubmitAt=System.currentTimeMillis();'''
new='''                lastPersonId=p.id;lastSubmitAt=System.currentTimeMillis();
                prefs.edit().putInt("last_person_id",lastPersonId).putLong("last_submit_at",lastSubmitAt).apply();'''
if old in s:s=s.replace(old,new,1)

# Front camera only + correct target rotation for CameraX.
s=replace_method(s,'    void startCamera(Runnable ready){','''    void startCamera(Runnable ready){
        if(ContextCompat.checkSelfPermission(this,Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED){requestNeededPermissions();showPermissionHelp();return;}
        ListenableFuture<ProcessCameraProvider> f=ProcessCameraProvider.getInstance(this);
        f.addListener(()->{
            try{
                cameraProvider=f.get();cameraProvider.unbindAll();
                int rotation=getWindowManager().getDefaultDisplay().getRotation();
                Preview pv=new Preview.Builder().setTargetRotation(rotation).build();pv.setSurfaceProvider(preview.getSurfaceProvider());
                imageCapture=new ImageCapture.Builder().setTargetRotation(rotation).setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY).setJpegQuality(88).build();
                CameraSelector selector=new CameraSelector.Builder().requireLensFacing(CameraSelector.LENS_FACING_FRONT).build();
                cameraProvider.bindToLifecycle(this,selector,pv,imageCapture);
                ready.run();
            }catch(Exception e){if(status!=null)status.setText("Front camera unavailable");toast("Front camera is required for secure face attendance: "+msg(e));}
        },ContextCompat.getMainExecutor(this));
    }''')

# Rotate EXIF before face detection AND re-encode proof JPEG in the corrected orientation.
old='''byte[] jpeg=readAll(file);Bitmap bmp=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);if(bmp==null)throw new Exception("Camera image decode failed");
                        InputImage image=InputImage.fromBitmap(bmp,0);'''
new='''byte[] jpeg=readAll(file);Bitmap bmp=BitmapFactory.decodeByteArray(jpeg,0,jpeg.length);if(bmp==null)throw new Exception("Camera image decode failed");
                        bmp=applyExifRotation(file,bmp);
                        ByteArrayOutputStream correctedOut=new ByteArrayOutputStream();bmp.compress(Bitmap.CompressFormat.JPEG,90,correctedOut);jpeg=correctedOut.toByteArray();
                        final byte[] finalJpeg=jpeg;
                        final Bitmap finalBmp=bmp;
                        InputImage image=InputImage.fromBitmap(finalBmp,0);'''
if old not in s: raise SystemExit('Camera decode anchor not found')
s=s.replace(old,new,1)
s=s.replace('Rect safe=expand(b,bmp.getWidth(),bmp.getHeight());','Rect safe=expand(b,finalBmp.getWidth(),finalBmp.getHeight());')
s=s.replace('Bitmap crop=Bitmap.createBitmap(bmp,safe.left,safe.top,safe.width(),safe.height());','Bitmap crop=Bitmap.createBitmap(finalBmp,safe.left,safe.top,safe.width(),safe.height());')
s=s.replace('android.util.Base64.encodeToString(jpeg,android.util.Base64.NO_WRAP)','android.util.Base64.encodeToString(finalJpeg,android.util.Base64.NO_WRAP)')

# Helper for EXIF rotation.
anchor='    static Rect expand(Rect r,int w,int h){'
helper='''    static Bitmap applyExifRotation(File file,Bitmap bmp)throws Exception{
        ExifInterface exif=new ExifInterface(file.getAbsolutePath());
        int o=exif.getAttributeInt(ExifInterface.TAG_ORIENTATION,ExifInterface.ORIENTATION_NORMAL);float deg=0f;
        if(o==ExifInterface.ORIENTATION_ROTATE_90)deg=90f;else if(o==ExifInterface.ORIENTATION_ROTATE_180)deg=180f;else if(o==ExifInterface.ORIENTATION_ROTATE_270)deg=270f;
        if(deg==0f)return bmp;Matrix m=new Matrix();m.postRotate(deg);return Bitmap.createBitmap(bmp,0,0,bmp.getWidth(),bmp.getHeight(),m,true);
    }

'''
if helper.strip() not in s:s=s.replace(anchor,helper+anchor,1)

# Reject stale cached GPS; server still applies actual accuracy/geofence policy.
old='''            if(a==null)return b;if(b==null)return a;return a.getTime()>=b.getTime()?a:b;'''
new='''            Location best;if(a==null)best=b;else if(b==null)best=a;else best=a.getTime()>=b.getTime()?a:b;
            if(best==null)return null;
            if(System.currentTimeMillis()-best.getTime()>120000L)return null;
            return best;'''
if old in s:s=s.replace(old,new,1)

# Permission recovery UX.
anchor='    LinearLayout page(String title,String sub){'
perm='''    void showPermissionHelp(){
        new AlertDialog.Builder(this).setTitle("Permissions required").setMessage("Camera and precise location permissions are required for secure face attendance.").setNegativeButton("Cancel",null).setPositiveButton("App Settings",(d,w)->{Intent i=new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS);i.setData(android.net.Uri.parse("package:"+getPackageName()));startActivity(i);}).show();
    }
    @Override public void onRequestPermissionsResult(int requestCode,String[] permissions,int[] grantResults){super.onRequestPermissionsResult(requestCode,permissions,grantResults);if(requestCode==RC_PERM){boolean ok=true;for(int r:grantResults)if(r!=PackageManager.PERMISSION_GRANTED)ok=false;if(!ok)showPermissionHelp();}}
    @Override public void onConfigurationChanged(Configuration newConfig){super.onConfigurationChanged(newConfig);int rotation=getWindowManager().getDefaultDisplay().getRotation();if(imageCapture!=null)imageCapture.setTargetRotation(rotation);}

'''
if perm.strip() not in s:s=s.replace(anchor,perm+anchor,1)

p.write_text(s,encoding='utf-8')
print('FINSEQ Attendance v1.3.2 stability patch applied')
