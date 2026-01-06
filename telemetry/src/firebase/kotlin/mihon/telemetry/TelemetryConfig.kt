package mihon.telemetry

import android.content.Context
import com.google.firebase.FirebaseApp
import com.google.firebase.analytics.FirebaseAnalytics
import com.google.firebase.crashlytics.FirebaseCrashlytics

object TelemetryConfig {
    private var analytics: FirebaseAnalytics? = null
    private var crashlytics: FirebaseCrashlytics? = null

    fun init(context: Context) {
        // To stop forks/test builds from polluting our data
        if (!context.isYomihonProductionApp()) return

        analytics = FirebaseAnalytics.getInstance(context)
        FirebaseApp.initializeApp(context)
        crashlytics = FirebaseCrashlytics.getInstance()
    }

    fun setAnalyticsEnabled(enabled: Boolean) {
        analytics?.setAnalyticsCollectionEnabled(enabled)
    }

    fun setCrashlyticsEnabled(enabled: Boolean) {
        crashlytics?.isCrashlyticsCollectionEnabled = enabled
    }

    private fun Context.isYomihonProductionApp(): Boolean {
        if (packageName !in YOMIHON_PACKAGES) return false

        return packageManager.getPackageInfo(packageName, SignatureFlags)
            .getCertificateFingerprints()
            .any { it == YOMIHON_CERTIFICATE_FINGERPRINT }
    }
}

private val YOMIHON_PACKAGES = hashSetOf("app.yomihon", "app.yomihon.debug", "app.yomihon.dev")
private const val YOMIHON_CERTIFICATE_FINGERPRINT =
    "47:94:5A:AD:F2:69:AD:6C:E3:E0:57:FE:96:E7:26:2A:41:17:CC:76:E3:47:4D:FE:B9:16:E2:CA:90:00:EE:D4"
