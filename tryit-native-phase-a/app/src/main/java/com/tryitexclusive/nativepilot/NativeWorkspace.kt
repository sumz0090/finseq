package com.tryitexclusive.nativepilot

import android.net.Uri
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.util.UUID

object SessionEvents { val expired=MutableSharedFlow<Unit>(extraBufferCapacity=1) }
private val WNavy=Color(0xFF0B1830)
private val WSoft=Color(0xFFF4F7FB)
private val WDmy=DateTimeFormatter.ofPattern("dd-MM-uuuu").withResolverStyle(java.time.format.ResolverStyle.STRICT)
private val WCats=linkedMapOf("party" to "Party","factory" to "Factory","art" to "Article","colour" to "Colour","dml" to "D/M/L","material" to "Material","box" to "Box","sole" to "Sole","soleVendor" to "Sole Vendor")
fun JSONArray.objects():List<JSONObject> = (0 until length()).mapNotNull{optJSONObject(it)}
private fun JSONObject.array(key:String)=optJSONArray(key)?:JSONArray()
private fun JSONObject.strings():List<String> = keys().asSequence().toList()
private fun JSONObject.can(p:String)=optJSONObject("permissions")?.optBoolean(p)==true
private fun wToday()=LocalDate.now().format(WDmy)
private fun wQuery(options:Map<String,String>)=options.filterValues{it.isNotBlank()}.entries.joinToString("&"){URLEncoder.encode(it.key,"UTF-8")+"="+URLEncoder.encode(it.value,"UTF-8")}

object WorkspaceApi {
    fun context(secure:SecureStore,module:String)=OrdersApi.request(secure,"/api/mobile/workspace/context?module=$module")
    fun save(secure:SecureStore,body:JSONObject)=OrdersApi.request(secure,"/api/mobile/workspace/save",body)
    fun export(secure:SecureStore,options:Map<String,String>):Pair<ByteArray?,String> = try {
        val c=URL(normalizeServer(secure.get("server_url"))+"/api/mobile/workspace/export?"+wQuery(options)).openConnection() as HttpURLConnection
        try{c.connectTimeout=12000;c.readTimeout=20000;c.instanceFollowRedirects=false;c.setRequestProperty("Cookie",secure.get("session_cookie"));val code=c.responseCode
            if(code==401)SessionEvents.expired.tryEmit(Unit)
            if(code==200&&c.contentType.orEmpty().startsWith("text/csv"))c.inputStream.use{it.readBytes()} to "" else null to "Export was not confirmed by server ($code)."
        }finally{c.disconnect()}
    } catch(e:Exception){null to "Unable to export. ${e.message.orEmpty()}"}
}

@Composable
fun NativeWorkspace(secure:SecureStore,onHome:()->Unit,onOrders:(String)->Unit,initialModule:String="hub") {
    var module by rememberSaveable{mutableStateOf(initialModule)}
    var context by remember{mutableStateOf<JSONObject?>(null)}
    var loading by remember{mutableStateOf(false)}
    var saving by remember{mutableStateOf(false)}
    var loadError by remember{mutableStateOf("")}
    var message by remember{mutableStateOf("")}
    var responseError by remember{mutableStateOf<OrderResponse?>(null)}
    var editor by rememberSaveable{mutableStateOf("")}
    var editRaw by rememberSaveable{mutableStateOf("{}")}
    var editToken by rememberSaveable{mutableStateOf("")}
    var editEpoch by rememberSaveable{mutableStateOf("")}
    var pending by remember{mutableStateOf<JSONObject?>(null)}
    var confirm by remember{mutableStateOf<Pair<String,JSONObject>?>(null)}
    val scope=rememberCoroutineScope()
    fun refresh(){loading=true;loadError="";scope.launch{val r=withContext(Dispatchers.IO){WorkspaceApi.context(secure,module)};loading=false;if(r.error.isBlank()){context=r.json}else{context=null;loadError=r.error}}}
    fun send(body:JSONObject,admin:Boolean=false){if(saving)return;saving=true;pending=body;scope.launch{val r=withContext(Dispatchers.IO){if(admin)OrdersApi.request(secure,"/api/mobile/workspace/admin/save",body)else WorkspaceApi.save(secure,body)};saving=false;if(r.error.isBlank()){editor="";pending=null;confirm=null;message="Saved on server";refresh()}else responseError=r}}
    fun openEditor(kind:String,data:JSONObject=JSONObject(),token:String=""){editor=kind;editRaw=if(kind=="receive")JSONObject(data.toString()).put("sizes",JSONObject()).toString() else data.toString();editToken=token;editEpoch=context?.optString("reset_epoch").orEmpty()}
    LaunchedEffect(module){context=null;refresh()}
    BackHandler{if(!saving){if(editor.isNotBlank())editor="" else if(module!="hub")module="hub" else onHome()}}
    Column(Modifier.fillMaxSize().background(WSoft).statusBarsPadding().navigationBarsPadding()) {
        Surface(color=WNavy){Row(Modifier.fillMaxWidth().heightIn(min=64.dp).padding(horizontal=8.dp),verticalAlignment=Alignment.CenterVertically){IconButton({if(!saving){if(editor.isNotBlank())editor="" else if(module!="hub")module="hub" else onHome()}}){Icon(Icons.Default.ArrowBack,"Back",tint=Color.White)};Column(Modifier.weight(1f)){Text(if(editor.isNotBlank())"${if(editToken.isBlank())"Create" else "Edit"} ${editor.replace('_',' ')}" else mapOf("hub" to "Modules","masters" to "Masters","production" to "Production","dispatch" to "Dispatch","stock" to "Stock & Purchases","business" to "Party Dashboard & Ledger","reports" to "Reports","admin" to "Administration","audit" to "Activity & Audit")[module].orEmpty(),color=Color.White,fontSize=19.sp,fontWeight=FontWeight.Bold);Text("TRYIT • All native phases",color=Color(0xFFCBD5E1),fontSize=11.sp)};if(editor.isBlank())IconButton({if(!saving)refresh()},enabled=!loading){Icon(Icons.Default.Refresh,"Refresh",tint=Color.White)}}}
        if(loading||saving)LinearProgressIndicator(Modifier.fillMaxWidth())
        if(message.isNotBlank())Row(Modifier.background(Color(0xFFE8F5ED)).padding(10.dp)){Text(message,Modifier.weight(1f));TextButton({message=""}){Text("Dismiss")}}
        if(loadError.isNotBlank())Column(Modifier.padding(18.dp)){Text(loadError,color=MaterialTheme.colorScheme.error);Button({refresh()}){Text("Retry")}}
        context?.let{ctx ->
            if(editor.isNotBlank())WorkspaceEditor(editor,JSONObject(editRaw),editToken,editEpoch,ctx,saving,onCancel={editor=""},onSave={body->send(body,editor=="user")})
            else when(module){
                "hub"->WorkspaceHub(ctx,onOpen={module=it},onOrders={onOrders("")})
                "masters"->MastersPane(ctx,onEdit={cat,row,tok->openEditor("master",JSONObject(row.toString()).put("category",cat),tok)},onDelete={cat,row,tok->confirm="Delete unused ${row.optString("name")}?" to JSONObject().put("action","master_delete").put("category",cat).put("old_name",row.optString("name")).put("expected_token",tok).put("reset_epoch",ctx.optString("reset_epoch")).put("request_id",UUID.randomUUID().toString())})
                "production","dispatch"->OperationsPane(ctx,module,onOrders)
                "business","reports"->BusinessPane(secure,ctx,module,onOrders,onMessage={message=it})
                "stock"->StockPane(ctx,onEdit={kind,row,tok->openEditor(kind,row,tok)})
                "admin"->AdministrationPane(ctx,onEdit={row,tok->openEditor("user",row,tok)},onAction={title,body->confirm=title to JSONObject().put("route","sessions").put("fields",body)})
                "audit"->ActivityPane(ctx)
            }
        }
    }
    confirm?.let{(title,body)->AlertDialog(onDismissRequest={if(!saving)confirm=null},title={Text("Confirm action")},text={Text(title)},confirmButton={Button({send(body,body.has("route"))},enabled=!saving){Text("Confirm")}},dismissButton={TextButton({confirm=null},enabled=!saving){Text("Cancel")}})}
    responseError?.let{r->val excess=r.json?.optBoolean("requires_excess_confirmation")==true;AlertDialog(onDismissRequest={responseError=null},title={Text(if(excess)"Confirm extra receipt" else "Change not saved")},text={Text(r.error)},confirmButton={TextButton({responseError=null;if(excess)pending?.let{send(JSONObject(it.toString()).put("confirm_excess",true))}}){Text(if(excess)"Confirm and save" else "OK")}},dismissButton={if(excess)TextButton({responseError=null;pending=null}){Text("Cancel")}})}
}

@Composable private fun WorkspaceHub(ctx:JSONObject,onOpen:(String)->Unit,onOrders:()->Unit){
    val modules=listOf("masters" to "Masters","production" to "Production","dispatch" to "Dispatch","stock" to "Stock & Purchases","business" to "Party Dashboard & Ledger","reports" to "Reports","admin" to "Users, Permissions & Devices","audit" to "Activity & Audit")
    LazyColumn(Modifier.fillMaxSize().padding(14.dp),verticalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(bottom=24.dp)){
        item{WCard{Text("Your workspace",fontSize=25.sp,fontWeight=FontWeight.Bold,color=WNavy);Text("Live data • role-based access • server-confirmed changes")}}
        if(listOf("orders_view","production_view","dispatch_view","accounts_view","reports_view").any{ctx.can(it)})item{Button(onOrders,modifier=Modifier.fillMaxWidth()){Text("Orders")}}
        modules.forEach{(key,title)->val allowed=when(key){"business"->ctx.can("accounts_view");"admin"->ctx.optBoolean("is_admin");"audit"->ctx.optBoolean("is_admin")||ctx.can("reports_view");else->ctx.can(key+"_view")||if(key in listOf("masters","stock"))ctx.can(key+"_edit")||ctx.can(key+"_add") else false};if(allowed)item{Surface(Modifier.fillMaxWidth().clickable{onOpen(key)},shape=RoundedCornerShape(18.dp),color=Color.White){Row(Modifier.padding(20.dp),verticalAlignment=Alignment.CenterVertically){Icon(when(key){"masters"->Icons.Default.Folder;"stock"->Icons.Default.Inventory2;"admin"->Icons.Default.ManageAccounts;"reports"->Icons.Default.Assessment;else->Icons.Default.Business},null,tint=WNavy);Spacer(Modifier.width(12.dp));Text(title,Modifier.weight(1f),fontWeight=FontWeight.SemiBold);Icon(Icons.Default.ChevronRight,null)}}}}
        if(modules.none{(k,_)->ctx.can((if(k=="business")"accounts"else k)+"_view")}&&!ctx.optBoolean("is_admin"))item{Text("Only modules enabled by your administrator are shown.")}
    }
}

@Composable private fun MastersPane(ctx:JSONObject,onEdit:(String,JSONObject,String)->Unit,onDelete:(String,JSONObject,String)->Unit){
    var category by rememberSaveable{mutableStateOf("party")};var search by rememberSaveable{mutableStateOf("")};var selected by rememberSaveable{mutableStateOf("")}
    val rows=ctx.optJSONObject("masters")?.array(category)?.objects().orEmpty()
    val row=rows.find{it.optJSONObject("data")?.optString("name")==selected}
    LazyColumn(Modifier.fillMaxSize().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=24.dp)){
        item{WPicker("Category",WCats[category].orEmpty(),WCats.values.toList(),{category=WCats.entries.find{x->x.value==it}!!.key;selected="";search=""})}
        if(row!=null){val m=row.optJSONObject("data")?:JSONObject();item{WCard{Text("${WCats[category]} profile",fontSize=22.sp,fontWeight=FontWeight.Bold);listOf("name","code","rating","phone","email","address","type","status","notes").forEach{if(m.optString(it).isNotBlank())WInfo(it.replaceFirstChar{c->c.uppercase()},m.optString(it))};if(ctx.can("masters_edit"))Button({onEdit(category,m,row.optString("token"))},modifier=Modifier.fillMaxWidth()){Text("Edit profile")};if(ctx.can("masters_delete"))OutlinedButton({onDelete(category,m,row.optString("token"))},modifier=Modifier.fillMaxWidth()){Text("Delete if unused")};TextButton({selected=""}){Text("Back to list")}}}}
        else{
            item{WField("Search name, code, phone…",search,{search=it})}
            if(ctx.can("masters_add"))item{Button({onEdit(category,JSONObject(),"")},modifier=Modifier.fillMaxWidth()){Text("Create ${WCats[category]}")}}
            val filtered=rows.filter{it.optJSONObject("data").toString().contains(search,true)}
            item{Text("${filtered.size} profiles",fontWeight=FontWeight.Bold)}
            items(filtered){w->val m=w.optJSONObject("data")?:JSONObject();WCard(onClick={selected=m.optString("name")}){Text(m.optString("name"),fontWeight=FontWeight.Bold);Text(listOf("code","phone","email").map{m.optString(it)}.filter{it.isNotBlank()}.joinToString(" • "),fontSize=12.sp);Text(m.optString("status","active"),fontSize=12.sp)}}
        }
    }
}

@Composable private fun OperationsPane(ctx:JSONObject,module:String,onOrder:(String)->Unit){
    var search by rememberSaveable{mutableStateOf("")};var stage by rememberSaveable(module){mutableStateOf(if(module=="production")"In Production" else "Ready")};var party by rememberSaveable{mutableStateOf("All")};var factory by rememberSaveable{mutableStateOf("All")};var view by rememberSaveable{mutableStateOf("Orders")}
    val rows=ctx.array("orders").objects().mapNotNull{it.optJSONObject("data")}
    val filtered=rows.filter{(stage=="All"||if(stage=="Hold")it.optBoolean("hold")else !it.optBoolean("hold")&&it.optString("status")==stage)&&it.toString().contains(search,true)&&(party=="All"||it.optString("party")==party)&&(factory=="All"||it.optString("factory")==factory)}
    LazyColumn(Modifier.fillMaxSize().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=20.dp)){
        if(module=="production")item{WPicker("View",view,listOf("Orders","Batches"),{view=it})}
        if(view=="Batches"&&module=="production"){
            val batches=ctx.array("batches").objects();item{Text("${batches.size} production print records")};items(batches){b->WCard{b.strings().filter{b.opt(it) !is JSONArray&&b.opt(it) !is JSONObject}.take(12).forEach{WInfo(it,b.optString(it))}}};if(batches.isEmpty())item{Text("No saved production print batches.")}
        }else{
            item{WField("Search order / party / article",search,{search=it});WPicker("Stage",stage,listOf("All","Pending","In Production","Ready","Hold","Dispatched","Cancelled"),{stage=it})}
            item{WPicker("Party",party,listOf("All")+rows.map{it.optString("party")}.distinct().sorted(),{party=it});WPicker("Factory",factory,listOf("All")+rows.map{it.optString("factory")}.distinct().sorted(),{factory=it})}
            item{WCard{Text("${filtered.size} lines • ${filtered.sumOf{it.optInt("totalPairs")}} pairs",fontWeight=FontWeight.Bold);Text("Open a line to review dates, sizes, history and permitted status actions.",fontSize=12.sp)}}
            items(filtered,key={it.optString("orderNo")}){WOrder(it){onOrder(it.optString("orderNo"))}}
            if(filtered.isEmpty())item{Text("No orders match this stage.")}
        }
    }
}

@Composable private fun StockPane(ctx:JSONObject,onEdit:(String,JSONObject,String)->Unit){
    var view by rememberSaveable{mutableStateOf("Soles")};var search by rememberSaveable{mutableStateOf("")};var selected by rememberSaveable{mutableStateOf("")};var pendingOnly by rememberSaveable{mutableStateOf(true)}
    LazyColumn(Modifier.fillMaxSize().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=24.dp)){
        item{WPicker("Stock view",view,listOf("Soles","Boxes","Purchase Orders","Purchase History"),{view=it;selected=""});WField("Search",search,{search=it})}
        when(view){
            "Soles"->{val soles=ctx.array("soles").objects().filter{it.optString("name").contains(search,true)};items(soles){s->val totals=s.optJSONObject("totals")?:JSONObject();WCard(onClick={selected=if(selected==s.optString("name"))"" else s.optString("name")}){Text(s.optString("name"),fontSize=20.sp,fontWeight=FontWeight.Bold);Text("Stock ${totals.optInt("balance")} • PO ${totals.optInt("po")} • To order ${totals.optInt("needOrder")}");if(selected==s.optString("name")){listOf("opening","purchase","consumed","pending","po","balance","projected","needOrder").forEach{WInfo(it,totals.optString(it))};(4..14).forEach{i->val sz=s.optJSONObject("sizes")?.optJSONObject("sz$i")?:JSONObject();Text("Size $i: Balance ${sz.optInt("balance")} | PO ${sz.optInt("po")} | Need ${sz.optInt("needOrder")}",fontSize=12.sp)};if(ctx.can("stock_edit"))Button({onEdit("sole",JSONObject(s.optJSONObject("data").toString()).put("name",s.optString("name")),s.optString("token"))}){Text("Edit size-wise stock")}}}}}
            "Boxes"->items(ctx.array("boxes").objects().filter{it.toString().contains(search,true)}){w->val b=w.optJSONObject("data")?:JSONObject();WCard{Text(b.optString("name"),fontSize=20.sp,fontWeight=FontWeight.Bold);Text("Required ${w.optInt("required")} • Stock ${b.optInt("stock")} • Ordered ${b.optInt("ordered")} • Short ${w.optInt("shortage")}");if(ctx.can("stock_edit"))OutlinedButton({onEdit("box",b,w.optString("token"))}){Text("Edit stock")}}}
            "Purchase Orders"->{if(ctx.can("stock_edit"))item{Button({onEdit("po",JSONObject(),"")},modifier=Modifier.fillMaxWidth()){Text("Create Purchase Order")}};item{Row(verticalAlignment=Alignment.CenterVertically){Checkbox(pendingOnly,{pendingOnly=it});Text("Pending only")}};val pos=ctx.array("pos").objects().filter{it.toString().contains(search,true)&&(!pendingOnly||it.optJSONObject("remaining")?.let{r->r.strings().sumOf{k->r.optInt(k)}}!=0)};items(pos){w->val p=w.optJSONObject("data")?:JSONObject();val remain=w.optJSONObject("remaining")?:JSONObject();WCard{Text("${p.optString("sole")} • ${p.optString("party")}",fontWeight=FontWeight.Bold);Text("${p.optString("date")} • Ordered ${p.optInt("totalQty")} • Received ${p.optInt("receivedQty")} • Pending ${remain.strings().sumOf{remain.optInt(it)}}");Text("Rate ₹${p.optString("rate")} • ${p.optString("note")}",fontSize=12.sp);if(ctx.can("stock_edit"))Button({onEdit("receive",JSONObject(p.toString()).put("remaining",remain),w.optString("token"))}){Text("Receive size-wise")}}}}
            else->items(ctx.array("purchases").objects().filter{it.toString().contains(search,true)}){w->val p=w.optJSONObject("data")?:JSONObject();WCard{Text("${p.optString("date")} • ${p.optString("sole")}",fontWeight=FontWeight.Bold);Text("${p.optInt("totalQty")} pairs → ${p.optString("dest","purchase")} • ${p.optString("party")}");Text("${p.optString("source")} • ₹${p.optString("rate")} • ${p.optString("note")}",fontSize=12.sp);WSizeInfo(p.optJSONObject("sizes")?:JSONObject());if(ctx.can("stock_edit"))OutlinedButton({onEdit("purchase",p,w.optString("token"))}){Text("Edit vendor / rate / note")}}}
        }
    }
}

@Composable private fun BusinessPane(secure:SecureStore,ctx:JSONObject,module:String,onOrder:(String)->Unit,onMessage:(String)->Unit){
    var party by rememberSaveable{mutableStateOf("All")};var search by rememberSaveable{mutableStateOf("")};var status by rememberSaveable{mutableStateOf("All")};var period by rememberSaveable{mutableStateOf("All Dates")};var from by rememberSaveable{mutableStateOf("")};var to by rememberSaveable{mutableStateOf("")};var view by rememberSaveable{mutableStateOf(if(module=="business")"Party Dashboard"else "Status")};var page by rememberSaveable{mutableStateOf(0)};var pageSize by rememberSaveable{mutableStateOf("20")};var exporting by remember{mutableStateOf(false)};var exportOptions by remember{mutableStateOf<Map<String,String>>(emptyMap())};var drillKey by rememberSaveable{mutableStateOf("")};var drillValue by rememberSaveable{mutableStateOf("")}
    val local=LocalContext.current;val scope=rememberCoroutineScope()
    val exporter=rememberLauncherForActivityResult(ActivityResultContracts.CreateDocument("text/csv")){uri:Uri?->if(uri!=null){exporting=true;scope.launch{val result=withContext(Dispatchers.IO){WorkspaceApi.export(secure,exportOptions)};val bytes=result.first;val error=if(bytes!=null)withContext(Dispatchers.IO){runCatching{local.contentResolver.openOutputStream(uri,"wt")?.use{it.write(bytes)}?:error("Unable to open selected file.")}.exceptionOrNull()?.message.orEmpty()}else result.second;exporting=false;onMessage(if(error.isBlank())"Report saved" else error)}}}
    val rows=ctx.array("orders").objects().mapNotNull{it.optJSONObject("data")}
    val range=if(period=="All Dates")null else runCatching{DashboardApi.range(period,from,to)}.getOrNull()
    val valid=period=="All Dates"||range!=null
    val filtered=rows.filter{o->val d=runCatching{LocalDate.parse(o.optString("orderDate"),WDmy)}.getOrNull();valid&&(drillKey.isBlank()||o.optString(drillKey)==drillValue)&&(party=="All"||o.optString("party")==party)&&(status=="All"||(if(o.optBoolean("hold"))"Hold" else o.optString("status"))==status)&&listOf("orderNo","groupOrderNo","party","factory","article","colour","sole","box").joinToString(" "){o.optString(it)}.contains(search,true)&&(range==null||d!=null&&d>=range.first&&d<=range.second)}.sortedByDescending{it.optLong("orderNo")}
    val pages=maxOf(1,(filtered.size+pageSize.toInt()-1)/pageSize.toInt())
    LaunchedEffect(party,search,status,period,from,to,pageSize,drillKey,drillValue){page=0}
    LaunchedEffect(pages){if(page>=pages)page=pages-1}
    LazyColumn(Modifier.fillMaxSize().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=24.dp)){
        item{WPicker("View",view,if(module=="business")listOf("Party Dashboard","Ledger")else listOf("Status","Party","Factory","Article","Colour","Daily","Order Lines"),{view=it});WPicker("Party",party,listOf("All")+rows.map{it.optString("party")}.filter{it.isNotBlank()}.distinct().sorted(),{party=it});WField("Search",search,{search=it})}
        item{WPicker("Period",period,listOf("All Dates","Today","Yesterday","Last 7 Days","This Month","Last Month","Last 30 Days","This Quarter","Financial Year","Custom"),{period=it});if(period=="Custom"){WField("From • YYYY-MM-DD",from,{from=it});WField("To • YYYY-MM-DD",to,{to=it})};WPicker("Status",status,listOf("All","Pending","In Production","Ready","Hold","Dispatched","Cancelled"),{status=it});if(!valid)Text("Enter a valid date range.",color=MaterialTheme.colorScheme.error)}
        if(drillKey.isNotBlank())item{OutlinedButton({drillKey="";drillValue=""}){Text("Clear $drillKey: $drillValue")}}
        item{WCard{Text("${filtered.map{it.optString("groupOrderNo").ifBlank{it.optString("orderNo")}}.distinct().size} orders • ${filtered.size} lines • ${filtered.sumOf{it.optInt("totalPairs")}} pairs",fontWeight=FontWeight.Bold);if(module=="business")Text("Order lifecycle ledger",fontSize=12.sp);if(party!="All"){ctx.array("parties").objects().find{it.optString("name")==party}?.let{p->listOf("code","phone","email","address").forEach{k->if(p.optString(k).isNotBlank())WInfo(k,p.optString(k))}}};if(ctx.can("reports_export"))OutlinedButton({exportOptions=mapOf("module" to module,"party" to party.takeUnless{it=="All"}.orEmpty(),"status" to status.takeUnless{it=="All"}.orEmpty(),"search" to search,"from" to range?.first?.toString().orEmpty(),"to" to range?.second?.toString().orEmpty())+if(drillKey.isBlank())emptyMap()else mapOf(drillKey to drillValue);exporter.launch("TRYIT_${if(module=="business")"Party_Ledger"else "Order_Report"}_${LocalDate.now()}.csv")},enabled=valid&&!exporting){Text(if(exporting)"Exporting…"else "Save order lines as CSV")}}}
        if(view !in listOf("Ledger","Order Lines")){
            val group=mapOf("Party Dashboard" to "status","Status" to "status","Party" to "party","Factory" to "factory","Article" to "article","Colour" to "colour","Daily" to "orderDate")[view]?:"status"
            val grouped=filtered.groupBy{if(group=="status"&&it.optBoolean("hold"))"Hold"else it.optString(group).ifBlank{"Unspecified"}}.entries.sortedByDescending{it.value.sumOf{o->o.optInt("totalPairs")}}
            items(grouped){(name,os)->WCard(onClick={if(group=="status"){status=name;view=if(module=="business")"Ledger"else "Order Lines"}else if(group=="party"){party=name;view="Order Lines"}else{drillKey=group;drillValue=name;view="Order Lines"}}){Text(name,fontWeight=FontWeight.Bold);Text("${os.size} lines • ${os.sumOf{it.optInt("totalPairs")}} pairs")}}
        }else{
            items(filtered.drop(page*pageSize.toInt()).take(pageSize.toInt()),key={it.optString("orderNo")}){o->WOrder(o){onOrder(o.optString("orderNo"))}}
            item{Row(verticalAlignment=Alignment.CenterVertically){WPicker("Per page",pageSize,listOf("20","50","100"),{pageSize=it},Modifier.width(110.dp));Spacer(Modifier.weight(1f));IconButton({page--},enabled=page>0){Icon(Icons.Default.ChevronLeft,"Previous")};Text("${page+1}/$pages");IconButton({page++},enabled=page+1<pages){Icon(Icons.Default.ChevronRight,"Next")}}}
        }
        if(filtered.isEmpty())item{Text("No records match these filters.")}
    }
}

@Composable private fun AdministrationPane(ctx:JSONObject,onEdit:(JSONObject,String)->Unit,onAction:(String,JSONObject)->Unit){
    var tab by rememberSaveable{mutableStateOf("Users")};var search by rememberSaveable{mutableStateOf("")};var selected by rememberSaveable{mutableStateOf("")}
    val users=ctx.array("users").objects()
    LazyColumn(Modifier.fillMaxSize().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=24.dp)){
        item{WPicker("Administration",tab,listOf("Users","Sessions","Devices"),{tab=it});WField("Search user / device / IP",search,{search=it})}
        when(tab){
            "Users"->{item{Button({onEdit(JSONObject(),"")},modifier=Modifier.fillMaxWidth()){Text("Create user")}};items(users.filter{it.optJSONObject("data").toString().contains(search,true)}){w->val u=w.optJSONObject("data")?:JSONObject();WCard{Text("${u.optString("full_name")} • ${u.optString("username")}",fontWeight=FontWeight.Bold);Text("${u.optString("role")} • ${u.optString("designation")} • ${if(u.optBoolean("active"))"Active"else "Inactive"}");Text("${u.optString("email")} • ${u.optString("mobile")}",fontSize=12.sp);Button({onEdit(u,w.optString("token"))}){Text("Profile & permissions")};OutlinedButton({onAction("Log out all sessions for ${u.optString("username")}?",JSONObject().put("action","logout_user").put("username",u.optString("username")))}){Text("Log out user sessions")}}}}
            "Sessions"->{item{OutlinedButton({onAction("Log out other sessions? Your current session will remain active.",JSONObject().put("action","logout_all").put("include_admin",false))},modifier=Modifier.fillMaxWidth()){Text("Log out other sessions")}};items(ctx.array("sessions").objects().filter{it.toString().contains(search,true)}){s->WCard{Text("${s.optString("username")} • ${s.optString("device_name")}",fontWeight=FontWeight.Bold);Text("IP ${s.optString("ip")} • ${s.optString("login_at")}",fontSize=12.sp);Text(if(s.optBoolean("current"))"Current session"else s.optString("session_id"),fontSize=12.sp);if(!s.optBoolean("current"))Button({onAction("End this session?",JSONObject().put("action","logout_session").put("session_id",s.optString("session_id")))}){Text("Force logout")}}}}
            else->{item{WPicker("User",selected,users.map{it.optJSONObject("data")?.optString("username").orEmpty()},{selected=it})};val u=users.find{it.optJSONObject("data")?.optString("username")==selected}?.optJSONObject("data");if(u!=null){item{WCard{WInfo("Device restriction",u.optBoolean("restrict_devices").toString());WInfo("Single device",u.optBoolean("single_device").toString());WInfo("WAN allowed",u.optBoolean("allow_wan").toString())}};listOf("pending_devices" to "Approve device","approved_devices" to "Revoke device").forEach{(key,label)->items(u.array(key).objects().filter{it.toString().contains(search,true)}){d->WCard{Text(d.optString("name"),fontWeight=FontWeight.Bold);Text(d.optString("id"),fontSize=12.sp);Text("${d.optString("last_ip")} • ${d.optString("last_seen",d.optString("requested_at"))}",fontSize=12.sp);Button({onAction("$label for $selected?",JSONObject().put("action",if(key=="pending_devices")"approve_device"else "revoke_device").put("username",selected).put("device_id",d.optString("id")))}){Text(label)}}}}}}
        }
    }
}

@Composable private fun ActivityPane(ctx:JSONObject){
    var view by rememberSaveable{mutableStateOf("Business Activity")};var search by rememberSaveable{mutableStateOf("")};var page by rememberSaveable{mutableStateOf(0)}
    val key=mapOf("Business Activity" to "activity","Access Log" to "access_logs","Administration Log" to "config_logs")[view]?:"activity"
    val rows=ctx.array(key).objects().filter{it.toString().contains(search,true)}
    val pages=maxOf(1,(rows.size+19)/20)
    LaunchedEffect(search,view){page=0}
    LazyColumn(Modifier.fillMaxSize().padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(bottom=24.dp)){
        item{WPicker("Activity view",view,if(ctx.optBoolean("is_admin"))listOf("Business Activity","Access Log","Administration Log")else listOf("Business Activity"),{view=it});WField("Search action / user / record",search,{search=it});Text("${rows.size} events")}
        items(rows.drop(page*20).take(20)){r->WCard{Text("${r.optString("action",r.optString("event"))} • ${r.optString("by",r.optString("actor",r.optString("username")))}",fontWeight=FontWeight.Bold);Text("${r.optString("date",r.optString("at",r.optString("timestamp")))} ${r.optString("time")}",fontSize=12.sp);Text(r.optString("details",r.optString("detail",r.optString("module"))),fontSize=13.sp);if(r.optString("record").isNotBlank())WInfo("Record",r.optString("record"))}}
        item{Row(verticalAlignment=Alignment.CenterVertically){IconButton({page--},enabled=page>0){Icon(Icons.Default.ChevronLeft,"Previous")};Text("${page+1}/$pages");IconButton({page++},enabled=page+1<pages){Icon(Icons.Default.ChevronRight,"Next")}}}
    }
}

@Composable private fun WorkspaceEditor(kind:String,source:JSONObject,baseToken:String,baseEpoch:String,ctx:JSONObject,saving:Boolean,onCancel:()->Unit,onSave:(JSONObject)->Unit){
    val key=kind+":"+source.optString("name",source.optString("id",source.optString("username")))+baseToken
    var raw by rememberSaveable(key){mutableStateOf(source.toString())};var step by rememberSaveable(key){mutableStateOf(0)};val request=rememberSaveable(key){UUID.randomUUID().toString()};var validation by remember{mutableStateOf("")};var password by remember{mutableStateOf("")};var stockKind by rememberSaveable(key){mutableStateOf("opening")}
    val data=JSONObject(raw)
    fun set(k:String,v:Any){val j=JSONObject(raw);j.put(k,v);raw=j.toString();validation=""}
    fun setSize(size:Int,v:String){val j=JSONObject(raw);val field=if(kind=="sole")stockKind else "sizes";val q=j.optJSONObject(field)?:JSONObject();q.put("sz$size",v.ifBlank{"0"});j.put(field,q);raw=j.toString()}
    fun build():JSONObject{
        val b=JSONObject().put("reset_epoch",baseEpoch).put("request_id",request).put("expected_token",baseToken)
        when(kind){
            "master"->{b.put("action",if(baseToken.isBlank())"master_create"else "master_edit").put("category",data.optString("category")).put("old_name",source.optString("name")).put("fields",data)}
            "sole"->b.put("action","sole_edit").put("name",data.optString("name")).put("kind",stockKind).put("sizes",data.optJSONObject(stockKind)?:JSONObject()).put("date",data.optString("date",wToday())).put("party",data.optString("party")).put("rate",data.optString("rate","0")).put("note",data.optString("note"))
            "box"->b.put("action","box_edit").put("name",data.optString("name")).put("stock",data.optString("stock","0")).put("ordered",data.optString("ordered","0"))
            "po"->b.put("action","po_create").put("sole",data.optString("sole")).put("party",data.optString("party")).put("sizes",data.optJSONObject("sizes")?:JSONObject()).put("date",data.optString("date",wToday())).put("rate",data.optString("rate","0")).put("note",data.optString("note"))
            "receive"->b.put("action","po_receive").put("id",data.optString("id")).put("sizes",data.optJSONObject("sizes")?:JSONObject()).put("date",data.optString("receive_date",wToday()))
            "purchase"->b.put("action","purchase_edit").put("id",data.optString("id")).put("party",data.optString("party")).put("rate",data.optString("rate","0")).put("note",data.optString("note"))
            "user"->{val fields=JSONObject(data.toString()).put("action","save");if(password.isNotBlank())fields.put("password",password);b.put("route","users").put("fields",fields)}
        }
        return b
    }
    BackHandler(enabled=!saving){if(step>0)step--else onCancel()}
    Column(Modifier.fillMaxSize()){
        Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)){
            Text(if(step==0)"Profile / transaction details"else "Review before saving",fontSize=23.sp,fontWeight=FontWeight.Bold,color=WNavy)
            if(step==0){
                when(kind){
                    "master"->{val cat=data.optString("category");WCard{WInfo("Category",WCats[cat].orEmpty());WField("Name *",data.optString("name"),{set("name",it)});if(cat=="party"){WField("Party code",data.optString("code"),{set("code",it)});WPicker("Party rating",data.optInt("rating").toString(),listOf("0","1","2","3"),{set("rating",it.toInt())})};if(cat in listOf("party","factory","soleVendor")){WField("Mobile",data.optString("phone"),{set("phone",it)},KeyboardType.Phone);WField("Email",data.optString("email"),{set("email",it)},KeyboardType.Email);WField("Address",data.optString("address"),{set("address",it)})};if(cat=="art")WField("Article type",data.optString("type"),{set("type",it)});WPicker("Status",data.optString("status","active").ifBlank{"active"},listOf("active","inactive"),{set("status",it)});WField("Notes",data.optString("notes"),{set("notes",it)});if(baseToken.isNotBlank())Text("Renaming updates linked order/stock references in one server transaction.",fontSize=12.sp)}}
                    "sole"->{WInfo("Sole",data.optString("name"));WPicker("Stock column",stockKind,listOf("opening","purchase"),{stockKind=it});WField("Transaction date • DD-MM-YYYY",data.optString("date",wToday()),{set("date",it)});WField("Note",data.optString("note"),{set("note",it)});WSizeEditor(data.optJSONObject(stockKind)?:JSONObject(),{s,v->setSize(s,v)});Text("Enter the new absolute totals. Differences are recorded in Purchase History.",fontSize=12.sp)}
                    "box"->{WInfo("Box",data.optString("name"));WField("Stock",data.optString("stock","0"),{set("stock",it)},KeyboardType.Number);WField("Ordered",data.optString("ordered","0"),{set("ordered",it)},KeyboardType.Number)}
                    "po"->{val choices=ctx.optJSONObject("choices")?:JSONObject();WPicker("Sole *",data.optString("sole"),choices.array("sole").let{a->(0 until a.length()).map{a.optString(it)}},{set("sole",it)});WPicker("Vendor *",data.optString("party"),choices.array("soleVendor").let{a->(0 until a.length()).map{a.optString(it)}},{set("party",it)});WField("PO date • DD-MM-YYYY",data.optString("date",wToday()),{set("date",it)});WField("Rate",data.optString("rate","0"),{set("rate",it)},KeyboardType.Decimal);WField("Note",data.optString("note"),{set("note",it)});WSizeEditor(data.optJSONObject("sizes")?:JSONObject(),{s,v->setSize(s,v)})}
                    "receive"->{WInfo("Sole / Vendor","${data.optString("sole")} • ${data.optString("party")}");WInfo("Pending",(data.optJSONObject("remaining")?:JSONObject()).let{r->r.strings().sumOf{r.optInt(it)}}.toString());WField("Receive date • DD-MM-YYYY",data.optString("receive_date",wToday()),{set("receive_date",it)});OutlinedButton({set("sizes",JSONObject(data.optJSONObject("remaining").toString()))}){Text("Fill pending quantities")};WSizeEditor(data.optJSONObject("sizes")?:JSONObject(),{s,v->setSize(s,v)});Text("Only this PO will be received. Extra quantities require confirmation and go to Opening stock.",fontSize=12.sp)}
                    "purchase"->{WInfo("Entry","${data.optString("date")} • ${data.optString("sole")} • ${data.optInt("totalQty")} pairs");WField("Vendor",data.optString("party"),{set("party",it)});WField("Rate",data.optString("rate","0"),{set("rate",it)},KeyboardType.Decimal);WField("Note",data.optString("note"),{set("note",it)});WSizeInfo(data.optJSONObject("sizes")?:JSONObject())}
                    "user"->{if(baseToken.isBlank())WField("Username *",data.optString("username"),{set("username",it)})else WInfo("Username",data.optString("username"));listOf("full_name" to "Full name *","designation" to "Designation *","email" to "Email","mobile" to "Mobile","father_name" to "Father's name","address" to "Address","aadhaar" to "Aadhaar","pan" to "PAN","joining_date" to "Joining date","salary" to "Salary").forEach{(k,label)->WField(label,data.optString(k),{set(k,it)})};WPicker("Role",data.optString("role","staff"),listOf("admin","staff","client"),{set("role",it)});OutlinedTextField(password,{password=it},label={Text(if(baseToken.isBlank())"Initial password *"else "New password (optional)")},visualTransformation=PasswordVisualTransformation(),singleLine=true,modifier=Modifier.fillMaxWidth());listOf("active" to "Active account","allow_wan" to "Allow WAN access","single_device" to "Single device only","restrict_devices" to "Approved devices only","must_change_password" to "Change password on next login").forEach{(k,label)->WCheck(label,data.optBoolean(k,k in listOf("active","allow_wan"))){set(k,it)}};WField("Allowed IPs • comma separated",data.array("allowed_ips").let{a->(0 until a.length()).map{a.optString(it)}.joinToString(",")},{set("allowed_ips",JSONArray(it.split(',').map{x->x.trim()}.filter{x->x.isNotBlank()}))});WCard{Text("Permissions",fontSize=20.sp,fontWeight=FontWeight.Bold);ctx.array("permission_keys").let{a->(0 until a.length()).map{a.optString(it)}}.forEach{p->WCheck(p.replace('_',' '),data.optJSONObject("permissions")?.optBoolean(p)==true){v->val perms=data.optJSONObject("permissions")?:JSONObject();perms.put(p,v);set("permissions",perms)}}};WCard{Text("Profile visibility",fontWeight=FontWeight.Bold);listOf("father_name","email","mobile","address","aadhaar","pan","joining_date","salary").forEach{k->WCheck(k.replace('_',' '),data.optJSONObject("profile_visibility")?.optBoolean(k,k !in listOf("aadhaar","pan","salary"))?: (k !in listOf("aadhaar","pan","salary"))){v->val vis=data.optJSONObject("profile_visibility")?:JSONObject();vis.put(k,v);set("profile_visibility",vis)}}}}
                }
            }else{WCard{data.strings().filter{it !in listOf("password","permissions","profile_visibility","approved_devices","pending_devices","remaining")}.forEach{k->if(data.opt(k) is JSONObject){WInfo(k,"");WSizeInfo(data.optJSONObject(k)?:JSONObject())}else WInfo(k.replace('_',' '),data.optString(k))};if(kind=="user"){WInfo("Enabled permissions",(data.optJSONObject("permissions")?:JSONObject()).let{p->p.strings().filter{p.optBoolean(it)}.joinToString(", ").ifBlank{"None"}});WInfo("Password",if(password.isBlank())"Unchanged"else "Will be updated")};Text("This change will be saved on the live OMS server.",fontSize=12.sp)}}
            if(validation.isNotBlank())Text(validation,color=MaterialTheme.colorScheme.error)
        }
        Surface(shadowElevation=4.dp){Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)){OutlinedButton({if(step>0)step--else onCancel()},enabled=!saving,modifier=Modifier.weight(1f)){Text(if(step>0)"Back"else "Cancel")};Button({if(step==0){validation=when{kind=="master"&&data.optString("name").isBlank()->"Name is required.";kind=="user"&&(data.optString("username").isBlank()||data.optString("full_name").isBlank()||data.optString("designation").isBlank())->"Username, full name and designation are required.";kind=="user"&&baseToken.isBlank()&&password.length<8->"Initial password must have at least 8 characters.";else->""};if(validation.isBlank())step=1}else onSave(build())},enabled=!saving,modifier=Modifier.weight(1f)){Text(if(saving)"Saving…"else if(step==0)"Review"else "Save on server")}}}
    }
}

@Composable private fun WCard(onClick:(()->Unit)?=null,content:@Composable ColumnScope.()->Unit){Surface(if(onClick==null)Modifier.fillMaxWidth()else Modifier.fillMaxWidth().clickable(onClick=onClick),shape=RoundedCornerShape(18.dp),color=Color.White){Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(9.dp),content=content)}}
@Composable private fun WInfo(label:String,value:String){Column{Text(label,color=Color(0xFF64748B),fontSize=12.sp);Text(value.ifBlank{"—"},fontWeight=FontWeight.SemiBold,color=WNavy)}}
@Composable private fun WField(label:String,value:String,onChange:(String)->Unit,type:KeyboardType=KeyboardType.Text){OutlinedTextField(value,onChange,label={Text(label)},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=type),modifier=Modifier.fillMaxWidth())}
@Composable private fun WCheck(label:String,value:Boolean,onChange:(Boolean)->Unit){Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically){Checkbox(value,onChange);Text(label,Modifier.weight(1f),fontSize=13.sp)}}
@Composable private fun WSizeEditor(values:JSONObject,onChange:(Int,String)->Unit){(4..14).chunked(3).forEach{row->Row(horizontalArrangement=Arrangement.spacedBy(8.dp)){row.forEach{s->OutlinedTextField(values.optString("sz$s","0").let{if(it=="0")""else it},{onChange(s,it)},label={Text("Size $s")},singleLine=true,keyboardOptions=KeyboardOptions(keyboardType=KeyboardType.Number),modifier=Modifier.weight(1f))}}}}
@Composable private fun WSizeInfo(values:JSONObject){Text((4..14).map{it to values.optInt("sz$it")}.filter{it.second!=0}.joinToString(" • "){"${it.first}: ${it.second}"}.ifBlank{"No size quantities"},fontSize=12.sp)}
@Composable private fun WOrder(row:JSONObject,onOpen:()->Unit){WCard(onClick=onOpen){Text("Order ${row.optString("groupOrderNo").ifBlank{row.optString("orderNo")}} • Line ${row.optInt("groupLineNo",1)}",fontWeight=FontWeight.Bold);Text("${row.optString("party")} • ${row.optString("article")} • ${row.optString("colour")}");Text("${row.optInt("totalPairs")} pairs • ${if(row.optBoolean("hold"))"Hold"else row.optString("status")} • ${row.optString("orderDate")}",fontSize=12.sp);Text("Production ${row.optString("prodDate").ifBlank{"—"}} • Ready ${row.optString("readyDate").ifBlank{"—"}} • Dispatch ${row.optString("dispatchDate").ifBlank{"—"}}",fontSize=11.sp)}}
@Composable private fun WPicker(label:String,value:String,choices:List<String>,onSelect:(String)->Unit,modifier:Modifier=Modifier.fillMaxWidth()){
    var open by remember{mutableStateOf(false)};var query by remember{mutableStateOf("")}
    OutlinedButton({query="";open=true},modifier=modifier.heightIn(min=54.dp)){Column(Modifier.weight(1f),horizontalAlignment=Alignment.Start){Text(label,fontSize=10.sp,color=Color(0xFF64748B));Text(value.ifBlank{"Select…"},fontSize=13.sp)};Icon(Icons.Default.ExpandMore,null)}
    if(open)Dialog(onDismissRequest={open=false}){Surface(shape=RoundedCornerShape(20.dp),color=Color.White){Column(Modifier.fillMaxWidth().heightIn(max=560.dp).padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)){Text(label,fontSize=20.sp,fontWeight=FontWeight.Bold);WField("Search",query,{query=it});LazyColumn(Modifier.weight(1f,false)){val matches=choices.filter{it.contains(query,true)};items(matches){choice->Text(choice,Modifier.fillMaxWidth().clickable{onSelect(choice);open=false}.padding(vertical=16.dp),fontWeight=FontWeight.SemiBold)};if(matches.isEmpty())item{Text("No matching values.",Modifier.padding(12.dp))}};TextButton({open=false},modifier=Modifier.align(Alignment.End)){Text("Close")}}}}
}
