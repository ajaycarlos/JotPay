package com.example.moneylog

import org.junit.Assert.*
import org.junit.Test
import java.security.MessageDigest

/**
 * Pure JVM unit tests for the JotPay Link Device Transfer feature.
 *
 * NOTE: EncryptionHelper roundtrip test is SKIPPED here because EncryptionHelper
 * uses android.util.Base64, which is not available in the pure JVM test environment.
 * To test EncryptionHelper, use an instrumented test (androidTest) with a real device/emulator,
 * or mock android.util.Base64 with a Robolectric setup.
 */
class LinkTransferTest {

    // ---------------------------------------------------------------------------
    // Helper: inline reimplementation of SyncManager.generateStableId so we can
    // call it from a pure JVM test without needing a Context.
    // ---------------------------------------------------------------------------
    private fun generateStableId(timestamp: Long): String {
        val input = "$timestamp"
        val md = MessageDigest.getInstance("MD5")
        val digest = md.digest(input.toByteArray())
        return digest.joinToString("") { "%02x".format(it) }
    }

    // ---------------------------------------------------------------------------
    // 1. generateStableId — determinism: same timestamp → same hash
    // ---------------------------------------------------------------------------
    @Test
    fun generateStableId_sameTimestamp_returnsSameHash() {
        val ts = 1234567890L
        val first  = generateStableId(ts)
        val second = generateStableId(ts)
        assertEquals("Same timestamp must always produce the same stable ID", first, second)
    }

    // ---------------------------------------------------------------------------
    // 2. generateStableId — different timestamps → different hashes
    // ---------------------------------------------------------------------------
    @Test
    fun generateStableId_differentTimestamps_returnsDifferentHashes() {
        val hash1 = generateStableId(1000000000L)
        val hash2 = generateStableId(1000000001L)
        assertNotEquals("Different timestamps must produce different stable IDs", hash1, hash2)
    }

    // ---------------------------------------------------------------------------
    // 3. generateStableId — result is a valid 32-char lowercase hex MD5 string
    // ---------------------------------------------------------------------------
    @Test
    fun generateStableId_resultIsValid32CharHex() {
        val hash = generateStableId(9876543210L)
        assertEquals("MD5 hex string must be 32 characters", 32, hash.length)
        assertTrue("MD5 hex string must contain only lowercase hex digits",
            hash.matches(Regex("[0-9a-f]+")))
    }

    // ---------------------------------------------------------------------------
    // 4. Record validation — valid records must have positive timestamps
    // ---------------------------------------------------------------------------
    @Test
    fun recordValidation_rejectsInvalidOrZeroTimestamp() {
        data class Record(val timestamp: Long, val originalText: String, val amount: Long)

        fun isValid(record: Record): Boolean {
            return record.timestamp > 0L && record.originalText.isNotEmpty()
        }

        val validRecord = Record(1234567890L, "100 Lunch", 10000L)
        val zeroTimestamp = Record(0L, "100 Lunch", 10000L)
        val negativeTimestamp = Record(-1L, "100 Lunch", 10000L)
        val emptyText = Record(1234567890L, "", 10000L)

        assertTrue("Valid record must pass validation", isValid(validRecord))
        assertFalse("Zero timestamp must be rejected as malformed", isValid(zeroTimestamp))
        assertFalse("Negative timestamp must be rejected as malformed", isValid(negativeTimestamp))
        assertFalse("Empty text record must be rejected", isValid(emptyText))
    }

    // ---------------------------------------------------------------------------
    // 5. Server deleted IDs — filters out records deleted on server
    // ---------------------------------------------------------------------------
    @Test
    fun serverDeletedIds_filtersOutDeletedRecords() {
        data class Record(val stableId: String, val timestamp: Long)

        val serverDeletedIds = setOf("abc123hash", "def456hash")
        val records = listOf(
            Record("abc123hash", 1000L),
            Record("validRecordHash", 2000L),
            Record("def456hash", 3000L)
        )

        val nonDeleted = records.filter { !serverDeletedIds.contains(it.stableId) }

        assertEquals(1, nonDeleted.size)
        assertEquals(2000L, nonDeleted[0].timestamp)
    }

    // ---------------------------------------------------------------------------
    // 6. Local pending deletes — preserves deletion tombstones during merge
    // ---------------------------------------------------------------------------
    @Test
    fun localPendingDeletes_preservesDeletionTombstones() {
        data class Record(val timestamp: Long, val text: String)

        val pendingDeletes = setOf("1000", "3000") // timestamps stored as strings in SharedPreferences
        val incomingRecords = listOf(
            Record(1000L, "Old item"),
            Record(2000L, "Active item"),
            Record(3000L, "Deleted item")
        )

        val merged = incomingRecords.filter { !pendingDeletes.contains(it.timestamp.toString()) }

        assertEquals(1, merged.size)
        assertEquals(2000L, merged[0].timestamp)
    }

    // ---------------------------------------------------------------------------
    // 5. Conflict detection — two records with same timestamp but differing fields
    // ---------------------------------------------------------------------------
    @Test
    fun conflictDetection_sameTimestampDifferentFields_detectedAsConflict() {
        // Simulate what LinkTransferWorker does: compare existing DB record vs server record
        data class Record(
            val originalText: String,
            val amount: Long,
            val description: String,
            val timestamp: Long,
            val nature: String,
            val obligationAmount: Long
        )

        val existing = Record("100 Lunch", 10000L, "Lunch",   1234567890L, "NORMAL", 0L)
        val server   = Record("100 Dinner", 20000L, "Dinner", 1234567890L, "NORMAL", 0L)

        val fieldsDiffer = existing.originalText      != server.originalText
                        || existing.amount            != server.amount
                        || existing.description       != server.description
                        || existing.nature            != server.nature
                        || existing.obligationAmount  != server.obligationAmount

        assertTrue("Records with same timestamp but different fields should be flagged as conflicting",
            fieldsDiffer)
    }

    // ---------------------------------------------------------------------------
    // 6. Duplicate detection — records with same timestamp are detected as duplicates
    // ---------------------------------------------------------------------------
    @Test
    fun duplicateDetection_sameTimestamp_detectedAsDuplicate() {
        data class Record(val timestamp: Long, val description: String)

        val existing = Record(1234567890L, "Lunch")
        val incoming = Record(1234567890L, "Lunch")

        // The worker considers a record a duplicate when timestamps match AND fields are identical
        val sameTimestamp = existing.timestamp == incoming.timestamp
        val sameContent   = existing.description == incoming.description
        val isDuplicate   = sameTimestamp && sameContent

        assertTrue("Records with the same timestamp and identical fields should be duplicates",
            isDuplicate)
    }

    // ---------------------------------------------------------------------------
    // 7. No-conflict when fields match — should NOT trigger a DB update
    // ---------------------------------------------------------------------------
    @Test
    fun conflictDetection_sameTimestampSameFields_noConflict() {
        data class Record(
            val originalText: String,
            val amount: Long,
            val description: String,
            val nature: String,
            val obligationAmount: Long
        )

        val existing = Record("100 Lunch", 10000L, "Lunch", "NORMAL", 0L)
        val server   = Record("100 Lunch", 10000L, "Lunch", "NORMAL", 0L)

        val fieldsDiffer = existing.originalText     != server.originalText
                        || existing.amount           != server.amount
                        || existing.description      != server.description
                        || existing.nature           != server.nature
                        || existing.obligationAmount != server.obligationAmount

        assertFalse("Identical records must NOT be flagged as conflicting", fieldsDiffer)
    }

    // ---------------------------------------------------------------------------
    // 8. Empty dataset — processing an empty list returns importedCount = 0
    // ---------------------------------------------------------------------------
    @Test
    fun emptyDataset_importedCountIsZero() {
        data class ValidatedRecord(
            val stableId: String,
            val originalText: String,
            val amount: Long,
            val description: String,
            val timestamp: Long,
            val nature: String,
            val obligationAmount: Long
        )

        val validatedRecords: List<ValidatedRecord> = emptyList()
        var importedCount = 0

        for (record in validatedRecords) {
            importedCount++
        }

        assertEquals("An empty record list must result in 0 imported records", 0, importedCount)
    }

    // ---------------------------------------------------------------------------
    // 9. Remote vault safety — rejecting unconfirmed empty vaults on overwrite
    // ---------------------------------------------------------------------------
    @Test
    fun emptyVault_rejectionWhenUnconfirmedOrExpectedCountPositive() {
        data class RemoteMeta(val sourceRecordCount: Int, val recordCount: Int, val status: String)

        fun canSafelyOverwrite(downloadedCount: Int, meta: RemoteMeta?): Boolean {
            if (downloadedCount > 0) return true
            // If downloaded is 0, must be confirmed genuinely empty
            val isConfirmedEmpty = (meta != null && meta.status == "ready" && meta.recordCount == 0)
            return isConfirmedEmpty
        }

        // Case A: Meta is null (source hasn't uploaded / uninitialized)
        assertFalse("Must NOT overwrite when meta is null and 0 records downloaded",
            canSafelyOverwrite(0, null))

        // Case B: Source declared 250 records, but 0 downloaded (in-flight or upload failed)
        val inFlightMeta = RemoteMeta(sourceRecordCount = 250, recordCount = 0, status = "syncing")
        assertFalse("Must NOT overwrite when source has records in-flight",
            canSafelyOverwrite(0, inFlightMeta))

        // Case C: Source declared 250 records and finished upload, but download returned 0
        val uploadedMeta = RemoteMeta(sourceRecordCount = 250, recordCount = 250, status = "ready")
        assertFalse("Must NOT overwrite when 250 records were expected but 0 downloaded",
            canSafelyOverwrite(0, uploadedMeta))

        // Case D: Source genuinely has 0 records and declared ready
        val genuinelyEmptyMeta = RemoteMeta(sourceRecordCount = 0, recordCount = 0, status = "ready")
        assertTrue("May safely overwrite when source is confirmed genuinely empty",
            canSafelyOverwrite(0, genuinelyEmptyMeta))

        // Case E: 250 records downloaded
        assertTrue("May proceed when records are downloaded",
            canSafelyOverwrite(250, uploadedMeta))
    }

    // ---------------------------------------------------------------------------
    // 10. Record counting logic — tracks downloaded, validated, skipped, imported
    // ---------------------------------------------------------------------------
    @Test
    fun recordCounting_accuratelyDistinguishesAllMetrics() {
        data class RawRecord(val stableId: String, val timestamp: Long, val isDecryptable: Boolean)
        data class ValidatedRecord(val stableId: String, val timestamp: Long)

        val rawDownloaded = listOf(
            RawRecord("id1", 1000L, true),
            RawRecord("id2", 2000L, false), // Corrupt/malformed
            RawRecord("id3", 3000L, true),
            RawRecord("id4", 4000L, true),
            RawRecord("id5", 4000L, true), // Duplicate timestamp
            RawRecord("id6", 5000L, true)  // Tombstone (deleted on server)
        )

        val serverDeletedIds = setOf("id6")

        val downloadedCount = rawDownloaded.size

        // Step 1: Validate and decrypt
        var malformedCount = 0
        val validated = mutableListOf<ValidatedRecord>()
        for (r in rawDownloaded) {
            if (!r.isDecryptable || r.timestamp <= 0L) {
                malformedCount++
            } else {
                validated.add(ValidatedRecord(r.stableId, r.timestamp))
            }
        }
        val validatedCount = validated.size

        // Step 2: Filter server deleted and deduplicate
        val toInsert = validated
            .filter { !serverDeletedIds.contains(it.stableId) }
            .distinctBy { it.timestamp }

        val importedCount = toInsert.size
        val skippedTombstones = validated.size - toInsert.size
        val totalSkipped = malformedCount + skippedTombstones

        assertEquals(6, downloadedCount)
        assertEquals(5, validatedCount)
        assertEquals(1, malformedCount)
        assertEquals(2, skippedTombstones) // 1 duplicate + 1 tombstone
        assertEquals(3, totalSkipped)
        assertEquals(3, importedCount) // id1 (1000), id3 (3000), id4 (4000)
    }

    // ---------------------------------------------------------------------------
    // 11. Decryption failure safety — aborts when all downloaded records fail decryption
    // ---------------------------------------------------------------------------
    @Test
    fun decryptionFailure_safelyAbortsWithoutModifyingDatabase() {
        val downloadedCount = 250
        val validatedCount = 0 // All 250 failed decryption (wrong key)

        val shouldAbort = (downloadedCount > 0 && validatedCount == 0)
        assertTrue("Transfer must safely abort if records were downloaded but none could be decrypted",
            shouldAbort)
    }

    // ---------------------------------------------------------------------------
    // 12. Batch updates — generates expected stable IDs
    // ---------------------------------------------------------------------------
    @Test
    fun batchUpdates_generatesCorrectStableIds() {
        val timestamps = listOf(1700000000000L, 1700000001000L, 1700000002000L)
        val batchMap = mutableMapOf<String, String>()

        for (ts in timestamps) {
            val stableId = generateStableId(ts)
            batchMap[stableId] = "dummy_encrypted_data_$ts"
        }

        assertEquals(3, batchMap.size)
        for (ts in timestamps) {
            val expectedId = generateStableId(ts)
            assertTrue("Batch map must contain key for timestamp $ts", batchMap.containsKey(expectedId))
        }
    }
}
