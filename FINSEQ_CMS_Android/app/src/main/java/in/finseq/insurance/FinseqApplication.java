package in.finseq.insurance;

import android.app.Application;
import android.content.SharedPreferences;

public class FinseqApplication extends Application {
    @Override public void onCreate() {
        super.onCreate();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, cause) -> {
            try {
                String location = "unknown";
                for (StackTraceElement frame : cause.getStackTrace()) {
                    if (frame.getClassName().startsWith("in.finseq.insurance.")) {
                        location = frame.getClassName().substring("in.finseq.insurance.".length()) + ":" + frame.getLineNumber();
                        break;
                    }
                }
                String code = cause.getClass().getSimpleName() + " · " + location;
                SharedPreferences prefs = getSharedPreferences("finseq_diagnostics", MODE_PRIVATE);
                prefs.edit().putString("last_crash", code).commit();
            } catch (Exception ignored) { }
            if (previous != null) previous.uncaughtException(thread, cause);
            else android.os.Process.killProcess(android.os.Process.myPid());
        });
    }
}
