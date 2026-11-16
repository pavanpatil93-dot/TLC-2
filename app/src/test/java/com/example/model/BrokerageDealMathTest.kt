package com.example.model

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class BrokerageDealMathTest {
    @Test fun leaseBrokerageAndDeposit() {
        assertEquals(150000.0, BrokerageDealMath.leaseBrokerage(150000.0, 1.0), 0.001)
        assertEquals(900000.0, BrokerageDealMath.securityDeposit(150000.0, 6.0), 0.001)
    }

    @Test fun saleBrokerageAndMilestone() {
        assertEquals(500000.0, BrokerageDealMath.saleBrokerage(25000000.0, 2.0), 0.001)
        assertEquals(16250000.0, BrokerageDealMath.milestoneAmount(25000000.0, 65.0), 0.001)
    }

    @Test fun gstIsOptional() {
        assertEquals(27000.0, BrokerageDealMath.gstOnBrokerage(150000.0, true), 0.001)
        assertEquals(150000.0, BrokerageDealMath.totalWithGst(150000.0, false), 0.001)
        assertEquals(177000.0, BrokerageDealMath.totalWithGst(150000.0, true), 0.001)
    }

    @Test fun invalidInputsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            BrokerageDealMath.saleBrokerage(-1.0, 2.0)
        }
    }
}

