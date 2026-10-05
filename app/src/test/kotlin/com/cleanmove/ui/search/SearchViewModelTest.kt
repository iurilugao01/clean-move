package com.cleanmove.ui.search

import app.cash.turbine.test
import com.cleanmove.domain.GeoStop
import com.cleanmove.domain.Line
import com.cleanmove.ui.FakeCatalog
import com.cleanmove.ui.FakeStopCatalog
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
        val vm = SearchViewModel(FakeCatalog(refreshResult = Result.failure(IOException("offline"))), FakeStopCatalog(refreshResult = Result.failure(IOException("offline"))))
        vm.state.test {
            val s = expectMostRecentItem()
            assertTrue(s.error); assertFalse(s.loading); assertTrue(s.lines.isEmpty())
            cancel()
        }
    }

    @Test fun `cached lines are shown without error even when refresh fails`() = runTest {
        val vm = SearchViewModel(FakeCatalog(lines, Result.failure(IOException("offline"))), FakeStopCatalog())
        vm.state.test {
            val s = expectMostRecentItem()
            assertEquals(2, s.lines.size); assertFalse(s.error); assertFalse(s.loading)
            cancel()
        }
    }

    @Test fun `typing filters the list`() = runTest {
        val vm = SearchViewModel(FakeCatalog(lines), FakeStopCatalog())
        vm.state.test {
            vm.onQueryChange("tabuaz")
            assertEquals(listOf("0073"), expectMostRecentItem().lines.map { it.code })
            cancel()
        }
    }

    @Test fun `retry refreshes again and clears the error on success`() = runTest {
        val catalog = FakeCatalog(emptyList(), Result.failure(IOException()))
        val vm = SearchViewModel(catalog, FakeStopCatalog(refreshResult = Result.failure(IOException())))
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

    private val stops = listOf(
        GeoStop(404, "110001", null, "Avenida Paulino Muller", "Vitória", -20.31, -40.31, false),
        GeoStop(405, "110002", null, "Rua do Caju", "Vitória", -20.32, -40.32, false),
    )

    @Test fun `stops load alongside lines and are filtered by the same query`() = runTest {
        val vm = SearchViewModel(FakeCatalog(lines), FakeStopCatalog(stops))
        vm.state.test {
            assertEquals(2, expectMostRecentItem().stops.size)
            vm.onQueryChange("caju")
            assertEquals(listOf(405), expectMostRecentItem().stops.map { it.id })
            cancel()
        }
    }

    @Test fun `a stop refresh failure alone is not an error while lines still loaded`() = runTest {
        val vm = SearchViewModel(FakeCatalog(lines), FakeStopCatalog(refreshResult = Result.failure(IOException())))
        vm.state.test {
            val s = expectMostRecentItem()
            assertFalse(s.error)
            assertEquals(2, s.lines.size)
            cancel()
        }
    }

    @Test fun `both sources failing with nothing cached is an error`() = runTest {
        val vm = SearchViewModel(FakeCatalog(emptyList(), Result.failure(IOException())), FakeStopCatalog(refreshResult = Result.failure(IOException())))
        vm.state.test {
            assertTrue(expectMostRecentItem().error)
            cancel()
        }
    }
}
