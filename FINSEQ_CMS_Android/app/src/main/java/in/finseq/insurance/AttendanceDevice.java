package in.finseq.insurance;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

public final class AttendanceDevice {
    private AttendanceDevice(){}
    public static String uid(Context c){
        String id= Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ANDROID_ID);
        return id==null||id.trim().isEmpty()?"unknown":id;
    }
    public static String label(){return Build.MANUFACTURER+" "+Build.MODEL+" · Android "+Build.VERSION.RELEASE;}
}
