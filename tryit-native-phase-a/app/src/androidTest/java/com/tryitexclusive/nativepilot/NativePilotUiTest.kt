package com.tryitexclusive.nativepilot

import android.content.Context
import android.util.Base64
import androidx.lifecycle.Lifecycle
import java.security.MessageDigest
import android.content.pm.ActivityInfo
import android.view.WindowManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.json.JSONArray
import org.json.JSONObject
import org.junit.After
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.ExternalResource
import org.junit.rules.RuleChain
import org.junit.runner.RunWith
import java.net.InetAddress
import java.net.ServerSocket
import java.net.SocketException
import java.nio.charset.StandardCharsets
import java.util.concurrent.CopyOnWriteArrayList

/** Isolated HTTP fixture; no production host or customer data is contacted. */
private class PilotFixture {
    val server=ServerSocket(0,20,InetAddress.getByName("127.0.0.1"))
    @Volatile var expired=false
    @Volatile var restricted=false
    val writes=CopyOnWriteArrayList<JSONObject>()
    private val keys=listOf("orders","production","dispatch","stock","accounts","reports","masters","settings").flatMap{m->listOf("view","add","edit","delete").map{"${m}_$it"}}+listOf("production_approve","dispatch_approve","reports_export")
    private val worker=Thread{
        while(!server.isClosed){
            try{server.accept().use{s->
                val input=s.getInputStream().bufferedReader(StandardCharsets.UTF_8)
                val first=input.readLine().orEmpty();var length=0
                while(true){val line=input.readLine()?:break;if(line.isBlank())break;if(line.startsWith("Content-Length:",true))length=line.substringAfter(':').trim().toInt()}
                val chars=CharArray(length);var got=0;while(got<length){val n=input.read(chars,got,length-got);if(n<0)break;got+=n}
                val path=first.split(' ').getOrElse(1){"/"}
                if(first.startsWith("POST")){val body=runCatching{JSONObject(String(chars))}.getOrDefault(JSONObject());writes.add(body)}
                val data=if(expired)JSONObject().put("ok",false).put("error","Session expired")else response(path,first.startsWith("POST"))
                val raw=data.toString().toByteArray(StandardCharsets.UTF_8)
                val status=if(expired)"401 Unauthorized"else "200 OK"
                s.getOutputStream().write(("HTTP/1.1 $status\r\nContent-Type: application/json\r\nContent-Length: ${raw.size}\r\nConnection: close\r\n\r\n").toByteArray(StandardCharsets.UTF_8));s.getOutputStream().write(raw)
            }}catch(_:SocketException){break}catch(_:Exception){}
        }
    }.apply{isDaemon=true;start()}
    private fun permissions()=JSONObject().apply{keys.forEach{put(it,!restricted||it=="orders_view")}}
    private fun order()=JSONObject().put("orderNo",1).put("groupOrderNo",1).put("groupLineNo",1).put("party","QA PARTY").put("partyCode","Q001").put("factory","QA FACTORY").put("article","QA ARTICLE").put("colour","BLACK").put("sole","QA SOLE").put("box","QA BOX").put("orderDate",java.time.LocalDate.now().format(java.time.format.DateTimeFormatter.ofPattern("dd-MM-uuuu"))).put("totalPairs",12).put("sizes",JSONObject().put("sz6",12)).put("status","In Production")
    private fun wrap(o:JSONObject)=JSONObject().put("data",o).put("token","fixture-token")
    private fun response(path:String,post:Boolean):JSONObject{
        if(post)return JSONObject().put("ok",true).put("message","Saved on server")
        if(path=="/api/state")return JSONObject().put("ok",true).put("values",JSONObject().put("st_orders",JSONArray().put(order()).toString()))
        if(path.startsWith("/api/mobile/orders/context"))return JSONObject().put("ok",true).put("permissions",permissions()).put("orders",JSONArray().put(wrap(order()))).put("masters",JSONObject()).put("reset_epoch","fixture-epoch")
        val base=JSONObject().put("ok",true).put("api_version",2).put("permissions",permissions()).put("is_admin",!restricted).put("reset_epoch","fixture-epoch")
        val module=path.substringAfter("module=","hub").substringBefore('&')
        when(module){
            "masters"->{val masters=JSONObject();listOf("party","factory","art","colour","dml","material","box","sole","soleVendor").forEach{k->masters.put(k,JSONArray().put(wrap(JSONObject().put("name","QA "+k.uppercase()).put("status","active").put("code","Q001"))))};base.put("masters",masters)}
            "production","dispatch","reports","business"->base.put("orders",JSONArray().put(wrap(order()))).put("batches",JSONArray()).put("parties",JSONArray())
            "stock"->{base.put("soles",JSONArray()).put("boxes",JSONArray()).put("pos",JSONArray()).put("purchases",JSONArray()).put("choices",JSONObject().put("sole",JSONArray().put("QA SOLE")).put("soleVendor",JSONArray().put("QA VENDOR")))}
            "admin"->{base.put("users",JSONArray().put(wrap(JSONObject().put("username","qa").put("full_name","QA Admin").put("designation","Tester").put("role","admin").put("active",true)))).put("sessions",JSONArray()).put("permission_keys",JSONArray(keys))}
            "audit"->base.put("activity",JSONArray()).put("access_logs",JSONArray()).put("config_logs",JSONArray())
        }
        return base
    }
    fun close(){server.close();worker.join(1500)}
}

@RunWith(AndroidJUnit4::class)
class NativePilotUiTest {
    private lateinit var fixture:PilotFixture
    private val compose=createAndroidComposeRule<MainActivity>()
    private val setup=object:ExternalResource(){
        override fun before(){
            fixture=PilotFixture()
            val context=ApplicationProvider.getApplicationContext<Context>()
            context.getSharedPreferences("tryit_native_secure",Context.MODE_PRIVATE).edit().clear().commit()
            val secure=SecureStore(context)
            secure.put("server_url","http://127.0.0.1:${fixture.server.localPort}")
            secure.put("session_cookie","kiran_oms_user=fixture")
            secure.put("lock_mode","none");secure.put("lock_setup_done","1")
            secure.put("profile_json",JSONObject().put("username","qa").put("full_name","QA Admin").put("role","admin").toString())
        }
        override fun after(){fixture.close()}
    }
    @get:Rule val chain:RuleChain=RuleChain.outerRule(setup).around(compose)
    private fun waitText(text:String){compose.waitUntil(20000){compose.onAllNodesWithText(text).fetchSemanticsNodes().isNotEmpty()}}
    private fun openModules(){waitText("All Modules");compose.onNodeWithText("All Modules").performClick();waitText("Your workspace")}
    private fun openModule(name:String){compose.onNodeWithText(name).performScrollTo().performClick()}

    @Test fun allNativeModulesOpenAndBackWorks(){
        openModules()
        val checks=listOf("Masters" to "Create Party","Production" to "Stage","Dispatch" to "Stage","Stock & Purchases" to "Stock view","Party Dashboard & Ledger" to "Period","Reports" to "Period","Users, Permissions & Devices" to "Create user","Activity & Audit" to "Activity view")
        checks.forEach{(title,marker)->openModule(title);waitText(marker);compose.onNodeWithContentDescription("Back").performClick();waitText("Your workspace")}
        assertEquals(0,fixture.writes.size)
    }

    @Test fun guidedMasterReviewHasNoWriteUntilSave(){
        openModules();openModule("Masters");waitText("Create Party")
        compose.onNodeWithText("Create Party").performClick();waitText("Name *")
        compose.onNodeWithText("Name *").performTextInput("QA NEW PARTY")
        compose.onNodeWithText("Review",useUnmergedTree=false).performClick();waitText("Review before saving")
        assertEquals(0,fixture.writes.size)
        compose.onNodeWithText("Save on server").performClick()
        compose.waitUntil(15000){fixture.writes.size==1}
        assertEquals("master_create",fixture.writes[0].getString("action"))
        assertEquals("QA NEW PARTY",fixture.writes[0].getJSONObject("fields").getString("name"))
    }

    @Test fun masterDraftSurvivesActivityRecreation(){
        openModules();openModule("Masters");waitText("Create Party");compose.onNodeWithText("Create Party").performClick();waitText("Name *")
        compose.onNodeWithText("Name *").performTextInput("QA DRAFT")
        compose.activityRule.scenario.recreate()
        waitText("QA DRAFT")
        compose.onNodeWithText("Name *").assertTextContains("QA DRAFT")
        assertEquals(0,fixture.writes.size)
    }

    @Test fun pinLockBlocksBackAndRestoresMasterDraft(){
        openModules();openModule("Masters");waitText("Create Party");compose.onNodeWithText("Create Party").performClick();waitText("Name *")
        compose.onNodeWithText("Name *").performTextInput("QA LOCK DRAFT")
        val secure=SecureStore(ApplicationProvider.getApplicationContext())
        val salt=ByteArray(16){it.toByte()};val digest=MessageDigest.getInstance("SHA-256").apply{update(salt);update("1234".toByteArray(StandardCharsets.UTF_8))}.digest()
        secure.put("pin_salt",Base64.encodeToString(salt,Base64.NO_WRAP));secure.put("pin_hash",Base64.encodeToString(digest,Base64.NO_WRAP));secure.put("lock_mode","pin");secure.put("lock_timeout","0")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        waitText("TRYIT Locked")
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("TRYIT Locked").assertIsDisplayed()
        compose.onNodeWithText("App PIN").performTextInput("9999");compose.onNodeWithText("Unlock",substring=false).performClick();waitText("Incorrect PIN")
        compose.onNodeWithText("App PIN").performTextReplacement("1234");compose.onNodeWithText("Unlock",substring=false).performClick();waitText("QA LOCK DRAFT")
        compose.onNodeWithText("Name *").assertTextContains("QA LOCK DRAFT")
        assertEquals(0,fixture.writes.size)
    }

    @Test fun networkLossShowsRetryAndDoesNotWrite(){
        fixture.close();compose.onNodeWithText("All Modules").performClick();waitText("Retry")
        compose.onNodeWithText("Retry").assertIsDisplayed()
        assertEquals(0,fixture.writes.size)
    }

    @Test fun expiredSessionReturnsToLogin(){
        openModules();fixture.expired=true
        compose.onNodeWithContentDescription("Refresh").performClick()
        compose.waitUntil(15000){compose.onAllNodesWithText("All Modules").fetchSemanticsNodes().isEmpty()&&compose.onAllNodesWithText("Your workspace").fetchSemanticsNodes().isEmpty()}
        compose.waitUntil(15000){SecureStore(ApplicationProvider.getApplicationContext()).get("session_cookie").isBlank()}
    }

    @Test fun responsiveRotationAndSensitiveWindowProtection(){
        openModules()
        assertTrue(compose.activity.window.attributes.flags and WindowManager.LayoutParams.FLAG_SECURE != 0)
        compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        waitText("Your workspace")
        compose.onNodeWithText("Masters").performScrollTo().assertIsDisplayed()
        compose.activity.requestedOrientation=ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        waitText("Your workspace")
    }

    @Test fun restrictedRoleDoesNotSeeAdministrationOrMasters(){
        fixture.restricted=true;openModules()
        compose.onNodeWithText("Users, Permissions & Devices").assertDoesNotExist()
        compose.onNodeWithText("Masters").assertDoesNotExist()
        compose.onNodeWithText("Orders").assertExists()
    }
}
