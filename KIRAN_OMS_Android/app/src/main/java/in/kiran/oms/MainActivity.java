package in.kiran.oms;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.webkit.CookieManager;
import android.webkit.ValueCallback;
import android.webkit.WebChromeClient;
import android.webkit.WebResourceError;
import android.webkit.WebResourceRequest;
import android.webkit.WebSettings;
import android.webkit.WebView;
import android.webkit.WebViewClient;
import android.widget.EditText;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final String PREFS="kiran_oms";
    private static final String PREF_SERVER="server_url";
    private static final int FILE_PICKER=701;
    private static final int DISCOVERY_PORT=8789;
    private static final String DISCOVERY_REQUEST="KIRAN_OMS_DISCOVER_V1";
    private static final String DISCOVERY_PREFIX="KIRAN_OMS_HERE|";

    private final ExecutorService executor=Executors.newSingleThreadExecutor();
    private WebView web;
    private TextView status;
    private ProgressBar progress;
    private SharedPreferences prefs;
    private ValueCallback<Uri[]> chooser;

    private int dp(int v){ return Math.round(v*getResources().getDisplayMetrics().density); }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        prefs=getSharedPreferences(PREFS,MODE_PRIVATE);
        buildUi();
        setupWebView();
        findServer(true);
    }

    private TextView action(String text){
        TextView v=new TextView(this);
        v.setText(text); v.setTextColor(Color.WHITE); v.setTextSize(20);
        v.setGravity(Gravity.CENTER); v.setPadding(dp(10),0,dp(10),0);
        return v;
    }

    private void buildUi(){
        LinearLayout root=new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setBackgroundColor(Color.rgb(248,250,252));

        LinearLayout bar=new LinearLayout(this);
        bar.setOrientation(LinearLayout.HORIZONTAL);
        bar.setGravity(Gravity.CENTER_VERTICAL);
        bar.setPadding(dp(14),0,dp(4),0);
        bar.setBackgroundColor(Color.rgb(15,23,42));
        root.addView(bar,new LinearLayout.LayoutParams(-1,dp(54)));

        LinearLayout titleWrap=new LinearLayout(this);
        titleWrap.setOrientation(LinearLayout.VERTICAL);
        titleWrap.setGravity(Gravity.CENTER_VERTICAL);
        bar.addView(titleWrap,new LinearLayout.LayoutParams(0,-1,1));

        TextView title=new TextView(this);
        title.setText("KIRAN OMS"); title.setTextColor(Color.WHITE); title.setTextSize(16);
        title.setTypeface(android.graphics.Typeface.DEFAULT_BOLD);
        titleWrap.addView(title);

        status=new TextView(this);
        status.setText("Finding server…"); status.setTextColor(Color.rgb(191,219,254));
        status.setTextSize(10); status.setSingleLine(true);
        titleWrap.addView(status);

        TextView refresh=action("↻");
        refresh.setOnClickListener(v->{ if(web.getUrl()!=null) web.reload(); else findServer(false); });
        bar.addView(refresh,new LinearLayout.LayoutParams(dp(48),-1));

        TextView settings=action("⚙");
        settings.setOnClickListener(v->serverDialog());
        bar.addView(settings,new LinearLayout.LayoutParams(dp(48),-1));

        FrameLayout content=new FrameLayout(this);
        root.addView(content,new LinearLayout.LayoutParams(-1,0,1));

        web=new WebView(this);
        content.addView(web,new FrameLayout.LayoutParams(-1,-1));

        progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal);
        progress.setMax(100); progress.setVisibility(ProgressBar.GONE);
        FrameLayout.LayoutParams plp=new FrameLayout.LayoutParams(-1,dp(3));
        plp.gravity=Gravity.TOP; content.addView(progress,plp);

        setContentView(root);
    }

    private void setupWebView(){
        WebSettings s=web.getSettings();
        s.setJavaScriptEnabled(true);
        s.setDomStorageEnabled(true);
        s.setDatabaseEnabled(true);
        s.setUseWideViewPort(true);
        s.setLoadWithOverviewMode(false);
        s.setCacheMode(WebSettings.LOAD_DEFAULT);
        s.setAllowFileAccess(false);
        s.setAllowContentAccess(true);
        s.setUserAgentString(s.getUserAgentString()+" KIRAN-OMS-Android/1.0");

        CookieManager.getInstance().setAcceptCookie(true);

        web.setWebChromeClient(new WebChromeClient(){
            @Override public void onProgressChanged(WebView view,int p){
                progress.setProgress(p);
                progress.setVisibility(p>=100?ProgressBar.GONE:ProgressBar.VISIBLE);
            }
            @Override public boolean onShowFileChooser(WebView view,ValueCallback<Uri[]> callback,FileChooserParams params){
                if(chooser!=null) chooser.onReceiveValue(null);
                chooser=callback;
                try{
                    startActivityForResult(params.createIntent(),FILE_PICKER);
                    return true;
                }catch(Exception e){
                    chooser=null;
                    Toast.makeText(MainActivity.this,"File picker unavailable",Toast.LENGTH_SHORT).show();
                    return false;
                }
            }
        });

        web.setWebViewClient(new WebViewClient(){
            @Override public void onPageFinished(WebView view,String url){
                status.setText("● Connected • "+host(url));
                status.setTextColor(Color.rgb(134,239,172));
            }
            @Override public void onReceivedError(WebView view,WebResourceRequest req,WebResourceError err){
                if(req.isForMainFrame()){
                    status.setText("● Offline • tap ↻ or ⚙");
                    status.setTextColor(Color.rgb(253,186,116));
                }
            }
            @Override public boolean shouldOverrideUrlLoading(WebView view,WebResourceRequest req){
                String scheme=req.getUrl().getScheme();
                if("http".equalsIgnoreCase(scheme)||"https".equalsIgnoreCase(scheme)) return false;
                try{ startActivity(new Intent(Intent.ACTION_VIEW,req.getUrl())); }catch(Exception ignored){}
                return true;
            }
        });
    }

    private String host(String url){
        try{
            Uri u=Uri.parse(url);
            return u.getHost()+(u.getPort()>0?":"+u.getPort():"");
        }catch(Exception e){ return "LAN"; }
    }

    private String normalize(String raw){
        String x=raw==null?"":raw.trim();
        if(x.isEmpty()) return "";
        if(!x.startsWith("http://")&&!x.startsWith("https://")) x="http://"+x;
        Uri u=Uri.parse(x);
        if(u.getPort()==-1 && "http".equalsIgnoreCase(u.getScheme()) && u.getHost()!=null)
            x="http://"+u.getHost()+":8787/";
        if(!x.endsWith("/")) x+="/";
        return x;
    }

    private void connect(String raw){
        String url=normalize(raw);
        if(url.isEmpty()){ serverDialog(); return; }
        prefs.edit().putString(PREF_SERVER,url).apply();
        status.setText("Connecting • "+host(url));
        status.setTextColor(Color.rgb(191,219,254));
        web.loadUrl(url);
    }

    private void findServer(boolean savedFallback){
        status.setText("Finding KIRAN server…");
        executor.execute(()->{
            String found=discover();
            String saved=prefs.getString(PREF_SERVER,"");
            runOnUiThread(()->{
                if(found!=null&&!found.isEmpty()) connect(found);
                else if(savedFallback&&!saved.isEmpty()) connect(saved);
                else{
                    status.setText("Server not found • set address");
                    status.setTextColor(Color.rgb(253,186,116));
                    serverDialog();
                }
            });
        });
    }

    private String discover(){
        try(DatagramSocket socket=new DatagramSocket()){
            socket.setBroadcast(true);
            socket.setSoTimeout(1800);
            byte[] out=DISCOVERY_REQUEST.getBytes(StandardCharsets.UTF_8);
            socket.send(new DatagramPacket(out,out.length,InetAddress.getByName("255.255.255.255"),DISCOVERY_PORT));
            byte[] buf=new byte[512];
            DatagramPacket reply=new DatagramPacket(buf,buf.length);
            socket.receive(reply);
            String msg=new String(reply.getData(),0,reply.getLength(),StandardCharsets.UTF_8).trim();
            if(msg.startsWith(DISCOVERY_PREFIX)) return normalize(msg.substring(DISCOVERY_PREFIX.length()));
        }catch(Exception ignored){}
        return null;
    }

    private void serverDialog(){
        EditText input=new EditText(this);
        input.setSingleLine(true);
        input.setHint("192.168.1.50:8787");
        input.setText(prefs.getString(PREF_SERVER,""));

        FrameLayout wrap=new FrameLayout(this);
        wrap.setPadding(dp(20),dp(6),dp(20),0);
        wrap.addView(input,new FrameLayout.LayoutParams(-1,-2));

        AlertDialog d=new AlertDialog.Builder(this)
            .setTitle("KIRAN OMS Server")
            .setMessage("Same Wi-Fi/LAN par Auto Find karein, ya Main PC ka address enter karein.")
            .setView(wrap)
            .setPositiveButton("Connect",null)
            .setNeutralButton("Auto Find",null)
            .setNegativeButton("Cancel",null)
            .create();

        d.setOnShowListener(x->{
            d.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener(v->{
                String u=normalize(input.getText().toString());
                if(u.isEmpty()){ input.setError("Server address required"); return; }
                d.dismiss(); connect(u);
            });
            d.getButton(AlertDialog.BUTTON_NEUTRAL).setOnClickListener(v->{ d.dismiss(); findServer(false); });
        });
        d.show();
    }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data){
        super.onActivityResult(requestCode,resultCode,data);
        if(requestCode==FILE_PICKER && chooser!=null){
            chooser.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode,data));
            chooser=null;
        }
    }

    @Override public void onBackPressed(){
        if(web!=null&&web.canGoBack()) web.goBack();
        else moveTaskToBack(true);
    }

    @Override protected void onDestroy(){
        executor.shutdownNow();
        if(web!=null){ web.stopLoading(); web.destroy(); }
        super.onDestroy();
    }
}
