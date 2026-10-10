package com.tryitexclusive.nativepilot

import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

private val CNavy = Color(0xFF0B1830)
private val CBlue = Color(0xFF2563EB)
private val CSoft = Color(0xFFF4F7FB)
private val CDmy = DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)

data class OrderRow(val data:JSONObject, val token:String) {
    val id:String get()=data.optString("orderNo")
    val group:String get()=data.optString("groupOrderNo").ifBlank{id}
    val line:Int get()=data.optInt("groupLineNo",1)
    val status:String get()=if(data.optBoolean("hold")) "Hold" else data.optString("status","Pending")
}
data class OrderContext(val rows:List<OrderRow>,val masters:JSONObject,val permissions:JSONObject,val epoch:String,val history:JSONArray=JSONArray(),val events:JSONArray=JSONArray()) {
    fun can(key:String)=permissions.optBoolean(key,false)
    fun choices(key:String):List<String> = masters.optJSONArray(key)?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.optString("name") }.filter{it.isNotBlank()}.distinct().sorted() } ?: emptyList()
}
data class OrderResponse(val json:JSONObject?=null,val error:String="",val code:Int=0)

object OrdersApi {
    fun request(secure:SecureStore,path:String,body:JSONObject?=null):OrderResponse = try {
        val c=URL(normalizeServer(secure.get("server_url")).trimEnd('/')+path).openConnection() as HttpURLConnection
        try {
            c.requestMethod=if(body==null) "GET" else "POST";c.instanceFollowRedirects=false
            c.connectTimeout=12000;c.readTimeout=20000;c.setRequestProperty("Accept","application/json")
            c.setRequestProperty("Cookie",secure.get("session_cookie"))
            if(body!=null){c.doOutput=true;c.setRequestProperty("Content-Type","application/json");c.outputStream.use{it.write(body.toString().toByteArray(Charsets.UTF_8))}}
            val code=c.responseCode
            val text=(if(code in 200..299)c.inputStream else c.errorStream)?.bufferedReader()?.use{it.readText()}.orEmpty()
            val j=runCatching{JSONObject(text)}.getOrNull()
            if(code in 200..299 && j?.optBoolean("ok")==true) OrderResponse(j,code=code)
            else OrderResponse(j,when(code){404->"Install the Phase C server patch on the OMS PC, then tap Retry.";401->"Session expired. Return to Dashboard and log in again.";else->j?.optString("error")?.ifBlank{"Request failed ($code)"}?:"Request failed ($code)"},code)
        } finally {c.disconnect()}
    } catch(e:Exception){OrderResponse(error="Unable to reach server. ${e.message.orEmpty()}")}

    fun parse(j:JSONObject):OrderContext {
        val a=j.optJSONArray("orders")?:JSONArray()
        return OrderContext((0 until a.length()).mapNotNull{a.optJSONObject(it)?.let{w->w.optJSONObject("data")?.let{OrderRow(it,w.optString("token"))}}},j.optJSONObject("masters")?:JSONObject(),j.optJSONObject("permissions")?:JSONObject(),j.optString("reset_epoch"),j.optJSONArray("history")?:JSONArray(),j.optJSONArray("events")?:JSONArray())
    }
}

@Composable
fun NativeOrders(secure:SecureStore,onHome:()->Unit,initialOrder:String="") {
    var context by remember { mutableStateOf<OrderContext?>(null) }
    var loading by remember { mutableStateOf(false) }
    var loadError by remember { mutableStateOf("") }
    var route by rememberSaveable { mutableStateOf(if(initialOrder.isBlank()) "list" else "detail") }
    var selectedId by rememberSaveable { mutableStateOf(initialOrder) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<OrderResponse?>(null) }
    var pending by remember { mutableStateOf<JSONObject?>(null) }
    var toast by remember { mutableStateOf("") }
    var statusOpen by remember { mutableStateOf(false) }
    var statusDate by rememberSaveable { mutableStateOf(LocalDate.now().format(CDmy)) }
    var target by rememberSaveable { mutableStateOf("") }
    val scope=rememberCoroutineScope()
    val row=context?.rows?.find{it.id==selectedId}

    fun reload() {
        loading=true;loadError=""
        scope.launch {
            val r=withContext(Dispatchers.IO){OrdersApi.request(secure,"/api/mobile/orders/context")}
            loading=false
            if(r.error.isBlank()&&r.json!=null) context=OrdersApi.parse(r.json) else {context=null;loadError=r.error}
        }
    }
    fun save(body:JSONObject) {
        if(saving)return
        saving=true;pending=body
        scope.launch {
            val r=withContext(Dispatchers.IO){OrdersApi.request(secure,"/api/mobile/orders/save",body)}
            saving=false
            if(r.error.isBlank()&&r.json!=null){
                selectedId=r.json.optJSONArray("orders")?.optJSONObject(0)?.optJSONObject("data")?.optString("orderNo").orEmpty()
                route=if(context?.can("orders_view")==true) "detail" else "list";statusOpen=false;pending=null;toast="Saved on server";reload()
            } else error=r
        }
    }
    LaunchedEffect(Unit){reload()}
    BackHandler {
        if(saving) return@BackHandler
        when {statusOpen->statusOpen=false; route=="edit"->route="detail";route=="create"||route=="detail"->route="list";else->onHome()}
    }
    Column(Modifier.fillMaxSize().background(CSoft).statusBarsPadding().navigationBarsPadding()) {
        Surface(color=CNavy){Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){
            IconButton({if(!saving){if(route=="list")onHome() else if(route=="edit")route="detail" else route="list"}}){Icon(Icons.Default.ArrowBack,"Back",tint=Color.White)}
            Column(Modifier.weight(1f)){Text(if(route=="create")"New Order" else if(route=="edit")"Edit Order" else "Orders",color=Color.White,fontWeight=FontWeight.Bold,fontSize=21.sp);Text("TRYIT • Native Phase C",color=Color(0xFFCBD5E1),fontSize=11.sp)}
            if(route=="list"||route=="detail") IconButton({if(!saving)reload()}){Icon(Icons.Default.Refresh,"Refresh",tint=Color.White)}
        }}
        if(loading||saving) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(toast.isNotBlank()) Row(Modifier.fillMaxWidth().background(Color(0xFFE8F5ED)).padding(8.dp)){Text(toast,color=Color(0xFF137147),modifier=Modifier.weight(1f));Text("Dismiss",modifier=Modifier.clickable{toast=""},color=CBlue)}
        if(loadError.isNotBlank()) {Column(Modifier.padding(20.dp)){Text(loadError,color=MaterialTheme.colorScheme.error);Button({reload()},enabled=!loading){Text("Retry")}}}
        context?.let { ctx ->
            when(route) {
                "create","edit" -> if(route=="edit"&&row==null) Text("Order not found. Return to the list and refresh.",Modifier.padding(20.dp)) else OrderEditor(ctx,if(route=="edit")row else null,saving,
                    onCancel={route=if(route=="edit")"detail" else "list"},onSave={lines,requestId,baseToken,baseEpoch ->
                        val body=JSONObject().put("action",if(route=="edit")"edit" else "create").put("reset_epoch",baseEpoch)
                        if(route=="edit") body.put("order_no",row!!.id).put("expected_token",baseToken).put("fields",lines.getJSONObject(0))
                        else body.put("lines",lines).put("request_id",requestId)
                        save(body)
                    })
                "detail" -> if(row==null && !loading) Text("Order no longer exists. Refresh the order list.",Modifier.padding(20.dp)) else row?.let { o ->
                    OrderDetail(o,ctx,onEdit={route="edit"},onLine={selectedId=it},onStatus={t->target=t;statusDate=LocalDate.now().format(CDmy);statusOpen=true},saving=saving)
                }
                else -> OrdersList(ctx,onOpen={selectedId=it;route="detail"},onCreate={route="create"})
            }
        }
    }
    if(statusOpen && row!=null && context!=null) AlertDialog(onDismissRequest={if(!saving)statusOpen=false},title={Text("${target} • #${row.id}")},text={Column{Text("Current: ${row.status}");if(target !in listOf("Hold","Resume")) CField("Action date • DD-MM-YYYY",statusDate,{statusDate=it});Text("This action updates the live order and its audit history.",fontSize=12.sp)}},confirmButton={Button({save(JSONObject().put("action","status").put("order_no",row.id).put("expected_token",row.token).put("reset_epoch",context!!.epoch).put("target",target).put("action_date",statusDate))},enabled=!saving){Text(if(saving)"Saving…" else "Confirm")}},dismissButton={TextButton({statusOpen=false},enabled=!saving){Text("Cancel")}})
    error?.let { e ->
        val duplicate=e.json?.optBoolean("requires_duplicate_confirmation")==true
        val batch=e.json?.optBoolean("requires_batch_confirmation")==true
        AlertDialog(onDismissRequest={error=null},title={Text(if(duplicate||batch)"Confirmation required" else "Order not saved")},text={Text(e.error)},confirmButton={TextButton({error=null;if(duplicate||batch)pending?.let{val b=JSONObject(it.toString()).put(if(duplicate)"allow_duplicate" else "confirm_without_batch",true);save(b)}}){Text(if(duplicate||batch)"Confirm and save" else "OK")}},dismissButton={if(duplicate||batch)TextButton({error=null;pending=null}){Text("Cancel")}})
    }
}

@Composable
private fun OrdersList(ctx:OrderContext,onOpen:(String)->Unit,onCreate:()->Unit) {
    var search by rememberSaveable { mutableStateOf("") }
    var status by rememberSaveable { mutableStateOf("All") }
    var period by rememberSaveable { mutableStateOf("All Dates") }
    var from by rememberSaveable { mutableStateOf("") }
    var to by rememberSaveable { mutableStateOf("") }
    var party by rememberSaveable { mutableStateOf("All") }
    var factory by rememberSaveable { mutableStateOf("All") }
    var article by rememberSaveable { mutableStateOf("All") }
    var colour by rememberSaveable { mutableStateOf("All") }
    var material by rememberSaveable { mutableStateOf("All") }
    var dml by rememberSaveable { mutableStateOf("All") }
    var filters by rememberSaveable { mutableStateOf(false) }
    var page by rememberSaveable { mutableStateOf(0) }
    var pageSize by rememberSaveable { mutableStateOf("20") }
    LaunchedEffect(search,status,period,from,to,party,factory,article,colour,material,dml,pageSize){page=0}
    val range=if(period=="All Dates")null else runCatching{DashboardApi.range(period,from,to)}.getOrNull()
    val dateError=period!="All Dates"&&range==null
    val filtered=ctx.rows.filter { o ->
        val j=o.data
        val q=(listOf(o.id,o.group)+listOf("party","partyCode","factory","article","colour","material","dml","sole","box").map{j.optString(it)}).joinToString(" ")
        val day=runCatching{LocalDate.parse(j.optString("orderDate"),CDmy)}.getOrNull()
        q.contains(search,true) && (status=="All"||o.status==status) && (period=="All Dates"||!dateError&&day!=null&&day>=range!!.first&&day<=range.second) && listOf("party" to party,"factory" to factory,"article" to article,"colour" to colour,"material" to material,"dml" to dml).all{(k,v)->v=="All"||j.optString(k)==v}
    }.sortedWith(compareByDescending<OrderRow>{runCatching{LocalDate.parse(it.data.optString("orderDate"),CDmy)}.getOrNull()}.thenByDescending{it.id.toLongOrNull()?:0})
    val pages=maxOf(1,(filtered.size+pageSize.toInt()-1)/pageSize.toInt())
    LaunchedEffect(pages){if(page>=pages)page=pages-1}
    LazyColumn(Modifier.fillMaxSize().padding(horizontal=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(vertical=14.dp)) {
        item {Row(verticalAlignment=Alignment.CenterVertically){Column(Modifier.weight(1f)){Text("Master Orders",fontSize=24.sp,fontWeight=FontWeight.Bold,color=CNavy);Text("${filtered.size} lines • ${filtered.sumOf{it.data.optInt("totalPairs")}} pairs",fontSize=12.sp)};if(ctx.can("orders_add"))Button(onCreate){Icon(Icons.Default.Add,null);Text("New")}}}
        item {CField("Search order, party, article, factory…",search,{search=it})}
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){CPicker("Status",status,listOf("All","Pending","In Production","Ready","Hold","Dispatched","Cancelled"),{status=it},Modifier.weight(1f));OutlinedButton({filters=!filters},Modifier.heightIn(min=54.dp)){Icon(Icons.Default.FilterList,null);Text("Filters")}}}
        if(filters) item {CCard {CPicker("Period",period,listOf("All Dates","Today","Yesterday","Last 7 Days","This Month","Last Month","Last 30 Days","This Quarter","Financial Year","Custom"),{period=it});if(period=="Custom"){CField("From • YYYY-MM-DD",from,{from=it});CField("To • YYYY-MM-DD",to,{to=it})};if(dateError)Text("Enter a valid date range.",color=MaterialTheme.colorScheme.error);listOf("Party" to "party","Factory" to "factory","Article" to "article","Colour" to "colour","Material" to "material","D/M/L" to "dml").forEach{(label,key)->val value=when(key){"party"->party;"factory"->factory;"article"->article;"colour"->colour;"material"->material;else->dml};CPicker(label,value,listOf("All")+ctx.rows.map{it.data.optString(key)}.filter{it.isNotBlank()}.distinct().sorted(),{when(key){"party"->party=it;"factory"->factory=it;"article"->article=it;"colour"->colour=it;"material"->material=it;else->dml=it}})};TextButton({search="";status="All";period="All Dates";party="All";factory="All";article="All";colour="All";material="All";dml="All";from="";to=""}){Text("Reset filters")}}}
        if(!ctx.can("orders_view"))item{Text("Order viewing permission is not enabled. You can create orders if allowed.")}
        else if(filtered.isEmpty())item{CCard{Text("No orders match these filters.")}}
        items(filtered.drop(page*pageSize.toInt()).take(pageSize.toInt()),key={it.id}) {o->COrderCard(o){onOpen(o.id)}}
        item {Row(verticalAlignment=Alignment.CenterVertically){CPicker("Per page",pageSize,listOf("20","50","100"),{pageSize=it},Modifier.width(110.dp));Spacer(Modifier.weight(1f));IconButton({page--},enabled=page>0){Icon(Icons.Default.ChevronLeft,"Previous page")};Text("${page+1}/$pages");IconButton({page++},enabled=page+1<pages){Icon(Icons.Default.ChevronRight,"Next page")}}}
    }
}

@Composable
private fun COrderCard(o:OrderRow,onOpen:()->Unit) {
    Surface(Modifier.fillMaxWidth().clickable(onClick=onOpen),shape=RoundedCornerShape(18.dp),color=Color.White,shadowElevation=1.dp){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(5.dp)){
        Row{Text("Order ${o.group} • Line ${o.line}",fontWeight=FontWeight.Bold,color=CNavy,modifier=Modifier.weight(1f));Text(o.status,color=CBlue,fontSize=12.sp,fontWeight=FontWeight.Bold)}
        Text(o.data.optString("party"),fontWeight=FontWeight.SemiBold)
        Text("${o.data.optString("article")} • ${o.data.optString("colour")} • ${o.data.optString("factory")}",fontSize=12.sp)
        Text("${o.data.optInt("totalPairs")} pairs  •  ${o.data.optString("orderDate")}  •  ID #${o.id}",fontSize=12.sp,color=Color(0xFF64748B))
    }}
}

@Composable
private fun OrderDetail(o:OrderRow,ctx:OrderContext,onEdit:()->Unit,onStatus:(String)->Unit,onLine:(String)->Unit,saving:Boolean) {
    var tab by rememberSaveable(o.id){mutableStateOf("Overview")}
    val j=o.data
    val related=ctx.rows.filter{it.group==o.group}.sortedBy{it.line}
    LazyColumn(Modifier.fillMaxSize().padding(14.dp),contentPadding=PaddingValues(bottom=20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
        item{COrderCard(o){}}
        item{CPicker("View",tab,listOf("Overview","Order Lines","Production","Dispatch","Activity"),{tab=it})}
        when(tab){
            "Overview"->{item{CCard{listOf("party" to "Party","partyCode" to "Party code","factory" to "Factory","article" to "Article","colour" to "Colour","dml" to "D/M/L","material" to "Material","box" to "Box","sole" to "Sole","rate" to "Rate","particular" to "Remarks","deliveryDate" to "Delivery date").forEach{(k,lb)->CInfo(lb,j.optString(k))}}};item{CCard{Text("Size-wise pairs",fontWeight=FontWeight.Bold);(4..14).chunked(3).forEach{row->Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach{s->Column(Modifier.weight(1f).padding(6.dp)){Text("Size $s",fontSize=12.sp);Text(j.optJSONObject("sizes")?.optInt("sz$s",0).toString(),fontWeight=FontWeight.Bold)}}}};CInfo("Total pairs",j.optInt("totalPairs").toString())}}}
            "Order Lines"->{item{Text("${related.size} lines • ${related.sumOf{it.data.optInt("totalPairs")}} pairs",fontWeight=FontWeight.Bold)};items(related,key={it.id}){r->Box(Modifier.clickable{onLine(r.id)}){CCard{Text("Line ${r.line} • #${r.id}",fontWeight=FontWeight.Bold);Text("${r.data.optString("article")} • ${r.data.optString("colour")} • ${r.data.optInt("totalPairs")} pairs");Text(r.status)}}}}
            "Production"->item{CCard{CInfo("Status",o.status);CInfo("Production date",j.optString("prodDate"));CInfo("Ready date",j.optString("readyDate"));CInfo("Batch",j.optString("lastBatch"));CInfo("Printed",j.optString("printed"));CInfo("Hold date",j.optString("holdDate"))}}
            "Dispatch"->item{CCard{CInfo("Dispatch date",j.optString("dispatchDate"));CInfo("Updated",j.optString("dispatchDateChangedAt"));CInfo("Delivery date",j.optString("deliveryDate"))}}
            "Activity"->item{OrderActivity(ctx,o)}
        }
        if(ctx.can("orders_edit"))item{Button(onEdit,enabled=!saving,modifier=Modifier.fillMaxWidth()){Icon(Icons.Default.Edit,null);Spacer(Modifier.width(6.dp));Text("Edit this line")}}
        item{CCard{Text("Status actions",fontWeight=FontWeight.Bold);val st=j.optString("status","Pending");val actions=buildList{if(!j.optBoolean("hold")){if(st=="Pending"&&ctx.can("production_edit"))add("In Production");if(st=="In Production"&&ctx.can("production_approve"))add("Ready");if(st in listOf("In Production","Ready")&&ctx.can("dispatch_approve"))add("Dispatched")};if(ctx.can("orders_edit")&&st!="Cancelled"){if(st!="Dispatched")add(if(j.optBoolean("hold"))"Resume" else "Hold");add("Cancelled")}};actions.forEach{t->OutlinedButton({onStatus(t)},enabled=!saving,modifier=Modifier.fillMaxWidth()){Text(t)}};if(actions.isEmpty())Text("No permitted status actions for this line.",fontSize=12.sp)}}
    }
}

@Composable
private fun OrderActivity(ctx:OrderContext,o:OrderRow) {
    val logs=(0 until ctx.history.length()).mapNotNull{ctx.history.optJSONObject(it)}.filter{it.optString("record")==o.id}
    val events=(0 until ctx.events.length()).mapNotNull{ctx.events.optJSONObject(it)}.filter{it.optString("orderNo")==o.id}
    CCard{CInfo("Created",o.data.optString("createdAt"));CInfo("Last updated",o.data.optString("updatedAt",o.data.optString("lastEdited")));CInfo("Last edited by",o.data.optString("lastEditedBy"));events.take(30).forEach{e->CInfo(e.optString("action"),"${e.optString("date")} • ${e.optString("by")}")};logs.take(30).forEach{e->CInfo("${e.optString("action")} • ${e.optString("date")} • ${e.optString("by")}",e.optString("details"))};if(logs.isEmpty()&&events.isEmpty())Text("No recorded activity for this line.",fontSize=12.sp)}
}

@Composable
private fun OrderEditor(ctx:OrderContext,existing:OrderRow?,saving:Boolean,onCancel:()->Unit,onSave:(JSONArray,String,String,String)->Unit) {
    val key=existing?.id?:"new"
    val baseToken=rememberSaveable(key){existing?.token.orEmpty()}
    val baseEpoch=rememberSaveable(key){ctx.epoch}
    var step by rememberSaveable(key){mutableStateOf(0)}
    var lineIndex by rememberSaveable(key){mutableStateOf(0)}
    var requestId by rememberSaveable(key){mutableStateOf(UUID.randomUUID().toString())}
    var raw by rememberSaveable(key){mutableStateOf(JSONArray().put(existing?.data?.let{JSONObject(it.toString())}?:JSONObject().put("orderDate",LocalDate.now().format(CDmy)).put("sizes",JSONObject())).toString())}
    var validation by remember {mutableStateOf("")}
    val lines=JSONArray(raw)
    val line=lines.getJSONObject(lineIndex.coerceAtMost(lines.length()-1))
    val common=lines.getJSONObject(0)
    fun set(field:String,value:String,all:Boolean=false){val a=JSONArray(raw);if(all)(0 until a.length()).forEach{a.getJSONObject(it).put(field,value)} else a.getJSONObject(lineIndex).put(field,value);raw=a.toString();validation=""}
    fun setSize(size:Int,value:String){val a=JSONArray(raw);val l=a.getJSONObject(lineIndex);val sizes=l.optJSONObject("sizes")?:JSONObject();sizes.put("sz$size",value.ifBlank{"0"});l.put("sizes",sizes);raw=a.toString();validation=""}
    fun validateAll():String {
        for(i in 0 until lines.length()){
            val l=lines.getJSONObject(i)
            for(k in listOf("party","partyCode","factory","article","colour","box"))if(l.optString(k).isBlank())return "Line ${i+1}: $k is required."
            val d=runCatching{LocalDate.parse(l.optString("orderDate"),CDmy)}.getOrNull()?:return "Enter a valid order date (DD-MM-YYYY)."
            if(l.optString("deliveryDate").isNotBlank()){val x=runCatching{LocalDate.parse(l.optString("deliveryDate"),CDmy)}.getOrNull()?:return "Line ${i+1}: delivery date is invalid.";if(x<d)return "Delivery date cannot precede order date."}
            var total=0L
            for(s in 4..14){val text=l.optJSONObject("sizes")?.optString("sz$s","0")?:"0";if(!text.matches(Regex("\\d+")))return "Line ${i+1}, size $s: use whole quantities.";val n=text.toLongOrNull()?:return "Size quantity is too large.";if(n>1000000)return "Size quantity is too large.";total+=n}
            if(total==0L)return "Line ${i+1}: enter at least one pair."
            val rate=l.optString("rate","0").ifBlank{"0"}.toDoubleOrNull();if(rate==null||!rate.isFinite()||rate<0)return "Line ${i+1}: rate must be non-negative."
        }
        return ""
    }
    BackHandler(enabled=!saving){if(step>0)step-- else onCancel()}
    Column(Modifier.fillMaxSize()){
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text("${step+1}/5 • ${listOf("Party","Product","Sizes","Delivery","Review")[step]}",fontSize=23.sp,fontWeight=FontWeight.Bold,color=CNavy)
            LinearProgressIndicator(progress={(step+1)/5f},modifier=Modifier.fillMaxWidth())
            if(step in 1..3){CPicker("Order line",(lineIndex+1).toString(),(1..lines.length()).map{it.toString()},{lineIndex=it.toInt()-1});if(existing==null&&step==1){Row{OutlinedButton({val a=JSONArray(raw);val n=JSONObject();listOf("party","partyCode","factory","orderDate").forEach{n.put(it,common.optString(it))};n.put("sizes",JSONObject());a.put(n);raw=a.toString();lineIndex=a.length()-1},enabled=lines.length()<50&&!saving){Icon(Icons.Default.Add,null);Text("Add line")};if(lines.length()>1)TextButton({val a=JSONArray(raw);a.remove(lineIndex);raw=a.toString();lineIndex=0},enabled=!saving){Text("Remove line")}}}}
            when(step){
                0->CCard{CPicker("Party *",common.optString("party"),ctx.choices("party"),{v->set("party",v,true);val a=ctx.masters.optJSONArray("party");val code=if(a==null)"" else (0 until a.length()).mapNotNull{a.optJSONObject(it)}.find{it.optString("name")==v}?.optString("code").orEmpty();set("partyCode",code,true)});CField("Party code *",common.optString("partyCode"),{set("partyCode",it,true)});CPicker("Factory *",common.optString("factory"),ctx.choices("factory"),{set("factory",it,true)});CField("Order date * • DD-MM-YYYY",common.optString("orderDate"),{set("orderDate",it,true)});Text("Party, factory and order date are common to all lines.",fontSize=12.sp)}
                1->CCard{listOf("Article *" to "article","Colour *" to "colour","D/M/L" to "dml","Material" to "material","Box *" to "box","Sole" to "sole").forEach{(label,field)->CPicker(label,line.optString(field),ctx.choices(if(field=="article")"art" else field),{set(field,it)},allowClear=field in listOf("dml","material","sole"))};CField("Rate per pair",line.optString("rate"),{set("rate",it)},KeyboardType.Decimal);CField("Remarks",line.optString("particular"),{set("particular",it)})}
                2->CCard{Text("Size-wise quantity",fontWeight=FontWeight.Bold);(4..14).chunked(3).forEach{sizes->Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){sizes.forEach{s->CField("Size $s",line.optJSONObject("sizes")?.optString("sz$s").orEmpty().let{if(it=="0")"" else it},{setSize(s,it)},KeyboardType.Number,Modifier.weight(1f))}}};Text("Total: ${(4..14).sumOf{line.optJSONObject("sizes")?.optString("sz$it","0")?.toLongOrNull()?:0L}} pairs",fontWeight=FontWeight.Bold,color=CBlue)}
                3->CCard{CField("Delivery date • DD-MM-YYYY (optional)",line.optString("deliveryDate"),{set("deliveryDate",it)});Text("New orders are saved as Pending. Production, Ready and Dispatch are separate permission-controlled actions.",fontSize=13.sp)}
                else->{for(i in 0 until lines.length()){val l=lines.getJSONObject(i);CCard{Text("Line ${i+1}",fontWeight=FontWeight.Bold);Text("${l.optString("party")} • ${l.optString("factory")}");Text("${l.optString("article")} • ${l.optString("colour")} • ${l.optString("material")}");Text("Box ${l.optString("box")} • Sole ${l.optString("sole")}");Text("${(4..14).sumOf{l.optJSONObject("sizes")?.optString("sz$it","0")?.toLongOrNull()?:0L}} pairs • ₹${l.optString("rate","0").ifBlank{"0"}} / pair");Text("Order: ${l.optString("orderDate")} • Delivery: ${l.optString("deliveryDate").ifBlank{"—"}}",fontSize=12.sp);Text(l.optString("particular"),fontSize=12.sp)}}}
            }
            if(validation.isNotBlank())Text(validation,color=MaterialTheme.colorScheme.error)
        }
        Surface(shadowElevation=4.dp){Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){
            OutlinedButton({if(step>0)step-- else onCancel()},enabled=!saving,modifier=Modifier.weight(1f)){Text(if(step>0)"Back" else "Cancel")}
            Button({if(step<4)step++ else {validation=validateAll();if(validation.isBlank())onSave(JSONArray(raw),requestId,baseToken,baseEpoch)}},enabled=!saving,modifier=Modifier.weight(1f)){Text(if(saving)"Saving…" else if(step<4)"Next" else "Save on server")}
        }}
    }
}

@Composable
private fun CCard(content:@Composable ColumnScope.()->Unit){Surface(Modifier.fillMaxWidth(),shape=RoundedCornerShape(18.dp),color=Color.White){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp),content=content)}}
@Composable
private fun CInfo(label:String,value:String){Column{Text(label,color=Color(0xFF64748B),fontSize=12.sp);Text(value.ifBlank{"—"},fontWeight=FontWeight.SemiBold,color=CNavy)}}
@Composable
private fun CField(label:String,value:String,onChange:(String)->Unit,type:KeyboardType=KeyboardType.Text,modifier:Modifier=Modifier.fillMaxWidth()){OutlinedTextField(value,onChange,label={Text(label)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=type),modifier=modifier)}
@Composable
private fun CPicker(label:String,value:String,choices:List<String>,onSelect:(String)->Unit,modifier:Modifier=Modifier.fillMaxWidth(),allowClear:Boolean=false){
    var open by remember{mutableStateOf(false)}
    var query by remember{mutableStateOf("")}
    OutlinedButton({query="";open=true},modifier=modifier.heightIn(min=54.dp)){Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start){Text(label,fontSize=10.sp,color=Color(0xFF64748B));Text(value.ifBlank{"Select…"},fontSize=13.sp)};Icon(Icons.Default.ExpandMore,null)}
    if(open)Dialog(onDismissRequest={open=false}){Surface(shape=RoundedCornerShape(20.dp),color=Color.White){Column(Modifier.fillMaxWidth().heightIn(max=560.dp).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(label,fontSize=20.sp,fontWeight=FontWeight.Bold);CField("Search",query,{query=it});LazyColumn(Modifier.weight(1f,false)){if(allowClear)item{Text("Clear selection",Modifier.fillMaxWidth().clickable{onSelect("");open=false}.padding(16.dp),color=CBlue)};val matches=choices.filter{it.contains(query,true)};items(matches){choice->Text(choice,Modifier.fillMaxWidth().clickable{onSelect(choice);open=false}.padding(vertical=16.dp),fontWeight=FontWeight.SemiBold)};if(matches.isEmpty())item{Text("No matching master. Add it through Masters on the OMS server.",Modifier.padding(12.dp))}};TextButton({open=false},modifier=Modifier.align(Alignment.End)){Text("Close")}}}}
}
