package gr.chris.comicreader

import android.os.Bundle
import androidx.activity.compose.setContent
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.fragment.app.FragmentActivity

val BgColor = Color(0xFF12101C)
val SurfaceColor = Color(0xFF1F1B33)
val AccentColor = Color(0xFFB388FF)

class MainActivity : FragmentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val prefs = Prefs(this)
        setContent {
            MaterialTheme(
                colorScheme = darkColorScheme(
                    primary = AccentColor,
                    background = BgColor,
                    surface = SurfaceColor,
                    onBackground = Color.White,
                    onSurface = Color.White
                )
            ) {
                Root(prefs, this)
            }
        }
    }
}

enum class Screen { LOCK, SETUP, WELCOME, LIBRARY, READER }

@Composable
fun Root(prefs: Prefs, activity: FragmentActivity) {
    fun afterLock(): Screen = if (prefs.setupDone) Screen.WELCOME else Screen.SETUP

    var screen by remember { mutableStateOf(if (prefs.lock) Screen.LOCK else afterLock()) }
    var comic by remember { mutableStateOf<Comic?>(null) }

    when (screen) {
        Screen.LOCK -> LockScreen(activity) { screen = afterLock() }
        Screen.SETUP -> SetupScreen(prefs) { screen = Screen.LIBRARY }
        Screen.WELCOME -> WelcomeScreen(prefs.name) { screen = Screen.LIBRARY }
        Screen.LIBRARY -> LibraryScreen(
            prefs = prefs,
            onOpen = { comic = it; screen = Screen.READER },
            onSetup = { screen = Screen.SETUP }
        )
        Screen.READER -> comic?.let { c ->
            ReaderScreen(c, prefs, activity) { screen = Screen.LIBRARY }
        }
    }
}

/** Fingerprint / face / PIN. If the phone has no screen lock at all, the app just opens. */
fun authenticate(activity: FragmentActivity, onOk: () -> Unit, onFail: () -> Unit) {
    val auth = BiometricManager.Authenticators.BIOMETRIC_WEAK or
        BiometricManager.Authenticators.DEVICE_CREDENTIAL
    if (BiometricManager.from(activity).canAuthenticate(auth) != BiometricManager.BIOMETRIC_SUCCESS) {
        onOk()
        return
    }
    val prompt = BiometricPrompt(
        activity,
        ContextCompat.getMainExecutor(activity),
        object : BiometricPrompt.AuthenticationCallback() {
            override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                onOk()
            }

            override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                onFail()
            }
        }
    )
    val info = BiometricPrompt.PromptInfo.Builder()
        .setTitle("Comic Reader")
        .setSubtitle("Unlock to continue")
        .setAllowedAuthenticators(auth)
        .build()
    prompt.authenticate(info)
}

@Composable
fun LockScreen(activity: FragmentActivity, onOk: () -> Unit) {
    var failed by remember { mutableStateOf(false) }
    fun ask() {
        failed = false
        authenticate(activity, onOk) { failed = true }
    }
    LaunchedEffect(Unit) { ask() }
    Column(
        Modifier.fillMaxSize().background(BgColor).padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("\uD83D\uDD12", fontSize = 56.sp)
        Text("Comic Reader", color = Color.White, fontSize = 26.sp, fontWeight = FontWeight.Bold)
        if (failed) {
            Button(onClick = { ask() }, modifier = Modifier.padding(top = 24.dp)) { Text("Unlock") }
        }
    }
}
