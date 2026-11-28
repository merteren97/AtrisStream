package com.atrishub.atrisstream.ui.screens.browse

import com.atrishub.atrisstream.core.model.Episode
import com.atrishub.atrisstream.core.model.HomeSection
import com.atrishub.atrisstream.core.model.MediaCard
import com.atrishub.atrisstream.core.model.MediaDetails
import com.atrishub.atrisstream.core.model.MediaProvider
import com.atrishub.atrisstream.core.model.MediaType
import com.atrishub.atrisstream.core.model.PagedResult
import com.atrishub.atrisstream.core.model.ProviderManifest
import com.atrishub.atrisstream.core.model.StreamResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class BrowseViewModelTest {

    private val testDispatcher = StandardTestDispatcher()

    @Before
    fun setUp() {
        Dispatchers.setMain(testDispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    @Test
    fun `page 1 deduplicates duplicate ids from provider`() = runTest(testDispatcher) {
        val provider = FakeBrowseProvider(
            pages = mapOf(
                1 to PagedResult(
                    items = listOf(
                        card(id = "anime", title = "Anime One"),
                        card(id = "anime", title = "Anime Duplicate"),
                        card(id = "movie-2", title = "Movie Two")
                    ),
                    page = 1,
                    hasNextPage = false
                )
            )
        )

        val vm = BrowseViewModel(
            mediaType = MediaType.ANIME,
            observeProviders = { flowOf(listOf(provider)) }
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(2, state.items.size)
        assertEquals(listOf("anime", "movie-2"), state.items.map { it.id })
        assertEquals("Anime One", state.items.first().title)
    }

    @Test
    fun `loadMore caps items at 200 and stops pagination`() = runTest(testDispatcher) {
        val page1Items = (1..150).map { card(id = "id-$it", title = "Item $it") }
        val page2Items = (141..250).map { card(id = "id-$it", title = "Item $it") }

        val provider = FakeBrowseProvider(
            pages = mapOf(
                1 to PagedResult(items = page1Items, page = 1, hasNextPage = true),
                2 to PagedResult(items = page2Items, page = 2, hasNextPage = true)
            )
        )

        val vm = BrowseViewModel(
            mediaType = MediaType.MOVIE,
            observeProviders = { flowOf(listOf(provider)) }
        )
        advanceUntilIdle()
        assertEquals(150, vm.uiState.value.items.size)

        vm.loadMore()
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(BrowseViewModel.MAX_BROWSE_ITEMS, state.items.size)
        assertFalse(state.hasNextPage)
    }

    @Test
    fun `filters providers strictly by mediaType and selects primarySourceId`() = runTest(testDispatcher) {
        val movieProvider = FakeBrowseProvider(
            id = "movie-only",
            supportedTypes = listOf(MediaType.MOVIE),
            pages = mapOf(1 to PagedResult(listOf(card(id = "m1", title = "Movie 1")), page = 1, hasNextPage = false))
        )
        val animeA = FakeBrowseProvider(
            id = "animex",
            supportedTypes = listOf(MediaType.ANIME),
            pages = mapOf(1 to PagedResult(listOf(card(id = "a1", title = "Anime A")), page = 1, hasNextPage = false))
        )
        val animeB = FakeBrowseProvider(
            id = "reanime",
            supportedTypes = listOf(MediaType.ANIME),
            pages = mapOf(1 to PagedResult(listOf(card(id = "b1", title = "Anime B")), page = 1, hasNextPage = false))
        )

        val vm = BrowseViewModel(
            mediaType = MediaType.ANIME,
            observeProviders = { flowOf(listOf(movieProvider, animeA, animeB)) },
            observePrimarySourceId = { flowOf("reanime") }
        )
        advanceUntilIdle()

        val state = vm.uiState.value
        assertEquals(listOf("animex", "reanime"), state.providers.map { it.manifest.id })
        assertEquals("reanime", state.selectedSourceId)
        assertEquals(listOf("b1"), state.items.map { it.id })
    }

    private fun card(id: String, title: String) = MediaCard(
        id = id,
        sourceId = "test-source",
        title = title,
        type = MediaType.ANIME
    )

    private class FakeBrowseProvider(
        private val pages: Map<Int, PagedResult<MediaCard>>,
        private val id: String = "test-source",
        private val supportedTypes: List<MediaType> = listOf(MediaType.MOVIE, MediaType.TV, MediaType.ANIME)
    ) : MediaProvider {
        override val manifest = ProviderManifest(
            id = id,
            name = "Test Source",
            version = "1.0.0",
            baseUrl = "https://example.test",
            supportedTypes = supportedTypes
        )

        override suspend fun getHome(): List<HomeSection> = emptyList()

        override suspend fun browse(type: MediaType, page: Int): PagedResult<MediaCard> =
            pages[page] ?: PagedResult(emptyList(), page, hasNextPage = false)

        override suspend fun search(query: String, page: Int): PagedResult<MediaCard> =
            PagedResult(emptyList(), page, hasNextPage = false)

        override suspend fun getDetails(id: String, type: MediaType): MediaDetails =
            error("Not needed")

        override suspend fun getEpisodes(id: String, season: Int): List<Episode> = emptyList()

        override suspend fun getStreams(
            mediaId: String,
            type: MediaType,
            season: Int?,
            episode: Int?
        ): StreamResult = StreamResult(emptyList())
    }
}
