package com.signalbot.engine.indicators

import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class SwingsTest {
    //                        0    1    2    3    4    5    6    7    8    9   10
    private val high = doubleArrayOf(1.0, 2.0, 3.0, 5.0, 4.0, 3.0, 2.0, 3.0, 4.0, 6.0, 5.0)
    private val low = doubleArrayOf(5.0, 4.0, 3.0, 4.0, 5.0, 2.0, 1.0, 2.0, 3.0, 4.0, 3.0)

    @Test fun findsSwingHighWithConfirmationBar() {
        val swings = Swings.highs(high, left = 2, right = 2)
        assertEquals(listOf(3), swings.map { it.index })
        assertEquals(5.0, swings[0].price)
        assertEquals(5, swings[0].confirmedAt)
    }

    @Test fun findsSwingLows() {
        assertEquals(listOf(2, 6), Swings.lows(low, left = 2, right = 2).map { it.index })
    }

    @Test fun equalHighsOnTheRightDoNotCreateTwoSwings() {
        val flatTop = doubleArrayOf(1.0, 2.0, 5.0, 5.0, 2.0, 1.0)
        assertEquals(listOf(2), Swings.highs(flatTop, 2, 2).map { it.index })
    }

    @Test fun noLookAhead() {
        val swings = Swings.lows(low, 2, 2)
        assertNull(Swings.lastConfirmed(swings, atIndex = 3), "swing at 2 is not known until bar 4")
        assertEquals(2, Swings.lastConfirmed(swings, atIndex = 4)?.index)
        assertEquals(2, Swings.lastConfirmed(swings, atIndex = 7)?.index, "swing at 6 is not known until bar 8")
        assertEquals(6, Swings.lastConfirmed(swings, atIndex = 8)?.index)
        assertEquals(listOf(6, 2), Swings.confirmedBefore(swings, 10).map { it.index })
    }
}
