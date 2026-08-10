package com.meowcoin.wallet.crypto

import java.math.BigDecimal
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class AmountCodecTest {
    @Test
    fun parsesPointTwentyNineExactly() {
        assertEquals(29_000_000L, AmountCodec.parseAtomic("0.29"))
        assertEquals(BigDecimal("0.29000000"), AmountCodec.fromAtomic(29_000_000L))
    }

    @Test
    fun parsesThreeAtomicUnitsExactly() {
        assertEquals(3L, AmountCodec.parseAtomic("0.00000003"))
        assertEquals("0.00000003", AmountCodec.formatAtomic(3L))
    }

    @Test
    fun rejectsFractionalAtomicUnitsInsteadOfRounding() {
        assertThrows(IllegalArgumentException::class.java) {
            AmountCodec.parseAtomic("0.000000001")
        }
    }

    @Test
    fun rejectsValuesThatOverflowLong() {
        assertThrows(IllegalArgumentException::class.java) {
            AmountCodec.toAtomic(BigDecimal("92233720368.54775808"))
        }
    }
}
