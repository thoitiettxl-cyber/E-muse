package io.github.thoitiet.emuse

import android.app.Application
import android.content.pm.ApplicationInfo
import android.os.Build
import android.util.Log
import org.lsposed.hiddenapibypass.HiddenApiBypass

/**
 * Hidden-API exemptions, mirroring Eta's PredictiveBackController pattern:
 * applied once at startup, every call wrapped in runCatching so a failure
 * on some ROM never crashes the app.
 */
object HiddenApi {
    private const val TAG = "E-Muse"

    fun applyDefaults(app: Application) {
        applyPredictiveBack(app.applicationInfo)
    }

    /**
     * Eta parity: allow ApplicationInfo.setEnableOnBackInvokedCallback on
     * Android 14+ (UpsideDownCake) so predictive back can be enabled.
     */
    private fun applyPredictiveBack(applicationInfo: ApplicationInfo) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) return
        val method = "setEnableOnBackInvokedCallback"
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(
                "Landroid/content/pm/ApplicationInfo;->$method",
            )
            ApplicationInfo::class.java.getDeclaredMethod(
                method,
                Boolean::class.javaPrimitiveType,
            ).apply {
                isAccessible = true
                invoke(applicationInfo, true)
            }
        }.onFailure {
            Log.w(TAG, "predictive back exemption failed: ${it.message}")
        }
    }

    /** Exempt extra hidden-API signatures when a future feature needs them. */
    fun addExemptions(vararg signatures: String) {
        runCatching {
            HiddenApiBypass.addHiddenApiExemptions(*signatures)
        }.onFailure {
            Log.w(TAG, "addHiddenApiExemptions failed: ${it.message}")
        }
    }
}
