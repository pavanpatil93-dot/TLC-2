package com.example.data

import com.example.model.PropertyItem
import com.example.model.ImportPreviewRow
import com.example.model.InventoryTab
import android.content.Context
import android.content.ContextWrapper
import androidx.test.core.app.ApplicationProvider
import java.io.File
import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35])
class AppsScriptSyncProtocolTest {
    private fun request(action: String = "ADD") = JSONObject().apply {
        put("action", action)
        put("propertyCode", "OL-TEST-1")
        put("recordId", "COM-TEST-1")
    }

    private fun acknowledgement(action: String = "ADDED") = JSONObject().apply {
        put("status", "success")
        put("action", action)
        put("propertyCode", "OL-TEST-1")
        put("recordId", "COM-TEST-1")
    }

    @Test fun onlyMatchingPropertyWritesAreAcknowledged() {
        assertTrue(AppsScriptSyncProtocol.validateResponse(200,
            acknowledgement().toString(), request()).accepted)
        assertTrue(AppsScriptSyncProtocol.validateResponse(200,
            acknowledgement("UPDATED").toString(), request("UPDATE")).accepted)
    }

    @Test fun httpSuccessDoesNotTurnErrorDisabledOrHtmlIntoSuccess() {
        listOf(
            """{"status":"error","message":"failed"}""",
            """{"status":"disabled"}""",
            """{"status":"success"}""",
            """{"status":"success","message":"ping only"}""",
            "<html>Sign in</html>", "", "not json", "[]"
        ).forEach { body ->
            assertFalse(body, AppsScriptSyncProtocol.validateResponse(200, body, request()).accepted)
        }
        assertFalse(AppsScriptSyncProtocol.validateResponse(500,
            acknowledgement().toString(), request()).accepted)
    }

    @Test fun mismatchedRecordActionAndMissingIdentityStayUnconfirmed() {
        listOf(
            acknowledgement().put("propertyCode", "OL-OTHER"),
            acknowledgement().put("recordId", "COM-OTHER"),
            acknowledgement("UPDATED"),
            acknowledgement().apply { remove("propertyCode") },
            acknowledgement().apply { remove("recordId") },
            acknowledgement().apply { remove("action") }
        ).forEach {
            assertFalse(AppsScriptSyncProtocol.validateResponse(200, it.toString(), request()).accepted)
        }
    }

    @Test fun diagnosticPingCannotAcknowledgeAPropertyWrite() {
        val ping = JSONObject("""{"status":"success","action":"DIAGNOSTIC_PING"}""")
        assertFalse(AppsScriptSyncProtocol.validateResponse(200, ping.toString(), request()).accepted)
        val pingRequest = JSONObject("""{"action":"DIAGNOSTIC_PING"}""")
        assertTrue(AppsScriptSyncProtocol.validateResponse(200, ping.toString(), pingRequest).accepted)
        assertFalse(AppsScriptSyncProtocol.validateResponse(200,
            """{"status":"success"}""", pingRequest).accepted)
    }

    @Test fun errorAndDisabledResponsesAreNotReadConnections() {
        listOf("""{"status":"disabled"}""", """{"status":"error","data":{"Commercial Inventory":[]}}""",
            """{"status":"success","message":"hello"}""", "<html>Sign in</html>", "broken")
            .forEach { assertFalse(AppsScriptSyncProtocol.hasInventoryResponse(it)) }
        assertTrue(AppsScriptSyncProtocol.hasInventoryResponse(
            """{"status":"success","data":{"Commercial Inventory":[],"Residential Inventory":[]}}"""))
        assertTrue(AppsScriptSyncProtocol.hasInventoryResponse("[]"))
    }

    @Test fun staleAcknowledgementDoesNotMarkNewLocalEditSynced() {
        val sent = PropertyItem(id = "1", propertyCode = "OL-TEST-1",
            priceOrRent = 80000.0, ownerContact = "TEST-CONTACT", isSyncedToSheet = false)
        val edited = sent.copy(priceOrRent = 90000.0)
        val other = sent.copy(id = "2", propertyCode = "OL-TEST-2")
        val result = AppsScriptSyncProtocol.markAcknowledged(listOf(edited, other), sent)
        assertEquals(listOf(edited, other), result)
        assertFalse(result[0].isSyncedToSheet)
        assertFalse(result[1].isSyncedToSheet)
    }

    @Test fun matchingSnapshotIsAcknowledgedWithoutLosingViewedTime() {
        val sent = PropertyItem(id = "1", propertyCode = "OL-TEST-1", isSyncedToSheet = false)
        val viewed = sent.copy(lastViewedTimestamp = 12345L)
        val acknowledged = AppsScriptSyncProtocol.markAcknowledged(listOf(viewed), sent).single()
        assertTrue(acknowledged.isSyncedToSheet)
        assertEquals(12345L, acknowledged.lastViewedTimestamp)
    }

    @Test fun failedPostIsNotRetriedAsGetOrPutIntoQueryString() {
        val count = AtomicInteger()
        val method = AtomicReference<String>()
        val query = AtomicReference<String>()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/write") { exchange ->
            count.incrementAndGet()
            method.set(exchange.requestMethod)
            query.set(exchange.requestURI.rawQuery)
            exchange.requestBody.use { it.readBytes() }
            exchange.sendResponseHeaders(500, -1)
            exchange.close()
        }
        server.start()
        try {
            val result = AppsScriptSyncProtocol.post(
                "http://127.0.0.1:${server.address.port}/write", request())
            assertFalse(result.first)
            assertEquals(1, count.get())
            assertEquals("POST", method.get())
            assertNull(query.get())
        } finally { server.stop(0) }
    }

    @Test fun resultRedirectUsesGetAndChecksTheAcknowledgement() {
        val posts = AtomicInteger()
        val gets = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/write") { exchange ->
            posts.incrementAndGet()
            assertEquals("POST", exchange.requestMethod)
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Location", "/result")
            exchange.sendResponseHeaders(302, -1)
            exchange.close()
        }
        server.createContext("/result") { exchange ->
            gets.incrementAndGet()
            assertEquals("GET", exchange.requestMethod)
            assertNull(exchange.requestURI.rawQuery)
            val bytes = acknowledgement().toString().toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        server.start()
        try {
            val result = AppsScriptSyncProtocol.post(
                "http://127.0.0.1:${server.address.port}/write", request())
            assertTrue(result.second, result.first)
            assertEquals(1, posts.get())
            assertEquals(1, gets.get())
        } finally { server.stop(0) }
    }

    @Test fun redirectRequiringPostReplayStaysUnconfirmed() {
        val writes = AtomicInteger()
        val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0)
        server.createContext("/write") { exchange ->
            writes.incrementAndGet()
            exchange.requestBody.use { it.readBytes() }
            exchange.responseHeaders.add("Location", "/write")
            exchange.sendResponseHeaders(307, -1)
            exchange.close()
        }
        server.start()
        try {
            assertFalse(AppsScriptSyncProtocol.post(
                "http://127.0.0.1:${server.address.port}/write", request()).first)
            assertEquals(1, writes.get())
        } finally { server.stop(0) }
    }

    @Test fun failedLocalImportSaveDoesNotReportSuccessfulImport() {
        val root = java.nio.file.Files.createTempDirectory("tlc-sync-regression").toFile()
        val app = ApplicationProvider.getApplicationContext<Context>()
        val isolatedContext = object : ContextWrapper(app) {
            override fun getFilesDir(): File = root
        }
        try {
            val repository = SheetsRepository(isolatedContext)
            // AtomicFile cannot open its staging file when that path is a directory.
            val staging = File(root, "real_estate_inventory_db.json.new")
            assertTrue(staging.mkdir())
            File(staging, "blocker").writeText("test")
            val row = ImportPreviewRow(
                rowNumber = 2, propertyCode = "OL-TEST-FAIL", projectName = "Test",
                location = "Kharadi", unitNumber = "", tab = InventoryTab.OFF_LEASE,
                carpetAreaSqFt = 1000.0, priceOrRent = 80000.0,
                isDuplicateCode = false, isValid = true
            )
            val stats = repository.importRowsDetailed(listOf(row))
            assertEquals(0, stats.addedCount)
            assertEquals(1, stats.failedCount)
            assertTrue(repository.properties.value.isEmpty())
            assertNotEquals("SUCCESS",
                repository.syncLogs.value.first { it.direction == "FILE_IMPORT" }.status)
        } finally { root.deleteRecursively() }
    }

}

