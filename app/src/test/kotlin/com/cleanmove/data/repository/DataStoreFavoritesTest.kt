package com.cleanmove.data.repository

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import app.cash.turbine.test
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

class DataStoreFavoritesTest {
    @get:Rule val tmp = TemporaryFolder()
    private val scope = CoroutineScope(Dispatchers.IO)

    @After fun tearDown() = scope.cancel()

    private fun favorites() = DataStoreFavorites(
        PreferenceDataStoreFactory.create(scope = scope) { tmp.newFile("fav.preferences_pb") },
    )

    @Test fun `starts empty then toggling adds and removes a line`() = runTest {
        val store = favorites()
        store.favoriteLines.test {
            assertEquals(emptySet<String>(), awaitItem())
            store.toggleLine("0010")
            assertEquals(setOf("0010"), awaitItem())
            store.toggleLine("0073")
            assertEquals(setOf("0010", "0073"), awaitItem())
            store.toggleLine("0010")
            assertEquals(setOf("0073"), awaitItem())
            cancel()
        }
    }
}
