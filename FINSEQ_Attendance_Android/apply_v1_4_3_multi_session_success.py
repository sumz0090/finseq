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

# v1.4.3: success screen is intentionally non-interactive for exactly 2 seconds.
s=replace_method(s,'    void showAttendanceSuccess(String name,String action,String tm){','''    void showAttendanceSuccess(String name,String action,String tm){
        kioskMode=false;fastSubmitting=true;stopCamera();
        String normalized=(action==null?"":action.toUpperCase(java.util.Locale.US));
        boolean isOut=normalized.contains("OUT");
        String title=isOut?"CHECK-OUT SUCCESS":"CHECK-IN SUCCESS";
        LinearLayout root=new LinearLayout(this);root.setOrientation(LinearLayout.VERTICAL);root.setGravity(Gravity.CENTER);root.setPadding(dp(24),dp(28),dp(24),dp(28));root.setBackgroundColor(Color.rgb(240,253,244));
        root.addView(finseqLogo(82));
        TextView tick=text("✓",64,true);tick.setGravity(Gravity.CENTER);tick.setTextColor(Color.rgb(22,163,74));tick.setPadding(0,dp(8),0,0);root.addView(tick);
        TextView ttl=text(title,28,true);ttl.setGravity(Gravity.CENTER);ttl.setTextColor(Color.rgb(21,128,61));root.addView(ttl);
        TextView person=text(name,22,true);person.setGravity(Gravity.CENTER);person.setTextColor(Color.rgb(15,23,42));person.setPadding(dp(16),dp(16),dp(16),dp(8));root.addView(person,new LinearLayout.LayoutParams(-1,-2));
        TextView time=text(tm,17,true);time.setGravity(Gravity.CENTER);time.setTextColor(Color.rgb(51,65,85));root.addView(time);
        TextView note=text("Attendance recorded successfully",14,false);note.setGravity(Gravity.CENTER);note.setTextColor(Color.rgb(71,85,105));note.setPadding(0,dp(12),0,0);root.addView(note);
        setContentView(root);
        ui.postDelayed(()->{fastSubmitting=false;showKiosk();},2000L);
    }''')

# Keep wording clear that multiple sessions are supported by the server.
s=s.replace('Automatically detect and mark IN / OUT','Automatically detect and mark multiple IN / OUT')
s=s.replace('Attendance · Fast Scan','Attendance · Fast Multi-Session Scan')

p.write_text(s,encoding='utf-8')
print('FINSEQ Attendance v1.4.3 multi-session + 2-second success patch applied')
