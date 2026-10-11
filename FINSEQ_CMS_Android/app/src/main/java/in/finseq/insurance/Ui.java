package in.finseq.insurance;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.view.*;
import android.widget.*;
import java.text.NumberFormat;
import java.util.*;

public final class Ui {
    public static final int BLUE=Color.rgb(13,105,154), TEAL=Color.rgb(20,156,132), GREEN=Color.rgb(15,157,104), AMBER=Color.rgb(202,139,4), RED=Color.rgb(190,40,40);
    private Ui(){}

    static int dp(Context c,int v){return Math.round(v*c.getResources().getDisplayMetrics().density);}
    static float fs(Context c,int sp){return sp*AppPrefs.fontScale(c);}
    static int bg(Context c){return AppPrefs.dark(c)?Color.rgb(15,23,42):Color.rgb(245,248,251);}
    static int cardColor(Context c){return AppPrefs.dark(c)?Color.rgb(30,41,59):Color.WHITE;}
    static int textColor(Context c){return AppPrefs.dark(c)?Color.rgb(241,245,249):Color.rgb(15,23,42);}
    static int mutedColor(Context c){return AppPrefs.dark(c)?Color.rgb(148,163,184):Color.rgb(100,116,139);}
    static int lineColor(Context c){return AppPrefs.dark(c)?Color.rgb(51,65,85):Color.rgb(226,232,240);}

    static TextView text(Context c,String s,int sp,boolean bold){
        TextView v=new TextView(c);v.setText(s);v.setTextSize(fs(c,sp));v.setTextColor(textColor(c));v.setPadding(0,dp(c,4),0,dp(c,4));
        if(bold)v.setTypeface(Typeface.DEFAULT,Typeface.BOLD);return v;
    }
    static TextView sub(Context c,String s){TextView v=text(c,s,13,false);v.setTextColor(mutedColor(c));return v;}
    static TextView label(Context c,String s){TextView v=text(c,s,12,true);v.setTextColor(mutedColor(c));return v;}

    static Button button(Context c,String s){
        Button b=new Button(c);b.setText(s);b.setAllCaps(false);b.setTextColor(Color.WHITE);b.setTextSize(fs(c,14));
        b.setBackground(round(BLUE,14));b.setPadding(dp(c,14),dp(c,10),dp(c,14),dp(c,10));
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(c,5),0,dp(c,5));b.setLayoutParams(p);return b;
    }
    static Button greenButton(Context c,String s){Button b=button(c,s);b.setBackground(round(GREEN,14));return b;}
    static Button redButton(Context c,String s){Button b=button(c,s);b.setBackground(round(RED,14));return b;}
    static Button outlineButton(Context c,String s){
        Button b=button(c,s);GradientDrawable g=round(cardColor(c),14);g.setStroke(dp(c,1),lineColor(c));b.setBackground(g);b.setTextColor(textColor(c));return b;
    }
    static GradientDrawable round(int color,int radius){GradientDrawable g=new GradientDrawable();g.setColor(color);g.setCornerRadius(radius*2f);return g;}

    static LinearLayout card(Context c){
        LinearLayout l=new LinearLayout(c);l.setOrientation(LinearLayout.VERTICAL);l.setPadding(dp(c,16),dp(c,14),dp(c,16),dp(c,14));
        GradientDrawable g=round(cardColor(c),18);g.setStroke(dp(c,1),lineColor(c));l.setBackground(g);
        LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(dp(c,12),dp(c,7),dp(c,12),dp(c,7));l.setLayoutParams(p);l.setElevation(dp(c,2));return l;
    }
    static LinearLayout compactCard(Context c){LinearLayout l=card(c);l.setPadding(dp(c,14),dp(c,12),dp(c,14),dp(c,12));return l;}
    static LinearLayout column(Activity a){LinearLayout root=new LinearLayout(a);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(0,0,0,dp(a,86));root.setBackgroundColor(bg(a));return root;}
    static LinearLayout contentColumn(Context c){LinearLayout root=new LinearLayout(c);root.setOrientation(LinearLayout.VERTICAL);root.setPadding(dp(c,10),dp(c,8),dp(c,10),dp(c,16));return root;}
    static ScrollView scroll(Activity a,View child){ScrollView s=new ScrollView(a);s.setFillViewport(true);s.addView(child,new ScrollView.LayoutParams(-1,-2));return s;}
    static EditText input(Context c,String hint){
        EditText e=new EditText(c);e.setHint(hint);e.setHintTextColor(mutedColor(c));e.setTextColor(textColor(c));e.setTextSize(fs(c,15));e.setSingleLine(true);e.setPadding(dp(c,14),dp(c,12),dp(c,14),dp(c,12));
        GradientDrawable g=round(cardColor(c),12);g.setStroke(dp(c,1),lineColor(c));e.setBackground(g);LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,dp(c,6),0,dp(c,10));e.setLayoutParams(p);return e;
    }

    static void appHeader(LinearLayout root,Activity a,String screenTitle){
        LinearLayout bar=new LinearLayout(a);bar.setOrientation(LinearLayout.HORIZONTAL);bar.setGravity(Gravity.CENTER_VERTICAL);bar.setPadding(dp(a,16),dp(a,10),dp(a,16),dp(a,10));bar.setBackgroundColor(cardColor(a));
        ImageView logo=new ImageView(a);logo.setImageResource(a.getResources().getIdentifier("finseq_company_logo_transparent","drawable",a.getPackageName()));logo.setScaleType(ImageView.ScaleType.FIT_CENTER);logo.setAdjustViewBounds(true);bar.addView(logo,new LinearLayout.LayoutParams(dp(a,92),dp(a,46)));
        LinearLayout titles=new LinearLayout(a);titles.setOrientation(LinearLayout.VERTICAL);titles.setPadding(dp(a,8),0,0,0);TextView main=text(a,"Client Management System",17,true);titles.addView(main);if(screenTitle!=null&&!screenTitle.isEmpty()){TextView sub=sub(a,screenTitle);titles.addView(sub);}bar.addView(titles,new LinearLayout.LayoutParams(0,-2,1));
        if(AccessControl.has(a,"renewals")){TextView bell=text(a,"🔔",22,false);bell.setGravity(Gravity.CENTER);bell.setContentDescription("Open renewals and reminders");bell.setClickable(true);bell.setOnClickListener(new View.OnClickListener(){public void onClick(View v){a.startActivity(new Intent(a,RenewalsActivity.class));}});bar.addView(bell,new LinearLayout.LayoutParams(dp(a,42),dp(a,42)));}
        root.addView(bar,new LinearLayout.LayoutParams(-1,-2));
        TextView live=sub(a,"● Live  ·  FINSEQ CMS "+"3.0.3.0");live.setTextColor(GREEN);live.setPadding(dp(a,16),dp(a,6),dp(a,16),dp(a,6));root.addView(live,new LinearLayout.LayoutParams(-1,-2));
    }

    static void title(LinearLayout root,Context c,String title,String subtitle){
        LinearLayout w=new LinearLayout(c);w.setOrientation(LinearLayout.VERTICAL);w.setPadding(dp(c,16),dp(c,10),dp(c,16),dp(c,4));w.addView(text(c,title,25,true));if(subtitle!=null&&!subtitle.isEmpty())w.addView(sub(c,subtitle));root.addView(w);
    }

    static LinearLayout metric(Context c,String icon,String value,String label){
        LinearLayout box=compactCard(c);TextView i=text(c,icon,18,true);i.setTextColor(TEAL);box.addView(i);box.addView(text(c,value,23,true));box.addView(sub(c,label+"  ›"));return box;
    }
    static LinearLayout actionRow(Context c,String icon,String title,String subtitle){
        LinearLayout row=compactCard(c);row.setOrientation(LinearLayout.HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL);
        TextView ico=text(c,icon,24,true);ico.setTextColor(TEAL);ico.setGravity(Gravity.CENTER);row.addView(ico,new LinearLayout.LayoutParams(dp(c,52),dp(c,52)));
        LinearLayout txt=new LinearLayout(c);txt.setOrientation(LinearLayout.VERTICAL);txt.addView(text(c,title,16,true));txt.addView(sub(c,subtitle));row.addView(txt,new LinearLayout.LayoutParams(0,-2,1));row.addView(text(c,"›",26,false));return row;
    }
    static TextView badge(Context c,String value,int color){TextView t=text(c,value,12,true);t.setTextColor(color);t.setGravity(Gravity.CENTER);GradientDrawable g=round(Color.argb(24,Color.red(color),Color.green(color),Color.blue(color)),10);t.setBackground(g);t.setPadding(dp(c,9),dp(c,4),dp(c,9),dp(c,4));return t;}
    static TextView avatar(Context c,String name){String s=(name==null||name.trim().isEmpty())?"?":name.trim().substring(0,1).toUpperCase(Locale.ROOT);TextView t=text(c,s,18,true);t.setTextColor(TEAL);t.setGravity(Gravity.CENTER);t.setBackground(round(Color.rgb(226,247,242),99));return t;}

    static LinearLayout nav(Activity a,int selected){
        if(a instanceof AccountsActivity)selected=-1;
        LinearLayout nav=new LinearLayout(a);nav.setOrientation(LinearLayout.HORIZONTAL);nav.setGravity(Gravity.CENTER);nav.setPadding(dp(a,2),dp(a,5),dp(a,2),dp(a,8));nav.setBackgroundColor(cardColor(a));
        if(AccessControl.attendanceOnly(a)){
            navItem(a,nav,"✓","Attendance",5,selected,AttendanceActivity.class);
            navItem(a,nav,"•••","More",4,selected,MoreActivity.class);
            return nav;
        }
        navItem(a,nav,"⌂","Home",0,selected,MainActivity.class);
        if(AccessControl.has(a,"customers"))navItem(a,nav,"♙","Clients",1,selected,ClientsActivity.class);
        if(AccessControl.has(a,"mf_beta"))navItem(a,nav,"₹","Invest",2,selected,InvestActivity.class);
        if(AccessControl.has(a,"physical_copies"))navItem(a,nav,"▤","Copies",3,selected,PhysicalCopyRegisterActivity.class);
        navItem(a,nav,"•••","More",4,selected,MoreActivity.class);
        return nav;
    }
    private static void navItem(Activity a,LinearLayout nav,String icon,String label,int index,int selected,Class<?> target){
        LinearLayout item=new LinearLayout(a);item.setOrientation(LinearLayout.VERTICAL);item.setGravity(Gravity.CENTER);TextView i=text(a,icon,17,true);TextView l=text(a,label,11,index==selected);int color=index==selected?BLUE:mutedColor(a);i.setTextColor(color);l.setTextColor(color);i.setGravity(Gravity.CENTER);l.setGravity(Gravity.CENTER);item.addView(i,new LinearLayout.LayoutParams(-1,-2));item.addView(l,new LinearLayout.LayoutParams(-1,-2));item.setPadding(0,dp(a,3),0,dp(a,3));item.setContentDescription(label);item.setOnClickListener(new View.OnClickListener(){public void onClick(View v){if(index==selected){if(a instanceof MainActivity)((MainActivity)a).load();return;}Intent in=new Intent(a,target);in.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT);a.startActivity(in);}});nav.addView(item,new LinearLayout.LayoutParams(0,-1,1));
    }
    static void setScreen(Activity a,LinearLayout root,int selected){FrameLayout frame=new FrameLayout(a);frame.addView(scroll(a,root),new FrameLayout.LayoutParams(-1,-1));LinearLayout n=nav(a,selected);FrameLayout.LayoutParams np=new FrameLayout.LayoutParams(-1,dp(a,70),Gravity.BOTTOM);frame.addView(n,np);a.setContentView(frame);}

    static void toast(Context c,String s){Toast.makeText(c,s==null?"":s,Toast.LENGTH_LONG).show();}
    static String money(double x){return NumberFormat.getCurrencyInstance(new Locale("en","IN")).format(x);}
    static void run(Activity a,Runnable work){new Thread(work).start();}
    static void ui(Activity a,Runnable r){a.runOnUiThread(r);}
}
