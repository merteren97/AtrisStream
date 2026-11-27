package com.atrishub.atrisstream.di

import android.app.ActivityManager
import android.content.Context
import coil.ImageLoader
import coil.disk.DiskCache
import coil.memory.MemoryCache
import com.atrishub.atrisstream.core.database.AtrisDatabase
import com.atrishub.atrisstream.core.database.preferences.UserPreferencesRepository
import com.atrishub.atrisstream.core.database.repository.AtrisRepository
import com.atrishub.atrisstream.core.network.AtrisNetworkClient
import com.atrishub.atrisstream.core.sourceengine.SourceManager
import com.atrishub.atrisstream.server.LanPasteServer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * Manual constructor-based dependency container for AtrisStream.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val appScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    val isLowRamDevice: Boolean =
        (appContext.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager)?.isLowRamDevice == true

    val networkClient: AtrisNetworkClient = AtrisNetworkClient()

    val database: AtrisDatabase = AtrisDatabase.getInstance(appContext)

    val repository: AtrisRepository = AtrisRepository(database)

    val preferencesRepository: UserPreferencesRepository = UserPreferencesRepository(appContext)

    val sourceManager: SourceManager = SourceManager(
        networkClient = networkClient,
        repository = repository
    )

    val imageLoader: ImageLoader = ImageLoader.Builder(appContext)
        .okHttpClient(networkClient.baseClient)
        .memoryCache {
            MemoryCache.Builder(appContext)
                .maxSizePercent(if (isLowRamDevice) 0.10 else 0.12)
                .build()
        }
        .diskCache {
            DiskCache.Builder()
                .directory(appContext.cacheDir.resolve("image_cache"))
                .maxSizeBytes(64L * 1024 * 1024)
                .build()
        }
        .allowRgb565(true)
        .crossfade(false)
        .build()

    val lanPasteServer: LanPasteServer = LanPasteServer(
        sourceManager = sourceManager,
        repository = repository,
        database = database,
        context = appContext
    )
}
