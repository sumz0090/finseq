package com.tryitexclusive.nativepilot

import android.content.Context
import android.app.KeyguardManager
import androidx.activity.result.contract.ActivityResultContracts
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec
import androidx.compose.foundation.BorderStroke
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.widget.Toast
import androidx.activity.compose.BackHandler
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.graphics.Color as ComposeColor
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.fragment.app.FragmentActivity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

private val Navy = ComposeColor(0xFF0B1830)
private val Red = ComposeColor(0xFFD71920)
private val Cream = ComposeColor(0xFFF4F7FB)
const val OMS_URL = "https://oms.tryitexclusive.com"
private val Soft = ComposeColor(0xFFF4F7FB)
private val Green = ComposeColor(0xFF0E9F6E)

class MainActivity : FragmentActivity() {
    private lateinit var secure: SecureStore
    private var lockRequested by mutableStateOf(false)
    private var lastBackAt = 0L
    private lateinit var deviceVault: RememberedDevice
    private var credentialSuccess: (() -> Unit)? = null
    private var credentialError: ((String) -> Unit)? = null
    private val credentialLauncher = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == RESULT_OK) credentialSuccess?.invoke() else credentialError?.invoke("Unlock cancelled. Try again.")
        credentialSuccess = null; credentialError = null
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 24, 48)
        secure = SecureStore(this)
        deviceVault = RememberedDevice(this, secure)
        // Only instrumentation/debug builds can point at an isolated test fixture.
        if (!BuildConfig.DEBUG || secure.get("server_url").isBlank()) {
            if (secure.get("server_url").isNotBlank() && secure.get("server_url") != OMS_URL) { clearSession(); deviceVault.clear() }
            secure.put("server_url", OMS_URL)
        }
        ensureDeviceId()
        window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)

        setContent {
            MaterialTheme(
                shapes = Shapes(small = RoundedCornerShape(12.dp), medium = RoundedCornerShape(18.dp), large = RoundedCornerShape(24.dp)),
                colorScheme = lightColorScheme(
                    primary = Navy,
                    secondary = Red,
                    background = Soft,
                    surface = ComposeColor.White
                )
            ) {
                TryItApp()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        if (secure.get("session_cookie").isNotBlank()) {
            secure.put("background_at", System.currentTimeMillis().toString())
        }
    }

    override fun onStart() {
        super.onStart()
        val cookie = secure.get("session_cookie")
        val mode = secure.get("lock_mode")
        if (cookie.isBlank() || mode.isBlank() || mode == "none") return
        val timeout = secure.get("lock_timeout").toLongOrNull() ?: 0L
        val bg = secure.get("background_at").toLongOrNull() ?: 0L
        if (bg > 0 && System.currentTimeMillis() - bg >= timeout * 1000L) {
            lockRequested = true
        }
    }

    private fun ensureDeviceId() {
        if (secure.get("device_id").isBlank()) {
            secure.put("device_id", "android-" + UUID.randomUUID().toString())
        }
    }

    private fun deviceName(): String = listOf(Build.MANUFACTURER, Build.MODEL)
        .filter { it.isNotBlank() }.joinToString(" ").ifBlank { "Android Device" }

    private fun launchSystemUnlock(onSuccess: () -> Unit, onError: (String) -> Unit) {
        try { deviceVault.prepareSystem() } catch (_: Exception) { onError("Set a device screen lock first, or choose an App PIN."); return }
        if (Build.VERSION.SDK_INT < 30) {
            val manager = getSystemService(Context.KEYGUARD_SERVICE) as KeyguardManager
            val intent = manager.createConfirmDeviceCredentialIntent("Unlock TRYIT", "Confirm your device screen lock")
            if (intent == null) { onError("Set a device screen lock first, or choose an App PIN."); return }
            credentialSuccess = onSuccess; credentialError = onError; credentialLauncher.launch(intent); return
        }
        val authenticators = BiometricManager.Authenticators.BIOMETRIC_STRONG or
            BiometricManager.Authenticators.DEVICE_CREDENTIAL
        val can = BiometricManager.from(this).canAuthenticate(authenticators)
        if (can != BiometricManager.BIOMETRIC_SUCCESS) {
            onError("Device screen lock/biometric is not available on this phone.")
            return
        }
        val prompt = BiometricPrompt(
            this,
            ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    super.onAuthenticationSucceeded(result)
                    onSuccess()
                }
                override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                    super.onAuthenticationError(errorCode, errString)
                    onError(errString.toString())
                }
            }
        )
        val info = BiometricPrompt.PromptInfo.Builder()
            .setTitle("Unlock TRYIT")
            .setSubtitle("Confirm your identity to continue")
            .setAllowedAuthenticators(authenticators)
            .build()
        prompt.authenticate(info)
    }

    @Composable
    private fun TryItApp() {
        var route by rememberSaveable { mutableStateOf(initialRoute()) }
        var profile by remember { mutableStateOf(loadStoredProfile()) }
        var resumeRoute by rememberSaveable { mutableStateOf("home") }
        var ordersInitialId by rememberSaveable { mutableStateOf("") }
        var ordersOrigin by rememberSaveable { mutableStateOf("home") }
        var workspaceInitial by rememberSaveable { mutableStateOf("hub") }
        val savedScreens = rememberSaveableStateHolder()
        val scope = rememberCoroutineScope()
        var unlockBusy by remember { mutableStateOf(false) }
        var unlockError by remember { mutableStateOf("") }
        fun completeUnlock() {
            if (unlockBusy) return
            unlockBusy = true; unlockError = ""
            scope.launch {
                val r = withContext(Dispatchers.IO) { renewOrEnroll() }
                unlockBusy = false
                if (r.ok) {
                    if (r.cookie.isNotBlank()) secure.put("session_cookie", r.cookie.substringBefore(';'))
                    r.json?.optJSONObject("profile")?.let { profile = UserProfile.fromJson(it); storeProfile(profile) }
                    secure.put("session_needs_refresh", ""); route = resumeRoute
                } else if (r.code in listOf(401,403)) {
                    deviceVault.clear(); clearSession(); savedScreens.removeState("orders"); savedScreens.removeState("workspace"); route = "login"
                    runOnUiThread { Toast.makeText(this@MainActivity, "Please sign in again. Device login expired or access changed.", Toast.LENGTH_LONG).show() }
                } else unlockError = r.error
            }
        }
        fun selectLock(mode: String, pin: String = "") {
            fun enable() {
                deviceVault.clear()
                if (pin.isNotBlank()) savePin(pin)
                secure.put("lock_mode", mode); secure.put("lock_timeout", "0"); secure.put("lock_setup_done", "1")
                resumeRoute = "home"; route = "unlock"; completeUnlock()
            }
            if (mode == "system") launchSystemUnlock({ enable() }) { Toast.makeText(this@MainActivity,it,Toast.LENGTH_LONG).show() }
            else enable()
        }

        LaunchedEffect(Unit) {
            SessionEvents.expired.collect { expiredCookie ->
                withContext(Dispatchers.Main.immediate) {
                    if (expiredCookie != secure.get("session_cookie") || route in listOf("unlock","login","locksetup")) return@withContext
                    if (deviceVault.exists() && secure.get("lock_mode") in listOf("system","pin")) {
                        resumeRoute = route; secure.put("session_needs_refresh", "1"); unlockError = "Session expired. Unlock to reconnect."; route = "unlock"
                    } else {
                        clearSession(); savedScreens.removeState("orders"); savedScreens.removeState("workspace")
                        resumeRoute = "home"; route = "login"
                    }
                }
            }
        }

        LaunchedEffect(lockRequested) {
            if (lockRequested && secure.get("session_cookie").isNotBlank()) {
                if(route !in listOf("unlock","login","locksetup")) resumeRoute = route
                unlockError = ""; route = "unlock"
                lockRequested = false
            }
        }

        BackHandler(enabled = route != "login") {
            when (route) {
                "unlock", "locksetup" -> Unit
                "home" -> {
                    val now = System.currentTimeMillis()
                    if (now - lastBackAt <= 1800L) finishAffinity()
                    else {
                        lastBackAt = now
                        Toast.makeText(this, "Press back again to exit", Toast.LENGTH_SHORT).show()
                    }
                }
                else -> route = "home"
            }
        }

        Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth()) {
        when (route) {
            "login" -> LoginScreen(
                initialServer = secure.get("server_url"),
                rememberedUser = secure.get("remembered_user"),
                onLogin = { server, user, pass, remember, setBusy, setError ->
                    setBusy(true); setError("")
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            ApiClient.login(server, user, pass, secure.get("device_id"), deviceName())
                        }
                        setBusy(false)
                        if (result.ok) {
                            secure.put("server_url", normalizeServer(server))
                            deviceVault.clear(); deviceVault.resetSystemKey()
                            secure.put("session_cookie", result.cookie)
                            secure.put("session_needs_refresh", "")
                            secure.put("remembered_user", if (remember) user else "")
                            profile = result.profile ?: UserProfile(username = user)
                            storeProfile(profile)
                            resumeRoute = "home"
                            route = if (secure.get("lock_setup_done") == "1") {
                                if (secure.get("lock_mode") == "none") "home" else "unlock"
                            } else "locksetup"
                        } else setError(result.error)
                    }
                },
                onForgot = { route = "forgot" }
            )
            "forgot" -> ForgotPasswordScreen(
                server = secure.get("server_url"),
                onBack = { route = "login" }
            )
            "locksetup" -> LockSetupScreen(
                onSystemLock = { selectLock("system") },
                onPinSaved = { selectLock("pin", it) },
                onSkip = { deviceVault.clear(); secure.put("lock_mode", "none"); secure.put("lock_setup_done", "1"); route = "home" }
            )
            "unlock" -> UnlockScreen(
                mode = secure.get("lock_mode"), busy = unlockBusy, externalError = unlockError,
                onSystemUnlock = { success, error -> launchSystemUnlock(success, error) },
                verifyPin = { verifyPin(it) },
                onUnlocked = { completeUnlock() },
                onLogout = { scope.launch { logoutRemote(); clearSession(); deviceVault.clear(); savedScreens.removeState("orders"); savedScreens.removeState("workspace"); resumeRoute = "home"; route = "login" } }
            )
            "orders" -> savedScreens.SaveableStateProvider("orders") { NativeOrders(secure, onHome = { route = ordersOrigin }, initialOrder = ordersInitialId, returnToSource = ordersOrigin == "workspace") }
            "workspace" -> savedScreens.SaveableStateProvider("workspace") { key(workspaceInitial) { NativeWorkspace(secure, onHome = { route = "home" }, onOrders = { id -> ordersInitialId = id; ordersOrigin = "workspace"; savedScreens.removeState("orders"); route = "orders" }, initialModule = workspaceInitial) } }
            "profile" -> ProfileScreen(profile = profile, onBack = { route = "home" })
            "settings" -> SettingsScreen(
                currentMode = secure.get("lock_mode"),
                currentTimeout = secure.get("lock_timeout").toLongOrNull() ?: 0L,
                server = secure.get("server_url"),
                deviceId = secure.get("device_id"),
                onSetSystem = { selectLock("system") },
                onSetPin = { pin -> selectLock("pin", pin) },
                onDisable = { scope.launch { revokeDeviceRemote(); deviceVault.clear(); secure.put("lock_mode", "none") } },
                onTimeout = { secure.put("lock_timeout", it.toString()) },
                onBack = { route = "home" }
            )
            else -> NativeDashboard(
                secure = secure,
                profile = profile,
                onOrders = { id -> ordersInitialId = id; ordersOrigin = "home"; savedScreens.removeState("orders"); route = "orders" },
                onModules = { workspaceInitial = "hub"; savedScreens.removeState("workspace"); route = "workspace" },
                onProfile = { route = "profile" },
                onSettings = { route = "settings" },
                onLock = {
                    val mode = secure.get("lock_mode")
                    if (mode != "none" && mode.isNotBlank()) { resumeRoute = "home"; route = "unlock" }
                },
                onLogout = {
                    scope.launch {
                        logoutRemote()
                        clearSession(); deviceVault.clear()
                        savedScreens.removeState("orders"); savedScreens.removeState("workspace"); resumeRoute = "home"
                        route = "login"
                    }
                }
            )
        }
        }
        if (route in listOf("home","orders","workspace")) {
            NavigationBar(containerColor = ComposeColor.White, tonalElevation = 0.dp) {
                val tabs = listOf("Dashboard" to Icons.Default.SpaceDashboard, "Orders" to Icons.Default.ReceiptLong, "Operations" to Icons.Default.LocalShipping, "Masters" to Icons.Default.FolderCopy, "More" to Icons.Default.GridView)
                tabs.forEachIndexed { i, (label, icon) ->
                    val selected = when(i) { 0 -> route=="home"; 1 -> route=="orders"; 2 -> route=="workspace" && workspaceInitial=="production"; 3 -> route=="workspace" && workspaceInitial=="masters"; else -> route=="workspace" && workspaceInitial=="hub" }
                    NavigationBarItem(modifier = Modifier.semantics { contentDescription = label + " tab" }, selected = selected, onClick = {
                        when(i) {
                            0 -> route="home"
                            1 -> { ordersInitialId=""; ordersOrigin="home"; route="orders" }
                            else -> { val next=when(i){2->"production";3->"masters";else->"hub"}; if(workspaceInitial!=next) savedScreens.removeState("workspace"); workspaceInitial=next; route="workspace" }
                        }
                    }, icon = { Icon(icon, null) }, label = { Text(label,fontSize=10.sp,maxLines=1,modifier=Modifier.clearAndSetSemantics {}) }, colors = NavigationBarItemDefaults.colors(selectedIconColor=Red,selectedTextColor=Navy,indicatorColor=ComposeColor(0xFFFDECEE)))
                }
            }
        }
        }
    }

    private fun initialRoute(): String {
        val cookie = secure.get("session_cookie")
        if (cookie.isBlank()) return "login"
        if (secure.get("lock_setup_done") != "1") return "locksetup"
        return if (secure.get("lock_mode") == "none") "home" else "unlock"
    }

    private fun renewOrEnroll(): ApiResult {
        val system = secure.get("lock_mode") == "system"
        return try {
            val token = deviceVault.read(system)
            val body = JSONObject().put("device_id", secure.get("device_id"))
            if (token.isNotBlank()) ApiClient.post(secure.get("server_url"), "/api/mobile/auth/refresh", body.put("token", token), "")
            else {
                val r = ApiClient.post(secure.get("server_url"), "/api/mobile/auth/enroll", body, secure.get("session_cookie"))
                val newToken = r.json?.optString("token").orEmpty()
                if (r.ok && newToken.isNotBlank()) { deviceVault.save(newToken, system); r }
                else if (r.code == 404 && secure.get("session_needs_refresh") != "1") {
                    runOnUiThread { Toast.makeText(this, "Install the Premium server update to remember this device.", Toast.LENGTH_LONG).show() }; ApiResult(true)
                } else if (r.ok) ApiResult(false,"Server did not confirm device login. Install the Premium server update.") else r
            }
        } catch (_: Exception) { ApiResult(false, "Device unlock could not be verified. Try again, or log out and sign in.") }
    }
    private suspend fun revokeDeviceRemote() = withContext(Dispatchers.IO) {
        runCatching { val token = deviceVault.read(secure.get("lock_mode")=="system"); if(token.isNotBlank()) ApiClient.post(secure.get("server_url"),"/api/mobile/auth/revoke",JSONObject().put("device_id",secure.get("device_id")).put("token",token),"") }
    }

    private suspend fun logoutRemote() {
        revokeDeviceRemote()
        withContext(Dispatchers.IO) {
        runCatching {
            ApiClient.post(
                secure.get("server_url"),
                "/api/oms/logout",
                JSONObject(),
                secure.get("session_cookie")
            )
        }
    }

    }

    private fun clearSession() {
        secure.put("session_cookie", "")
        secure.put("background_at", "")
        secure.put("profile_json", "")
        secure.put("session_needs_refresh", "")
    }

    private fun storeProfile(p: UserProfile) {
        secure.put("profile_json", JSONObject().apply {
            put("username", p.username); put("full_name", p.fullName); put("designation", p.designation)
            put("role", p.role); put("email", p.email); put("mobile", p.mobile)
        }.toString())
    }

    private fun loadStoredProfile(): UserProfile {
        val raw = secure.get("profile_json")
        if (raw.isBlank()) return UserProfile(username = secure.get("remembered_user"))
        return runCatching { UserProfile.fromJson(JSONObject(raw)) }.getOrDefault(UserProfile())
    }

    private fun savePin(pin: String) {
        val salt = ByteArray(16).also { SecureRandom().nextBytes(it) }
        secure.put("pin_salt", Base64.encodeToString(salt, Base64.NO_WRAP))
        secure.put("pin_hash", hashPin(pin, salt, true))
        secure.put("pin_kdf", "pbkdf2")
        secure.put("pin_attempts", "0"); secure.put("pin_blocked_until", "0")
    }

    private fun verifyPin(pin: String): Boolean {
        if ((secure.get("pin_blocked_until").toLongOrNull() ?: 0L) > System.currentTimeMillis()) return false
        val saltRaw = secure.get("pin_salt"); val expected = secure.get("pin_hash")
        if (saltRaw.isBlank() || expected.isBlank()) return false
        val salt = Base64.decode(saltRaw, Base64.NO_WRAP)
        val matches = MessageDigest.isEqual(expected.toByteArray(), hashPin(pin,salt,secure.get("pin_kdf")=="pbkdf2").toByteArray())
        if (matches) { if(secure.get("pin_kdf")!="pbkdf2") savePin(pin); secure.put("pin_attempts","0") }
        else { val attempts=(secure.get("pin_attempts").toIntOrNull()?:0)+1; secure.put("pin_attempts",attempts.toString()); if(attempts>=5) { secure.put("pin_blocked_until",(System.currentTimeMillis()+30000L).toString()); secure.put("pin_attempts","0") } }
        return matches
    }
    private fun hashPin(pin: String, salt: ByteArray, modern: Boolean): String {
        val bytes = if(modern) SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(PBEKeySpec(pin.toCharArray(),salt,120000,256)).encoded
        else MessageDigest.getInstance("SHA-256").apply { update(salt); update(pin.toByteArray(StandardCharsets.UTF_8)) }.digest()
        return Base64.encodeToString(bytes,Base64.NO_WRAP)
    }

    @Composable
    private fun LoginScreen(
        initialServer: String,
        rememberedUser: String,
        onLogin: (String, String, String, Boolean, (Boolean) -> Unit, (String) -> Unit) -> Unit,
        onForgot: () -> Unit
    ) {
        val server = initialServer.ifBlank { OMS_URL }
        var username by rememberSaveable { mutableStateOf(rememberedUser) }
        var password by remember { mutableStateOf("") }
        var remember by rememberSaveable { mutableStateOf(rememberedUser.isNotBlank()) }
        var showPassword by rememberSaveable { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }

        Box(Modifier.fillMaxSize().background(Cream)) {
            Column(
                Modifier.fillMaxSize().statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(38.dp))
                Surface(shape=RoundedCornerShape(24.dp),color=Navy,modifier=Modifier.size(78.dp)) { Box(contentAlignment=Alignment.Center){Text("T",fontSize=48.sp,fontWeight=FontWeight.Black,color=ComposeColor.White)} }
                Spacer(Modifier.height(16.dp))
                Text("TRYIT",fontSize=36.sp,fontWeight=FontWeight.Black,color=Navy,letterSpacing=4.sp)
                Text("E X C L U S I V E",fontSize=11.sp,fontWeight=FontWeight.SemiBold,color=Red)
                Spacer(Modifier.height(30.dp))
                Surface(shape = RoundedCornerShape(24.dp), color = ComposeColor.White, shadowElevation = 2.dp) {
                    Column(Modifier.padding(22.dp).widthIn(max = 480.dp)) {
                        Row(verticalAlignment=Alignment.CenterVertically){Icon(Icons.Default.VerifiedUser,null,tint=Red,modifier=Modifier.size(18.dp));Spacer(Modifier.width(8.dp));Text("YOUR BUSINESS, CONNECTED",fontSize=10.sp,fontWeight=FontWeight.Bold,color=Navy)}
                        Spacer(Modifier.height(20.dp))
                        Text("Welcome back", fontSize = 26.sp, fontWeight = FontWeight.Bold, color = Navy)
                        Text("Sign in to manage your day with TRYIT OMS.", color = ComposeColor(0xFF64748B))
                        Spacer(Modifier.height(18.dp))
                        OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            password, { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { IconButton({ showPassword = !showPassword }) { Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } }
                        )
                        Column {
                            Row(verticalAlignment=Alignment.CenterVertically) { Checkbox(remember, { remember = it }); Text("Remember username") }
                            TextButton(onForgot,modifier=Modifier.align(Alignment.End)) { Text("Forgot password?") }
                        }
                        if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(vertical = 8.dp))
                        Button(
                            onClick = { onLogin(server, username.trim(), password, remember, { busy = it }, { error = it }) },
                            enabled = !busy && server.isNotBlank() && username.isNotBlank() && password.isNotBlank(),
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = Red)
                        ) {
                            if (busy) CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = ComposeColor.White)
                            else Text("Log In", fontWeight = FontWeight.Bold)
                        }
                        Spacer(Modifier.height(14.dp))
                        Text("TRYIT OMS • Secure connection • v1.1.0", color = ComposeColor(0xFF94A3B8), fontSize = 12.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun ForgotPasswordScreen(server: String, onBack: () -> Unit) {
        var user by rememberSaveable { mutableStateOf("") }
        var otp by remember { mutableStateOf("") }
        var newPass by remember { mutableStateOf("") }
        var stage by rememberSaveable { mutableIntStateOf(1) }
        var message by remember { mutableStateOf("") }
        var error by remember { mutableStateOf("") }
        var busy by remember { mutableStateOf(false) }
        val scope = rememberCoroutineScope()

        Scaffold(topBar = { AppTopBar("Forgot Password", onBack) }) { pad ->
            Column(Modifier.padding(pad).padding(20.dp).verticalScroll(rememberScrollState())) {
                Text("Reset your OMS password", fontSize = 22.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(14.dp))
                OutlinedTextField(user, { user = it }, label = { Text("Username") }, enabled = stage == 1, modifier = Modifier.fillMaxWidth())
                if (stage == 2) {
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(otp, { otp = it }, label = { Text("OTP") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
                    Spacer(Modifier.height(10.dp))
                    OutlinedTextField(newPass, { newPass = it }, label = { Text("New password") }, visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth())
                }
                if (message.isNotBlank()) Text(message, color = Green, modifier = Modifier.padding(top = 10.dp))
                if (error.isNotBlank()) Text(error, color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 10.dp))
                Spacer(Modifier.height(16.dp))
                Button(onClick = {
                    busy = true; error = ""; message = ""
                    scope.launch {
                        val path = if (stage == 1) "/api/auth/recovery/request" else "/api/auth/recovery/verify"
                        val body = if (stage == 1) JSONObject().put("username", user.trim()) else JSONObject().apply {
                            put("username", user.trim()); put("otp", otp.trim()); put("new_password", newPass)
                        }
                        val r = withContext(Dispatchers.IO) { ApiClient.post(normalizeServer(server), path, body, "") }
                        busy = false
                        if (r.ok) {
                            if (stage == 1) { stage = 2; message = "OTP sent. Enter the verification code." }
                            else { message = "Password updated. You can return to login." }
                        } else error = r.error
                    }
                }, enabled = !busy && user.isNotBlank() && (stage == 1 || (otp.isNotBlank() && newPass.length >= 6)), modifier = Modifier.fillMaxWidth()) {
                    Text(if (stage == 1) "Send OTP" else "Reset Password")
                }
            }
        }
    }

    @Composable
    private fun LockSetupScreen(onSystemLock: () -> Unit, onPinSaved: (String) -> Unit, onSkip: () -> Unit) {
        var pin by remember { mutableStateOf("") }
        var confirm by remember { mutableStateOf("") }
        var showPinForm by rememberSaveable { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().background(Soft).statusBarsPadding().navigationBarsPadding().imePadding().padding(22.dp).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(28.dp))
            Icon(Icons.Default.Lock, null, tint = Navy, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(12.dp))
            Text("Protect TRYIT", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Navy)
            Text("Unlock securely when you return. This device can reconnect without entering your password for up to 90 days.", color = ComposeColor(0xFF64748B))
            Spacer(Modifier.height(24.dp))
            ActionCard(Icons.Default.Fingerprint, "Use Screen Lock", "Fingerprint, face or device PIN/password", onSystemLock)
            Spacer(Modifier.height(12.dp))
            ActionCard(Icons.Default.Pin, "Set App PIN", "Create a separate 4–6 digit TRYIT PIN") { showPinForm = true }
            if (showPinForm) {
                Spacer(Modifier.height(14.dp))
                Surface(shape = RoundedCornerShape(18.dp), color = ComposeColor.White) {
                    Column(Modifier.padding(16.dp)) {
                        OutlinedTextField(pin, { if (it.length <= 6 && it.all(Char::isDigit)) pin = it }, label = { Text("4–6 digit PIN") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(confirm, { if (it.length <= 6 && it.all(Char::isDigit)) confirm = it }, label = { Text("Confirm PIN") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), modifier = Modifier.fillMaxWidth())
                        Spacer(Modifier.height(10.dp))
                        Button({ onPinSaved(pin) }, enabled = pin.length in 4..6 && pin == confirm, modifier = Modifier.fillMaxWidth()) { Text("Save PIN") }
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            TextButton(onClick = onSkip, modifier = Modifier.align(Alignment.CenterHorizontally)) { Text("Skip for now") }
        }
    }

    @Composable
    private fun UnlockScreen(
        mode: String,
        busy: Boolean, externalError: String,
        onSystemUnlock: ((() -> Unit), (String) -> Unit) -> Unit,
        verifyPin: (String) -> Boolean,
        onUnlocked: () -> Unit,
        onLogout: () -> Unit
    ) {
        var pin by remember { mutableStateOf("") }
        var error by remember { mutableStateOf("") }
        var launched by remember { mutableStateOf(false) }
        LaunchedEffect(mode) {
            if (mode == "system" && !launched && !busy) {
                launched = true
                onSystemUnlock(onUnlocked) { error = it }
            }
        }
        Column(Modifier.fillMaxSize().background(Navy).statusBarsPadding().navigationBarsPadding().imePadding().verticalScroll(rememberScrollState()).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Lock, null, tint = ComposeColor.White, modifier = Modifier.size(54.dp))
            Spacer(Modifier.height(12.dp))
            Text("TRYIT Locked", color = ComposeColor.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text("Unlock to continue", color = ComposeColor(0xFFCBD5E1))
            Spacer(Modifier.height(24.dp))
            if (mode == "pin") {
                OutlinedTextField(pin, { if (it.length <= 6 && it.all(Char::isDigit)) pin = it }, label = { Text("App PIN") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = ComposeColor.White, unfocusedTextColor = ComposeColor.White, focusedBorderColor = ComposeColor.White, unfocusedBorderColor = ComposeColor(0xFF64748B), focusedLabelColor = ComposeColor.White, unfocusedLabelColor = ComposeColor(0xFFCBD5E1)), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Button({ if (verifyPin(pin)) { error=""; onUnlocked() } else error = "Incorrect PIN. After 5 attempts, wait 30 seconds." }, enabled = !busy && pin.length >= 4, modifier = Modifier.fillMaxWidth()) { Text("Unlock") }
            } else {
                Button({ onSystemUnlock(onUnlocked) { error = it } }, enabled = !busy, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Unlock with Device") }
            }
            if(busy) { Spacer(Modifier.height(14.dp)); CircularProgressIndicator(color=ComposeColor.White); Text("Reconnecting securely…",color=ComposeColor.White) }
            if(externalError.isNotBlank()) Text(externalError,color=ComposeColor(0xFFFCA5A5),modifier=Modifier.padding(top=12.dp))
            if (error.isNotBlank()) Text(error, color = ComposeColor(0xFFFCA5A5), modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(18.dp))
            TextButton(onClick = onLogout, enabled = !busy) { Text("Log out", color = ComposeColor.White) }
        }
    }

    @Composable
    private fun ProfileScreen(profile: UserProfile, onBack: () -> Unit) {
        Scaffold(topBar = { AppTopBar("My Profile", onBack) }) { pad ->
            Column(Modifier.padding(pad).padding(18.dp).verticalScroll(rememberScrollState())) {
                ProfileRow("Full Name", profile.fullName.ifBlank { "—" })
                ProfileRow("Username", profile.username.ifBlank { "—" })
                ProfileRow("Designation", profile.designation.ifBlank { "—" })
                ProfileRow("Role", profile.role.ifBlank { "—" })
                ProfileRow("Email", profile.email.ifBlank { "—" })
                ProfileRow("Mobile", profile.mobile.ifBlank { "—" })
            }
        }
    }

    @Composable
    private fun SettingsScreen(
        currentMode: String,
        currentTimeout: Long,
        server: String,
        deviceId: String,
        onSetSystem: () -> Unit,
        onSetPin: (String) -> Unit,
        onDisable: () -> Unit,
        onTimeout: (Long) -> Unit,
        onBack: () -> Unit
    ) {
        var mode by remember { mutableStateOf(currentMode) }
        var timeout by remember { mutableLongStateOf(currentTimeout) }
        var showPin by remember { mutableStateOf(false) }
        var pin by remember { mutableStateOf("") }
        Scaffold(topBar = { AppTopBar("Settings", onBack) }) { pad ->
            Column(Modifier.padding(pad).padding(18.dp).verticalScroll(rememberScrollState())) {
                Text("App Lock", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(8.dp))
                SettingChoice("System Screen Lock", mode == "system") { onSetSystem(); mode = "system" }
                SettingChoice("Custom App PIN", mode == "pin") { showPin = true }
                SettingChoice("Disabled", mode == "none") { onDisable(); mode = "none" }
                if (showPin) {
                    OutlinedTextField(pin, { if (it.length <= 6 && it.all(Char::isDigit)) pin = it }, label = { Text("New 4–6 digit PIN") }, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), visualTransformation = PasswordVisualTransformation(), modifier = Modifier.fillMaxWidth().padding(top = 8.dp))
                    Button({ onSetPin(pin); mode = "pin"; showPin = false; pin = "" }, enabled = pin.length in 4..6, modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) { Text("Save App PIN") }
                }
                Spacer(Modifier.height(22.dp))
                Text("Auto Lock", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Navy)
                listOf(0L to "Immediately", 60L to "1 minute", 300L to "5 minutes", 900L to "15 minutes", 1800L to "30 minutes").forEach { (v, label) ->
                    SettingChoice(label, timeout == v) { timeout = v; onTimeout(v) }
                }
                Spacer(Modifier.height(22.dp))
                Text("App & Device", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Navy)
                ProfileRow("Version", "1.1.0")
                ProfileRow("Connected to", "TRYIT OMS")
                Text("With App Lock enabled, remembered device login lasts up to 90 days. Access changes may require a fresh sign in.",color=ComposeColor(0xFF64748B),modifier=Modifier.padding(top=12.dp))
            }
        }
    }

    @Composable
    private fun AppTopBar(title: String, onBack: () -> Unit) {
        Surface(color = Navy) {
            Row(Modifier.fillMaxWidth().statusBarsPadding().height(58.dp).padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onBack) { Icon(Icons.Default.ArrowBack, null, tint = ComposeColor.White) }
                Text(title, color = ComposeColor.White, fontSize = 19.sp, fontWeight = FontWeight.Bold)
            }
        }
    }

    @Composable
    private fun ActionCard(icon: androidx.compose.ui.graphics.vector.ImageVector, title: String, subtitle: String, action: () -> Unit) {
        Surface(Modifier.fillMaxWidth().clickable(onClick = action), shape = RoundedCornerShape(18.dp), color = ComposeColor.White, shadowElevation = 2.dp) {
            Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                Surface(shape = RoundedCornerShape(14.dp), color = Soft) { Icon(icon, null, tint = Navy, modifier = Modifier.padding(11.dp).size(24.dp)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) { Text(title, fontWeight = FontWeight.Bold, color = Navy); Text(subtitle, color = ComposeColor(0xFF64748B), fontSize = 13.sp) }
                Icon(Icons.Default.ChevronRight, null, tint = ComposeColor(0xFF94A3B8))
            }
        }
    }

    @Composable
    private fun ProfileRow(label: String, value: String) {
        Surface(shape = RoundedCornerShape(14.dp), color = ComposeColor.White, modifier = Modifier.fillMaxWidth().padding(vertical = 5.dp)) {
            Column(Modifier.padding(14.dp)) { Text(label, fontSize = 12.sp, color = ComposeColor(0xFF64748B)); Text(value, fontWeight = FontWeight.SemiBold, color = Navy) }
        }
    }

    @Composable
    private fun SettingChoice(label: String, selected: Boolean, action: () -> Unit) {
        Row(Modifier.fillMaxWidth().clickable(onClick = action).padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            RadioButton(selected, onClick = action)
            Text(label, color = Navy)
        }
    }
}

data class UserProfile(
    val username: String = "",
    val fullName: String = "",
    val designation: String = "",
    val role: String = "",
    val email: String = "",
    val mobile: String = ""
) {
    companion object {
        fun fromJson(j: JSONObject): UserProfile = UserProfile(
            username = j.optString("username", j.optString("user", "")),
            fullName = j.optString("full_name", j.optString("name", "")),
            designation = j.optString("designation", ""),
            role = j.optString("role", ""),
            email = j.optString("email", ""),
            mobile = j.optString("mobile", j.optString("phone", ""))
        )
    }
}

data class ApiResult(val ok: Boolean, val error: String = "", val cookie: String = "", val profile: UserProfile? = null, val json: JSONObject? = null, val code: Int = 0)

object ApiClient {
    fun login(server: String, username: String, password: String, deviceId: String, deviceName: String): ApiResult {
        val body = JSONObject().apply {
            put("username", username); put("password", password); put("device_id", deviceId); put("device_name", deviceName)
        }
        val r = post(normalizeServer(server), "/api/oms/login", body, "")
        if (!r.ok) return r
        val cookie = r.cookie.substringBefore(';').trim()
        val profile = r.json?.optJSONObject("profile")?.let(UserProfile::fromJson)
            ?: UserProfile(username = r.json?.optString("user", username) ?: username, role = r.json?.optString("role", "") ?: "")
        return r.copy(cookie = cookie, profile = profile)
    }

    fun post(server: String, path: String, body: JSONObject, cookie: String): ApiResult {
        var conn: HttpURLConnection? = null
        return try {
            conn = URL(server.trimEnd('/') + path).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
            conn.instanceFollowRedirects = false
            conn.connectTimeout = 12000
            conn.readTimeout = 15000
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "application/json")
            if (cookie.isNotBlank()) conn.setRequestProperty("Cookie", cookie)
            conn.outputStream.use { it.write(body.toString().toByteArray(StandardCharsets.UTF_8)) }
            val code = conn.responseCode
            val stream = if (code in 200..299) conn.inputStream else conn.errorStream
            val text = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            val json = runCatching { JSONObject(text) }.getOrElse { JSONObject() }
            val ok = code in 200..299 && json.optBoolean("ok", false)
            val setCookie = conn.getHeaderField("Set-Cookie").orEmpty()
            ApiResult(ok, if (ok) "" else json.optString("error", "Request failed ($code)"), setCookie, json = json, code = code)
        } catch (e: Exception) {
            ApiResult(false, e.message ?: "Unable to connect to server")
        } finally { conn?.disconnect() }
    }
}

fun normalizeServer(raw: String): String {
    var s = raw.trim().trimEnd('/')
    if (s.isBlank()) return s
    if (!s.startsWith("http://") && !s.startsWith("https://")) s = "https://$s"
    return s
}

class SecureStore(context: Context) {
    private val prefs = context.getSharedPreferences("tryit_native_secure", Context.MODE_PRIVATE)
    private val alias = "tryit_native_phase_a_key"

    init { getOrCreateKey() }

    fun put(key: String, value: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, getOrCreateKey())
        val encrypted = cipher.doFinal(value.toByteArray(StandardCharsets.UTF_8))
        val payload = ByteArray(cipher.iv.size + encrypted.size)
        System.arraycopy(cipher.iv, 0, payload, 0, cipher.iv.size)
        System.arraycopy(encrypted, 0, payload, cipher.iv.size, encrypted.size)
        prefs.edit().putString(key, Base64.encodeToString(payload, Base64.NO_WRAP)).commit()
    }

    fun get(key: String): String {
        val raw = prefs.getString(key, null) ?: return ""
        return try {
            val payload = Base64.decode(raw, Base64.NO_WRAP)
            if (payload.size < 13) return ""
            val iv = payload.copyOfRange(0, 12)
            val encrypted = payload.copyOfRange(12, payload.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, getOrCreateKey(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(encrypted), StandardCharsets.UTF_8)
        } catch (_: Exception) { "" }
    }

    private fun getOrCreateKey(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(alias, null) as? SecretKey)?.let { return it }
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setRandomizedEncryptionRequired(true)
                .build()
        )
        return generator.generateKey()
    }
}

