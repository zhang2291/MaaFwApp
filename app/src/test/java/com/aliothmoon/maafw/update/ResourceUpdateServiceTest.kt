package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.project.ProjectRepository
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkObject
import io.mockk.unmockkObject
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ResourceUpdateServiceTest {
    @get:Rule val temp = TemporaryFolder()

    @Before fun setup() {
        mockkObject(AppPaths)
        every { AppPaths.ROOT } returns temp.root
    }

    @After fun teardown() = unmockkObject(AppPaths)

    private fun installed(version: String) {
        File(temp.root, AppFiles.PI_DIR).mkdirs()
        File(File(temp.root, AppFiles.PI_DIR), ".resource-version").writeText(version)
    }

    private fun gateway(version: String) = RecordingHttpClientHelper(FakeHttpResponse(200,
        """{"version":"$version","asset_url":"https://github.com/zhang2291/MBCCtools/releases/download/resource-v$version/MBCCtools-resource.zip","sha256":"${"a".repeat(64)}"}"""))

    @Test fun `checks fork manifest and accepts newer resource`() = runBlocking {
        installed("20261022-010000")
        val http = gateway("20261022-020000")
        val result = ResourceUpdateService(http.mock, mockk<ProjectRepository>()).check("zhang2291/MBCCtools")
        assertTrue(result is ResourceUpdateCheckResult.UpdateAvailable)
        assertEquals("https://raw.githubusercontent.com/zhang2291/MBCCtools/resource-manifest/resource-update.json", http.requests.single().first)
    }

    @Test fun `rejects older published resource without changing user data`() = runBlocking {
        installed("20261022-010000")
        val data = File(temp.root, "data/record/gacha/test.jsonl").apply { parentFile!!.mkdirs(); writeText("record") }
        val config = File(temp.root, "user-config.json").apply { writeText("config") }
        val service = ResourceUpdateService(gateway("20261006-005124").mock, mockk<ProjectRepository>())
        assertTrue(service.check("zhang2291/MBCCtools") is ResourceUpdateCheckResult.Failed)
        assertTrue(service.install(ResourceUpdateManifest("20261006-005124", "https://example.com/old.zip", "a".repeat(64))) is ResourceUpdateInstallResult.Failed)
        assertEquals("record", data.readText())
        assertEquals("config", config.readText())
        assertEquals("20261022-010000", service.currentVersion())
    }

    @Test fun `equal resource is up to date and unversioned APK can update`() = runBlocking {
        val service = ResourceUpdateService(gateway("20261022-010000").mock, mockk<ProjectRepository>())
        assertTrue(service.check("zhang2291/MBCCtools") is ResourceUpdateCheckResult.UpdateAvailable)
        installed("20261022-010000")
        val same = ResourceUpdateService(gateway("20261022-010000").mock, mockk<ProjectRepository>())
        assertTrue(same.check("zhang2291/MBCCtools") is ResourceUpdateCheckResult.UpToDate)
    }

    @Test fun `install replaces PI while retaining archives and sibling configuration`() {
        installed("20261006-005124")
        val pi = File(temp.root, AppFiles.PI_DIR)
        File(pi, "obsolete.json").writeText("old resource")
        val records = File(temp.root, "data/record/gacha/new-character/history.jsonl")
        records.parentFile!!.mkdirs()
        records.writeText("record")
        val categories = File(temp.root, "data/gacha-categories.json").apply { writeText("categories") }
        val config = File(temp.root, "user_configuration.json").apply { writeText("config") }
        val archive = temp.newFile("resource.zip")
        ZipOutputStream(archive.outputStream()).use { zip ->
            mapOf("interface.json" to "{}", "resource/base/pipeline/test.json" to "{}", "agent/main.py" to "pass").forEach { (name, body) ->
                zip.putNextEntry(ZipEntry(name))
                zip.write(body.toByteArray())
                zip.closeEntry()
            }
        }
        val service = ResourceUpdateService(mockk(), mockk())
        // Exercise the real on-disk installer without HTTP or altering its production API.
        ResourceUpdateService::class.java.getDeclaredMethod("installArchive", File::class.java, String::class.java)
            .apply { isAccessible = true }.invoke(service, archive, "20261022-010000")
        assertEquals("20261022-010000", service.currentVersion())
        assertTrue(File(pi, "resource/base/pipeline/test.json").isFile)
        assertTrue(File(pi, "agent/main.py").isFile)
        assertFalse(File(pi, "obsolete.json").exists())
        assertEquals("record", records.readText())
        assertEquals("categories", categories.readText())
        assertEquals("config", config.readText())
    }
}
