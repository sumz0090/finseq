from pathlib import Path
p=Path('app/src/main/java/in/finseq/attendance/MainActivity.java')
s=p.read_text(encoding='utf-8')
s=s.replace('"/api/attendance/kiosk/operator-login"','"/api/attendance/secure/operator-login"')
s=s.replace('"/api/attendance/kiosk/enroll"','"/api/attendance/secure/enroll"')
s=s.replace('"/api/attendance/kiosk/submit"','"/api/attendance/secure/submit"')
p.write_text(s,encoding='utf-8')
print('v1.2 secure routes applied')
