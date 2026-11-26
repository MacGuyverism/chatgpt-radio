package com.macguyverism.briefsona

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.io.IOException

class ReliabilityTest {
    @Test fun verifiedDownloadMatchesExpectedBytes() = withTemp { file ->
        val bytes = "test".toByteArray()
        copyVerified(bytes.inputStream(), file, 4,
            "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08")
        assertArrayEquals(bytes, file.readBytes())
    }

    @Test(expected = IOException::class)
    fun truncatedDownloadFails() = withTemp { file ->
        copyVerified("test".byteInputStream(), file, 5, "")
    }

    @Test(expected = IOException::class)
    fun wrongDigestFails() = withTemp { file ->
        copyVerified("test".byteInputStream(), file, 4, "invalid")
    }

    @Test(expected = IOException::class)
    fun oversizedDownloadFails() = withTemp { file ->
        copyVerified("test".byteInputStream(), file, 3, "")
    }

    @Test fun chapterAndResumeBoundaries() {
        val starts = listOf(0L, 5_000L, 10_000L)
        assertEquals(0, chapterIndex(starts, 4_999))
        assertEquals(1, chapterIndex(starts, 5_000))
        assertEquals(2, chapterIndex(starts, 12_000))
        assertEquals(5_000L, resumePosition(5_000, 20_000, true))
        assertEquals(0L, resumePosition(5_000, 20_000, false))
        assertEquals(0L, resumePosition(20_000, 20_000, true))
    }

    private fun withTemp(run: (File) -> Unit) {
        val file = File.createTempFile("briefsona", ".part")
        try { run(file) } finally { file.delete() }
    }
}
