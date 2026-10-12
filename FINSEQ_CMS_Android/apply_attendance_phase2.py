from pathlib import Path
root=Path(__file__).parent
p=root/'app/src/main/java/in/finseq/insurance/AttendanceActivity.java'
s=p.read_text(encoding='utf-8')
s=s.replace('import android.location.*;','import android.location.*;\nimport android.graphics.Bitmap;\nimport android.provider.MediaStore;')
s=s.replace('@Override public void onCreate(Bundle b){super.onCreate(b);render();load();}','@Override public void onCreate(Bundle b){super.onCreate(b);render();if(getIntent().getBooleanExtra("reminder_open",false))postEvent("REMINDER_OPENED","Opened");load();}')
s=s.replace('config=j;Ui.ui(this,this::show);','config=j;AttendanceReminderScheduler.sync(this,j);Ui.ui(this,this::show);')
old_open='''void openCamera(){\n        try{Intent i=new Intent(this,SelfieCameraActivity.class);startActivityForResult(i,REQ_CAMERA);}catch(Exception e){logBlocked("Internal camera open failed");Ui.toast(this,"Mandatory live selfie camera could not be opened.");}\n    }'''
new_open='''void openCamera(){\n        try{EmbeddedSelfieCapture.open(this,new EmbeddedSelfieCapture.Callback(){\n            @Override public void onUse(File file){selfieFile=file;submit(selfieFile);}\n            @Override public void onCancel(String reason){String r=(reason==null||reason.trim().isEmpty())?"Mandatory selfie not captured":reason.trim();logBlocked(r);Ui.toast(AttendanceActivity.this,"Selfie cancelled: "+r);}\n            @Override public void onFallback(String reason){Ui.toast(AttendanceActivity.this,"Using system camera for fresh selfie…");openSystemCamera(reason);}\n        });}catch(Exception e){openSystemCamera("Embedded camera open failed: "+e.getMessage());}\n    }\n\n    void openSystemCamera(String previousReason){\n        try{\n            Intent i=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);\n            i.putExtra("android.intent.extras.CAMERA_FACING",1);\n            i.putExtra("android.intent.extra.USE_FRONT_CAMERA",true);\n            i.putExtra("android.intent.extras.LENS_FACING_FRONT",1);\n            if(i.resolveActivity(getPackageManager())==null){String r="No system camera app available"+(previousReason==null?"":" · "+previousReason);logBlocked(r);Ui.toast(this,r);return;}\n            startActivityForResult(i,REQ_CAMERA);\n        }catch(Exception e){String r="System camera failed to open: "+e.getMessage();logBlocked(r);Ui.toast(this,r);}\n    }'''
s=s.replace(old_open,new_open)
start=s.find('    @Override protected void onActivityResult(int request,int result,Intent data){')
if start!=-1:
    end=s.find('\n    void submit(File photo){',start)
    if end!=-1:
        fallback='''    @Override protected void onActivityResult(int request,int result,Intent data){\n        super.onActivityResult(request,result,data);if(request!=REQ_CAMERA)return;\n        if(result!=RESULT_OK||data==null){logBlocked("Fresh system-camera selfie cancelled");Ui.toast(this,"Fresh selfie was not captured. Gallery is not allowed.");return;}\n        try{\n            Bundle extras=data.getExtras();Object raw=extras==null?null:extras.get("data");\n            if(!(raw instanceof Bitmap)){logBlocked("System camera returned no fresh image");Ui.toast(this,"Camera returned no fresh selfie. Please retry.");return;}\n            Bitmap bmp=(Bitmap)raw;File dir=new File(getCacheDir(),"attendance_camera");if(!dir.exists())dir.mkdirs();\n            File f=new File(dir,"system_selfie_"+System.currentTimeMillis()+".jpg");\n            try(FileOutputStream out=new FileOutputStream(f)){if(!bmp.compress(Bitmap.CompressFormat.JPEG,82,out))throw new IOException("JPEG save failed");out.flush();}\n            if(!f.isFile()||f.length()<100){logBlocked("System-camera selfie file empty");Ui.toast(this,"Fresh selfie could not be saved. Please retry.");return;}\n            selfieFile=f;submit(selfieFile);\n        }catch(Exception e){String r="System-camera selfie save failed: "+e.getMessage();logBlocked(r);Ui.toast(this,r);}\n    }\n'''
        s=s[:start]+fallback+s[end+1:]
p.write_text(s,encoding='utf-8')

m=root/'app/src/main/AndroidManifest.xml'
x=m.read_text(encoding='utf-8')
marker='        <activity android:name=".AttendanceActivity"/>'
extra='''        <receiver android:name=".AttendanceReminderReceiver" android:exported="false"/>\n        <activity android:name=".AttendanceCalendarActivity"/>\n        <activity android:name=".AttendanceLeaveActivity"/>\n        <activity android:name=".AttendanceCorrectionActivity"/>\n        <activity android:name=".AttendanceSalaryActivity"/>\n'''
if 'AttendanceCalendarActivity' not in x:x=x.replace(marker,extra+marker)
m.write_text(x,encoding='utf-8')
