package com.storyteller_f.divedeep

import kotlin.test.Test
import kotlin.test.assertEquals

class SharedLogicAndroidHostTest {

    @Test
    fun example() {
        assertEquals(EXPECTED_SUM, 1 + 2)
    }

    private companion object {
        const val EXPECTED_SUM = 3
    }
}
