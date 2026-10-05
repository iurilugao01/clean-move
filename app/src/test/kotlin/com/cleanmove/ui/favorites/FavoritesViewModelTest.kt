package com.cleanmove.ui.favorites

import app.cash.turbine.test
import com.cleanmove.domain.Line
import com.cleanmove.ui.FakeCatalog
import com.cleanmove.ui.FakeFavorites
import com.cleanmove.ui.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test

class FavoritesViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val lines = listOf(Line("0010", "A"), Line("0073", "B"), Line("0099", "C"))

    @Test fun `shows only favorite lines that exist in the catalog`() = runTest {
        val vm = FavoritesViewModel(FakeCatalog(lines), FakeFavorites(setOf("0073", "9999")))
        vm.state.test {
            assertEquals(listOf("0073"), expectMostRecentItem().map { it.code })
            cancel()
        }
    }

    @Test fun `remove drops a favorite`() = runTest {
        val vm = FavoritesViewModel(FakeCatalog(lines), FakeFavorites(setOf("0010", "0073")))
        vm.state.test {
            vm.remove("0010")
            assertEquals(listOf("0073"), expectMostRecentItem().map { it.code })
            cancel()
        }
    }
}
