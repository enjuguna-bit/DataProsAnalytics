package com.datapros.app.billing

import com.datapros.app.BuildConfig
import okhttp3.CertificatePinner
import okhttp3.OkHttpClient
import java.net.URL
import java.util.concurrent.TimeUnit

/**
 * Ported from BulkSMS2 NetworkSecurityConfig.
 */
object NetworkSecurityConfig {

    private val BILLING_DOMAIN: String by lazy {
        try {
            URL(BuildConfig.BILLING_BASE_URL).host
        } catch (_: Exception) {
            "bulksms-webhook.enjuguna794.workers.dev"
        }
    }

    private val CLOUDFLARE_PINS = listOf(
        // Current Google Trust Services / workers.dev chain observed on device
        "sha256/kIdp6NNEd8wsugYyyIYFsi1ylMCED3hZbSR8ZFsa/A4=",
        "sha256/mEflZT5enoR1FuXLgYYGqnVEoZvmf9c2bVBpiOjYQ0c=",
        // Previous workers.dev chain backups kept for certificate rotation safety
        "sha256/Wf2JRxU+Y7V2OcYEpJFjJFoLOtrCwU7G8OKxPgCtdtk=",
        "sha256/RQeZkB42znUfsDIIFWIRiYEcKl7nHwNFwWCrnMMJbVc=",
        "sha256/r/mIkG3eEpVdm+u/ko/cwxzOMo1bk4TyHIlByibiA5E="
    )

    fun createCertificatePinner(): CertificatePinner =
        CertificatePinner.Builder()
            .apply { CLOUDFLARE_PINS.forEach { add(BILLING_DOMAIN, it) } }
            .build()

    fun createSecureBillingClient(): OkHttpClient =
        baseBuilder()
            .certificatePinner(createCertificatePinner())
            .build()

    fun createStandardClient(): OkHttpClient =
        baseBuilder()
            .build()

    private fun baseBuilder(): OkHttpClient.Builder =
        OkHttpClient.Builder()
            .connectTimeout(15000, TimeUnit.MILLISECONDS)
            .readTimeout(20000, TimeUnit.MILLISECONDS)
            .writeTimeout(15000, TimeUnit.MILLISECONDS)
            .retryOnConnectionFailure(true)
}
