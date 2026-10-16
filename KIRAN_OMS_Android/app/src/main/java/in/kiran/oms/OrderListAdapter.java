package in.kiran.oms;

import android.content.Context;
import android.graphics.Color;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.LinearLayout;
import android.widget.TextView;

import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

final class OrderListAdapter extends BaseAdapter {
    interface Listener { void onOrder(JSONObject order); }

    private final Context context;
    private final Listener listener;
    private final List<JSONObject> items = new ArrayList<>();
    private boolean dark;

    OrderListAdapter(Context context, boolean dark, Listener listener) {
        this.context=context; this.dark=dark; this.listener=listener;
    }

    void setDark(boolean value){ dark=value; notifyDataSetChanged(); }

    void setItems(List<JSONObject> source) {
        items.clear();
        if(source!=null) items.addAll(source);
        notifyDataSetChanged();
    }

    @Override public int getCount(){ return items.size(); }
    @Override public JSONObject getItem(int p){ return items.get(p); }
    @Override public long getItemId(int p){ return getItem(p).optInt("orderNo",p); }

    @Override public View getView(int position, View convertView, ViewGroup parent) {
        JSONObject o=getItem(position);
        int surface=dark?Color.rgb(30,41,59):Color.WHITE;
        int text=dark?Color.rgb(241,245,249):UiKit.TEXT;
        int muted=dark?Color.rgb(148,163,184):UiKit.MUTED;

        LinearLayout card=new LinearLayout(context);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setPadding(UiKit.dp(context,14),UiKit.dp(context,11),UiKit.dp(context,14),UiKit.dp(context,11));
        card.setBackground(UiKit.rounded(surface,6,dark?Color.rgb(51,65,85):UiKit.BORDER));

        LinearLayout top=new LinearLayout(context);
        top.setGravity(Gravity.CENTER_VERTICAL);
        card.addView(top,new LinearLayout.LayoutParams(-1,-2));

        TextView no=UiKit.text(context,"#"+o.optInt("orderNo",0),14,text,true);
        top.addView(no,new LinearLayout.LayoutParams(0,-2,1));

        String st=o.optBoolean("hold",false)?"HOLD":o.optString("status","Pending");
        int sc=o.optBoolean("hold",false)?UiKit.RED:UiKit.statusColor(st);
        TextView chip=UiKit.chip(context,st,withAlpha(sc,30),sc);
        top.addView(chip);

        TextView party=UiKit.text(context,o.optString("party","—")+"  •  "+o.optString("article","—"),15,text,true);
        party.setPadding(0,UiKit.dp(context,7),0,0);
        card.addView(party);

        String meta=o.optString("factory","—")+"  ·  "+o.optString("colour","—")+"  ·  "+o.optInt("totalPairs",0)+" pairs";
        TextView m=UiKit.text(context,meta,12,muted,false);
        m.setPadding(0,UiKit.dp(context,3),0,0);
        card.addView(m);

        String dates="Order "+o.optString("orderDate","—");
        if(!o.optString("prodDate","").isEmpty()) dates+="  ·  Prod "+o.optString("prodDate");
        if(!o.optString("readyDate","").isEmpty()) dates+="  ·  Ready "+o.optString("readyDate");
        if(!o.optString("dispatchDate","").isEmpty()) dates+="  ·  Dispatch "+o.optString("dispatchDate");
        TextView d=UiKit.text(context,dates,10,muted,false);
        d.setPadding(0,UiKit.dp(context,5),0,0);
        card.addView(d);

        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);
        lp.setMargins(UiKit.dp(context,12),UiKit.dp(context,5),UiKit.dp(context,12),UiKit.dp(context,5));
        card.setLayoutParams(lp);
        card.setOnClickListener(v->listener.onOrder(o));
        return card;
    }

    private static int withAlpha(int color,int alphaPct){
        int a=Math.max(0,Math.min(255,(int)(255*(alphaPct/100f))));
        return Color.argb(a,Color.red(color),Color.green(color),Color.blue(color));
    }
}
