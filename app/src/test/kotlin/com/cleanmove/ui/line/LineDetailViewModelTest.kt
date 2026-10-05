package com.cleanmove.ui.line

import androidx.lifecycle.SavedStateHandle
import app.cash.turbine.test
import com.cleanmove.domain.Line
import com.cleanmove.domain.LineDetail
import com.cleanmove.ui.FakeCatalog
import com.cleanmove.ui.FakeFavorites
import com.cleanmove.ui.MainDispatcherRule
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.io.IOException

class LineDetailViewModelTest {
    @get:Rule val main = MainDispatcherRule()

    private val detail = LineDetail(Line("0010", "NOVA PALESTINA / FRADINHOS"), emptyList(), emptyList())
    private fun vm(catalog: FakeCatalog, favorites: FakeFavorites = FakeFavorites()) =
        LineDetailViewModel(SavedStateHandle(mapOf("code" to "0010")), catalog, favorites)

    @Test fun `loads detail and reflects favorite status`() = runTest {
        val vm = vm(FakeCatalog(detailResult = Result.success(detail)), FakeFavorites(setOf("0010")))
        vm.state.test {
            val s = expectMostRecentItem() as LineDetailUiState.Loaded
            assertEquals(detail, s.detail); assertTrue(s.isFavorite)
            cancel()
        }
    }

    @Test fun `empty schedules still load successfully`() = runTest {
        val vm = vm(FakeCatalog(detailResult = Result.success(detail)))
        vm.state.test {
            assertTrue((expectMostRecentItem() as LineDetailUiState.Loaded).detail.schedules.isEmpty())
            cancel()
        }
    }

    @Test fun `failure becomes an error state and retry recovers`() = runTest {
        val catalog = FakeCatalog(detailResult = Result.failure(IOException("offline")))
        val vm = vm(catalog)
        vm.state.test {
            assertEquals(LineDetailUiState.Error, expectMostRecentItem())
            catalog.detailResult = Result.success(detail)
            vm.retry()
            assertTrue(expectMostRecentItem() is LineDetailUiState.Loaded)
            cancel()
        }
    }

    @Test fun `toggleFavorite flips the favorite flag`() = runTest {
        val vm = vm(FakeCatalog(detailResult = Result.success(detail)))
        vm.state.test {
            assertFalse((expectMostRecentItem() as LineDetailUiState.Loaded).isFavorite)
            vm.toggleFavorite()
            assertTrue((expectMostRecentItem() as LineDetailUiState.Loaded).isFavorite)
            cancel()
        }
    }
}
