package com.yuvraj.movieroll

import android.app.Application
import android.content.Context
import android.graphics.Bitmap
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.bitmapConfig
import coil3.request.crossfade
import com.yuvraj.movieroll.app.plugin.PluginRepositoryService
import com.yuvraj.movieroll.core.network.DohDns
import com.yuvraj.movieroll.core.registry.PluginRegistry
import com.yuvraj.movieroll.core.util.AppSessionManager
import com.yuvraj.movieroll.data.preferences.PreferencesRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath

class MovieRollApp : Application(), SingletonImageLoader.Factory {
    override fun onCreate() {
        super.onCreate()
        val prefs = getSharedPreferences("movieroll_session", Context.MODE_PRIVATE)
        val lastIndex = prefs.getInt("launch_session_index", 0)
        val nextIndex = lastIndex + 1
        prefs.edit().putInt("launch_session_index", nextIndex).apply()
        AppSessionManager.initialize(nextIndex)

        // Initialize 50MB HTTP disk cache and connection pool
        DohDns.initHttpCache(cacheDir)
        com.yuvraj.movieroll.ui.player.sequential.SequentialStreamManager.cleanOrphanedCache(this)

        // Synchronously initialize JsPluginStorage, AddonRepository, and P2P engine instances
        com.yuvraj.movieroll.core.js.JsPluginStorage.getInstance(this)
        com.yuvraj.movieroll.core.addons.AddonRepository.getInstance(this)
        com.yuvraj.movieroll.core.p2p.P2pStreamingEngine.initialize(this)
        com.yuvraj.movieroll.core.p2p.P2pSettingsRepository.initialize(this)
        com.yuvraj.movieroll.core.browser.HeadlessWebResolver.getInstance(this)

        // Initialize plugins, prefetch DNS, and restore default selected provider across restarts
        CoroutineScope(Dispatchers.IO).launch {
            try {
                com.yuvraj.movieroll.core.js.JsPluginStorage.getInstance(this@MovieRollApp).initialize()
                com.yuvraj.movieroll.core.addons.AddonRepository.getInstance(this@MovieRollApp).initialize()
            } catch (_: Exception) {}

            // DNS pre-fetch for critical media & metadata hosts
            DohDns.DEFAULT.prefetch(
                "movieroll-vault.sehgalvansh716.workers.dev",
                "api.themoviedb.org",
                "image.tmdb.org",
                "graphql.anilist.co"
            )

            try {
                val prefsRepo = PreferencesRepository.getInstance(this@MovieRollApp)
                launch {
                    kotlinx.coroutines.flow.combine(
                        prefsRepo.debridEnabled,
                        prefsRepo.activeDebridProvider,
                        prefsRepo.realDebridApiKey,
                        prefsRepo.allDebridApiKey,
                        prefsRepo.torBoxApiKey
                    ) { enabled, provider, rdKey, adKey, tbKey ->
                        if (!enabled) {
                            com.yuvraj.movieroll.core.debrid.DebridManager.getInstance().setActiveProviders(emptyList())
                            return@combine
                        }
                        val pType = com.yuvraj.movieroll.core.debrid.DebridProviderType.fromId(provider)
                        val key = when (pType) {
                            com.yuvraj.movieroll.core.debrid.DebridProviderType.REAL_DEBRID -> rdKey
                            com.yuvraj.movieroll.core.debrid.DebridProviderType.ALL_DEBRID -> adKey
                            com.yuvraj.movieroll.core.debrid.DebridProviderType.TORBOX -> tbKey
                            com.yuvraj.movieroll.core.debrid.DebridProviderType.NONE -> ""
                        }
                        if (pType != com.yuvraj.movieroll.core.debrid.DebridProviderType.NONE && key.isNotBlank()) {
                            com.yuvraj.movieroll.core.debrid.DebridManager.getInstance().setActiveProviders(
                                listOf(com.yuvraj.movieroll.core.debrid.DebridManager.ActiveDebridProvider(pType, true))
                            )
                        } else {
                            com.yuvraj.movieroll.core.debrid.DebridManager.getInstance().setActiveProviders(emptyList())
                        }
                    }.collect { }
                }
            } catch (_: Exception) {}

            try {
                val savedProvider = PreferencesRepository.getInstance(this@MovieRollApp).activeProvider.first()
                if (savedProvider.isNotBlank()) {
                    PluginRegistry.Default.setActivePlugin(savedProvider)
                }
            } catch (_: Exception) {}

            try {
                PluginRepositoryService.getInstance(this@MovieRollApp).initializePlugins()
            } catch (_: Exception) {}

            try {
                val prefsRepo = PreferencesRepository.getInstance(this@MovieRollApp)
                val savedProvider = prefsRepo.activeProvider.first()
                if (savedProvider.isNotBlank() && PluginRegistry.Default.getPlugin(savedProvider) != null) {
                    PluginRegistry.Default.setActivePlugin(savedProvider)
                } else {
                    val firstPublic = PluginRepositoryService.getInstance(this@MovieRollApp)
                        .getInstalledPublicPlugins()
                        .firstOrNull { it.isEnabled }
                    if (firstPublic != null) {
                        PluginRegistry.Default.setActivePlugin(firstPublic.name)
                        prefsRepo.setActiveProvider(firstPublic.name)
                    }
                }
            } catch (_: Exception) {}

            launch(Dispatchers.IO) {
                try {
                    val updated = PluginRepositoryService.getInstance(this@MovieRollApp)
                        .checkAndAutoUpdatePlugins(installNewFromRepos = false)
                    if (updated > 0) {
                        android.util.Log.i("MovieRollApp", "Auto-updated $updated provider plugin(s) on launch")
                    }
                } catch (e: Exception) {
                    android.util.Log.w("MovieRollApp", "Startup plugin auto-update error: ${e.message}")
                }
            }

            launch(Dispatchers.IO) {
                try {
                    com.yuvraj.movieroll.data.sync.MediaSyncManager.getInstance(this@MovieRollApp)
                        .repairMissingLocalPosters()
                } catch (_: Exception) {}
            }
        }
    }

    private val imageHttpClient by lazy {
        DohDns.sharedBaseClient.newBuilder()
            .dispatcher(
                okhttp3.Dispatcher().apply {
                    maxRequests = 64
                    maxRequestsPerHost = 16
                }
            )
            .addInterceptor { chain ->
                val orig = chain.request()
                val req = if (orig.header("User-Agent") == null) {
                    orig.newBuilder()
                        .header(
                            "User-Agent",
                            "Mozilla/5.0 (Linux; Android 14; Pixel 8 Pro) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/124.0.0.0 Mobile Safari/537.36"
                        )
                        .build()
                } else {
                    orig
                }
                try {
                    val response = chain.proceed(req)
                    if (!response.isSuccessful && (response.code == 429 || response.code in 500..599)) {
                        response.close()
                        chain.proceed(req)
                    } else {
                        response
                    }
                } catch (e: java.io.IOException) {
                    if (chain.call().isCanceled()) throw e
                    chain.proceed(req)
                }
            }
            .build()
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader {
        return ImageLoader.Builder(context)
            .components {
                add(
                    OkHttpNetworkFetcherFactory(
                        callFactory = { imageHttpClient }
                    )
                )
            }
            .memoryCache {
                MemoryCache.Builder()
                    .maxSizePercent(context, 0.25)
                    .build()
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("image_cache").toOkioPath())
                    .maxSizeBytes(250L * 1024 * 1024)
                    .build()
            }
            .bitmapConfig(Bitmap.Config.HARDWARE)
            .crossfade(220)
            .build()
    }
}
