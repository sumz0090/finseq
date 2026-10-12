from pathlib import Path
root=Path(__file__).parent
p=root/'app/src/main/java/in/finseq/insurance/AttendanceActivity.java'
s=p.read_text(encoding='utf-8')
s=s.replace('@Override public void onCreate(Bundle b){super.onCreate(b);render();load();}','@Override public void onCreate(Bundle b){super.onCreate(b);render();if(getIntent().getBooleanExtra("reminder_open",false))postEvent("REMINDER_OPENED","Opened");load();}')
s=s.replace('config=j;Ui.ui(this,this::show);','config=j;AttendanceReminderScheduler.sync(this,j);Ui.ui(this,this::show);')
p.write_text(s,encoding='utf-8')

m=root/'app/src/main/AndroidManifest.xml'
x=m.read_text(encoding='utf-8')
marker='        <activity android:name=".AttendanceActivity"/>'
extra='''        <receiver android:name=".AttendanceReminderReceiver" android:exported="false"/>\n        <activity android:name=".AttendanceCalendarActivity"/>\n        <activity android:name=".AttendanceLeaveActivity"/>\n        <activity android:name=".AttendanceCorrectionActivity"/>\n        <activity android:name=".AttendanceSalaryActivity"/>\n'''
if 'AttendanceCalendarActivity' not in x:x=x.replace(marker,extra+marker)
m.write_text(x,encoding='utf-8')
