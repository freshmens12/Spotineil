package com.spotineil.core.network

import android.content.Context
import android.content.pm.PackageManager
import java.security.MessageDigest
import okhttp3.Interceptor
import okhttp3.Response

/**
 * Sends the headers Google uses to check "Android apps" API-key restrictions
 * (package name + signing-certificate SHA-1). Lets the YouTube key be restricted to this app,
 * so a copy of the key pulled from the APK is useless elsewhere.
 */
class AndroidAppIdentityInterceptor(context: Context) : Interceptor {
    private val packageName = context.packageName
    private val certSha1: String? = runCatching {
        val info = context.packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signer = info.signingInfo?.apkContentsSigners?.firstOrNull() ?: return@runCatching null
        MessageDigest.getInstance("SHA-1").digest(signer.toByteArray()).joinToString("") { "%02X".format(it) }
    }.getOrNull()

    override fun intercept(chain: Interceptor.Chain): Response {
        val builder = chain.request().newBuilder().header("X-Android-Package", packageName)
        certSha1?.let { builder.header("X-Android-Cert", it) }
        return chain.proceed(builder.build())
    }
}
