package com.example.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.example.model.*
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35])
class Build02RepositoryGuardTest {
    private lateinit var c: Context
    private lateinit var repo: SheetsRepository
    @Before fun setup() {
        c=ApplicationProvider.getApplicationContext()
        PropertiesLocalDatabase.clearAll(c)
        c.getSharedPreferences("real_estate_user_settings",0).edit().clear().putString("user_username","Tester").putString("user_role","Sales Executive").commit()
        repo=SheetsRepository(c,false)
    }
    @Test fun sensitiveActionsAreCheckedAtRepository() {
        assertTrue(repo.addProperty(PropertyItem(id="a",ownerContact="PRIVATE",sourceInfo="PRIVATE",remarks="PRIVATE")))
        assertFalse(repo.deleteProperty("a"))
        assertFalse(repo.deleteAllProperties())
        assertTrue(runCatching { repo.exportStandardCsv() }.isFailure)
        assertTrue(runCatching { repo.createBackupNow() }.isFailure)
        assertFalse(repo.exportStandardCsv(isClientExport=true).contains("PRIVATE"))
        assertEquals(ArchiveStatus.ACTIVE,repo.properties.value.single().archiveStatus)
        c.getSharedPreferences("real_estate_user_settings",0).edit().putString("user_role","Admin").commit()
        assertTrue(repo.deleteProperty("a"))
        val retained=PropertiesLocalDatabase.loadProperties(c).single()
        assertEquals(ArchiveStatus.ARCHIVED,retained.archiveStatus)
        assertEquals("Tester | ADMIN",retained.updatedBy)
        repo.close()
    }
    @Test fun datesAuditAndDuplicateDecisionsSurviveReload() {
        val p=PropertyItem(id="a",leaseExpiryDate="2027-01-01",upcomingVacancyDate="2026-12-15")
        assertTrue(repo.addProperty(p))
        assertTrue(repo.addProperty(p.copy(id="b")))
        assertFalse(repo.resolveDuplicate("a","b","Link as Same Unit"))
        c.getSharedPreferences("real_estate_user_settings",0).edit().putString("user_role","Admin").commit()
        assertTrue(repo.resolveDuplicate("a","b","Link as Same Unit"))
        val loaded=PropertiesLocalDatabase.loadProperties(c)
        assertEquals(loaded[0].sameUnitGroupId,loaded[1].sameUnitGroupId)
        assertTrue(loaded[0].sameUnitGroupId.isNotBlank())
        assertEquals("2027-01-01",loaded[0].leaseExpiryDate)
        assertTrue(loaded.all { it.updatedBy=="Tester | ADMIN" && it.updatedAt.isNotBlank() })
        assertFalse(repo.updateProperty(p.copy(id="missing"),"missing"))
        assertFalse(repo.addProperty(p.copy(id="bad",leaseExpiryDate="bad")))
        repo.close()
    }
    @Test fun failedAtomicSaveLeavesOldRecordsAndReturnsFalse() {
        assertTrue(repo.addProperty(PropertyItem(id="old")))
        val file=File(c.filesDir,"real_estate_inventory_db.json")
        file.writeText("corrupt-original")
        assertFalse(repo.addProperty(PropertyItem(id="new")))
        assertFalse(repo.updateProperty(repo.properties.value.single().copy(location="changed"),"edit"))
        assertEquals("corrupt-original",file.readText())
        assertEquals("old",repo.properties.value.single().id)
        repo.close()
    }
}
