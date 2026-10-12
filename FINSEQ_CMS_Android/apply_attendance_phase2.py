from pathlib import Path
root=Path(__file__).parent
p=root/'app/src/main/java/in/finseq/insurance/AttendanceActivity.java'
s=p.read_text(encoding='utf-8')
s=s.replace('@Override public void onCreate(Bundle b){super.onCreate(b);render();load();}','@Override public void onCreate(Bundle b){super.onCreate(b);render();if(getIntent().getBooleanExtra("reminder_open",false))postEvent("REMINDER_OPENED","Opened");load();}')
s=s.replace('config=j;Ui.ui(this,this::show);','config=j;AttendanceReminderScheduler.sync(this,j);Ui.ui(this,this::show);')
old='if(result!=RESULT_OK||data==null){logBlocked("Mandatory selfie not captured");Ui.toast(this,"Mandatory live selfie was not captured. Gallery/upload is not allowed.");return;}'
new='if(result!=RESULT_OK||data==null){String cameraError=data==null?"":data.getStringExtra("camera_error");String reason=(cameraError==null||cameraError.trim().isEmpty())?"Mandatory selfie not captured":cameraError.trim();logBlocked(reason);Ui.toast(this,"Selfie camera: "+reason);return;}'
s=s.replace(old,new)
old2='new AlertDialog.Builder(this).setTitle("Use this live selfie?").setMessage("This fresh selfie was captured inside FINSEQ and will be attached to this attendance session.").setNegativeButton("Retake",(d,w)->openCamera()).setPositiveButton("Use Photo",(d,w)->submit(selfieFile)).show();'
s=s.replace(old2,'submit(selfieFile);')
p.write_text(s,encoding='utf-8')

m=root/'app/src/main/AndroidManifest.xml'
x=m.read_text(encoding='utf-8')
marker='        <activity android:name=".AttendanceActivity"/>'
extra='''        <receiver android:name=".AttendanceReminderReceiver" android:exported="false"/>\n        <activity android:name=".AttendanceCalendarActivity"/>\n        <activity android:name=".AttendanceLeaveActivity"/>\n        <activity android:name=".AttendanceCorrectionActivity"/>\n        <activity android:name=".AttendanceSalaryActivity"/>\n'''
if 'AttendanceCalendarActivity' not in x:x=x.replace(marker,extra+marker)
m.write_text(x,encoding='utf-8')
