package com.nitinshukla.notebook.core

import com.nitinshukla.notebook.core.backup.BackupAttachment
import com.nitinshukla.notebook.core.backup.BackupCodec
import com.nitinshukla.notebook.core.backup.BackupException
import com.nitinshukla.notebook.core.backup.BackupFile
import com.nitinshukla.notebook.core.backup.BackupLabel
import com.nitinshukla.notebook.core.backup.BackupNote
import com.nitinshukla.notebook.core.backup.DuplicateStrategy
import com.nitinshukla.notebook.core.backup.ImportPlanner
import com.nitinshukla.notebook.core.backup.LegacyData
import com.nitinshukla.notebook.core.backup.NoteFingerprint
import com.nitinshukla.notebook.core.backup.TextImport
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class BackupTest {
    private val sample = BackupFile(
        createdAt = 1_700_000_000_000,
        appVersion = "1.0.0",
        labels = listOf(BackupLabel(1, "Work", "LIGHT_BLUE")),
        notes = listOf(
            BackupNote(id = 10, title = "Hello", content = "World", createdAt = 1, updatedAt = 2, labelIds = listOf(1)),
            BackupNote(id = 11, title = "Second", content = "", type = "CHECKLIST", createdAt = 3, updatedAt = 4),
        ),
    )

    private fun expectFailure(reason: BackupException.Reason, json: String) {
        try {
            BackupCodec.decode(json)
            fail("Expected $reason")
        } catch (e: BackupException) {
            assertEquals(reason, e.reason)
        }
    }

    @Test fun `encode decode round trip`() {
        val decoded = BackupCodec.decode(BackupCodec.encode(sample))
        assertEquals(sample, decoded)
    }

    @Test fun `unknown fields are ignored for forward compatibility`() {
        val json = BackupCodec.encode(sample).replaceFirst("{", "{\"futureField\": 1,")
        assertEquals(sample, BackupCodec.decode(json))
    }

    @Test fun `rejects non json and wrong format`() {
        expectFailure(BackupException.Reason.NOT_JSON, "hello")
        expectFailure(BackupException.Reason.WRONG_FORMAT, "{\"createdAt\": \"x\"}")
        expectFailure(BackupException.Reason.WRONG_FORMAT, BackupCodec.encode(sample.copy(format = "other")))
        expectFailure(BackupException.Reason.WRONG_FORMAT, "{ \"format\": \"notebook-backup\", ")
    }

    @Test fun `rejects newer versions`() {
        expectFailure(BackupException.Reason.UNSUPPORTED_VERSION, BackupCodec.encode(sample.copy(version = 99)))
    }

    @Test fun `rejects invalid data`() {
        val dupe = sample.copy(notes = sample.notes + sample.notes.first())
        expectFailure(BackupException.Reason.INVALID_DATA, BackupCodec.encode(dupe))
        val badType = sample.copy(notes = listOf(sample.notes.first().copy(type = "HOLOGRAM")))
        expectFailure(BackupException.Reason.INVALID_DATA, BackupCodec.encode(badType))
        val traversal = sample.copy(
            notes = listOf(sample.notes.first().copy(attachments = listOf(BackupAttachment("IMAGE", "../../etc/passwd", "image/png")))),
        )
        expectFailure(BackupException.Reason.INVALID_DATA, BackupCodec.encode(traversal))
    }

    @Test fun `format 1 backups with labels still decode and fold into tags`() {
        val v1 = BackupCodec.encode(sample.copy(version = 1)).replace("\"version\": 2", "\"version\": 1")
        val decoded = BackupCodec.decode(v1)
        assertEquals(1, decoded.version)
        val names = decoded.labels.filter { it.id in decoded.notes.first().labelIds }.map { it.name }
        assertEquals("World\n\n#Work", LegacyData.foldLabelsIntoContent("World", names))
        assertEquals("#Work", LegacyData.foldLabelsIntoContent("", listOf("Work")))
        assertEquals("x\n\n#Work", LegacyData.foldLabelsIntoContent("x\n\n#Work", listOf("Work")))
        assertEquals("x", LegacyData.foldLabelsIntoContent("x", emptyList()))
        assertEquals("x\n\n#to_do", LegacyData.foldLabelsIntoContent("x", listOf("to do")))
    }

    @Test fun `safe entry names`() {
        assertTrue(BackupCodec.isSafeEntryName("img_123.jpg"))
        assertFalse(BackupCodec.isSafeEntryName("../x"))
        assertFalse(BackupCodec.isSafeEntryName("a/b"))
    }

    @Test fun `import planner handles duplicates`() {
        val existing = listOf(NoteFingerprint(99, "Hello", "World", 1))
        val skip = ImportPlanner.plan(existing, sample.notes, DuplicateStrategy.SKIP)
        assertEquals(listOf(11L), skip.toInsert.map { it.id })
        assertEquals(1, skip.skipped)
        val both = ImportPlanner.plan(existing, sample.notes, DuplicateStrategy.KEEP_BOTH)
        assertEquals(2, both.toInsert.size)
        val replace = ImportPlanner.plan(existing, sample.notes, DuplicateStrategy.REPLACE)
        assertEquals(listOf(10L to 99L), replace.toReplace.map { it.first.id to it.second })
        assertEquals(1, replace.toInsert.size)
    }

    @Test fun `duplicates inside one backup are skipped`() {
        val notes = listOf(sample.notes[0], sample.notes[0].copy(id = 12))
        assertEquals(1, ImportPlanner.plan(emptyList(), notes, DuplicateStrategy.SKIP).toInsert.size)
    }

    @Test fun `text import helpers`() {
        assertTrue(TextImport.isSupported("a.TXT", null))
        assertTrue(TextImport.isSupported("x", "text/plain"))
        assertFalse(TextImport.isSupported("a.png", "image/png"))
        assertEquals("My file", TextImport.titleFor("My file.txt"))
        assertEquals("a\nb\nc", TextImport.normalize("﻿a\r\nb\rc"))
        assertEquals("a b c.txt", TextImport.exportFileName("a/b:c"))
        assertEquals("Untitled note.txt", TextImport.exportFileName("  "))
    }
}
