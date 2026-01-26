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

private val YOMIHON_PACKAGES = hashSetOf("app.yomihon", "app.yomihon.debug")
private const val YOMIHON_CERTIFICATE_FINGERPRINT =
    "80:0D:DF:D7:15:EE:C6:F4:8E:69:C8:69:54:38:C0:9D:54:3E:6B:85:34:35:62:24:C6:7A:D9:EF:A5:B9:B0:CE"
