package com.example.data.projects

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class ProjectDirectoryStoreTest {
    private lateinit var context: Context
    @Before fun setup() {
        context = ApplicationProvider.getApplicationContext()
        context.getSharedPreferences("project_directory_v1", Context.MODE_PRIVATE).edit().clear().commit()
        context.getSharedPreferences("project_unit_links_v1", Context.MODE_PRIVATE).edit().clear().commit()
    }
    @Test fun importedDirectoryHasStableUniqueCodesAndNoInventedVerification() {
        val records = ProjectDirectoryStore.read(context)
        assertEquals(928, records.size)
        assertEquals(399, records.count { it.division == "commercial" })
        assertEquals(529, records.count { it.division == "residential" })
        assertEquals(records.size, records.map { it.id }.distinct().size)
        assertTrue(records.none { it.verification == "Verified" })
        assertEquals("TLC-PRJ-001", records.first { it.id == "CP-0001" }.raw.getJSONObject("raw").getString("Source Master Project ID"))
    }
    @Test fun newProjectSurvivesReloadAndDuplicateDoesNotAddRow() {
        val created = ProjectDirectoryStore.create(context, "commercial", "Test project", "Kharadi", "")
        assertEquals("Unverified", created.verification)
        assertTrue(ProjectDirectoryStore.read(context).any { it.id == created.id })
        assertTrue(runCatching { ProjectDirectoryStore.create(context, "commercial", "Test project", "Kharadi", "") }.isFailure)
        assertEquals(929, ProjectDirectoryStore.read(context).size)
    }
    @Test fun unitLinkCannotSilentlyMoveToAnotherProject() {
        assertTrue(ProjectDirectoryStore.linkUnit(context, "CP-0001", "unit-a"))
        assertTrue(runCatching { ProjectDirectoryStore.linkUnit(context, "CP-0002", "unit-a") }.isFailure)
        assertEquals("CP-0001", ProjectDirectoryStore.links(context)["unit-a"])
    }
}

