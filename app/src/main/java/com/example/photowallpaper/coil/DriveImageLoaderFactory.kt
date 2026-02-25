package com.example.photowallpaper.coil

import android.content.Context
import android.util.Log
import coil.ImageLoader
import coil.ImageLoaderFactory
import coil.disk.DiskCache
import com.google.android.gms.auth.GoogleAuthUtil
import com.google.android.gms.auth.api.signin.GoogleSignIn
import okhttp3.Cache
import okhttp3.Interceptor
import okhttp3.OkHttpClient
import java.io.File

/**
 * Provides a Coil [ImageLoader] that automatically injects a Google OAuth
 * Bearer token into requests for Google Drive thumbnail URLs.
 *
 * Includes both OkHttp HTTP cache and Coil disk cache so thumbnails survive
 * memory pressure when the app is backgrounded.
 */
class DriveImageLoaderFactory(private val context: Context) : ImageLoaderFactory {

    override fun newImageLoader(): ImageLoader {
        val authInterceptor = Interceptor { chain ->
            val original = chain.request()
            val url = original.url.toString()

            // Only add auth header for Google-hosted thumbnail URLs
            if (!url.contains("googleusercontent.com") && !url.contains("googleapis.com")) {
                return@Interceptor chain.proceed(original)
            }

            val token = try {
                val account = GoogleSignIn.getLastSignedInAccount(context)?.account
                if (account != null) {
                    GoogleAuthUtil.getToken(
                        context,
                        account,
                        "oauth2:https://www.googleapis.com/auth/drive.readonly"
                    )
                } else null
            } catch (e: Exception) {
                Log.w("DriveImageLoader", "Failed to get auth token for thumbnail", e)
                null
            }

            val request = if (token != null) {
                original.newBuilder()
                    .addHeader("Authorization", "Bearer $token")
                    .build()
            } else {
                original
            }
            chain.proceed(request)
        }

        // OkHttp HTTP-level cache (respects Cache-Control headers)
        val httpCacheDir = File(context.cacheDir, "http_thumbnails")
        val httpCache = Cache(httpCacheDir, 50L * 1024 * 1024) // 50 MB

        val okHttpClient = OkHttpClient.Builder()
            .addInterceptor(authInterceptor)
            .cache(httpCache)
            .build()

        // Coil disk cache (decoded image cache — survives memory eviction)
        val diskCache = DiskCache.Builder()
            .directory(File(context.cacheDir, "coil_thumbnails"))
            .maxSizeBytes(100L * 1024 * 1024) // 100 MB
            .build()

        return ImageLoader.Builder(context)
            .okHttpClient(okHttpClient)
            .diskCache(diskCache)
            .crossfade(true)
            .build()
    }
}
