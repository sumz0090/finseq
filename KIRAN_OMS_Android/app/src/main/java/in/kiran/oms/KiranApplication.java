package in.kiran.oms;

import android.app.Activity;
import android.app.Application;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Bundle;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewTreeObserver;
import android.widget.FrameLayout;
import android.widget.TextView;

import java.util.WeakHashMap;

public class KiranApplication extends Application {
    private static final String PREFS="kiran_oms_native";
    private static final String PREF_LANG="ui_language";
    private final WeakHashMap<Activity, ViewTreeObserver.OnGlobalLayoutListener> listeners=new WeakHashMap<>();

    @Override public void onCreate(){
        super.onCreate();
        registerActivityLifecycleCallbacks(new ActivityLifecycleCallbacks(){
            public void onActivityCreated(Activity a, Bundle b){ install(a); }
            public void onActivityStarted(Activity a){}
            public void onActivityResumed(Activity a){ install(a); apply(a); }
            public void onActivityPaused(Activity a){}
            public void onActivityStopped(Activity a){}
            public void onActivitySaveInstanceState(Activity a, Bundle b){}
            public void onActivityDestroyed(Activity a){ listeners.remove(a); }
        });
    }

    private boolean hindi(){
        return "hi".equals(getSharedPreferences(PREFS,MODE_PRIVATE).getString(PREF_LANG,"en"));
    }

    private void install(Activity a){
        final View decor=a.getWindow().getDecorView();
        if(!listeners.containsKey(a)){
            ViewTreeObserver.OnGlobalLayoutListener l=()->apply(a);
            decor.getViewTreeObserver().addOnGlobalLayoutListener(l);
            listeners.put(a,l);
        }
        addLanguageButton(a);
        apply(a);
    }

    private void apply(Activity a){
        View decor=a.getWindow().getDecorView();
        NativeLang.apply(decor,hindi());
        View v=decor.findViewWithTag("kiran_language_overlay");
        if(v instanceof TextView){
            ((TextView)v).setText(hindi()?"हि":"EN");
            v.setContentDescription(hindi()?"Switch interface to English":"Switch interface to Hindi");
        }
    }

    private void addLanguageButton(Activity a){
        View decor=a.getWindow().getDecorView();
        if(!(decor instanceof ViewGroup)) return;
        ViewGroup vg=(ViewGroup)decor;
        if(decor.findViewWithTag("kiran_language_overlay")!=null) return;

        TextView b=new TextView(a);
        b.setTag("kiran_language_overlay");
        b.setText(hindi()?"हि":"EN");
        b.setTextColor(Color.WHITE);
        b.setTextSize(11);
        b.setGravity(Gravity.CENTER);
        b.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        b.setBackground(UiKit.rounded(Color.rgb(30,41,59),8,Color.rgb(71,85,105)));
        b.setElevation(UiKit.dp(a,10));
        b.setOnClickListener(v->{
            SharedPreferences p=getSharedPreferences(PREFS,MODE_PRIVATE);
            boolean next=!hindi();
            p.edit().putString(PREF_LANG,next?"hi":"en").apply();
            apply(a);
        });

        int status=0;
        int id=getResources().getIdentifier("status_bar_height","dimen","android");
        if(id>0) status=getResources().getDimensionPixelSize(id);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(UiKit.dp(a,42),UiKit.dp(a,34),Gravity.TOP|Gravity.END);
        lp.topMargin=status+UiKit.dp(a,15);
        lp.rightMargin=UiKit.dp(a,108);
        try{
            if(vg instanceof FrameLayout) ((FrameLayout)vg).addView(b,lp);
            else vg.addView(b,new ViewGroup.LayoutParams(UiKit.dp(a,42),UiKit.dp(a,34)));
        }catch(Exception ignored){}
    }
}
