package com.cleanmove.ui.nav

import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.cleanmove.ui.theme.CleanMoveTheme
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BottomBarTest {
    @get:Rule val rule = createComposeRule()

    @Test
    fun `shows three tabs and reports the tapped one`() {
        var tapped: Tab? = null
        rule.setContent { CleanMoveTheme { BottomBar(selected = Tab.Map, onSelect = { tapped = it }) } }

        rule.onNodeWithText("Mapa").assertExists()
        rule.onNodeWithText("Favoritos").assertExists()
        rule.onNodeWithText("Buscar").performClick()

        assertEquals(Tab.Search, tapped)
    }
}
