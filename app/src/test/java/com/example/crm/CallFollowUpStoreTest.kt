package com.example.crm
import org.junit.Assert.assertEquals
import org.junit.Test
import java.text.SimpleDateFormat
import java.util.*
class CallFollowUpStoreTest {
 @Test fun classifiesDates(){ val f=SimpleDateFormat("yyyy-MM-dd",Locale.US); val d=f.parse("2026-11-12")!!; assertEquals(FollowUpState.DUE,CallFollowUpStore.state("2026-11-12",d)); assertEquals(FollowUpState.OVERDUE,CallFollowUpStore.state("2026-11-11",d)); assertEquals(FollowUpState.UPCOMING,CallFollowUpStore.state("2026-11-13",d)) }
 @Test fun rejectsInvalidDate(){assertEquals(FollowUpState.INVALID,CallFollowUpStore.state("tomorrow"))}
}

