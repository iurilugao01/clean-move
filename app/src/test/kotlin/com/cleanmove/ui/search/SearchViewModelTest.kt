package com.cleanmove.ui.search

import app.cash.turbine.test
import com.cleanmove.domain.Line
import com.cleanmove.ui.FakeCatalog
import com.cleanmove.ui.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class SearchViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val lines = listOf(Line("0010", "NOVA PALESTINA / FRADINHOS"), Line("0073", "TABUAZEIRO CIRCULAR"))

    @Test fun `empty cache and failed refresh shows error without loading`() = runTest {
        val vm = SearchViewModel(FakeCatalog(refreshResult = Result.failure(IOException("offline"))))
        vm.state.test {
            val s = expectMostRecentItem()
            assertTrue(s.error); assertFalse(s.loading); assertTrue(s.lines.isEmpty())
            cancel()
        }
    }

    @Test fun `cached lines are shown without error even when refresh fails`() = runTest {
        val vm = SearchViewModel(FakeCatalog(lines, Result.failure(IOException("offline"))))
        vm.state.test {
            val s = expectMostRecentItem()
            assertEquals(2, s.lines.size); assertFalse(s.error); assertFalse(s.loading)
            cancel()
        }
    }

    @Test fun `typing filters the list`() = runTest {
        val vm = SearchViewModel(FakeCatalog(lines))
        vm.state.test {
            vm.onQueryChange("tabuaz")
            assertEquals(listOf("0073"), expectMostRecentItem().lines.map { it.code })
            cancel()
        }
    }

    @Test fun `retry refreshes again and clears the error on success`() = runTest {
        val catalog = FakeCatalog(emptyList(), Result.failure(IOException()))
        val vm = SearchViewModel(catalog)
        vm.state.test {
            assertTrue(expectMostRecentItem().error)
            catalog.refreshResult = Result.success(Unit)
            catalog.lines.value = lines
            vm.retry()
            val s = expectMostRecentItem()
            assertFalse(s.error); assertEquals(2, s.lines.size)
            cancel()
        }
        assertEquals(2, catalog.refreshCalls)
    }
}
