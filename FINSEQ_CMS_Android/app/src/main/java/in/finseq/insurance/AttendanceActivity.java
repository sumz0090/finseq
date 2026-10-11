package in.finseq.insurance;

import android.Manifest;
import android.app.*;
import android.content.*;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.location.*;
import android.net.Uri;
import android.os.*;
import android.provider.MediaStore;
import android.util.Base64;
import android.view.*;
import android.widget.*;
import androidx.core.content.FileProvider;
import org.json.*;
import java.io.*;
import java.text.*;
import java.util.*;

/** Employee self-attendance: server rights + approved device + GPS geofence + fresh camera selfie. */
public class AttendanceActivity extends FinseqActivity {
    static final int REQ_PERMS=7101, REQ_CAMERA=7102;
    LinearLayout root,body,card,actions; TextView state,detail,verify; Button primary;
    JSONObject config; String pendingAction=""; Location pendingLocation; File selfieFile; Uri selfieUri;

    @Override public void onCreate(Bundle b){super.onCreate(b);render();load();}
    @Override protected void onResume(){super.onResume();if(root!=null)load();}

    void render(){
        root=Ui.column(this);Ui.appHeader(root,this,"Attendance");body=Ui.contentColumn(this);root.addView(body);
        body.addView(Ui.text(this,"My Attendance",25,true));body.addView(Ui.sub(this,"Secure self attendance · server time · device + geofence verification"));
        card=Ui.card(this);state=Ui.text(this,"Loading…",22,true);detail=Ui.sub(this,"");verify=Ui.sub(this,"");card.addView(state);card.addView(detail);card.addView(verify);body.addView(card);
        actions=new LinearLayout(this);actions.setOrientation(LinearLayout.VERTICAL);body.addView(actions);
        Ui.setScreen(this,root, AccessControl.attendanceOnly(this)?5:5);
    }

    void load(){Ui.run(this,()->{try{JSONObject j=ApiClient.get(this,"/api/attendance/mobile-config");config=j;Ui.ui(this,()->show());}catch(Exception e){Ui.ui(this,()->{state.setText("Attendance unavailable");detail.setText(e.getMessage());});}});}

    boolean right(String key){JSONObject r=config==null?null:config.optJSONObject("rights");return r!=null&&r.optInt(key,0)==1;}
    void show(){
        if(config==null)return; actions.removeAllViews();JSONObject today=config.optJSONObject("today"), shift=config.optJSONObject("shift"), office=config.optJSONObject("office");
        String in=today==null?"":today.optString("check_in"), out=today==null?"":today.optString("check_out"), status=today==null?"Not checked in":today.optString("status","Present");
        state.setText(status);detail.setText("Shift "+(shift==null?"—":shift.optString("start")+" – "+shift.optString("end"))+"\nCheck-In: "+time(in)+"   Check-Out: "+time(out));
        String sec="Device: "+AttendanceDevice.label();
        if(right("require_geofence"))sec+="\nGeofence: "+(office==null?"Not configured":office.optString("name")+" · "+office.optInt("radius_m")+"m");
        if(right("require_approved_device"))sec+="\nApproved device: required";
        if(right("require_photo"))sec+="\nLive selfie: required (camera only)";verify.setText(sec);
        if((in==null||in.isEmpty())&&right("can_check_in")){primary=Ui.greenButton(this,"CHECK IN");primary.setOnClickListener(v->startAttempt("check_in"));actions.addView(primary);}
        else if((out==null||out.isEmpty())&&right("can_check_out")){primary=Ui.redButton(this,"CHECK OUT");primary.setOnClickListener(v->startAttempt("check_out"));actions.addView(primary);}
        else {Button done=Ui.outlineButton(this,"Attendance completed for today");done.setEnabled(false);actions.addView(done);}
        if(right("can_view_calendar")){Button history=Ui.outlineButton(this,"My Attendance Calendar");history.setOnClickListener(v->Ui.toast(this,"Calendar sync is enabled in next screen update."));actions.addView(history);}
        if(right("can_leave")){Button leave=Ui.outlineButton(this,"Leave Request");leave.setOnClickListener(v->Ui.toast(this,"Leave request mobile form is being linked to the same server approval workflow."));actions.addView(leave);}
        if(right("can_correction")){Button correction=Ui.outlineButton(this,"Correction Request");correction.setOnClickListener(v->Ui.toast(this,"Correction request mobile form uses Admin approval."));actions.addView(correction);}
        if(right("can_view_salary")){Button pay=Ui.outlineButton(this,"Salary / Payable Days");pay.setOnClickListener(v->Ui.toast(this,"Salary visibility is enabled by Admin."));actions.addView(pay);}
    }
    static String time(String value){if(value==null||value.isEmpty())return "—";int p=value.indexOf('T');if(p<0)p=value.indexOf(' ');return p>=0&&value.length()>=p+6?value.substring(p+1,p+6):value;}

    void startAttempt(String action){
        pendingAction=action;postEvent(action.equals("check_in")?"CHECKIN_STARTED":"CHECKOUT_STARTED","Started");
        ArrayList<String> missing=new ArrayList<>();
        if(right("require_geofence")||("check_out".equals(action)&&right("checkout_geofence"))){if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.ACCESS_FINE_LOCATION);}
        if(right("require_photo")&&checkSelfPermission(Manifest.permission.CAMERA)!=PackageManager.PERMISSION_GRANTED)missing.add(Manifest.permission.CAMERA);
        if(!missing.isEmpty()){requestPermissions(missing.toArray(new String[0]),REQ_PERMS);return;}collectLocation();
    }
    @Override public void onRequestPermissionsResult(int r,String[] p,int[] g){super.onRequestPermissionsResult(r,p,g);if(r!=REQ_PERMS)return;for(int x:g)if(x!=PackageManager.PERMISSION_GRANTED){logBlocked("Permission denied");Ui.toast(this,"Attendance cannot continue without the required permission.");return;}collectLocation();}

    void collectLocation(){
        boolean need=right("require_geofence")||("check_out".equals(pendingAction)&&right("checkout_geofence"));
        if(!need){pendingLocation=null;nextProof();return;}
        final LocationManager lm=(LocationManager)getSystemService(LOCATION_SERVICE);
        if(lm==null||(!lm.isProviderEnabled(LocationManager.GPS_PROVIDER)&&!lm.isProviderEnabled(LocationManager.NETWORK_PROVIDER))){logBlocked("Location services disabled");Ui.toast(this,"Enable precise location/GPS and retry.");return;}
        if(checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED)return;
        primary.setEnabled(false);primary.setText("Verifying office location…");
        final LocationListener listener=new LocationListener(){public void onLocationChanged(Location l){if(l==null)return;pendingLocation=l;try{lm.removeUpdates(this);}catch(Exception ignored){}primary.setEnabled(true);nextProof();}public void onProviderEnabled(String p){}public void onProviderDisabled(String p){}public void onStatusChanged(String p,int s,Bundle e){}};
        try{lm.requestLocationUpdates(LocationManager.GPS_PROVIDER,0,0,listener,Looper.getMainLooper());Location last=lm.getLastKnownLocation(LocationManager.GPS_PROVIDER);if(last!=null&&System.currentTimeMillis()-last.getTime()<30000){pendingLocation=last;lm.removeUpdates(listener);primary.setEnabled(true);nextProof();}}
        catch(Exception e){primary.setEnabled(true);logBlocked("GPS error: "+e.getMessage());Ui.toast(this,"Could not read precise GPS location.");}
        new Handler(Looper.getMainLooper()).postDelayed(()->{if(pendingLocation==null){try{lm.removeUpdates(listener);}catch(Exception ignored){}if(primary!=null){primary.setEnabled(true);primary.setText(pendingAction.equals("check_in")?"CHECK IN":"CHECK OUT");}logBlocked("GPS timeout");Ui.toast(this,"Precise GPS location could not be obtained. Please retry near the office.");}},15000);
    }

    void nextProof(){if(right("require_photo"))openCamera();else submit(null);}
    void openCamera(){
        try{
            File dir=new File(getCacheDir(),"attendance_camera");dir.mkdirs();selfieFile=File.createTempFile("selfie_",".jpg",dir);selfieUri=FileProvider.getUriForFile(this,getPackageName()+".fileprovider",selfieFile);
            Intent i=new Intent(MediaStore.ACTION_IMAGE_CAPTURE);i.putExtra(MediaStore.EXTRA_OUTPUT,selfieUri);i.addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_READ_URI_PERMISSION);
            i.putExtra("android.intent.extras.CAMERA_FACING",1);i.putExtra("android.intent.extra.USE_FRONT_CAMERA",true);i.putExtra("android.intent.extra.LENS_FACING_FRONT",1);
            startActivityForResult(i,REQ_CAMERA);
        }catch(Exception e){logBlocked("Camera open failed");Ui.toast(this,"Mandatory live camera could not be opened. Attendance was not recorded.");}
    }
    @Override protected void onActivityResult(int request,int result,Intent data){super.onActivityResult(request,result,data);if(request!=REQ_CAMERA)return;if(result!=RESULT_OK||selfieFile==null||!selfieFile.isFile()||selfieFile.length()<100){logBlocked("Mandatory selfie not captured");Ui.toast(this,"Selfie is mandatory. Gallery/upload is not allowed.");return;}new AlertDialog.Builder(this).setTitle("Use this live selfie?").setMessage("The captured selfie will be attached to this attendance attempt. You can retake it, but cannot choose an old photo from Gallery.").setNegativeButton("Retake",(d,w)->openCamera()).setPositiveButton("Use Photo",(d,w)->submit(selfieFile)).show();}

    void submit(File photo){
        if(primary!=null){primary.setEnabled(false);primary.setText("Recording attendance…");}
        Ui.run(this,()->{try{
            JSONObject b=new JSONObject().put("action",pendingAction).put("device_uid",AttendanceDevice.uid(this)).put("device_label",AttendanceDevice.label());
            if(pendingLocation!=null)b.put("latitude",pendingLocation.getLatitude()).put("longitude",pendingLocation.getLongitude()).put("gps_accuracy",pendingLocation.getAccuracy());
            if(photo!=null)b.put("selfie_jpeg_base64",Base64.encodeToString(readLimited(photo,2*1024*1024),Base64.NO_WRAP));
            JSONObject r=ApiClient.post(this,"/api/attendance/mobile-submit",b);postEvent(pendingAction.equals("check_in")?"CHECKIN_COMPLETED":"CHECKOUT_COMPLETED","Success");Ui.ui(this,()->{Ui.toast(this,r.optString("message","Attendance recorded"));load();});
        }catch(Exception e){postEvent(pendingAction.equals("check_in")?"CHECKIN_COMPLETED":"CHECKOUT_COMPLETED","Failed");Ui.ui(this,()->{if(primary!=null){primary.setEnabled(true);primary.setText(pendingAction.equals("check_in")?"CHECK IN":"CHECK OUT");}Ui.toast(this,e.getMessage());});}});
    }
    static byte[] readLimited(File f,int max)throws IOException{try(InputStream in=new FileInputStream(f);ByteArrayOutputStream out=new ByteArrayOutputStream()){byte[] b=new byte[8192];int n,total=0;while((n=in.read(b))!=-1){total+=n;if(total>max)throw new IOException("Selfie image is too large. Retake the photo.");out.write(b,0,n);}return out.toByteArray();}}
    void logBlocked(String reason){postEvent(pendingAction.equals("check_out")?"CHECKOUT_COMPLETED":"CHECKIN_COMPLETED","Blocked: "+reason);}
    void postEvent(String type,String status){Ui.run(this,()->{try{ApiClient.post(this,"/api/attendance/mobile-event-json",new JSONObject().put("event_type",type).put("event_status",status).put("device_uid",AttendanceDevice.uid(this)).put("payload",pendingAction));}catch(Exception ignored){}});}
}
