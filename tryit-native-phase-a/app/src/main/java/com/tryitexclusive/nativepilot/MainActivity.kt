package com.tryitexclusive.nativepilot

import android.content.Context
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
private val Cream = ComposeColor(0xFFFFF9F1)
private val Soft = ComposeColor(0xFFF4F7FB)
private val Green = ComposeColor(0xFF0E9F6E)

class MainActivity : FragmentActivity() {
    private lateinit var secure: SecureStore
    private var lockRequested by mutableStateOf(false)
    private var lastBackAt = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(11, 24, 48)
        secure = SecureStore(this)
        ensureDeviceId()

        setContent {
            MaterialTheme(
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
        val scope = rememberCoroutineScope()

        LaunchedEffect(lockRequested) {
            if (lockRequested && secure.get("session_cookie").isNotBlank()) {
                route = "unlock"
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
                            secure.put("session_cookie", result.cookie)
                            secure.put("remembered_user", if (remember) user else "")
                            profile = result.profile ?: UserProfile(username = user)
                            storeProfile(profile)
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
                onSystemLock = {
                    secure.put("lock_mode", "system")
                    secure.put("lock_timeout", "0")
                    secure.put("lock_setup_done", "1")
                    route = "home"
                },
                onPinSaved = { pin ->
                    savePin(pin)
                    secure.put("lock_mode", "pin")
                    secure.put("lock_timeout", "0")
                    secure.put("lock_setup_done", "1")
                    route = "home"
                },
                onSkip = {
                    secure.put("lock_mode", "none")
                    secure.put("lock_setup_done", "1")
                    route = "home"
                }
            )
            "unlock" -> UnlockScreen(
                mode = secure.get("lock_mode"),
                onSystemUnlock = { success, error -> launchSystemUnlock(success, error) },
                verifyPin = { verifyPin(it) },
                onUnlocked = { route = "home" },
                onLogout = {
                    scope.launch {
                        logoutRemote()
                        clearSession()
                        route = "login"
                    }
                }
            )
            "profile" -> ProfileScreen(profile = profile, onBack = { route = "home" })
            "settings" -> SettingsScreen(
                currentMode = secure.get("lock_mode"),
                currentTimeout = secure.get("lock_timeout").toLongOrNull() ?: 0L,
                server = secure.get("server_url"),
                deviceId = secure.get("device_id"),
                onSetSystem = { secure.put("lock_mode", "system") },
                onSetPin = { pin -> savePin(pin); secure.put("lock_mode", "pin") },
                onDisable = { secure.put("lock_mode", "none") },
                onTimeout = { secure.put("lock_timeout", it.toString()) },
                onBack = { route = "home" }
            )
            else -> HomeScreen(
                profile = profile,
                server = secure.get("server_url"),
                onProfile = { route = "profile" },
                onSettings = { route = "settings" },
                onLock = {
                    val mode = secure.get("lock_mode")
                    if (mode != "none" && mode.isNotBlank()) route = "unlock"
                },
                onLogout = {
                    scope.launch {
                        logoutRemote()
                        clearSession()
                        route = "login"
                    }
                }
            )
        }
    }

    private fun initialRoute(): String {
        val cookie = secure.get("session_cookie")
        if (cookie.isBlank()) return "login"
        if (secure.get("lock_setup_done") != "1") return "locksetup"
        return if (secure.get("lock_mode") == "none") "home" else "unlock"
    }

    private suspend fun logoutRemote() = withContext(Dispatchers.IO) {
        runCatching {
            ApiClient.post(
                secure.get("server_url"),
                "/api/oms/logout",
                JSONObject(),
                secure.get("session_cookie")
            )
        }
    }

    private fun clearSession() {
        secure.put("session_cookie", "")
        secure.put("background_at", "")
        secure.put("profile_json", "")
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
        secure.put("pin_hash", hashPin(pin, salt))
    }

    private fun verifyPin(pin: String): Boolean {
        val saltRaw = secure.get("pin_salt")
        val expected = secure.get("pin_hash")
        if (saltRaw.isBlank() || expected.isBlank()) return false
        val salt = Base64.decode(saltRaw, Base64.NO_WRAP)
        return MessageDigest.isEqual(
            expected.toByteArray(StandardCharsets.UTF_8),
            hashPin(pin, salt).toByteArray(StandardCharsets.UTF_8)
        )
    }

    private fun hashPin(pin: String, salt: ByteArray): String {
        val md = MessageDigest.getInstance("SHA-256")
        md.update(salt)
        md.update(pin.toByteArray(StandardCharsets.UTF_8))
        return Base64.encodeToString(md.digest(), Base64.NO_WRAP)
    }

    @Composable
    private fun LoginScreen(
        initialServer: String,
        rememberedUser: String,
        onLogin: (String, String, String, Boolean, (Boolean) -> Unit, (String) -> Unit) -> Unit,
        onForgot: () -> Unit
    ) {
        var server by rememberSaveable { mutableStateOf(initialServer) }
        var username by rememberSaveable { mutableStateOf(rememberedUser) }
        var password by rememberSaveable { mutableStateOf("") }
        var remember by rememberSaveable { mutableStateOf(rememberedUser.isNotBlank()) }
        var showPassword by rememberSaveable { mutableStateOf(false) }
        var busy by remember { mutableStateOf(false) }
        var error by remember { mutableStateOf("") }

        Box(Modifier.fillMaxSize().background(Cream)) {
            Column(
                Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(22.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Spacer(Modifier.height(34.dp))
                Surface(shape = RoundedCornerShape(24.dp), color = ComposeColor.White, shadowElevation = 8.dp) {
                    Column(Modifier.padding(22.dp).widthIn(max = 480.dp)) {
                        Text("TRYIT", fontSize = 32.sp, fontWeight = FontWeight.Black, color = Navy)
                        Text("Native Pilot • Phase A", color = Red, fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(22.dp))
                        Text("Sign in", fontSize = 24.sp, fontWeight = FontWeight.Bold, color = Navy)
                        Text("Use your existing KIRAN OMS account.", color = ComposeColor(0xFF64748B))
                        Spacer(Modifier.height(18.dp))
                        OutlinedTextField(server, { server = it }, label = { Text("Server URL") }, placeholder = { Text("https://... or http://192.168...") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(username, { username = it }, label = { Text("Username") }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                        Spacer(Modifier.height(10.dp))
                        OutlinedTextField(
                            password, { password = it }, label = { Text("Password") }, modifier = Modifier.fillMaxWidth(), singleLine = true,
                            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
                            trailingIcon = { IconButton({ showPassword = !showPassword }) { Icon(if (showPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, null) } }
                        )
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(remember, { remember = it }); Text("Remember username")
                            Spacer(Modifier.weight(1f))
                            TextButton(onForgot) { Text("Forgot password?") }
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
                        Text("App version 0.1.0 • Native Android", color = ComposeColor(0xFF94A3B8), fontSize = 12.sp)
                    }
                }
            }
        }
    }

    @Composable
    private fun ForgotPasswordScreen(server: String, onBack: () -> Unit) {
        var user by rememberSaveable { mutableStateOf("") }
        var otp by rememberSaveable { mutableStateOf("") }
        var newPass by rememberSaveable { mutableStateOf("") }
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
        var pin by rememberSaveable { mutableStateOf("") }
        var confirm by rememberSaveable { mutableStateOf("") }
        var showPinForm by rememberSaveable { mutableStateOf(false) }
        Column(Modifier.fillMaxSize().background(Soft).padding(22.dp).verticalScroll(rememberScrollState())) {
            Spacer(Modifier.height(28.dp))
            Icon(Icons.Default.Lock, null, tint = Navy, modifier = Modifier.size(52.dp))
            Spacer(Modifier.height(12.dp))
            Text("Protect TRYIT", fontSize = 28.sp, fontWeight = FontWeight.Black, color = Navy)
            Text("Choose how the app should unlock when you return.", color = ComposeColor(0xFF64748B))
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
        onSystemUnlock: ((() -> Unit), (String) -> Unit) -> Unit,
        verifyPin: (String) -> Boolean,
        onUnlocked: () -> Unit,
        onLogout: () -> Unit
    ) {
        var pin by rememberSaveable { mutableStateOf("") }
        var error by remember { mutableStateOf("") }
        var launched by remember { mutableStateOf(false) }
        LaunchedEffect(mode) {
            if (mode == "system" && !launched) {
                launched = true
                onSystemUnlock(onUnlocked) { error = it }
            }
        }
        Column(Modifier.fillMaxSize().background(Navy).padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.Center) {
            Icon(Icons.Default.Lock, null, tint = ComposeColor.White, modifier = Modifier.size(54.dp))
            Spacer(Modifier.height(12.dp))
            Text("TRYIT Locked", color = ComposeColor.White, fontSize = 26.sp, fontWeight = FontWeight.Black)
            Text("Unlock to continue", color = ComposeColor(0xFFCBD5E1))
            Spacer(Modifier.height(24.dp))
            if (mode == "pin") {
                OutlinedTextField(pin, { if (it.length <= 6 && it.all(Char::isDigit)) pin = it }, label = { Text("App PIN") }, visualTransformation = PasswordVisualTransformation(), keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword), colors = OutlinedTextFieldDefaults.colors(focusedTextColor = ComposeColor.White, unfocusedTextColor = ComposeColor.White, focusedBorderColor = ComposeColor.White, unfocusedBorderColor = ComposeColor(0xFF64748B), focusedLabelColor = ComposeColor.White, unfocusedLabelColor = ComposeColor(0xFFCBD5E1)), modifier = Modifier.fillMaxWidth())
                Spacer(Modifier.height(12.dp))
                Button({ if (verifyPin(pin)) onUnlocked() else error = "Incorrect PIN" }, enabled = pin.length >= 4, modifier = Modifier.fillMaxWidth()) { Text("Unlock") }
            } else {
                Button({ onSystemUnlock(onUnlocked) { error = it } }, modifier = Modifier.fillMaxWidth()) { Icon(Icons.Default.Fingerprint, null); Spacer(Modifier.width(8.dp)); Text("Unlock with Device") }
            }
            if (error.isNotBlank()) Text(error, color = ComposeColor(0xFFFCA5A5), modifier = Modifier.padding(top = 12.dp))
            Spacer(Modifier.height(18.dp))
            TextButton(onClick = onLogout) { Text("Log out", color = ComposeColor.White) }
        }
    }

    @Composable
    private fun HomeScreen(profile: UserProfile, server: String, onProfile: () -> Unit, onSettings: () -> Unit, onLock: () -> Unit, onLogout: () -> Unit) {
        Scaffold(
            topBar = {
                Surface(color = Navy, shadowElevation = 4.dp) {
                    Row(Modifier.fillMaxWidth().statusBarsPadding().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Text("TRYIT", color = ComposeColor.White, fontSize = 22.sp, fontWeight = FontWeight.Black)
                        Spacer(Modifier.weight(1f))
                        Column(Modifier.clickable(onClick = onProfile), horizontalAlignment = Alignment.End) {
                            Text(profile.fullName.ifBlank { profile.username.ifBlank { "User" } }, color = ComposeColor.White, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                            Text(profile.designation.ifBlank { profile.role.ifBlank { "Account" } }, color = ComposeColor(0xFFCBD5E1), fontSize = 11.sp)
                        }
                        IconButton(onClick = onSettings) { Icon(Icons.Default.Settings, null, tint = ComposeColor.White) }
                    }
                }
            }
        ) { pad ->
            Column(Modifier.padding(pad).padding(16.dp).verticalScroll(rememberScrollState())) {
                Surface(shape = RoundedCornerShape(22.dp), color = Navy) {
                    Column(Modifier.padding(20.dp)) {
                        Text("Native Foundation Ready", color = ComposeColor.White, fontSize = 24.sp, fontWeight = FontWeight.Black)
                        Text("Phase A • Login, secure session, app lock, profile and settings", color = ComposeColor(0xFFCBD5E1))
                    }
                }
                Spacer(Modifier.height(16.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusTile("Server", if (server.startsWith("https://")) "HTTPS" else "LAN / HTTP", Icons.Default.Cloud, Modifier.weight(1f))
                    StatusTile("Role", profile.role.ifBlank { "—" }, Icons.Default.Badge, Modifier.weight(1f))
                }
                Spacer(Modifier.height(12.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    StatusTile("App Lock", secure.get("lock_mode").ifBlank { "None" }.replaceFirstChar { it.uppercase() }, Icons.Default.Lock, Modifier.weight(1f))
                    StatusTile("Native", "Android", Icons.Default.PhoneAndroid, Modifier.weight(1f))
                }
                Spacer(Modifier.height(20.dp))
                Text("Phase A controls", fontWeight = FontWeight.Bold, color = Navy)
                Spacer(Modifier.height(8.dp))
                ActionCard(Icons.Default.Person, "My Profile", "View account details loaded from OMS", onProfile)
                Spacer(Modifier.height(10.dp))
                ActionCard(Icons.Default.Settings, "Settings", "App lock, timeout and device information", onSettings)
                Spacer(Modifier.height(10.dp))
                ActionCard(Icons.Default.Lock, "Lock App Now", "Protect the current session immediately", onLock)
                Spacer(Modifier.height(10.dp))
                ActionCard(Icons.Default.Logout, "Log Out", "End current OMS session", onLogout)
                Spacer(Modifier.height(20.dp))
                Surface(shape = RoundedCornerShape(18.dp), color = ComposeColor.White) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Next native modules", fontWeight = FontWeight.Bold, color = Navy)
                        Text("Dashboard data, Orders, Masters, Production, Dispatch, Stock, Party Dashboard and Reports will connect to the same OMS backend in the next phases.", color = ComposeColor(0xFF64748B), modifier = Modifier.padding(top = 6.dp))
                    }
                }
            }
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
        var pin by rememberSaveable { mutableStateOf("") }
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
                ProfileRow("Version", "0.1.0 Phase A")
                ProfileRow("Server", server.ifBlank { "Not configured" })
                ProfileRow("Device ID", deviceId)
                ProfileRow("Package", "com.tryitexclusive.nativepilot")
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
    private fun StatusTile(label: String, value: String, icon: androidx.compose.ui.graphics.vector.ImageVector, modifier: Modifier = Modifier) {
        Surface(modifier, shape = RoundedCornerShape(18.dp), color = ComposeColor.White, shadowElevation = 2.dp) {
            Column(Modifier.padding(15.dp)) {
                Icon(icon, null, tint = Red)
                Spacer(Modifier.height(10.dp))
                Text(value, fontWeight = FontWeight.Bold, color = Navy)
                Text(label, fontSize = 12.sp, color = ComposeColor(0xFF64748B))
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

data class ApiResult(val ok: Boolean, val error: String = "", val cookie: String = "", val profile: UserProfile? = null, val json: JSONObject? = null)

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
        return try {
            val conn = URL(server.trimEnd('/') + path).openConnection() as HttpURLConnection
            conn.requestMethod = "POST"
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
            val ok = code in 200..299 && json.optBoolean("ok", true)
            val setCookie = conn.getHeaderField("Set-Cookie").orEmpty()
            ApiResult(ok, if (ok) "" else json.optString("error", "Request failed ($code)"), setCookie, json = json)
        } catch (e: Exception) {
            ApiResult(false, e.message ?: "Unable to connect to server")
        }
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
        prefs.edit().putString(key, Base64.encodeToString(payload, Base64.NO_WRAP)).apply()
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
