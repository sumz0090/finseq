package in.kiran.oms;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.ColorStateList;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.InputType;
import android.text.TextWatcher;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.view.Window;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.TextView;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS="kiran_oms_native";
    private static final String PREF_SERVER="server_url";
    private static final String PREF_DARK="dark_mode";
    private static final String PREF_CLIENT="client_id";

    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private SharedPreferences prefs;
    private boolean dark;
    private String server="";
    private String clientId="";
    private AppState state=new AppState();

    private LinearLayout root;
    private FrameLayout content;
    private TextView connection;
    private ProgressBar loading;
    private LinearLayout bottom;
    private int currentScreen=0;

    private String orderQuery="";
    private String orderStatus="All";
    private String productionStage="In Production";
    private boolean soleMode=true;
    private OrderListAdapter ordersAdapter;
    private EditText orderSearch;
    private Spinner orderStatusSpinner;

    @Override public void onCreate(Bundle savedInstanceState){
        super.onCreate(savedInstanceState);
        prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        dark=prefs.getBoolean(PREF_DARK,false);
        server=prefs.getString(PREF_SERVER,"");
        clientId=prefs.getString(PREF_CLIENT,"");
        if(clientId.isEmpty()){
            clientId="android-"+UUID.randomUUID().toString();
            prefs.edit().putString(PREF_CLIENT,clientId).apply();
        }
        buildShell();
        if(server.isEmpty()) autoFindServer(true);
        else loadData(true);
    }

    private int bg(){ return dark?Color.rgb(15,23,42):UiKit.BG; }
    private int surface(){ return dark?Color.rgb(30,41,59):Color.WHITE; }
    private int text(){ return dark?Color.rgb(241,245,249):UiKit.TEXT; }
    private int muted(){ return dark?Color.rgb(148,163,184):UiKit.MUTED; }
    private int border(){ return dark?Color.rgb(51,65,85):UiKit.BORDER; }

    private void buildShell(){
        Window w=getWindow();
        w.setStatusBarColor(dark?Color.rgb(2,6,23):Color.rgb(15,23,42));
        w.setNavigationBarColor(dark?Color.rgb(2,6,23):Color.WHITE);
        if(Build.VERSION.SDK_INT>=23) w.getDecorView().setSystemUiVisibility(dark?0:View.SYSTEM_UI_FLAG_LIGHT_NAVIGATION_BAR);

        root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(bg());

        LinearLayout bar=new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(UiKit.dp(this,16),UiKit.dp(this,8),UiKit.dp(this,8),UiKit.dp(this,8));
        bar.setBackgroundColor(dark?Color.rgb(2,6,23):Color.rgb(15,23,42));
        root.addView(bar,new LinearLayout.LayoutParams(-1,UiKit.dp(this,64)));

        LinearLayout titleWrap=new LinearLayout(this);
        titleWrap.setOrientation(LinearLayout.VERTICAL);
        titleWrap.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(titleWrap,new LinearLayout.LayoutParams(0,-1,1f));

        TextView title=UiKit.text(this,"KIRAN OMS",18,Color.WHITE,true);
        titleWrap.addView(title);
        connection=UiKit.text(this,"Native Android • connecting…",10,Color.rgb(191,219,254),false);
        connection.setSingleLine(true);
        titleWrap.addView(connection);

        TextView theme=topAction(dark?"☀":"☾");
        theme.setContentDescription("Light/Dark mode");
        theme.setOnClickListener(v->toggleTheme());
        bar.addView(theme,new LinearLayout.LayoutParams(UiKit.dp(this,48),-1));

        TextView serverBtn=topAction("⋮");
        serverBtn.setContentDescription("Server settings");
        serverBtn.setOnClickListener(v->serverDialog());
        bar.addView(serverBtn,new LinearLayout.LayoutParams(UiKit.dp(this,48),-1));

        content=new FrameLayout(this);
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1f));

        loading=new ProgressBar(this);
        loading.setIndeterminate(true);
        FrameLayout.LayoutParams llp=new FrameLayout.LayoutParams(UiKit.dp(this,42),UiKit.dp(this,42));
        llp.gravity=Gravity.CENTER;
        content.addView(loading,llp);

        bottom=new LinearLayout(this);
        bottom.setOrientation(LinearLayout.HORIZONTAL);
        bottom.setGravity(Gravity.CENTER);
        bottom.setPadding(UiKit.dp(this,4),UiKit.dp(this,4),UiKit.dp(this,4),UiKit.dp(this,5));
        bottom.setBackgroundColor(surface());
        bottom.setElevation(UiKit.dp(this,10));
        root.addView(bottom,new LinearLayout.LayoutParams(-1,UiKit.dp(this,66)));

        addNav("⌂","Home",0);
        addNav("▤","Orders",1);
        addNav("⚙","Production",2);
        addNav("▦","Stock",3);
        addNav("⋯","More",4);

        setContentView(root);
        updateNav();
        renderCurrent();
    }

    private TextView topAction(String value){
        TextView t=UiKit.text(this,value,24,Color.WHITE,true);
        t.setGravity(Gravity.CENTER);
        t.setBackground(UiKit.rounded(Color.TRANSPARENT,8,Color.TRANSPARENT));
        return t;
    }

    private void addNav(String icon,String label,int id){
        LinearLayout item=new LinearLayout(this);
        item.setTag(id);
        item.setOrientation(LinearLayout.VERTICAL);
        item.setGravity(Gravity.CENTER);
        item.setPadding(UiKit.dp(this,2),UiKit.dp(this,4),UiKit.dp(this,2),UiKit.dp(this,3));

        TextView i=UiKit.text(this,icon,20,muted(),true);
        i.setGravity(Gravity.CENTER);
        i.setTag("icon");
        item.addView(i,new LinearLayout.LayoutParams(-1,0,1f));

        TextView l=UiKit.text(this,label,10,muted(),false);
        l.setGravity(Gravity.CENTER);
        l.setTag("label");
        item.addView(l,new LinearLayout.LayoutParams(-1,-2));

        item.setOnClickListener(v->{
            currentScreen=(Integer)v.getTag();
            updateNav();
            renderCurrent();
        });
        bottom.addView(item,new LinearLayout.LayoutParams(0,-1,1f));
    }

    private void updateNav(){
        if(bottom==null) return;
        for(int n=0;n<bottom.getChildCount();n++){
            LinearLayout item=(LinearLayout)bottom.getChildAt(n);
            boolean active=((Integer)item.getTag())==currentScreen;
            item.setBackground(active?UiKit.rounded(dark?Color.rgb(30,58,138):Color.rgb(239,246,255),8,Color.TRANSPARENT):null);
            for(int i=0;i<item.getChildCount();i++){
                TextView t=(TextView)item.getChildAt(i);
                t.setTextColor(active?UiKit.BLUE:muted());
                if("label".equals(t.getTag())) t.setTypeface(active?android.graphics.Typeface.DEFAULT_BOLD:android.graphics.Typeface.DEFAULT);
            }
        }
    }

    private void toggleTheme(){
        dark=!dark;
        prefs.edit().putBoolean(PREF_DARK,dark).apply();
        buildShell();
    }

    private void showLoading(boolean show){
        if(loading!=null) loading.setVisibility(show?View.VISIBLE:View.GONE);
    }

    private void autoFindServer(boolean fallbackDialog){
        showLoading(true);
        connection.setText("Finding KIRAN server on LAN…");
        executor.execute(()->{
            String found=KiranApi.discover();
            runOnUiThread(()->{
                if(!found.isEmpty()){
                    server=found;
                    prefs.edit().putString(PREF_SERVER,server).apply();
                    loadData(true);
                }else if(!prefs.getString(PREF_SERVER,"").isEmpty()){
                    server=prefs.getString(PREF_SERVER,"");
                    loadData(true);
                }else{
                    showLoading(false);
                    connection.setText("Server not found");
                    if(fallbackDialog) serverDialog();
                }
            });
        });
    }

    private void loadData(boolean showBusy){
        if(server.isEmpty()){ serverDialog(); return; }
        if(showBusy) showLoading(true);
        connection.setText("Connecting • "+serverLabel());
        executor.execute(()->{
            try{
                JSONObject response=KiranApi.getState(server);
                AppState fresh=AppState.fromStateResponse(response);
                runOnUiThread(()->{
                    state=fresh;
                    showLoading(false);
                    connection.setText("● Connected • "+serverLabel()+" • v"+state.version);
                    connection.setTextColor(Color.rgb(134,239,172));
                    renderCurrent();
                });
            }catch(Exception ex){
                runOnUiThread(()->{
                    showLoading(false);
                    connection.setText("● Offline • "+serverLabel());
                    connection.setTextColor(Color.rgb(253,186,116));
                    Toast.makeText(this,"Server connect nahi hua: "+safeMessage(ex),Toast.LENGTH_LONG).show();
                    renderCurrent();
                });
            }
        });
    }

    private String safeMessage(Exception e){
        String s=e.getMessage();
        return s==null?e.getClass().getSimpleName():s;
    }

    private String serverLabel(){
        String x=server.replace("http://","").replace("https://","");
        while(x.endsWith("/")) x=x.substring(0,x.length()-1);
        return x.isEmpty()?"Not set":x;
    }

    private void serverDialog(){
        EditText input=field("192.168.1.50:8787");
        input.setText(server);
        LinearLayout box=new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setPadding(UiKit.dp(this,20),0,UiKit.dp(this,20),0);
        TextView hint=UiKit.text(this,"Main PC aur phone same Wi-Fi/LAN par hone chahiye.",12,muted(),false);
        hint.setPadding(0,0,0,UiKit.dp(this,10));
        box.addView(hint);
        box.addView(input,new LinearLayout.LayoutParams(-1,UiKit.dp(this,48)));

        AlertDialog d=new AlertDialog.Builder(this)
                .setTitle("KIRAN OMS Server")
                .setView(box)
                .setPositiveButton("Connect",null)
                .setNeutralButton("Auto Find",null)
                .setNegativeButton("Cancel",null).create();
        d.setOnShowListener(x->{
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                String s=KiranApi.normalize(input.getText().toString());
                if(s.isEmpty()){ input.setError("Server address required"); return; }
                server=s; prefs.edit().putString(PREF_SERVER,server).apply(); d.dismiss(); loadData(true);
            });
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{ d.dismiss(); server=""; autoFindServer(true); });
        });
        d.show();
    }

    private void renderCurrent(){
        if(content==null) return;
        content.removeAllViews();
        switch(currentScreen){
            case 1: renderOrders(); break;
            case 2: renderProduction(); break;
            case 3: renderStock(); break;
            case 4: renderMore(); break;
            default: renderDashboard();
        }
    }

    private LinearLayout page(){
        LinearLayout p=new LinearLayout(this);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setPadding(UiKit.dp(this,14),UiKit.dp(this,12),UiKit.dp(this,14),UiKit.dp(this,12));
        p.setBackgroundColor(bg());
        return p;
    }

    private TextView pageTitle(String title,String sub){
        LinearLayout wrap=new LinearLayout(this);
        wrap.setOrientation(LinearLayout.VERTICAL);
        TextView t=UiKit.text(this,title,21,text(),true);
        wrap.addView(t);
        if(sub!=null&&!sub.isEmpty()){
            TextView s=UiKit.text(this,sub,11,muted(),false);
            s.setPadding(0,UiKit.dp(this,2),0,0);
            wrap.addView(s);
        }
        return t;
    }

    private void renderDashboard(){
        ScrollView scroll=new ScrollView(this);
        LinearLayout p=page();
        scroll.addView(p);
        content.addView(scroll,new FrameLayout.LayoutParams(-1,-1));

        p.addView(UiKit.text(this,"Operations Overview",22,text(),true));
        TextView sub=UiKit.text(this,"Live central LAN data • "+state.orders.size()+" total orders",11,muted(),false);
        sub.setPadding(0,UiKit.dp(this,3),0,UiKit.dp(this,12)); p.addView(sub);

        addKpiRow(p,
                kpi("Pending",state.countStatus("Pending"),state.pairsStatus("Pending"),UiKit.RED,()->goScreen(1,"","Pending")),
                kpi("Production",state.countStatus("In Production"),state.pairsStatus("In Production"),UiKit.AMBER,()->goProduction("In Production")));
        addKpiRow(p,
                kpi("Ready",state.countStatus("Ready"),state.pairsStatus("Ready"),UiKit.BLUE,()->goProduction("Ready")),
                kpi("On Hold",state.countHold(),holdPairs(),UiKit.RED,()->goProduction("HOLD")));
        addKpiRow(p,
                kpi("Dispatched",state.countStatus("Dispatched"),state.pairsStatus("Dispatched"),UiKit.GREEN,()->goScreen(1,"","Dispatched")),
                kpi("Open PO",openPoCount(),openPoPairs(),Color.rgb(124,58,237),()->{currentScreen=4;updateNav();renderCurrent();}));

        p.addView(sectionLabel("Quick Actions"));
        LinearLayout quick=new LinearLayout(this); quick.setOrientation(LinearLayout.HORIZONTAL);
        quick.addView(actionButton("+ New Order",UiKit.BLUE,v->showNewOrder()),new LinearLayout.LayoutParams(0,UiKit.dp(this,46),1f));
        LinearLayout.LayoutParams gapLp=new LinearLayout.LayoutParams(UiKit.dp(this,8),1); quick.addView(new View(this),gapLp);
        quick.addView(actionButton("Refresh",dark?Color.rgb(51,65,85):Color.rgb(226,232,240),v->loadData(true)),new LinearLayout.LayoutParams(0,UiKit.dp(this,46),1f));
        p.addView(quick);

        p.addView(sectionLabel("Attention"));
        LinearLayout alert=UiKit.card(this); applyCardTheme(alert);
        int ageing=pendingAgeingCount(7);
        alert.addView(rowText("Pending 7+ days",String.valueOf(ageing),ageing>0?UiKit.RED:UiKit.GREEN));
        alert.addView(rowText("Orders on hold",String.valueOf(state.countHold()),state.countHold()>0?UiKit.AMBER:UiKit.GREEN));
        alert.addView(rowText("Purchase orders open",String.valueOf(openPoCount()),openPoCount()>0?Color.rgb(124,58,237):UiKit.GREEN));
        p.addView(alert,cardLp());

        p.addView(sectionLabel("Recent Orders"));
        int max=Math.min(6,state.orders.size());
        for(int i=0;i<max;i++) p.addView(compactOrder(state.orders.get(i)));
        if(max==0){
            TextView empty=UiKit.text(this,"Abhi koi order nahi hai.",13,muted(),false);
            empty.setGravity(Gravity.CENTER); empty.setPadding(0,UiKit.dp(this,20),0,UiKit.dp(this,20)); p.addView(empty);
        }
    }

    private void addKpiRow(LinearLayout p,View a,View b){
        LinearLayout row=new LinearLayout(this); row.setOrientation(LinearLayout.HORIZONTAL);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(0,UiKit.dp(this,118),1f);
        lp.setMargins(0,0,UiKit.dp(this,5),UiKit.dp(this,8)); row.addView(a,lp);
        LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(0,UiKit.dp(this,118),1f);
        rp.setMargins(UiKit.dp(this,5),0,0,UiKit.dp(this,8)); row.addView(b,rp);
        p.addView(row);
    }

    private View kpi(String label,int count,int pairs,int accent,Runnable click){
        LinearLayout c=UiKit.card(this); applyCardTheme(c); c.setGravity(Gravity.CENTER_VERTICAL);
        TextView l=UiKit.text(this,label.toUpperCase(Locale.ROOT),10,muted(),true); c.addView(l);
        TextView n=UiKit.text(this,String.valueOf(count),30,accent,true); n.setPadding(0,UiKit.dp(this,4),0,0); c.addView(n);
        c.addView(UiKit.text(this,pairs+" pairs",11,muted(),false));
        c.setOnClickListener(v->click.run());
        return c;
    }

    private void renderOrders(){
        LinearLayout p=page(); content.addView(p,new FrameLayout.LayoutParams(-1,-1));

        LinearLayout head=new LinearLayout(this); head.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout hw=new LinearLayout(this); hw.setOrientation(LinearLayout.VERTICAL);
        hw.addView(UiKit.text(this,"Master Orders",21,text(),true));
        hw.addView(UiKit.text(this,"Search, filter and open native order details",11,muted(),false));
        head.addView(hw,new LinearLayout.LayoutParams(0,-2,1f));
        TextView add=smallAction("+ New",UiKit.BLUE,Color.WHITE);
        add.setOnClickListener(v->showNewOrder()); head.addView(add,new LinearLayout.LayoutParams(UiKit.dp(this,76),UiKit.dp(this,40)));
        p.addView(head);

        LinearLayout filters=new LinearLayout(this); filters.setOrientation(LinearLayout.HORIZONTAL);
        filters.setPadding(0,UiKit.dp(this,10),0,UiKit.dp(this,8));
        orderSearch=field("Search Order / Party / Article");
        orderSearch.setText(orderQuery);
        filters.addView(orderSearch,new LinearLayout.LayoutParams(0,UiKit.dp(this,46),1f));

        orderStatusSpinner=statusSpinner(orderStatus);
        LinearLayout.LayoutParams slp=new LinearLayout.LayoutParams(UiKit.dp(this,132),UiKit.dp(this,46)); slp.setMargins(UiKit.dp(this,8),0,0,0);
        filters.addView(orderStatusSpinner,slp);
        p.addView(filters);

        ListView list=new ListView(this);
        list.setDivider(null); list.setDividerHeight(0); list.setCacheColorHint(Color.TRANSPARENT);
        ordersAdapter=new OrderListAdapter(this,dark,this::showOrderDetail);
        list.setAdapter(ordersAdapter);
        p.addView(list,new LinearLayout.LayoutParams(-1,0,1f));

        orderSearch.addTextChangedListener(new TextWatcher(){
            public void beforeTextChanged(CharSequence s,int st,int c,int a){}
            public void onTextChanged(CharSequence s,int st,int before,int count){ orderQuery=s.toString(); refreshOrders(); }
            public void afterTextChanged(Editable e){}
        });
        orderStatusSpinner.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener(){
            public void onItemSelected(AdapterView<?> a,View v,int pos,long id){ orderStatus=String.valueOf(a.getItemAtPosition(pos)); refreshOrders(); }
            public void onNothingSelected(AdapterView<?> a){}
        });
        refreshOrders();
    }

    private Spinner statusSpinner(String selected){
        String[] items={"All","Pending","In Production","Ready","Dispatched","Hold","Cancelled"};
        Spinner sp=new Spinner(this);
        ArrayAdapter<String> a=new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,items);
        sp.setAdapter(a); sp.setBackground(UiKit.rounded(surface(),8,border()));
        for(int i=0;i<items.length;i++) if(items[i].equals(selected)) sp.setSelection(i);
        return sp;
    }

    private void refreshOrders(){
        if(ordersAdapter==null) return;
        List<JSONObject> out=new ArrayList<>();
        String q=orderQuery.trim().toLowerCase(Locale.ROOT);
        for(JSONObject o:state.orders){
            boolean hold=o.optBoolean("hold",false);
            String st=hold?"Hold":o.optString("status","Pending");
            if(!"All".equals(orderStatus)&&!orderStatus.equalsIgnoreCase(st)) continue;
            String hay=(o.optInt("orderNo",0)+" "+o.optString("party")+" "+o.optString("article")+" "+o.optString("factory")+" "+o.optString("colour")+" "+o.optString("box")+" "+o.optString("sole")).toLowerCase(Locale.ROOT);
            if(!q.isEmpty()&&!hay.contains(q)) continue;
            out.add(o);
        }
        ordersAdapter.setItems(out);
    }

    private void renderProduction(){
        LinearLayout p=page(); content.addView(p,new FrameLayout.LayoutParams(-1,-1));
        p.addView(UiKit.text(this,"Production",21,text(),true));
        TextView sub=UiKit.text(this,"Native workflow board • tap an order for actions",11,muted(),false);
        sub.setPadding(0,UiKit.dp(this,2),0,UiKit.dp(this,8)); p.addView(sub);

        HorizontalScrollView hsv=new HorizontalScrollView(this); hsv.setHorizontalScrollBarEnabled(false);
        LinearLayout chips=new LinearLayout(this); chips.setOrientation(LinearLayout.HORIZONTAL); hsv.addView(chips);
        String[] stages={"Pending","In Production","Ready","HOLD","Dispatched"};
        for(String s:stages){
            int count="HOLD".equals(s)?state.countHold():state.countStatus(s);
            TextView chip=smallAction(("HOLD".equals(s)?"Hold":s)+"  "+count,
                    s.equals(productionStage)?UiKit.BLUE:surface(),
                    s.equals(productionStage)?Color.WHITE:text());
            LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,UiKit.dp(this,40)); cp.setMargins(0,0,UiKit.dp(this,7),0);
            chips.addView(chip,cp);
            chip.setOnClickListener(v->{ productionStage=s; renderProduction(); });
        }
        p.addView(hsv,new LinearLayout.LayoutParams(-1,UiKit.dp(this,48)));

        List<JSONObject> rows=new ArrayList<>();
        for(JSONObject o:state.orders){
            if("HOLD".equals(productionStage)){
                if(o.optBoolean("hold",false)) rows.add(o);
            }else if(!o.optBoolean("hold",false)&&productionStage.equalsIgnoreCase(o.optString("status"))) rows.add(o);
        }
        TextView sum=UiKit.text(this,rows.size()+" orders • "+sumPairs(rows)+" pairs",12,muted(),true);
        sum.setPadding(0,UiKit.dp(this,4),0,UiKit.dp(this,4)); p.addView(sum);

        ListView list=new ListView(this); list.setDivider(null); list.setDividerHeight(0);
        OrderListAdapter a=new OrderListAdapter(this,dark,this::showOrderDetail); a.setItems(rows); list.setAdapter(a);
        p.addView(list,new LinearLayout.LayoutParams(-1,0,1f));
    }

    private void renderStock(){
        LinearLayout p=page(); content.addView(p,new FrameLayout.LayoutParams(-1,-1));
        p.addView(UiKit.text(this,"Stock Control",21,text(),true));
        p.addView(UiKit.text(this,"Central Sole / Box inventory overview",11,muted(),false));

        LinearLayout tabs=new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL); tabs.setPadding(0,UiKit.dp(this,10),0,UiKit.dp(this,8));
        TextView sole=smallAction("Sole Stock",soleMode?UiKit.BLUE:surface(),soleMode?Color.WHITE:text());
        TextView box=smallAction("Box Stock",!soleMode?UiKit.BLUE:surface(),!soleMode?Color.WHITE:text());
        sole.setOnClickListener(v->{soleMode=true;renderStock();}); box.setOnClickListener(v->{soleMode=false;renderStock();});
        tabs.addView(sole,new LinearLayout.LayoutParams(0,UiKit.dp(this,42),1f));
        LinearLayout.LayoutParams blp=new LinearLayout.LayoutParams(0,UiKit.dp(this,42),1f); blp.setMargins(UiKit.dp(this,8),0,0,0); tabs.addView(box,blp);
        p.addView(tabs);

        ScrollView scroll=new ScrollView(this); LinearLayout list=new LinearLayout(this); list.setOrientation(LinearLayout.VERTICAL); scroll.addView(list);
        p.addView(scroll,new LinearLayout.LayoutParams(-1,0,1f));

        if(soleMode){
            List<String> names=state.soleNames();
            for(String name:names) list.addView(soleCard(name));
            if(names.isEmpty()) list.addView(emptyText("No sole stock data"));
        }else{
            for(JSONObject b:state.boxes) list.addView(boxCard(b));
            if(state.boxes.isEmpty()) list.addView(emptyText("No box stock data"));
        }
    }

    private View soleCard(String name){
        JSONObject x=state.soles.optJSONObject(name); if(x==null) x=new JSONObject();
        int opening=sumMap(x.optJSONObject("opening"));
        int received=sumMap(x.optJSONObject("purchase"));
        int incoming=sumMap(x.optJSONObject("purchaseOrdered"));
        LinearLayout c=UiKit.card(this); applyCardTheme(c);
        TextView n=UiKit.text(this,name,15,text(),true); c.addView(n);
        LinearLayout stats=new LinearLayout(this); stats.setOrientation(LinearLayout.HORIZONTAL); stats.setPadding(0,UiKit.dp(this,8),0,0);
        stats.addView(stat("Opening",opening),new LinearLayout.LayoutParams(0,-2,1f));
        stats.addView(stat("Received",received),new LinearLayout.LayoutParams(0,-2,1f));
        stats.addView(stat("Incoming",incoming),new LinearLayout.LayoutParams(0,-2,1f)); c.addView(stats);
        c.setOnClickListener(v->goScreen(1,name,"All"));
        c.setLayoutParams(cardLp()); return c;
    }

    private View boxCard(JSONObject b){
        String name=b.optString("name","—");
        int stock=b.optInt("stock",0), incoming=b.optInt("ordered",0), demand=boxDemand(name);
        int after=stock+incoming-demand;
        LinearLayout c=UiKit.card(this); applyCardTheme(c);
        LinearLayout h=new LinearLayout(this); h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(UiKit.text(this,name,15,text(),true),new LinearLayout.LayoutParams(0,-2,1f));
        h.addView(UiKit.chip(this,after>=0?"OK":"SHORT "+Math.abs(after),after>=0?Color.rgb(220,252,231):Color.rgb(254,226,226),after>=0?UiKit.GREEN:UiKit.RED));
        c.addView(h);
        LinearLayout stats=new LinearLayout(this); stats.setOrientation(LinearLayout.HORIZONTAL); stats.setPadding(0,UiKit.dp(this,8),0,0);
        stats.addView(stat("Stock",stock),new LinearLayout.LayoutParams(0,-2,1f));
        stats.addView(stat("Incoming",incoming),new LinearLayout.LayoutParams(0,-2,1f));
        stats.addView(stat("Demand",demand),new LinearLayout.LayoutParams(0,-2,1f)); c.addView(stats);
        c.setOnClickListener(v->goScreen(1,name,"All"));
        c.setLayoutParams(cardLp()); return c;
    }

    private void renderMore(){
        ScrollView scroll=new ScrollView(this); LinearLayout p=page(); scroll.addView(p); content.addView(scroll,new FrameLayout.LayoutParams(-1,-1));
        p.addView(UiKit.text(this,"More",21,text(),true));
        p.addView(UiKit.text(this,"Server, backup, theme and purchasing",11,muted(),false));

        p.addView(sectionLabel("System"));
        p.addView(settingCard("Server","Connected to "+serverLabel(),"Change",v->serverDialog()));
        p.addView(settingCard("Appearance",dark?"Dark Mode":"Light Mode",dark?"Light":"Dark",v->toggleTheme()));
        p.addView(settingCard("Central Sync","State version "+state.version,"Refresh",v->loadData(true)));
        p.addView(settingCard("Manual Backup","Create server-side backup now","Backup",v->manualBackup()));

        p.addView(sectionLabel("Purchase / PO"));
        int shown=0;
        for(JSONObject po:state.poLog){
            int pending=po.optInt("totalQty",0)-po.optInt("receivedQty",0);
            if(pending<=0) continue;
            LinearLayout c=UiKit.card(this); applyCardTheme(c);
            c.addView(UiKit.text(this,po.optString("sole",po.optString("item","Purchase Order")),14,text(),true));
            String meta=po.optString("party",po.optString("vendor",""))+"  •  Pending "+pending+" / "+po.optInt("totalQty",0);
            c.addView(UiKit.text(this,meta,11,muted(),false)); c.setLayoutParams(cardLp()); p.addView(c);
            if(++shown>=8) break;
        }
        if(shown==0) p.addView(emptyText("No pending PO"));

        p.addView(sectionLabel("About"));
        LinearLayout about=UiKit.card(this); applyCardTheme(about);
        about.addView(UiKit.text(this,"KIRAN OMS Android V2",15,text(),true));
        about.addView(UiKit.text(this,"Native Android UI • No WebView • LAN central data",11,muted(),false));
        about.addView(UiKit.text(this,"Package: in.kiran.oms",10,muted(),false)); about.setLayoutParams(cardLp()); p.addView(about);
    }

    private View settingCard(String title,String sub,String action,View.OnClickListener click){
        LinearLayout c=UiKit.card(this); applyCardTheme(c); c.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout row=new LinearLayout(this); row.setGravity(Gravity.CENTER_VERTICAL);
        LinearLayout tx=new LinearLayout(this); tx.setOrientation(LinearLayout.VERTICAL);
        tx.addView(UiKit.text(this,title,14,text(),true)); tx.addView(UiKit.text(this,sub,11,muted(),false));
        row.addView(tx,new LinearLayout.LayoutParams(0,-2,1f));
        TextView b=smallAction(action,UiKit.BLUE,Color.WHITE); b.setOnClickListener(click);
        row.addView(b,new LinearLayout.LayoutParams(UiKit.dp(this,82),UiKit.dp(this,38))); c.addView(row); c.setLayoutParams(cardLp()); return c;
    }

    private void manualBackup(){
        showLoading(true);
        executor.execute(()->{
            try{
                JSONObject r=KiranApi.backup(server);
                runOnUiThread(()->{ showLoading(false); Toast.makeText(this,"Backup created: "+r.optString("backup","OK"),Toast.LENGTH_LONG).show(); });
            }catch(Exception e){ runOnUiThread(()->{showLoading(false);Toast.makeText(this,"Backup failed: "+safeMessage(e),Toast.LENGTH_LONG).show();}); }
        });
    }

    private void showOrderDetail(JSONObject o){
        ScrollView scroll=new ScrollView(this); LinearLayout p=new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); p.setPadding(UiKit.dp(this,18),UiKit.dp(this,8),UiKit.dp(this,18),UiKit.dp(this,8)); scroll.addView(p);
        p.addView(UiKit.text(this,"Order #"+o.optInt("orderNo",0),20,text(),true));
        String current=o.optBoolean("hold",false)?"HOLD":o.optString("status","Pending");
        TextView st=UiKit.chip(this,current,o.optBoolean("hold",false)?Color.rgb(254,226,226):Color.rgb(239,246,255),o.optBoolean("hold",false)?UiKit.RED:UiKit.statusColor(current));
        LinearLayout.LayoutParams stlp=new LinearLayout.LayoutParams(-2,-2); stlp.setMargins(0,UiKit.dp(this,6),0,UiKit.dp(this,10)); p.addView(st,stlp);

        detail(p,"Party",o.optString("party","—"));
        detail(p,"Article",o.optString("article","—"));
        detail(p,"Factory",o.optString("factory","—"));
        detail(p,"Colour",o.optString("colour","—"));
        detail(p,"D/M/L",o.optString("dml","—"));
        detail(p,"Material",o.optString("material","—"));
        detail(p,"Box",o.optString("box","—"));
        detail(p,"Sole",o.optString("sole","—"));
        detail(p,"Total Pairs",String.valueOf(o.optInt("totalPairs",0)));
        detail(p,"Rate",String.valueOf(o.optDouble("rate",0)));
        detail(p,"Order Date",o.optString("orderDate","—"));
        detail(p,"Production",o.optString("prodDate","—"));
        detail(p,"Ready",o.optString("readyDate","—"));
        detail(p,"Dispatch",o.optString("dispatchDate","—"));
        if(o.has("lastBatch")) detail(p,"Batch",String.valueOf(o.opt("lastBatch")));

        JSONObject sizes=o.optJSONObject("sizes");
        if(sizes!=null){
            p.addView(sectionLabel("Size Wise"));
            LinearLayout sz=new LinearLayout(this); sz.setOrientation(LinearLayout.HORIZONTAL);
            for(int s=4;s<=14;s++){
                int q=sizes.optInt(String.valueOf(s),0); if(q<=0) continue;
                TextView chip=UiKit.chip(this,s+" : "+q,dark?Color.rgb(51,65,85):Color.rgb(241,245,249),text());
                LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2); cp.setMargins(0,0,UiKit.dp(this,5),UiKit.dp(this,5)); sz.addView(chip,cp);
            }
            HorizontalScrollView sh=new HorizontalScrollView(this); sh.addView(sz); p.addView(sh);
        }

        String next=nextStatus(o);
        AlertDialog d=new AlertDialog.Builder(this)
                .setView(scroll)
                .setPositiveButton(next==null?"Close":"Next: "+next,null)
                .setNeutralButton(o.optBoolean("hold",false)?"Resume":"Hold",null)
                .setNegativeButton(next==null?null:"Close",null).create();
        d.setOnShowListener(x->{
            if(next!=null) d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{ d.dismiss(); requestStatus(o,next); });
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{ d.dismiss(); toggleHold(o); });
        });
        d.show();
    }

    private String nextStatus(JSONObject o){
        if(o.optBoolean("hold",false)) return null;
        String s=o.optString("status","Pending");
        if("Pending".equals(s)) return "In Production";
        if("In Production".equals(s)) return "Ready";
        if("Ready".equals(s)) return "Dispatched";
        return null;
    }

    private void requestStatus(JSONObject o,String target){
        if("In Production".equals(target)){
            EditText batch=field("Batch No.");
            batch.setInputType(InputType.TYPE_CLASS_TEXT);
            LinearLayout wrap=new LinearLayout(this); wrap.setPadding(UiKit.dp(this,20),0,UiKit.dp(this,20),0); wrap.addView(batch,new LinearLayout.LayoutParams(-1,UiKit.dp(this,48)));
            AlertDialog d=new AlertDialog.Builder(this).setTitle("Move to Production").setMessage("Batch No. enter karein.").setView(wrap).setPositiveButton("Continue",null).setNegativeButton("Cancel",null).create();
            d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                String b=batch.getText().toString().trim();
                if(b.isEmpty()){batch.setError("Batch required");return;}
                d.dismiss(); mutateOrder(o,target,b,false);
            })); d.show();
        }else{
            new AlertDialog.Builder(this).setTitle("Confirm Status").setMessage("Order #"+o.optInt("orderNo")+" → "+target+"\nDate: "+todayDmy()).setPositiveButton("Confirm",(d,w)->mutateOrder(o,target,"",false)).setNegativeButton("Cancel",null).show();
        }
    }

    private void toggleHold(JSONObject o){
        boolean newHold=!o.optBoolean("hold",false);
        new AlertDialog.Builder(this).setTitle(newHold?"Put On Hold":"Resume Order").setMessage("Order #"+o.optInt("orderNo")+" "+(newHold?"hold par jayega.":"resume hoga.")).setPositiveButton("Confirm",(d,w)->mutateOrder(o,o.optString("status","Pending"),"",true)).setNegativeButton("Cancel",null).show();
    }

    private void mutateOrder(JSONObject o,String target,String batch,boolean toggleHold){
        String oldStatus=o.optString("status","Pending");
        boolean oldHold=o.optBoolean("hold",false);
        try{
            if(toggleHold){
                o.put("hold",!oldHold);
                if(!oldHold) o.put("holdDate",todayDmy()); else o.remove("holdDate");
            }else{
                o.put("status",target);
                if("In Production".equals(target)){ o.put("prodDate",todayDmy()); o.put("lastBatch",batch); }
                if("Ready".equals(target)) o.put("readyDate",todayDmy());
                if("Dispatched".equals(target)) o.put("dispatchDate",todayDmy());
                o.put("statusChangedAt",nowIso());
            }
            o.put("updatedAt",nowIso());
            o.put("lastEdited",nowIso());
        }catch(Exception ignored){}

        JSONObject audit=auditEntry(toggleHold?(oldHold?"Resume":"Hold"):"Status","Order",String.valueOf(o.optInt("orderNo")),toggleHold?"Native Android hold/resume":"Native Android status change",oldStatus,toggleHold?(oldHold?"Active":"On Hold"):target);
        saveOrdersAndAudit(audit,"Order updated");
    }

    private void saveOrdersAndAudit(JSONObject audit,String success){
        JSONArray audits=new JSONArray(); audits.put(audit); for(JSONObject a:state.auditLog) audits.put(a);
        JSONObject vals=new JSONObject();
        try{ vals.put("st_orders",state.ordersJson().toString()); vals.put("st_audit_log",audits.toString()); }catch(Exception ignored){}
        showLoading(true);
        executor.execute(()->{
            try{
                KiranApi.saveValues(server,vals,clientId);
                runOnUiThread(()->{Toast.makeText(this,success,Toast.LENGTH_SHORT).show();loadData(false);});
            }catch(Exception e){runOnUiThread(()->{showLoading(false);Toast.makeText(this,"Save failed: "+safeMessage(e),Toast.LENGTH_LONG).show();loadData(false);});}
        });
    }

    private JSONObject auditEntry(String action,String module,String record,String details,String oldV,String newV){
        JSONObject a=new JSONObject();
        try{
            long ts=System.currentTimeMillis();
            a.put("id",ts+"_android_"+UUID.randomUUID().toString().substring(0,6));
            a.put("ts",ts); a.put("date",todayIso()); a.put("time",new SimpleDateFormat("hh:mm:ss a",Locale.US).format(new Date()));
            a.put("by","android"); a.put("action",action); a.put("module",module); a.put("record",record); a.put("details",details); a.put("oldValue",oldV); a.put("newValue",newV);
        }catch(Exception ignored){}
        return a;
    }

    private void showNewOrder(){
        ScrollView scroll=new ScrollView(this); LinearLayout p=new LinearLayout(this); p.setOrientation(LinearLayout.VERTICAL); p.setPadding(UiKit.dp(this,18),UiKit.dp(this,6),UiKit.dp(this,18),UiKit.dp(this,10)); scroll.addView(p);

        Spinner factory=masterSpinner(state.masterNames("factory"),"Factory");
        Spinner party=masterSpinner(state.masterNames("party"),"Party");
        EditText article=field("Article");
        Spinner colour=masterSpinner(state.masterNames("colour"),"Colour");
        Spinner dml=masterSpinner(state.masterNames("dml"),"D/M/L");
        EditText material=field("Material");
        Spinner box=masterSpinner(state.masterNames("box"),"Box");
        Spinner sole=masterSpinner(state.soleNames(),"Sole");
        EditText rate=field("Rate"); rate.setInputType(InputType.TYPE_CLASS_NUMBER|InputType.TYPE_NUMBER_FLAG_DECIMAL);

        p.addView(label("Factory"));p.addView(factory);
        p.addView(label("Party"));p.addView(party);
        p.addView(label("Article"));p.addView(article,new LinearLayout.LayoutParams(-1,UiKit.dp(this,48)));
        p.addView(label("Colour"));p.addView(colour);
        p.addView(label("D/M/L"));p.addView(dml);
        p.addView(label("Material"));p.addView(material,new LinearLayout.LayoutParams(-1,UiKit.dp(this,48)));
        p.addView(label("Box"));p.addView(box);
        p.addView(label("Sole"));p.addView(sole);
        p.addView(label("Rate"));p.addView(rate,new LinearLayout.LayoutParams(-1,UiKit.dp(this,48)));
        p.addView(sectionLabel("Size Wise Pairs"));

        final EditText[] sizes=new EditText[11];
        for(int row=0;row<3;row++){
            LinearLayout r=new LinearLayout(this); r.setOrientation(LinearLayout.HORIZONTAL);
            for(int col=0;col<4;col++){
                int idx=row*4+col; if(idx>=11) break;
                int sz=4+idx;
                LinearLayout cell=new LinearLayout(this); cell.setOrientation(LinearLayout.VERTICAL); cell.setPadding(UiKit.dp(this,2),0,UiKit.dp(this,2),0);
                TextView sl=UiKit.text(this,String.valueOf(sz),10,muted(),true); sl.setGravity(Gravity.CENTER); cell.addView(sl);
                EditText q=field("0"); q.setGravity(Gravity.CENTER); q.setInputType(InputType.TYPE_CLASS_NUMBER); sizes[idx]=q; cell.addView(q,new LinearLayout.LayoutParams(-1,UiKit.dp(this,44)));
                r.addView(cell,new LinearLayout.LayoutParams(0,-2,1f));
            }
            p.addView(r);
        }

        AlertDialog d=new AlertDialog.Builder(this).setTitle("New Order").setView(scroll).setPositiveButton("Save",null).setNegativeButton("Cancel",null).create();
        d.setOnShowListener(x->d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
            String pa=selected(party), ar=article.getText().toString().trim();
            if(pa.isEmpty()||pa.startsWith("Select ")){Toast.makeText(this,"Party select karein",Toast.LENGTH_SHORT).show();return;}
            if(ar.isEmpty()){article.setError("Article required");return;}
            JSONObject sm=new JSONObject(); int total=0;
            try{
                for(int i=0;i<11;i++){int q=parseInt(sizes[i].getText().toString()); if(q>0){sm.put(String.valueOf(4+i),q);total+=q;}}
            }catch(Exception ignored){}
            if(total<=0){Toast.makeText(this,"Size wise quantity enter karein",Toast.LENGTH_SHORT).show();return;}

            JSONObject o=new JSONObject();
            try{
                o.put("orderNo",state.nextOrderNo()); o.put("orderDate",todayDmy()); o.put("factory",cleanSelected(factory)); o.put("partyCode",""); o.put("party",pa.toUpperCase(Locale.ROOT)); o.put("article",ar.replaceAll("\\.0$","")); o.put("colour",cleanSelected(colour)); o.put("dml",cleanSelected(dml)); o.put("material",material.getText().toString().trim().toUpperCase(Locale.ROOT)); o.put("box",cleanSelected(box)); o.put("sole",cleanSelected(sole)); o.put("particular",""); o.put("sizes",sm); o.put("totalPairs",total); o.put("rate",parseDouble(rate.getText().toString())); o.put("dispatchDate",""); o.put("readyDate",""); o.put("prodDate",""); o.put("status","Pending"); o.put("printed",""); o.put("isNew",true); o.put("createdAt",nowIso()); o.put("updatedAt",nowIso()); o.put("statusChangedAt",nowIso());
            }catch(Exception ignored){}
            if(hasDuplicate(o)){
                new AlertDialog.Builder(this).setTitle("Possible Duplicate").setMessage("Same Party + Article + Colour + Factory ka order already hai. Fir bhi save karein?").setPositiveButton("Save Anyway",(dd,ww)->{d.dismiss();commitNewOrder(o);}).setNegativeButton("Cancel",null).show();
            }else{d.dismiss();commitNewOrder(o);}
        }));
        d.show();
    }

    private void commitNewOrder(JSONObject o){
        state.orders.add(0,o);
        JSONObject audit=auditEntry("Create","Order",String.valueOf(o.optInt("orderNo")),"Native Android order created","",o.optString("status"));
        saveOrdersAndAudit(audit,"Order #"+o.optInt("orderNo")+" saved");
    }

    private boolean hasDuplicate(JSONObject n){
        for(JSONObject o:state.orders){
            if(o.optString("party").equalsIgnoreCase(n.optString("party"))&&o.optString("article").equalsIgnoreCase(n.optString("article"))&&o.optString("colour").equalsIgnoreCase(n.optString("colour"))&&o.optString("factory").equalsIgnoreCase(n.optString("factory"))) return true;
        }
        return false;
    }

    private Spinner masterSpinner(List<String> values,String hint){
        List<String> all=new ArrayList<>(); all.add("Select "+hint); all.addAll(values);
        Spinner s=new Spinner(this); s.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,all)); s.setBackground(UiKit.rounded(surface(),8,border()));
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,UiKit.dp(this,48)); lp.setMargins(0,0,0,UiKit.dp(this,6)); s.setLayoutParams(lp); return s;
    }

    private String selected(Spinner s){Object v=s.getSelectedItem();return v==null?"":String.valueOf(v);}
    private String cleanSelected(Spinner s){String v=selected(s);return v.startsWith("Select ")?"":v.toUpperCase(Locale.ROOT);}

    private void goScreen(int screen,String query,String status){
        currentScreen=screen; orderQuery=query==null?"":query; orderStatus=status==null?"All":status; updateNav(); renderCurrent();
    }
    private void goProduction(String stage){currentScreen=2;productionStage=stage;updateNav();renderCurrent();}

    private TextView sectionLabel(String s){
        TextView t=UiKit.text(this,s.toUpperCase(Locale.ROOT),11,muted(),true);
        t.setPadding(0,UiKit.dp(this,18),0,UiKit.dp(this,8)); return t;
    }

    private LinearLayout compactOrder(JSONObject o){
        LinearLayout c=UiKit.card(this);applyCardTheme(c);
        LinearLayout h=new LinearLayout(this);h.setGravity(Gravity.CENTER_VERTICAL);
        h.addView(UiKit.text(this,"#"+o.optInt("orderNo")+"  "+o.optString("party","—"),13,text(),true),new LinearLayout.LayoutParams(0,-2,1f));
        String st=o.optBoolean("hold",false)?"HOLD":o.optString("status","Pending");
        h.addView(UiKit.chip(this,st,dark?Color.rgb(51,65,85):Color.rgb(241,245,249),o.optBoolean("hold",false)?UiKit.RED:UiKit.statusColor(st)));c.addView(h);
        c.addView(UiKit.text(this,o.optString("article","—")+" • "+o.optString("factory","—")+" • "+o.optInt("totalPairs",0)+" pairs",11,muted(),false));
        c.setOnClickListener(v->showOrderDetail(o)); c.setLayoutParams(cardLp()); return c;
    }

    private TextView rowText(String left,String right,int color){
        TextView t=UiKit.text(this,left+"                                      "+right,13,text(),false);
        t.setPadding(0,UiKit.dp(this,8),0,UiKit.dp(this,8)); return t;
    }

    private LinearLayout stat(String label,int value){
        LinearLayout x=new LinearLayout(this);x.setOrientation(LinearLayout.VERTICAL);
        TextView v=UiKit.text(this,String.valueOf(value),18,text(),true);v.setGravity(Gravity.CENTER);x.addView(v);
        TextView l=UiKit.text(this,label,9,muted(),false);l.setGravity(Gravity.CENTER);x.addView(l);return x;
    }

    private TextView smallAction(String label,int background,int foreground){
        TextView b=UiKit.text(this,label,12,foreground,true);b.setGravity(Gravity.CENTER);b.setPadding(UiKit.dp(this,10),0,UiKit.dp(this,10),0);b.setBackground(UiKit.rounded(background,8,background==surface()?border():Color.TRANSPARENT));return b;
    }

    private TextView actionButton(String label,int background,View.OnClickListener click){
        int fg=(background==UiKit.BLUE)?Color.WHITE:text();
        TextView b=smallAction(label,background,fg);b.setOnClickListener(click);return b;
    }

    private EditText field(String hint){
        EditText e=new EditText(this);e.setHint(hint);e.setTextColor(text());e.setHintTextColor(muted());e.setTextSize(13);e.setSingleLine(true);e.setPadding(UiKit.dp(this,12),0,UiKit.dp(this,12),0);e.setBackground(UiKit.rounded(surface(),8,border()));return e;
    }

    private TextView label(String s){TextView t=UiKit.text(this,s,10,muted(),true);t.setPadding(0,UiKit.dp(this,8),0,UiKit.dp(this,4));return t;}
    private TextView emptyText(String s){TextView t=UiKit.text(this,s,13,muted(),false);t.setGravity(Gravity.CENTER);t.setPadding(0,UiKit.dp(this,24),0,UiKit.dp(this,24));return t;}

    private LinearLayout.LayoutParams cardLp(){LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,-2);lp.setMargins(0,0,0,UiKit.dp(this,8));return lp;}
    private void applyCardTheme(LinearLayout c){c.setBackground(UiKit.rounded(surface(),6,border()));}

    private void detail(LinearLayout p,String k,String v){
        LinearLayout r=new LinearLayout(this);r.setGravity(Gravity.CENTER_VERTICAL);r.setPadding(0,UiKit.dp(this,6),0,UiKit.dp(this,6));
        r.addView(UiKit.text(this,k,12,muted(),false),new LinearLayout.LayoutParams(0,-2,.42f));
        TextView val=UiKit.text(this,v==null||v.isEmpty()?"—":v,12,text(),true);val.setGravity(Gravity.END);r.addView(val,new LinearLayout.LayoutParams(0,-2,.58f));p.addView(r);
    }

    private int holdPairs(){int n=0;for(JSONObject o:state.orders)if(o.optBoolean("hold",false))n+=o.optInt("totalPairs",0);return n;}
    private int sumPairs(List<JSONObject> rows){int n=0;for(JSONObject o:rows)n+=o.optInt("totalPairs",0);return n;}

    private int boxDemand(String name){
        int n=0;
        for(JSONObject o:state.orders){
            if(o.optBoolean("hold",false))continue;
            String st=o.optString("status","");
            if("Dispatched".equals(st)||"Cancelled".equals(st))continue;
            if(name.equalsIgnoreCase(o.optString("box","")))n+=o.optInt("totalPairs",0);
        }
        return n;
    }

    private int sumMap(JSONObject x){
        if(x==null)return 0;int n=0;Iterator<String> it=x.keys();while(it.hasNext()){String k=it.next();n+=x.optInt(k,0);}return n;
    }

    private int openPoCount(){int n=0;for(JSONObject p:state.poLog)if(p.optInt("totalQty",0)-p.optInt("receivedQty",0)>0)n++;return n;}
    private int openPoPairs(){int n=0;for(JSONObject p:state.poLog)n+=Math.max(0,p.optInt("totalQty",0)-p.optInt("receivedQty",0));return n;}

    private int pendingAgeingCount(int days){
        int n=0; long now=System.currentTimeMillis();
        for(JSONObject o:state.orders){
            if(!"Pending".equals(o.optString("status"))||o.optBoolean("hold",false))continue;
            try{Date d=new SimpleDateFormat("dd-MM-yyyy",Locale.US).parse(o.optString("orderDate",""));if(d!=null&&(now-d.getTime())>=days*86400000L)n++;}catch(Exception ignored){}
        }
        return n;
    }

    private int parseInt(String s){try{return Integer.parseInt(s.trim());}catch(Exception e){return 0;}}
    private double parseDouble(String s){try{return Double.parseDouble(s.trim());}catch(Exception e){return 0;}}

    private String todayDmy(){return new SimpleDateFormat("dd-MM-yyyy",Locale.US).format(new Date());}
    private String todayIso(){return new SimpleDateFormat("yyyy-MM-dd",Locale.US).format(new Date());}
    private String nowIso(){return new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX",Locale.US).format(new Date());}

    @Override public void onBackPressed(){
        if(currentScreen!=0){currentScreen=0;updateNav();renderCurrent();}
        else moveTaskToBack(true);
    }

    @Override protected void onDestroy(){executor.shutdownNow();super.onDestroy();}
}
