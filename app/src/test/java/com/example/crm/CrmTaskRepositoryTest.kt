package com.example.crm

import androidx.test.core.app.ApplicationProvider
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class CrmTaskRepositoryTest {
    @Test fun taskPersistsAndCompletes() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val task = CrmTask(id = "test-persist", title = "Call owner", origin = CrmTaskOrigin.MANUAL, originId = "", dueDate = "2026-11-13")
        assertTrue(CrmTaskRepository.upsert(context, task))
        assertEquals("Call owner", CrmTaskRepository.read(context).first { it.id == task.id }.title)
        assertTrue(CrmTaskRepository.setStatus(context, task.id, CrmTaskStatus.COMPLETED))
        assertEquals(CrmTaskStatus.COMPLETED, CrmTaskRepository.read(context).first { it.id == task.id }.status)
    }
    @Test fun repeatedCallOriginDoesNotDuplicate() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val a = CrmTaskRepository.ensureFromOrigin(context, CrmTaskOrigin.CALL, "call-test-ldr01", "Follow up")
        val b = CrmTaskRepository.ensureFromOrigin(context, CrmTaskOrigin.CALL, "call-test-ldr01", "Follow up")
        assertEquals(a.id, b.id)
        assertEquals(1, CrmTaskRepository.read(context).count { it.origin == CrmTaskOrigin.CALL && it.originId == "call-test-ldr01" })
    }
    @Test fun overdueExcludesCompleted() {
        val task = CrmTask(title = "Visit", origin = CrmTaskOrigin.VISIT, originId = "v1", dueDate = "2026-01-01")
        assertTrue(task.isOverdue("2026-11-13"))
        assertFalse(task.copy(status = CrmTaskStatus.COMPLETED).isOverdue("2026-11-13"))
    }
    @Test(expected = IllegalArgumentException::class)
    fun invalidDateRejected() {
        CrmTaskRepository.upsert(ApplicationProvider.getApplicationContext(), CrmTask(title = "Bad date", origin = CrmTaskOrigin.MANUAL, originId = "", dueDate = "tomorrow"))
    }
}
