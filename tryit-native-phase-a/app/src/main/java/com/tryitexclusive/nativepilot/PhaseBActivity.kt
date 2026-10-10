package com.tryitexclusive.nativepilot

import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.foundation.gestures.detectTapGestures
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

    @Composable
    fun NativeDashboard(secure:SecureStore, profile:UserProfile,onProfile:()->Unit,onSettings:()->Unit,onLogout:()->Unit, onLock:()->Unit, onOrders:(String)->Unit) {
        var period by rememberSaveable { mutableStateOf("This Month") }
        var customFrom by rememberSaveable { mutableStateOf("") }
        var customTo by rememberSaveable { mutableStateOf("") }
        var data by remember { mutableStateOf(DashboardData()) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }
        var expanded by remember { mutableStateOf(false) }
        var selectedStatus by remember { mutableStateOf<StatusMetric?>(null) }
        var drill by remember { mutableStateOf<List<RecentOrder>?>(null) }
        var requestId by remember { mutableStateOf(0) }
        val scope = rememberCoroutineScope()

        fun refresh() {
            val id=++requestId; busy=true; error=""; data=DashboardData()
            scope.launch {
                val r = withContext(Dispatchers.IO) { DashboardApi.load(secure.get("server_url"),secure.get("session_cookie"),period,customFrom,customTo) }
                if(id != requestId) return@launch
                busy=false
                if(r.first!=null) data=r.first!! else error=r.second
            }
        }
        LaunchedEffect(period,customFrom,customTo) { if(period!="Custom" || (customFrom.isNotBlank()&&customTo.isNotBlank())) refresh() }

        Scaffold(topBar={
            Surface(color=BNavy,shadowElevation=4.dp){Row(Modifier.fillMaxWidth().statusBarsPadding().height(66.dp).padding(horizontal=14.dp),verticalAlignment=Alignment.CenterVertically){
                Column{Text("TRYIT",color=ComposeColor.White,fontSize=22.sp,fontWeight=FontWeight.Black);Text("Operations Dashboard",color=ComposeColor(0xFFCBD5E1),fontSize=11.sp)}
                Spacer(Modifier.weight(1f)); Column(Modifier.clickable(onClick=onProfile),horizontalAlignment=Alignment.End){Text(profile.fullName.ifBlank{profile.username.ifBlank{"User"}},color=ComposeColor.White,fontWeight=FontWeight.Bold,fontSize=12.sp);Text(profile.designation.ifBlank{profile.role.ifBlank{"Account"}},color=ComposeColor(0xFFCBD5E1),fontSize=10.sp)}
                IconButton(onLock){Icon(Icons.Default.Lock,"Lock",tint=ComposeColor.White)};IconButton(onSettings){Icon(Icons.Default.Settings,null,tint=ComposeColor.White)}
            }}
        }) { pad ->
            Column(Modifier.padding(pad).fillMaxSize().verticalScroll(rememberScrollState()).padding(14.dp)) {
                Surface(shape=RoundedCornerShape(22.dp),color=BNavy){Column(Modifier.padding(18.dp)){Text("Operations Dashboard",color=ComposeColor.White,fontSize=25.sp,fontWeight=FontWeight.Black);Text("Live OMS data • same period across all dashboard widgets",color=ComposeColor(0xFFCBD5E1))}}
                Spacer(Modifier.height(12.dp)); Button({onOrders("")},modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.ReceiptLong,null);Spacer(Modifier.width(6.dp));Text("Open Orders")};Spacer(Modifier.height(12.dp))
                Surface(shape=RoundedCornerShape(18.dp),color=ComposeColor.White){Column(Modifier.padding(14.dp)){
                    Box{OutlinedButton({expanded=true}){Icon(Icons.Default.DateRange,null);Spacer(Modifier.width(6.dp));Text(period)};DropdownMenu(expanded,{expanded=false}){listOf("Today","Yesterday","Last 7 Days","This Month","Last Month","Last 30 Days","This Quarter","Financial Year","Custom").forEach{p->DropdownMenuItem({Text(p)},{period=p;expanded=false})}}}
                    if(period=="Custom") {Spacer(Modifier.height(8.dp));Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedTextField(customFrom,{customFrom=it},label={Text("From YYYY-MM-DD")},modifier=Modifier.weight(1f),singleLine=true);OutlinedTextField(customTo,{customTo=it},label={Text("To YYYY-MM-DD")},modifier=Modifier.weight(1f),singleLine=true)}}
                    if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=10.dp))
                    if(error.isNotBlank()) {Text(error,color=MaterialTheme.colorScheme.error,modifier=Modifier.padding(top=10.dp));TextButton({refresh()}){Text("Retry")}}
                }}
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement=Arrangement.spacedBy(10.dp)){MetricCard("Orders",data.totalOrders.toString(),Icons.Default.ReceiptLong,Modifier.weight(1f).clickable{drill=data.recent});MetricCard("Pairs",data.totalPairs.toString(),Icons.Default.Numbers,Modifier.weight(1f).clickable{drill=data.recent})}
                Spacer(Modifier.height(10.dp))
                data.statuses.chunked(2).forEach { row -> Row(horizontalArrangement=Arrangement.spacedBy(10.dp),modifier=Modifier.fillMaxWidth()) { row.forEach { s -> StatusCard(s,Modifier.weight(1f)){selectedStatus=s} }; if(row.size==1) Spacer(Modifier.weight(1f)) }; Spacer(Modifier.height(10.dp)) }
                SectionTitle("Order Status")
                if(data.statuses.isEmpty()) EmptyCard("No status data returned for this period.") else Surface(shape=RoundedCornerShape(18.dp),color=ComposeColor.White){Column(Modifier.padding(14.dp)){StatusDonut(data.statuses,data.totalOrders){selectedStatus=it};data.statuses.forEach{s->Box(Modifier.clickable{selectedStatus=s}){StatusBar(s,data.totalOrders)}}}}
                Spacer(Modifier.height(14.dp)); SectionTitle("Daily Order Trend")
                if(data.trend.isEmpty()) EmptyCard("No trend data returned.") else Surface(shape=RoundedCornerShape(18.dp),color=ComposeColor.White){Column(Modifier.padding(14.dp)){data.trend.forEach{t->Box(Modifier.clickable{drill=data.recent.filter{it.date==t.label}}){TrendRow(t)}}}}
                Spacer(Modifier.height(14.dp)); SectionTitle("Recent Orders")
                if(data.recent.isEmpty()) EmptyCard("No recent orders in selected period.") else data.recent.take(8).forEach{Box(Modifier.clickable{drill=listOf(it)}){RecentOrderCard(it)}}
                Spacer(Modifier.height(14.dp)); SectionTitle("Top Parties")
                if(data.topParties.isEmpty()) EmptyCard("No party ranking data returned.") else data.topParties.take(8).forEach{Box(Modifier.clickable{drill=data.recent.filter{o->o.party==it.name}}){PartyCard(it)}}
                Spacer(Modifier.height(18.dp)); OutlinedButton(onLogout,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Logout,null);Spacer(Modifier.width(6.dp));Text("Log Out")};Spacer(Modifier.height(24.dp))
            }
        }
        drill?.let { orders -> AlertDialog(onDismissRequest={drill=null},confirmButton={TextButton({drill=null}){Text("Close")}},title={Text("Orders (${orders.size})")},text={Column(Modifier.heightIn(max=420.dp).verticalScroll(rememberScrollState())){orders.forEach{Box(Modifier.clickable{onOrders(it.orderNo)}){RecentOrderCard(it)}};if(orders.isEmpty())Text("No orders")}}) }
        selectedStatus?.let { s -> AlertDialog(onDismissRequest={selectedStatus=null},confirmButton={TextButton({drill=data.recent.filter{it.status==s.name};selectedStatus=null}){Text("View Orders")}},dismissButton={TextButton({selectedStatus=null}){Text("Close")}},title={Text(s.name)},text={Text("Orders: ${s.orders}\nPairs: ${s.pairs}\nShare: ${if(data.totalOrders>0) "%.1f".format(s.orders*100.0/data.totalOrders) else "0.0"}%")}) }
    }

    @Composable private fun MetricCard(label:String,value:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier){Surface(modifier,shape=RoundedCornerShape(18.dp),color=ComposeColor.White,shadowElevation=2.dp){Column(Modifier.padding(15.dp)){Icon(icon,null,tint=BRed);Text(value,fontSize=24.sp,fontWeight=FontWeight.Black,color=BNavy);Text(label,fontSize=12.sp,color=ComposeColor(0xFF64748B))}}}
    @Composable private fun StatusCard(s:StatusMetric,modifier:Modifier,onClick:()->Unit){Surface(modifier.clickable(onClick=onClick),shape=RoundedCornerShape(18.dp),color=ComposeColor.White,shadowElevation=2.dp){Column(Modifier.padding(14.dp)){Text(s.name,fontSize=12.sp,color=ComposeColor(0xFF64748B));Text(s.orders.toString(),fontSize=22.sp,fontWeight=FontWeight.Black,color=BNavy);Text("${s.pairs} pairs",fontSize=11.sp,color=BRed)}}}
    @Composable private fun SectionTitle(t:String){Text(t,fontSize=19.sp,fontWeight=FontWeight.Bold,color=BNavy,modifier=Modifier.padding(bottom=8.dp))}
    @Composable private fun EmptyCard(t:String){Surface(shape=RoundedCornerShape(18.dp),color=ComposeColor.White){Text(t,modifier=Modifier.padding(16.dp),color=ComposeColor(0xFF64748B))}}
    @Composable private fun StatusBar(s:StatusMetric,total:Int){val pct=if(total>0)s.orders.toFloat()/total else 0f;Column(Modifier.padding(vertical=5.dp)){Row{Text(s.name,modifier=Modifier.weight(1f),fontWeight=FontWeight.SemiBold,color=BNavy);Text("${s.orders} • ${s.pairs} pairs",fontSize=12.sp,color=ComposeColor(0xFF64748B))};LinearProgressIndicator(progress={pct.coerceIn(0f,1f)},modifier=Modifier.fillMaxWidth().height(8.dp),color=when(s.name.lowercase()){ "pending"->BAmber; "dispatched"->BGreen; else->BRed },trackColor=ComposeColor(0xFFE2E8F0))}}
    @Composable private fun TrendRow(t:TrendPoint){Row(Modifier.fillMaxWidth().padding(vertical=6.dp),verticalAlignment=Alignment.CenterVertically){Text(t.label,modifier=Modifier.width(92.dp),fontSize=12.sp,color=ComposeColor(0xFF64748B));LinearProgressIndicator(progress={(t.orders/ maxOf(1f,t.maxOrders.toFloat())).coerceIn(0f,1f)},modifier=Modifier.weight(1f).height(8.dp));Spacer(Modifier.width(8.dp));Text("${t.orders} / ${t.pairs}",fontSize=12.sp,color=BNavy)}}
    @Composable private fun RecentOrderCard(o:RecentOrder){Surface(shape=RoundedCornerShape(16.dp),color=ComposeColor.White,modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)){Row(Modifier.padding(14.dp),verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text(o.orderNo.ifBlank{"Order"},fontWeight=FontWeight.Bold,color=BNavy);Text(listOf(o.party,o.article).filter{it.isNotBlank()}.joinToString(" • "),fontSize=12.sp,color=ComposeColor(0xFF64748B))};Column(horizontalAlignment=Alignment.End){Text(o.status,fontSize=12.sp,fontWeight=FontWeight.Bold,color=BRed);Text("${o.pairs} pairs • ${o.date}",fontSize=11.sp,color=ComposeColor(0xFF64748B))}}}}
    @Composable private fun PartyCard(p:PartyMetric){Surface(shape=RoundedCornerShape(16.dp),color=ComposeColor.White,modifier=Modifier.fillMaxWidth().padding(bottom=8.dp)){Row(Modifier.padding(14.dp)){Column(Modifier.weight(1f)){Text(p.name,fontWeight=FontWeight.Bold,color=BNavy);Text("${p.orders} orders",fontSize=12.sp,color=ComposeColor(0xFF64748B))};Text("${p.pairs} pairs",fontWeight=FontWeight.Bold,color=BRed)}}}

data class DashboardData(val totalOrders:Int=0,val totalPairs:Int=0,val statuses:List<StatusMetric> = emptyList(),val trend:List<TrendPoint> = emptyList(),val recent:List<RecentOrder> = emptyList(),val topParties:List<PartyMetric> = emptyList())
data class StatusMetric(val name:String,val orders:Int,val pairs:Int)
data class TrendPoint(val label:String,val orders:Int,val pairs:Int,val maxOrders:Int=1)
data class RecentOrder(val orderNo:String,val party:String,val article:String,val status:String,val pairs:Int,val date:String="")
data class PartyMetric(val name:String,val orders:Int,val pairs:Int)

@Composable
private fun StatusDonut(statuses:List<StatusMetric>,total:Int,onSelect:(StatusMetric)->Unit) {
    val colors=listOf(BAmber,ComposeColor(0xFF2563EB),BGreen,ComposeColor(0xFF9333EA),BRed)
    Box(Modifier.fillMaxWidth(),contentAlignment=Alignment.Center) {
        androidx.compose.foundation.Canvas(Modifier.size(220.dp).pointerInput(statuses) {
            detectTapGestures { p ->
                val dx=p.x-size.width/2f;val dy=p.y-size.height/2f
                val radius=kotlin.math.sqrt(dx*dx+dy*dy)
                if(radius>=size.width*.28f && radius<=size.width*.49f && total>0) {
                    val angle=((Math.toDegrees(kotlin.math.atan2(dy.toDouble(),dx.toDouble()))+450)%360).toFloat()
                    var end=0f
                    for(s in statuses){end+=s.orders*360f/total;if(angle<end){onSelect(s);break}}
                }
            }
        }) {
            val stroke=size.width*.17f
            var start=-90f
            drawArc(ComposeColor(0xFFE2E8F0),0f,360f,false,style=androidx.compose.ui.graphics.drawscope.Stroke(stroke),topLeft=androidx.compose.ui.geometry.Offset(stroke/2,stroke/2),size=androidx.compose.ui.geometry.Size(size.width-stroke,size.height-stroke))
            statuses.forEachIndexed { i,s -> val sweep=if(total>0)s.orders*360f/total else 0f
                drawArc(colors[i%colors.size],start,sweep,false,style=androidx.compose.ui.graphics.drawscope.Stroke(stroke),topLeft=androidx.compose.ui.geometry.Offset(stroke/2,stroke/2),size=androidx.compose.ui.geometry.Size(size.width-stroke,size.height-stroke));start+=sweep
            }
        }
        Column(horizontalAlignment=Alignment.CenterHorizontally){Text(total.toString(),fontSize=30.sp,fontWeight=FontWeight.Black);Text("Orders",fontSize=12.sp)}
    }
}

object DashboardApi {
    fun load(server:String,cookie:String,period:String,from:String,to:String):Pair<DashboardData?,String> = try {
        val dates=range(period,from,to)
        val c=URL(normalizeServer(server).trimEnd('/')+"/api/state").openConnection() as HttpURLConnection
        try {
            c.requestMethod="GET";c.instanceFollowRedirects=false;c.connectTimeout=9000;c.readTimeout=15000
            c.setRequestProperty("Accept","application/json");c.setRequestProperty("Cookie",cookie)
            val code=c.responseCode
            if(code !in 200..299) null to if(code==401||code==403) "Session expired or access denied. Please log in again." else "Dashboard request failed ($code)"
            else {
                val j=JSONObject(c.inputStream.bufferedReader().use{it.readText()})
                if(!j.optBoolean("ok")) null to j.optString("error","Unable to load dashboard")
                else {
                    val raw=j.getJSONObject("values").opt("st_orders")
                    if(raw==null||raw==JSONObject.NULL) null to "Order viewing permission is required for this dashboard."
                    else {
                        val a=if(raw is JSONArray) raw else JSONArray(raw.toString())
                        val fmt=java.time.format.DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)
                        val orders=(0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let { o ->
                            val date=runCatching{java.time.LocalDate.parse(o.optString("orderDate"),fmt)}.getOrNull()
                            if(date==null||date<dates.first||date>dates.second||o.optString("status")=="Cancelled") null
                            else RecentOrder(o.optString("orderNo"),o.optString("party"),o.optString("article"),if(o.optBoolean("hold"))"Hold" else o.optString("status","Pending"),o.optDouble("totalPairs",0.0).toInt(),date.toString())
                        }}.sortedByDescending{it.date}
                        val names=(listOf("Pending","In Production","Ready","Hold","Dispatched")+orders.map{it.status}).distinct()
                        val statuses=names.map{n->val rows=orders.filter{it.status==n};StatusMetric(n,rows.size,rows.sumOf{it.pairs})}
                        val trend=orders.groupBy{it.date}.toSortedMap().map{(d,rows)->TrendPoint(d,rows.size,rows.sumOf{it.pairs})}
                        val mx=trend.maxOfOrNull{it.orders}?:1
                        val parties=orders.groupBy{it.party.ifBlank{"Unassigned"}}.map{(p,rows)->PartyMetric(p,rows.size,rows.sumOf{it.pairs})}.sortedWith(compareByDescending<PartyMetric>{it.pairs}.thenByDescending{it.orders})
                        DashboardData(orders.size,orders.sumOf{it.pairs},statuses,trend.map{it.copy(maxOrders=mx)},orders,parties) to ""
                    }
                }
            }
        } finally {c.disconnect()}
    } catch(e:Exception) { null to (e.message?:"Unable to load dashboard") }

    fun range(period:String,from:String,to:String):Pair<java.time.LocalDate,java.time.LocalDate> {
        val today=java.time.LocalDate.now(java.time.ZoneId.of("Asia/Kolkata"))
        return when(period) {
            "Today"->today to today
            "Yesterday"->today.minusDays(1) to today.minusDays(1)
            "Last 7 Days"->today.minusDays(6) to today
            "Last 30 Days"->today.minusDays(29) to today
            "Last Month"->{val d=today.minusMonths(1).withDayOfMonth(1);d to d.withDayOfMonth(d.lengthOfMonth())}
            "This Quarter"->{val d=today.withMonth((today.monthValue-1)/3*3+1).withDayOfMonth(1);d to d.plusMonths(3).minusDays(1)}
            "Financial Year"->{val d=java.time.LocalDate.of(if(today.monthValue>=4)today.year else today.year-1,4,1);d to d.plusYears(1).minusDays(1)}
            "Custom"->{val a=java.time.LocalDate.parse(from);val b=java.time.LocalDate.parse(to);require(a<=b){"From date must be on or before To date"};a to b}
            else->today.withDayOfMonth(1) to today.withDayOfMonth(today.lengthOfMonth())
        }
    }
}
