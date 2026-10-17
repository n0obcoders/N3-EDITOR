package com.nitinshukla.notebook.security

import androidx.biometric.BiometricManager
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_STRONG
import androidx.biometric.BiometricManager.Authenticators.BIOMETRIC_WEAK
import androidx.biometric.BiometricManager.Authenticators.DEVICE_CREDENTIAL
import androidx.biometric.BiometricPrompt
import androidx.fragment.app.FragmentActivity
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.nitinshukla.notebook.core.repository.SettingsRepository
import com.nitinshukla.notebook.core.settings.AppSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import javax.inject.Inject
import javax.inject.Singleton
import kotlin.coroutines.resume
import kotlinx.coroutines.suspendCancellableCoroutine

/**
 * Tracks whether the app is locked. The app locks when it goes to the background for
 * longer than the configured auto-lock period. Individual locked notes always need a
 * fresh authentication to open, regardless of app lock.
 */
@Singleton
class AppLockManager @Inject constructor(private val settings: SettingsRepository) : DefaultLifecycleObserver {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val _locked = MutableStateFlow(false)
    val locked: StateFlow<Boolean> = _locked
    private var backgroundedAt: Long? = null
    private var current: AppSettings = AppSettings()

    init {
        ProcessLifecycleOwner.get().lifecycle.addObserver(this)
        scope.launch {
            settings.settings.collect { s ->
                val wasEnabled = current.appLockEnabled
                current = s
                if (s.appLockEnabled && !wasEnabled) _locked.value = true
                if (!s.appLockEnabled) _locked.value = false
            }
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        backgroundedAt = System.currentTimeMillis()
    }

    override fun onStart(owner: LifecycleOwner) {
        val away = backgroundedAt?.let { System.currentTimeMillis() - it } ?: return
        backgroundedAt = null
        if (current.appLockEnabled && away >= current.autoLockMinutes * 60_000L) _locked.value = true
    }

    fun lockNow() {
        if (current.appLockEnabled) _locked.value = true
    }

    fun unlock() {
        _locked.value = false
    }

    companion object {
        const val AUTHENTICATORS = BIOMETRIC_STRONG or BIOMETRIC_WEAK or DEVICE_CREDENTIAL

        fun canAuthenticate(activity: FragmentActivity): Boolean =
            BiometricManager.from(activity).canAuthenticate(AUTHENTICATORS) == BiometricManager.BIOMETRIC_SUCCESS

        /** Shows the system prompt and returns true when the user authenticated. */
        suspend fun authenticate(activity: FragmentActivity, title: String, subtitle: String? = null): Boolean =
            suspendCancellableCoroutine { cont ->
                val prompt = BiometricPrompt(
                    activity,
                    androidx.core.content.ContextCompat.getMainExecutor(activity),
                    object : BiometricPrompt.AuthenticationCallback() {
                        override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                            if (cont.isActive) cont.resume(true)
                        }

                        override fun onAuthenticationError(errorCode: Int, errString: CharSequence) {
                            if (cont.isActive) cont.resume(false)
                        }
                    },
                )
                val info = BiometricPrompt.PromptInfo.Builder()
                    .setTitle(title)
                    .apply { subtitle?.let { setSubtitle(it) } }
                    .setAllowedAuthenticators(AUTHENTICATORS)
                    .build()
                prompt.authenticate(info)
                cont.invokeOnCancellation { prompt.cancelAuthentication() }
            }
    }
}
