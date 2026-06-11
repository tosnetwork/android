package com.tonapps.wallet.api

import android.content.Context
import android.os.Build
import com.tonapps.extensions.appVersionName
import com.tonapps.extensions.locale
import com.tonapps.network.interceptor.AcceptLanguageInterceptor
import com.tonapps.network.interceptor.AuthorizationInterceptor
import com.tonapps.wallet.api.entity.ConfigEntity
import okhttp3.CertificatePinner
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.util.concurrent.TimeUnit

abstract class CoreAPI(private val context: Context) {

    val appVersionName = context.appVersionName

    private val userAgent = "TOSWallet/${appVersionName} (Linux; Android ${Build.VERSION.RELEASE}; ${Build.MODEL})"

    // TOS: plain OkHttp only. Cronet (Google Play Services networking) has been removed
    // so the wallet has no dependency on Google Play Services for HTTP.
    val defaultHttpClient = baseOkHttpClientBuilder(
        timeoutSeconds = 30,
        interceptors = listOf(
            UserAgentInterceptor(userAgent),
        )
    ).build()

    val seeHttpClient = baseOkHttpClientBuilder(
        timeoutSeconds = 60,
        interceptors = listOf(
            UserAgentInterceptor(userAgent),
        )
    ).build()

    fun tonAPIHttpClient(config: () -> ConfigEntity): OkHttpClient {
        return createTonAPIHttpClient(
            context = context,
            userAgent = userAgent,
            tosApiKey = { config().tosApiKey },
            allowDomains = { config().domains }
        )
    }

    private companion object {

        // Builds a CertificatePinner from Constants.TOS_CERT_PINS. Returns null when no
        // pins are configured, leaving the standard system trust store in effect.
        // Pinning only constrains the explicitly listed hosts; all other hosts are
        // unaffected, so this is safe to attach to every client.
        private fun certificatePinner(): CertificatePinner? {
            if (Constants.TOS_CERT_PINS.isEmpty()) {
                return null
            }
            val builder = CertificatePinner.Builder()
            for ((host, pins) in Constants.TOS_CERT_PINS) {
                for (pin in pins) {
                    builder.add(host, pin)
                }
            }
            return builder.build()
        }

        class UserAgentInterceptor(private val userAgent: String) : Interceptor {
            override fun intercept(chain: Interceptor.Chain): okhttp3.Response {
                val request = chain.request().newBuilder()
                    .addHeader("User-Agent", userAgent)
                    .build()
                return chain.proceed(request)
            }
        }

        private fun baseOkHttpClientBuilder(
            timeoutSeconds: Long = 30,
            interceptors: List<Interceptor> = emptyList()
        ): OkHttpClient.Builder {
            val builder = OkHttpClient().newBuilder()
                .retryOnConnectionFailure(true)
                .connectTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .readTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .writeTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .callTimeout(timeoutSeconds, TimeUnit.SECONDS)
                .pingInterval(timeoutSeconds, TimeUnit.SECONDS)
                .followSslRedirects(true)
                .followRedirects(true)

            certificatePinner()?.let { builder.certificatePinner(it) }

            for (interceptor in interceptors) {
                builder.addInterceptor(interceptor)
            }

            return builder
        }

        private fun createTonAPIHttpClient(
            userAgent: String,
            context: Context,
            tosApiKey: () -> String,
            allowDomains: () -> List<String>
        ): OkHttpClient {
            val interceptors = listOf(
                UserAgentInterceptor(userAgent),
                AcceptLanguageInterceptor(context.locale),
                AuthorizationInterceptor.bearer(
                    token = tosApiKey,
                    allowDomains = allowDomains
                )
            )

            return baseOkHttpClientBuilder(
                interceptors = interceptors
            ).build()
        }
    }
}
