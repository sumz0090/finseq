package in.finseq.insurance;

import android.app.AlertDialog;
import android.content.DialogInterface;
import android.widget.Button;
import java.text.SimpleDateFormat;
import java.util.Calendar;
import java.util.Locale;

/** Calendar period selection for the dashboard totals. */
public final class DashboardPeriod {
    private static String month(Calendar date) {
        return new SimpleDateFormat("yyyy-MM",Locale.US).format(date.getTime());
    }

    public static String currentMonth() {return month(Calendar.getInstance());}

    private static String lastMonth() {
        Calendar date=Calendar.getInstance();date.add(Calendar.MONTH,-1);return month(date);
    }

    public static String path(String choice) {
        if("last_month".equals(choice)||"quarter".equals(choice)||"all_time".equals(choice))
            return "/api/v1/sync?period="+choice;
        return "/api/v1/sync?month="+(choice==null||choice.isEmpty()?currentMonth():choice);
    }

    public static String drilldownMonth(String choice) {
        if("last_month".equals(choice))return lastMonth();
        if("quarter".equals(choice)||"all_time".equals(choice))return "";
        return choice==null||choice.isEmpty()?currentMonth():choice;
    }

    public static boolean contains(String choice,String issued) {
        if("all_time".equals(choice)||choice==null||choice.isEmpty())return true;
        if(issued==null||issued.length()<10)return false;
        String date=issued.substring(0,10);
        if("last_month".equals(choice))return date.startsWith(lastMonth());
        if("quarter".equals(choice)) {
            Calendar c=Calendar.getInstance();int year=c.get(Calendar.YEAR);
            int first=((c.get(Calendar.MONTH))/3)*3+1;
            String start=String.format(Locale.US,"%04d-%02d-01",year,first);
            c.set(year,first-1,1);c.add(Calendar.MONTH,3);
            String end=month(c)+"-01";
            return date.compareTo(start)>=0&&date.compareTo(end)<0;
        }
        return date.startsWith(choice);
    }

    public static void choose(MainActivity activity,Button button) {
        String[] labels={"Current Month","Last Month","Quarter","All Time"};
        new AlertDialog.Builder(activity).setTitle("Dashboard period").setItems(labels,
            new DialogInterface.OnClickListener(){@Override public void onClick(DialogInterface dialog,int which){
                activity.selectedMonth=which==0?currentMonth():which==1?"last_month":which==2?"quarter":"all_time";
                button.setText(labels[which]+" ▾");
                activity.load();
            }}).show();
    }
}
