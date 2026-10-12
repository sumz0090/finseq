from pathlib import Path
root=Path(__file__).parent
p=root/'app/src/main/java/in/finseq/insurance/AttendanceActivity.java'
s=p.read_text(encoding='utf-8')
s=s.replace('@Override public void onCreate(Bundle b){super.onCreate(b);render();load();}','@Override public void onCreate(Bundle b){super.onCreate(b);render();if(getIntent().getBooleanExtra("reminder_open",false))postEvent("REMINDER_OPENED","Opened");load();}')
s=s.replace('config=j;Ui.ui(this,this::show);','config=j;AttendanceReminderScheduler.sync(this,j);Ui.ui(this,this::show);')
old_open='''void openCamera(){\n        try{Intent i=new Intent(this,SelfieCameraActivity.class);startActivityForResult(i,REQ_CAMERA);}catch(Exception e){logBlocked("Internal camera open failed");Ui.toast(this,"Mandatory live selfie camera could not be opened.");}\n    }'''
new_open='''void openCamera(){\n        try{EmbeddedSelfieCapture.open(this,new EmbeddedSelfieCapture.Callback(){\n            @Override public void onUse(File file){selfieFile=file;submit(selfieFile);}\n            @Override public void onCancel(String reason){String r=(reason==null||reason.trim().isEmpty())?"Mandatory selfie not captured":reason.trim();logBlocked(r);Ui.toast(AttendanceActivity.this,"Selfie camera: "+r);}\n        });}catch(Exception e){String r="Embedded camera open failed: "+e.getMessage();logBlocked(r);Ui.toast(this,r);}\n    }'''
s=s.replace(old_open,new_open)
start=s.find('    @Override protected void onActivityResult(int request,int result,Intent data){')
if start!=-1:
    end=s.find('\n    void submit(File photo){',start)
    if end!=-1:s=s[:start]+s[end+1:]
p.write_text(s,encoding='utf-8')

m=root/'app/src/main/AndroidManifest.xml'
x=m.read_text(encoding='utf-8')
marker='        <activity android:name=".AttendanceActivity"/>'
extra='''        <receiver android:name=".AttendanceReminderReceiver" android:exported="false"/>\n        <activity android:name=".AttendanceCalendarActivity"/>\n        <activity android:name=".AttendanceLeaveActivity"/>\n        <activity android:name=".AttendanceCorrectionActivity"/>\n        <activity android:name=".AttendanceSalaryActivity"/>\n'''
if 'AttendanceCalendarActivity' not in x:x=x.replace(marker,extra+marker)
m.write_text(x,encoding='utf-8')
