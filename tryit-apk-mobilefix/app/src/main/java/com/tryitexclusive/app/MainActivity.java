package com.tryitexclusive.app;

import android.app.*;
import android.os.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.drawable.GradientDrawable;
import android.net.Uri;
import android.view.*;
import android.view.inputmethod.EditorInfo;
import android.webkit.*;
import android.widget.*;
import java.util.Locale;

public class MainActivity extends Activity {
    private static final String PREFS = "tryit_prefs";
    private static final String KEY_URL = "saved_url";
    private static final int FILE_CHOOSER = 7001;
    private static final long DOUBLE_BACK_EXIT_MS = 1800L;
    private WebView webView;
    private ValueCallback<Uri[]> fileCallback;
    private String homeUrl = "";
    private long lastDashboardBack = 0L;
    private boolean backCheckRunning = false;

    private int dp(int v){ return Math.round(v * getResources().getDisplayMetrics().density); }

    private void applyTopSystemInset(final View target){
        if(target==null) return;
        target.setOnApplyWindowInsetsListener((v,insets)->{
            int top=0;
            if(Build.VERSION.SDK_INT>=30){
                top=insets.getInsets(WindowInsets.Type.statusBars()).top;
            }else{
                top=insets.getSystemWindowInsetTop();
            }
            v.setPadding(v.getPaddingLeft(),top,v.getPaddingRight(),v.getPaddingBottom());
            return insets;
        });
        target.requestApplyInsets();
    }

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        getWindow().setStatusBarColor(Color.WHITE);
        if(Build.VERSION.SDK_INT >= 23) getWindow().getDecorView().setSystemUiVisibility(View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR);
        String saved = getSharedPreferences(PREFS,MODE_PRIVATE).getString(KEY_URL,"");
        if(saved != null && !saved.trim().isEmpty()) openWeb(saved.trim()); else showUrlSetup();
    }

    private TextView text(String s,int sp,int color,boolean bold){
        TextView t=new TextView(this); t.setText(s); t.setTextSize(sp); t.setTextColor(color);
        if(bold) t.setTypeface(android.graphics.Typeface.DEFAULT, android.graphics.Typeface.BOLD);
        return t;
    }

    private GradientDrawable bg(int color,float radius){
        GradientDrawable g=new GradientDrawable(); g.setColor(color); g.setCornerRadius(dp((int)radius)); return g;
    }

    private void showUrlSetup(){
        webView=null;
        ScrollView scroll=new ScrollView(this); scroll.setFillViewport(true); scroll.setBackgroundColor(Color.rgb(255,249,241));
        LinearLayout root=new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setGravity(Gravity.CENTER_HORIZONTAL); root.setPadding(dp(24),dp(42),dp(24),dp(28));
        scroll.addView(root,new ScrollView.LayoutParams(-1,-1));

        ImageView icon=new ImageView(this); icon.setImageResource(com.tryitexclusive.app.R.drawable.ic_tryit);
        LinearLayout.LayoutParams ip=new LinearLayout.LayoutParams(dp(110),dp(110)); ip.bottomMargin=dp(10); root.addView(icon,ip);
        TextView title=text("TRYIT",32,Color.rgb(11,24,48),true); title.setGravity(Gravity.CENTER); root.addView(title,new LinearLayout.LayoutParams(-1,-2));
        TextView sub=text("Exclusive • Web Application",14,Color.rgb(102,112,128),false); sub.setGravity(Gravity.CENTER); root.addView(sub,new LinearLayout.LayoutParams(-1,-2));

        LinearLayout card=new LinearLayout(this); card.setOrientation(LinearLayout.VERTICAL); card.setPadding(dp(18),dp(18),dp(18),dp(18)); card.setBackground(bg(Color.WHITE,18));
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-1,-2); cp.topMargin=dp(30); root.addView(card,cp);
        TextView h=text("Enter Application URL",20,Color.rgb(11,24,48),true); card.addView(h);
        TextView help=text("Enter your TRYIT web/server address. HTTP and HTTPS are supported.",13,Color.rgb(100,116,139),false); LinearLayout.LayoutParams hp=new LinearLayout.LayoutParams(-1,-2); hp.topMargin=dp(5); hp.bottomMargin=dp(14); card.addView(help,hp);

        EditText url=new EditText(this); url.setSingleLine(true); url.setTextSize(15); url.setHint("https://example.com or http://192.168.1.10:8765"); url.setInputType(android.text.InputType.TYPE_CLASS_TEXT|android.text.InputType.TYPE_TEXT_VARIATION_URI); url.setImeOptions(EditorInfo.IME_ACTION_GO); url.setPadding(dp(14),0,dp(14),0);
        GradientDrawable eb=bg(Color.rgb(248,250,252),12); eb.setStroke(dp(1),Color.rgb(203,213,225)); url.setBackground(eb); card.addView(url,new LinearLayout.LayoutParams(-1,dp(56)));

        CheckBox remember=new CheckBox(this); remember.setText("Remember this URL"); remember.setChecked(true); remember.setTextColor(Color.rgb(51,65,85)); LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2); rp.topMargin=dp(10); card.addView(remember,rp);

        Button open=new Button(this); open.setText("Open Application"); open.setTextColor(Color.WHITE); open.setTextSize(15); open.setAllCaps(false); open.setTypeface(android.graphics.Typeface.DEFAULT,android.graphics.Typeface.BOLD); open.setBackground(bg(Color.rgb(215,25,32),12)); LinearLayout.LayoutParams bp=new LinearLayout.LayoutParams(-1,dp(54)); bp.topMargin=dp(12); card.addView(open,bp);
        View.OnClickListener go=v->{
            String n=normalize(url.getText().toString());
            if(n==null){ url.setError("Please enter a valid web address"); url.requestFocus(); return; }
            if(remember.isChecked()) getSharedPreferences(PREFS,MODE_PRIVATE).edit().putString(KEY_URL,n).apply(); else getSharedPreferences(PREFS,MODE_PRIVATE).edit().remove(KEY_URL).apply();
            openWeb(n);
        };
        open.setOnClickListener(go); url.setOnEditorActionListener((v,id,e)->{ if(id==EditorInfo.IME_ACTION_GO){go.onClick(v);return true;} return false; });

        TextView note=text("You can change this URL anytime from the ⋮ menu inside the app.",12,Color.rgb(100,116,139),false); note.setGravity(Gravity.CENTER); LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(-1,-2); np.topMargin=dp(18); root.addView(note,np);
        setContentView(scroll);
        applyTopSystemInset(scroll);
    }

    private String normalize(String raw){
        if(raw==null) return null; String s=raw.trim(); if(s.isEmpty()) return null;
        if(!s.matches("^[a-zA-Z][a-zA-Z0-9+.-]*://.*$")) s="https://"+s;
        try{ Uri u=Uri.parse(s); String scheme=u.getScheme(); if(scheme==null || !(scheme.equalsIgnoreCase("http")||scheme.equalsIgnoreCase("https")) || u.getHost()==null) return null; return s; }catch(Exception e){return null;}
    }

    private void openWeb(String url){
        homeUrl=url;
        lastDashboardBack=0L;
        LinearLayout outer=new LinearLayout(this); outer.setOrientation(LinearLayout.VERTICAL); outer.setBackgroundColor(Color.WHITE);
        LinearLayout bar=new LinearLayout(this); bar.setGravity(Gravity.CENTER_VERTICAL); bar.setPadding(dp(12),0,dp(6),0); bar.setBackgroundColor(Color.WHITE);
        ImageView icon=new ImageView(this); icon.setImageResource(R.drawable.ic_tryit); bar.addView(icon,new LinearLayout.LayoutParams(dp(34),dp(34)));
        TextView name=text("TRYIT",17,Color.rgb(11,24,48),true); LinearLayout.LayoutParams np=new LinearLayout.LayoutParams(0,-2,1); np.leftMargin=dp(8); bar.addView(name,np);
        Button more=new Button(this); more.setText("⋮"); more.setTextSize(24); more.setTextColor(Color.rgb(11,24,48)); more.setBackgroundColor(Color.TRANSPARENT); bar.addView(more,new LinearLayout.LayoutParams(dp(52),dp(48)));
        outer.addView(bar,new LinearLayout.LayoutParams(-1,dp(50)));

        ProgressBar progress=new ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal); progress.setMax(100); progress.setProgressTintList(android.content.res.ColorStateList.valueOf(Color.rgb(215,25,32))); outer.addView(progress,new LinearLayout.LayoutParams(-1,dp(2)));
        webView=new WebView(this); outer.addView(webView,new LinearLayout.LayoutParams(-1,0,1)); setContentView(outer);
        applyTopSystemInset(outer);

        WebSettings s=webView.getSettings();
        s.setJavaScriptEnabled(true); s.setDomStorageEnabled(true); s.setDatabaseEnabled(true); s.setAllowContentAccess(true); s.setAllowFileAccess(true); s.setSupportZoom(true); s.setBuiltInZoomControls(true); s.setDisplayZoomControls(false); s.setUseWideViewPort(true); s.setLoadWithOverviewMode(true); s.setMediaPlaybackRequiresUserGesture(false); s.setJavaScriptCanOpenWindowsAutomatically(true);
        if(Build.VERSION.SDK_INT>=21) s.setMixedContentMode(WebSettings.MIXED_CONTENT_ALWAYS_ALLOW);
        CookieManager.getInstance().setAcceptCookie(true); if(Build.VERSION.SDK_INT>=21) CookieManager.getInstance().setAcceptThirdPartyCookies(webView,true);

        webView.setWebViewClient(new WebViewClient(){
            @Override public boolean shouldOverrideUrlLoading(WebView view, WebResourceRequest req){ return handleUri(req.getUrl()); }
            @Override public boolean shouldOverrideUrlLoading(WebView view,String u){ return handleUri(Uri.parse(u)); }
            @Override public void onReceivedError(WebView view,WebResourceRequest req,WebResourceError err){ super.onReceivedError(view,req,err); }
            @Override public void onPageFinished(WebView view,String url){ super.onPageFinished(view,url); injectMobileFix(); }
        });
        webView.setWebChromeClient(new WebChromeClient(){
            @Override public void onProgressChanged(WebView v,int p){ progress.setProgress(p); progress.setVisibility(p>=100?View.GONE:View.VISIBLE); }
            @Override public boolean onShowFileChooser(WebView w,ValueCallback<Uri[]> callback,FileChooserParams params){
                if(fileCallback!=null) fileCallback.onReceiveValue(null); fileCallback=callback;
                try{ startActivityForResult(params.createIntent(),FILE_CHOOSER); return true; }catch(Exception e){ fileCallback=null; Toast.makeText(MainActivity.this,"No file picker available",Toast.LENGTH_SHORT).show(); return false; }
            }
        });
        webView.setDownloadListener((u,ua,cd,mime,len)->{ try{ startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse(u))); }catch(Exception e){Toast.makeText(this,"Unable to open download",Toast.LENGTH_SHORT).show();} });
        more.setOnClickListener(v->showMenu(more));
        webView.loadUrl(url);
    }

    private void injectMobileFix(){
        if(webView==null) return;
        String css = "@media (max-width:760px){#app-header.p1013-header{position:relative!important;z-index:60000!important;overflow:visible!important}.p1013-right,.p1013-profile-wrap{overflow:visible!important;position:relative!important;z-index:60010!important}.p1013-profile-btn{position:relative!important;z-index:60020!important;min-width:76px!important;min-height:44px!important;touch-action:manipulation!important;pointer-events:auto!important}.p1013-profile-menu{position:fixed!important;z-index:999999!important;top:64px!important;right:8px!important;left:auto!important;width:min(260px,calc(100vw - 16px))!important;max-height:calc(100dvh - 76px)!important;overflow-y:auto!important;pointer-events:none!important;opacity:0!important;visibility:hidden!important;transform:translateY(-4px)!important}.p1013-profile-menu.open{display:block!important;pointer-events:auto!important;opacity:1!important;visibility:visible!important;transform:none!important}.p1013-profile-menu button{min-height:46px!important;padding:11px 12px!important;font-size:13px!important;touch-action:manipulation!important;pointer-events:auto!important}}";
        String js = "(function(){try{var s=document.getElementById('tryit-mobile-fix');if(!s){s=document.createElement('style');s.id='tryit-mobile-fix';s.textContent="+org.json.JSONObject.quote(css)+";document.head.appendChild(s);}var q=function(x,r){return (r||document).querySelector(x)},m=function(){return q('#p1013-profile-menu')},b=function(){return q('.p1013-profile-btn')},c=function(){var x=m();if(x)x.classList.remove('open')},t=function(e){if(e){e.preventDefault();e.stopPropagation();}var x=m();if(!x)return;x.classList.toggle('open');if(x.classList.contains('open')){x.style.right='8px';x.style.left='auto';}};window.p1013ToggleProfileMenu=t;window.p1013CloseProfileMenu=c;var z=b(),x=m();if(z&&x&&z.dataset.tryitApkBound!=='1'){z.dataset.tryitApkBound='1';z.removeAttribute('onclick');z.addEventListener('pointerup',t,{passive:false});z.addEventListener('click',function(e){e.preventDefault();e.stopPropagation();},{passive:false});x.addEventListener('pointerdown',function(e){e.stopPropagation();});x.addEventListener('click',function(e){e.stopPropagation();});document.addEventListener('pointerdown',function(e){if(!(e.target.closest&&e.target.closest('.p1013-profile-wrap'))&&!(e.target.closest&&e.target.closest('#p1013-profile-menu')))c();},true);}}catch(e){}})();";
        webView.evaluateJavascript(js,null);
    }

    private boolean handleUri(Uri u){
        String scheme=u.getScheme()==null?"":u.getScheme().toLowerCase(Locale.ROOT);
        if(scheme.equals("http")||scheme.equals("https")) return false;
        try{ startActivity(new Intent(Intent.ACTION_VIEW,u)); }catch(Exception e){ Toast.makeText(this,"Cannot open this link",Toast.LENGTH_SHORT).show(); }
        return true;
    }

    private void showMenu(View anchor){
        PopupMenu p=new PopupMenu(this,anchor); p.getMenu().add("Reload"); p.getMenu().add("Home"); p.getMenu().add("Change URL");
        p.setOnMenuItemClickListener(i->{ String t=i.getTitle().toString(); if(t.equals("Reload")&&webView!=null) webView.reload(); else if(t.equals("Home")&&webView!=null) webView.loadUrl(homeUrl); else if(t.equals("Change URL")){ getSharedPreferences(PREFS,MODE_PRIVATE).edit().remove(KEY_URL).apply(); showUrlSetup(); } return true; }); p.show();
    }

    private void goToDashboard(){
        if(webView==null) return;
        String js="(function(){try{var els=[].slice.call(document.querySelectorAll('a,button,[role=button],[onclick]'));var ok=function(e){if(!e)return false;var r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden';};var d=els.find(function(e){var t=(e.innerText||e.textContent||e.getAttribute('aria-label')||'').trim().toLowerCase();return ok(e)&&(t==='dashboard'||t.indexOf('operations dashboard')>=0);});if(d){d.click();return 'clicked';}if(typeof showPage==='function'){try{showPage('dashboard');return 'showPage';}catch(x){}}return 'none';}catch(e){return 'none';}})();";
        webView.evaluateJavascript(js,value->{
            if(value==null || value.contains("none")) webView.loadUrl(homeUrl);
        });
    }

    private void requestSmartBack(){
        if(webView==null){ super.onBackPressed(); return; }
        if(backCheckRunning) return;
        backCheckRunning=true;
        String js="(function(){try{var vis=function(e){if(!e)return false;var r=e.getBoundingClientRect(),s=getComputedStyle(e);return r.width>0&&r.height>0&&s.display!=='none'&&s.visibility!=='hidden'&&s.opacity!=='0';};var pm=document.querySelector('#p1013-profile-menu.open,.p1013-profile-menu.open');if(pm){pm.classList.remove('open');return 'handled';}var modals=[].slice.call(document.querySelectorAll('[role=dialog],.modal,.dialog,.popup,.overlay,.drawer')).filter(vis);if(modals.length){var m=modals[modals.length-1];var b=m.querySelector('[data-dismiss],.close,.btn-close,[aria-label=Close],[aria-label=\"Close\"],button[title=Close],button[title=\"Close\"],button');if(b&&vis(b)){b.click();return 'handled';}}var body=(document.body?document.body.innerText:'').toLowerCase();var h=[].slice.call(document.querySelectorAll('h1,h2,.page-title,.dashboard-title')).filter(vis).map(function(e){return (e.innerText||'').toLowerCase();}).join(' ');var path=(location.pathname||'').toLowerCase();var dash=path.indexOf('dashboard')>=0||h.indexOf('operations dashboard')>=0||h.trim()==='dashboard'||body.indexOf('live operations')>=0&&body.indexOf('operations dashboard')>=0;return dash?'dashboard':'content';}catch(e){return 'content';}})();";
        webView.evaluateJavascript(js,value->{
            backCheckRunning=false;
            String state=value==null?"content":value.replace("\"","").trim();
            if("handled".equals(state)){ lastDashboardBack=0L; return; }
            if(webView.canGoBack()){
                lastDashboardBack=0L;
                webView.goBack();
                return;
            }
            if("dashboard".equals(state)){
                long now=System.currentTimeMillis();
                if(now-lastDashboardBack<=DOUBLE_BACK_EXIT_MS){
                    finishAffinity();
                }else{
                    lastDashboardBack=now;
                    Toast.makeText(MainActivity.this,"Press back again to exit",Toast.LENGTH_SHORT).show();
                }
                return;
            }
            lastDashboardBack=0L;
            goToDashboard();
        });
    }

    @Override protected void onActivityResult(int r,int c,Intent data){
        super.onActivityResult(r,c,data); if(r==FILE_CHOOSER && fileCallback!=null){ Uri[] result=null; if(c==RESULT_OK){ if(data!=null && data.getData()!=null) result=new Uri[]{data.getData()}; else if(data!=null && data.getClipData()!=null){ int n=data.getClipData().getItemCount(); result=new Uri[n]; for(int i=0;i<n;i++) result[i]=data.getClipData().getItemAt(i).getUri(); } } fileCallback.onReceiveValue(result); fileCallback=null; }
    }

    @Override public void onBackPressed(){ requestSmartBack(); }
}
