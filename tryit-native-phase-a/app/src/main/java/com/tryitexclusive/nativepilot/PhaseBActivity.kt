package com.tryitexclusive.nativepilot

import android.graphics.Color
import android.os.Bundle
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets

private val BNavy = ComposeColor(0xFF0B1830)
private val BRed = ComposeColor(0xFFD71920)
private val BSoft = ComposeColor(0xFFF4F7FB)
private val BGreen = ComposeColor(0xFF0E9F6E)
private val BAmber = ComposeColor(0xFFF59E0B)

class PhaseBActivity : FragmentActivity() {
    private lateinit var secure: SecureStore
    private var lastBackAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11,24,48)
        secure = SecureStore(this)
        setContent {
            MaterialTheme(colorScheme = lightColorScheme(primary = BNavy, secondary = BRed, background = BSoft)) {
                PhaseBApp()
            }
        }
    }

    @Composable
    private fun PhaseBApp() {
        var route by rememberSaveable { mutableStateOf(if (secure.get("session_cookie").isBlank()) "login" else "dashboard") }
        var profile by remember { mutableStateOf(loadProfile()) }
        val scope = rememberCoroutineScope()

        BackHandler(enabled = route != "login") {
            if (route != "dashboard") route = "dashboard"
            else {
                val now = System.currentTimeMillis()
                if (now - lastBackAt <= 1800L) finishAffinity()
                else { lastBackAt = now; Toast.makeText(this,"Press back again to exit",Toast.LENGTH_SHORT).show() }
            }
        }

        when(route) {
            "login" -> LoginScreen { server,user,pass,remember,setBusy,setError ->
                setBusy(true); setError("")
                scope.launch {
                    val r = withContext(Dispatchers.IO) { ApiClient.login(server,user,pass,secure.get("device_id"),android.os.Build.MODEL) }
                    setBusy(false)
                    if (r.ok) {
                        secure.put("server_url", normalizeServer(server))
                        secure.put("session_cookie", r.cookie)
                        secure.put("remembered_user", if(remember) user else "")
                        profile = r.profile ?: UserProfile(username=user)
                        secure.put("profile_json", JSONObject().apply {
                            put("username", profile.username); put("full_name", profile.fullName); put("designation", profile.designation); put("role",profile.role); put("email",profile.email); put("mobile",profile.mobile)
                        }.toString())
                        route = "dashboard"
                    } else setError(r.error)
                }
            }
            "profile" -> ProfileScreenB(profile) { route = "dashboard" }
            "settings" -> SettingsScreenB { route = "dashboard" }
            else -> DashboardScreen(profile, onProfile={route="profile"}, onSettings={route="settings"}, onLogout={
                secure.put("session_cookie",""); secure.put("profile_json",""); route="login"
            })
        }
    }

    private fun loadProfile(): UserProfile {
        val raw = secure.get("profile_json")
        return runCatching { if(raw.isBlank()) UserProfile(username=secure.get("remembered_user")) else UserProfile.fromJson(JSONObject(raw)) }
            .getOrDefault(UserProfile())
    }

    @Composable
    private fun LoginScreen(onLogin:(String,String,String,Boolean,(Boolean)->Unit,(String)->Unit)->Unit) {
        var server by rememberSaveable { mutableStateOf(secure.get("server_url")) }
        var user by rememberSaveable { mutableStateOf(secure.get("remembered_user")) }
        var pass by rememberSaveable { mutableStateOf("") }
        var remember by rememberSaveable { mutableStateOf(user.isNotBlank()) }
        var show by rememberSaveable { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        Box(Modifier.fillMaxSize().background(ComposeColor(0xFFFFF9F1))) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp), horizontalAlignment=Alignment.CenterHorizontally) {
                Spacer(Modifier.height(40.dp))
                Surface(shape=RoundedCornerShape(24.dp), color=ComposeColor.White, shadowElevation=8.dp) {
                    Column(Modifier.padding(22.dp).widthIn(max=480.dp)) {
                        Text("TRYIT",fontSize=32.sp,fontWeight=FontWeight.Black,color=BNavy)
                        Text("Native Pilot • Phase B",color=BRed,fontWeight=FontWeight.Bold)
                        Spacer(Modifier.height(22.dp))
                        OutlinedTextField(server,{server=it},label={Text("Server URL")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(user,{user=it},label={Text("Username")},modifier=Modifier.fillMaxWidth(),singleLine=true)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(pass,{pass=it},label={Text("Password")},modifier=Modifier.fillMaxWidth(),singleLine=true,visualTransformation=if(show) VisualTransformation.None else PasswordVisualTransformation(),trailingIcon={IconButton({show=!show}){Icon(if(show) Icons.Default.VisibilityOff else Icons.Default.Visibility,null)}})
                        Row(verticalAlignment=Alignment.CenterVertically){Checkbox(remember,{remember=it});Text("Remember username")}
                        if(error.isNotBlank()) Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(vertical=8.dp))
                        Button({onLogin(server,user.trim(),pass,remember,{busy=it},{error=it})},enabled=!busy&&server.isNotBlank()&&user.isNotBlank()&&pass.isNotBlank(),modifier=Modifier.fillMaxWidth().height(52.dp),colors=ButtonDefaults.buttonColors(containerColor=BRed)){if(busy) CircularProgressIndicator(Modifier.size(20.dp),strokeWidth=2.dp,color=ComposeColor.White) else Text("Log In",fontWeight=FontWeight.Bold)}
                        Spacer(Modifier.height(12.dp)); Text("Version 0.2.0 • Native Android",fontSize=12.sp,color=ComposeColor(0xFF94A3B8))
                    }
                }
            }
        }
    }

    @Composable
    private fun DashboardScreen(profile:UserProfile,onProfile:()->Unit,onSettings:()->Unit,onLogout:()->Unit) {
        var period by rememberSaveable { mutableStateOf("This Month") }
        var customFrom by rememberSaveable { mutableStateOf("") }
        var customTo by rememberSaveable { mutableStateOf("") }
        var data by remember { mutableStateOf(DashboardData()) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        var expanded by remember { mutableStateOf(false) }
        var selectedStatus by remember { mutableStateOf<StatusMetric?>(null) }
        val scope = rememberCoroutineScope()

        fun refresh() {
            busy=true; error=""
            scope.launch {
                val r = withContext(Dispatchers.IO) { DashboardApi.load(secure.get("server_url"),secure.get("session_cookie"),period,customFrom,customTo) }
                busy=false
                if(r.first!=null) data=r.first!! else error=r.second
            }
        }
        LaunchedEffect(period,customFrom,customTo) { if(period!="Custom" || (customFrom.isNotBlank()&&customTo.isNotBlank())) refresh() }

        Scaffold(topBar={
            Surface(color=BNavy,shadowElevation=4.dp){Row(Modifier.fillMaxWidth().statusBarsPadding().height(66.dp).padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically){
                Column{Text("TRYIT",color=ComposeColor.White,fontSize=22.sp,fontWeight=FontWeight.Black);Text("Operations Dashboard",color=ComposeColor(0xFFCBD5E1),fontSize=11.sp)}
                Spacer(Modifier.weight(1f)); Column(Modifier.clickable(onClick=onProfile),horizontalAlignment=Alignment.End){Text(profile.fullName.ifBlank{profile.username.ifBlank{"User"}},color=ComposeColor.White,fontWeight=FontWeight.Bold,fontSize=12.sp);Text(profile.designation.ifBlank{profile.role.ifBlank{"Account"}},color=ComposeColor(0xFFCBD5E1),fontSize=10.sp)}
                IconButton(onSettings){Icon(Icons.Default.Settings,null,tint=ComposeColor.White)}
            }}
        }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
                Surface(shape=RoundedCornerShape(22.dp),color=BNavy){Column(Modifier.padding(18.dp)){Text("Operations Dashboard",color=ComposeColor.White,fontSize=25.sp,fontWeight=FontWeight.Black);Text("Live OMS data • same period across all dashboard widgets",color=ComposeColor(0xFFCBD5E1))}}
                Spacer(Modifier.height(12.dp))
                Surface(shape=RoundedCornerShape(18.dp),color=ComposeColor.White){Column(Modifier.padding(14.dp)){
                    Box{OutlinedButton({expanded=true}){Icon(Icons.Default.DateRange,null);Spacer(Modifier.width(6.dp));Text(period)};DropdownMenu(expanded,{expanded=false}){listOf("Today","Yesterday","Last 7 Days","This Month","Last Month","Last 30 Days","This Quarter","Financial Year","Custom").forEach{p->DropdownMenuItem({Text(p)},{period=p;expanded=false})}}}
                    if(period=="Custom") {Spacer(Modifier.height(8.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(customFrom,{customFrom=it},label={Text("From YYYY-MM-DD")},modifier=Modifier.weight(1f),singleLine=true);OutlinedTextField(customTo,{customTo=it},label={Text("To YYYY-MM-DD")},modifier=Modifier.weight(1f),singleLine=true)}}
                    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=10.dp))
                    if(error.isNotBlank()) {Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=10.dp));TextButton({refresh()}){Text("Retry")}}
                }}
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){MetricCard("Orders",data.totalOrders.toString(),Icons.Default.ReceiptLong,Modifier.weight(1f));MetricCard("Pairs",data.totalPairs.toString(),Icons.Default.Numbers,Modifier.weight(1f))}
                Spacer(Modifier.height(10.dp))
                data.statuses.chunked(2).forEach { row -> Row(horizontalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.fillMaxWidth()) { row.forEach { s -> StatusCard(s,Modifier.weight(1f)){selectedStatus=s} }; if(row.size==1) Spacer(Modifier.weight(1f)) }; Spacer(Modifier.height(10.dp)) }
                SectionTitle("Order Status")
                if(data.statuses.isEmpty()) EmptyCard("No status data returned for this period.") else Surface(shape=RoundedCornerShape(18.dp),color=ComposeColor.White){Column(Modifier.padding(14.dp)){data.statuses.forEach{s->StatusBar(s,data.totalOrders)}}}
                Spacer(Modifier.height(14.dp)); SectionTitle("Daily Order Trend")
                if(data.trend.isEmpty()) EmptyCard("No trend data returned.") else Surface(shape=RoundedCornerShape(18.dp),color=ComposeColor.White){Column(Modifier.padding(14.dp)){data.trend.takeLast(14).forEach{t->TrendRow(t)}}}
                Spacer(Modifier.height(14.dp)); SectionTitle("Recent Orders")
                if(data.recent.isEmpty()) EmptyCard("No recent orders in selected period.") else data.recent.take(8).forEach{RecentOrderCard(it)}
                Spacer(Modifier.height(14.dp)); SectionTitle("Top Parties")
                if(data.topParties.isEmpty()) EmptyCard("No party ranking data returned.") else data.topParties.take(8).forEach{PartyCard(it)}
                Spacer(Modifier.height(18.dp)); OutlinedButton(onLogout,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Logout,null);Spacer(Modifier.width(6.dp));Text("Log Out")};Spacer(Modifier.height(24.dp))
            }
        }
        selectedStatus?.let { s -> AlertDialog(onDismissRequest={selectedStatus=null},confirmButton={TextButton({selectedStatus=null}){Text("Close")}},title={Text(s.name)},text={Text("Orders: ${s.orders}\nPairs: ${s.pairs}\nShare: ${if(data.totalOrders>0) "%.1f".format(s.orders*100.0/data.totalOrders) else "0.0"}%")}) }
    }

    @Composable private fun MetricCard(label:String,value:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier){Surface(modifier,shape=RoundedCornerShape(18.dp),color=ComposeColor.White,shadowElevation=2.dp){Column(Modifier.padding(15.dp)){Icon(icon,null,tint=BRed);Text(value,fontSize=24.sp,fontWeight=FontWeight.Black,color=BNavy);Text(label,fontSize=12.sp,color=ComposeColor(0xFF64748B))}}}
    @Composable private fun StatusCard(s:StatusMetric,modifier:Modifier,onClick:()->Unit){Surface(modifier.clickable(onClick=onClick),shape=RoundedCornerShape(18.dp),color=ComposeColor.White,shadowElevation=2.dp){Column(Modifier.padding(14.dp)){Text(s.name,fontSize=12.sp,color=ComposeColor(0xFF64748B));Text(s.orders.toString(),fontSize=22.sp,fontWeight=FontWeight.Black,color=BNavy);Text("${s.pairs} pairs",fontSize=11.sp,color=BRed)}}}
    @Composable private fun SectionTitle(t:String){Text(t,fontSize=19.sp,fontWeight=FontWeight.Bold,color=BNavy,modifier=Modifier.padding(bottom=8.dp))}
    @Composable private fun EmptyCard(t:String){Surface(shape=RoundedCornerShape(18.dp),color=ComposeColor.White){Text(t,modifier=Modifier.padding(16.dp),color=ComposeColor(0xFF64748B))}}
    @Composable private fun StatusBar(s:StatusMetric,total:Int){val pct=if(total>0)s.orders.toFloat()/total else 0f;Column(Modifier.padding(vertical=5.dp)){Row{Text(s.name,modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold,color=BNavy);Text("${s.orders} • ${s.pairs} pairs",fontSize=12.sp,color=ComposeColor(0xFF64748B))};LinearProgressIndicator(progress={pct.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(8.dp),color=when(s.name.lowercase()){ "pending"->BAmber; "dispatched"->BGreen; else->BRed },trackColor=ComposeColor(0xFFE2E8F0))}}
    @Composable private fun TrendRow(t:TrendPoint){Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Text(t.label,modifier=Modifier.width(92.dp),fontSize=12.sp,color=ComposeColor(0xFF64748B));LinearProgressIndicator(progress={(t.orders/ maxOf(1f,t.maxOrders.toFloat())).coerceIn(0f,1f)},modifier=Modifier.weight(1f).height(8.dp));Spacer(Modifier.width(8.dp));Text("${t.orders} / ${t.pairs}",fontSize=12.sp,color=BNavy)}}
    @Composable private fun RecentOrderCard(o:RecentOrder){Surface(shape=RoundedCornerShape(16.dp),color=ComposeColor.White,modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(o.orderNo.ifBlank{"Order"},fontWeight=FontWeight.Bold,color=BNavy);Text(listOf(o.party,o.article).filter{it.isNotBlank()}.joinToString(" • "),fontSize=12.sp,color=ComposeColor(0xFF64748B))};Column(horizontalAlignment=Alignment.End){Text(o.status,fontSize=12.sp,fontWeight=FontWeight.Bold,color=BRed);Text("${o.pairs} pairs",fontSize=11.sp,color=ComposeColor(0xFF64748B))}}}}
    @Composable private fun PartyCard(p:PartyMetric){Surface(shape=RoundedCornerShape(16.dp),color=ComposeColor.White,modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)){Row(Modifier.padding(14.dp)){Column(Modifier.weight(1f)){Text(p.name,fontWeight=FontWeight.Bold,color=BNavy);Text("${p.orders} orders",fontSize=12.sp,color=ComposeColor(0xFF64748B))};Text("${p.pairs} pairs",fontWeight=FontWeight.Bold,color=BRed)}}}

    @Composable private fun ProfileScreenB(p:UserProfile,onBack:()->Unit){Scaffold(topBar={TopB("My Profile",onBack)}){pad->Column(Modifier.padding(pad).padding(16.dp)){Info("Full Name",p.fullName);Info("Username",p.username);Info("Designation",p.designation);Info("Role",p.role);Info("Email",p.email);Info("Mobile",p.mobile)}}}
    @Composable private fun SettingsScreenB(onBack:()->Unit){Scaffold(topBar={TopB("Settings",onBack)}){pad->Column(Modifier.padding(pad).padding(16.dp)){Info("Version","0.2.0 Phase B");Info("Server",secure.get("server_url"));Info("App Lock",secure.get("lock_mode").ifBlank{"Not configured"});Info("Device ID",secure.get("device_id"));Text("Phase A security preferences are preserved in encrypted storage.",color=ComposeColor(0xFF64748B),modifier=Modifier.padding(top=12.dp))}}}
    @Composable private fun TopB(t:String,onBack:()->Unit){Surface(color=BNavy){Row(Modifier.fillMaxWidth().statusBarsPadding().height(58.dp),verticalAlignment=Alignment.CenterVertically){IconButton(onBack){Icon(Icons.Default.ArrowBack,null,tint=ComposeColor.White)};Text(t,color=ComposeColor.White,fontSize=19.sp,fontWeight=FontWeight.Bold)}}}
    @Composable private fun Info(l:String,v:String){Surface(shape=RoundedCornerShape(14.dp),color=ComposeColor.White,modifier=Modifier.fillMaxWidth().padding(vertical=4.dp)){Column(Modifier.padding(14.dp)){Text(l,fontSize=12.sp,color=ComposeColor(0xFF64748B));Text(v.ifBlank{"—"},fontWeight=FontWeight.SemiBold,color=BNavy)}}}
}

data class DashboardData(val totalOrders:Int=0,val totalPairs:Int=0,val statuses:List<StatusMetric> = emptyList(),val trend:List<TrendPoint> = emptyList(),val recent:List<RecentOrder> = emptyList(),val topParties:List<PartyMetric> = emptyList())
data class StatusMetric(val name:String,val orders:Int,val pairs:Int)
data class TrendPoint(val label:String,val orders:Int,val pairs:Int,val maxOrders:Int=1)
data class RecentOrder(val orderNo:String,val party:String,val article:String,val status:String,val pairs:Int)
data class PartyMetric(val name:String,val orders:Int,val pairs:Int)

object DashboardApi {
    fun load(server:String,cookie:String,period:String,from:String,to:String):Pair<DashboardData?,String> {
        if(server.isBlank()) return null to "Server URL is not configured."
        val payload=JSONObject().apply{put("period",period);if(period=="Custom"){put("from",from);put("to",to)}}
        val paths=listOf("/api/oms/dashboard","/api/dashboard","/api/oms/dashboard/data")
        var last="Dashboard API is not available on the server."
        for(path in paths){val r=post(server,path,payload,cookie);if(r.first!=null){return parse(r.first!!) to ""};last=r.second}
        return null to last
    }
    private fun post(server:String,path:String,body:JSONObject,cookie:String):Pair<JSONObject?,String>{return try{val c=URL(normalizeServer(server).trimEnd('/')+path).openConnection() as HttpURLConnection;c.requestMethod="POST";c.connectTimeout=9000;c.readTimeout=12000;c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.setRequestProperty("Accept","application/json");if(cookie.isNotBlank())c.setRequestProperty("Cookie",cookie);c.outputStream.use{it.write(body.toString().toByteArray(StandardCharsets.UTF_8))};val code=c.responseCode;val text=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty();if(code !in 200..299) null to "Dashboard request failed ($code)" else runCatching{JSONObject(text)}.fold({it to ""},{null to "Invalid dashboard response"})}catch(e:Exception){null to (e.message?:"Unable to connect to dashboard")}}
    private fun parse(root:JSONObject):DashboardData{val j=root.optJSONObject("dashboard")?:root.optJSONObject("data")?:root;val statusObj=j.optJSONObject("status_counts")?:j.optJSONObject("order_status")?:j.optJSONObject("statuses");val names=listOf("Pending","In Production","Ready","Hold","Dispatched");val statuses=names.map{n->val x=statusObj?.opt(n)?:statusObj?.opt(n.lowercase().replace(" ","_"));when(x){is JSONObject->StatusMetric(n,x.optInt("orders",x.optInt("count",0)),x.optInt("pairs",0));is Number->StatusMetric(n,x.toInt(),0);else->StatusMetric(n,0,0)}}.filter{it.orders>0||it.pairs>0};val recent=parseRecent(j.optJSONArray("recent_orders")?:j.optJSONArray("recent"));val parties=parseParties(j.optJSONArray("top_parties")?:j.optJSONArray("parties"));val trendRaw=parseTrend(j.optJSONArray("daily_trend")?:j.optJSONArray("trend"));val mx=maxOf(1,trendRaw.maxOfOrNull{it.orders}?:1);return DashboardData(j.optInt("total_orders",j.optInt("totalOrders",statuses.sumOf{it.orders})),j.optInt("total_pairs",j.optInt("totalPairs",statuses.sumOf{it.pairs})),statuses,trendRaw.map{it.copy(maxOrders=mx)},recent,parties)}
    private fun parseRecent(a:JSONArray?):List<RecentOrder>{if(a==null)return emptyList();return (0 until a.length()).mapNotNull{i->a.optJSONObject(i)?.let{RecentOrder(it.optString("order_no",it.optString("orderNo",it.optString("id",""))),it.optString("party",it.optString("party_name","")),it.optString("article",it.optString("article_name","")),it.optString("status",""),it.optInt("pairs",it.optInt("total_pairs",0)))}}}
    private fun parseParties(a:JSONArray?):List<PartyMetric>{if(a==null)return emptyList();return (0 until a.length()).mapNotNull{i->a.optJSONObject(i)?.let{PartyMetric(it.optString("party",it.optString("name",it.optString("party_name",""))),it.optInt("orders",it.optInt("count",0)),it.optInt("pairs",it.optInt("total_pairs",0)))}}}
    private fun parseTrend(a:JSONArray?):List<TrendPoint>{if(a==null)return emptyList();return (0 until a.length()).mapNotNull{i->a.optJSONObject(i)?.let{TrendPoint(it.optString("date",it.optString("label","")),it.optInt("orders",it.optInt("count",0)),it.optInt("pairs",it.optInt("total_pairs",0)))}}}
}
