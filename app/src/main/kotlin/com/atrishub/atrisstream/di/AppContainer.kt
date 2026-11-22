package com.atrishub.atrisstream.di

import android.content.Context
import coil.ImageLoader
import com.atrishub.atrisstream.core.database.AtrisDatabase
import com.atrishub.atrisstream.core.database.preferences.UserPreferencesRepository
import com.atrishub.atrisstream.core.database.repository.AtrisRepository
import com.atrishub.atrisstream.core.network.AtrisNetworkClient
import com.atrishub.atrisstream.core.sourceengine.SourceManager
import com.atrishub.atrisstream.server.LanPasteServer

/**
 * Manual constructor-based dependency container for AtrisStream.
 */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

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
        .crossfade(true)
        .build()

    val lanPasteServer: LanPasteServer = LanPasteServer(sourceManager = sourceManager)
}
