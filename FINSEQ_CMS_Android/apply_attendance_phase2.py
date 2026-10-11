from pathlib import Path
root=Path(__file__).parent
p=root/'app/src/main/java/in/finseq/insurance/AttendanceActivity.java'
s=p.read_text(encoding='utf-8')
s=s.replace('@Override public void onCreate(Bundle b){super.onCreate(b);render();load();}','@Override public void onCreate(Bundle b){super.onCreate(b);render();if(getIntent().getBooleanExtra("reminder_open",false))postEvent("REMINDER_OPENED","Opened");load();}')
s=s.replace('void load(){Ui.run(this,()->{try{JSONObject j=ApiClient.get(this,"/api/attendance/mobile-config");config=j;Ui.ui(this,()->show());}catch(Exception e){Ui.ui(this,()->{state.setText("Attendance unavailable");detail.setText(e.getMessage());});}});}','void load(){Ui.run(this,()->{try{JSONObject j=ApiClient.get(this,"/api/attendance/mobile-config");config=j;AttendanceReminderScheduler.sync(this,j);Ui.ui(this,()->show());}catch(Exception e){Ui.ui(this,()->{state.setText("Attendance unavailable");detail.setText(e.getMessage());});}});}')
s=s.replace('history.setOnClickListener(v->Ui.toast(this,"Calendar sync is enabled in next screen update."));','history.setOnClickListener(v->startActivity(new Intent(this,AttendanceCalendarActivity.class)));')
s=s.replace('leave.setOnClickListener(v->Ui.toast(this,"Leave request mobile form is being linked to the same server approval workflow."));','leave.setOnClickListener(v->startActivity(new Intent(this,AttendanceLeaveActivity.class)));')
s=s.replace('correction.setOnClickListener(v->Ui.toast(this,"Correction request mobile form uses Admin approval."));','correction.setOnClickListener(v->startActivity(new Intent(this,AttendanceCorrectionActivity.class)));')
s=s.replace('pay.setOnClickListener(v->Ui.toast(this,"Salary visibility is enabled by Admin."));','pay.setOnClickListener(v->startActivity(new Intent(this,AttendanceSalaryActivity.class)));')
p.write_text(s,encoding='utf-8')

m=root/'app/src/main/AndroidManifest.xml'
x=m.read_text(encoding='utf-8')
marker='        <activity android:name=".AttendanceActivity" android:screenOrientation="portrait"/>'
extra='''        <receiver android:name=".AttendanceReminderReceiver" android:exported="false"/>\n        <activity android:name=".AttendanceCalendarActivity" android:screenOrientation="portrait"/>\n        <activity android:name=".AttendanceLeaveActivity" android:screenOrientation="portrait"/>\n        <activity android:name=".AttendanceCorrectionActivity" android:screenOrientation="portrait"/>\n        <activity android:name=".AttendanceSalaryActivity" android:screenOrientation="portrait"/>\n'''
if 'AttendanceCalendarActivity' not in x:x=x.replace(marker,extra+marker)
m.write_text(x,encoding='utf-8')

g=root/'app/build.gradle';x=g.read_text(encoding='utf-8').replace('versionCode 341','versionCode 350').replace("versionName '3.0.3.1'","versionName '3.0.4.0'");g.write_text(x,encoding='utf-8')
