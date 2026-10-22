package com.aliothmoon.maafw.update

import com.aliothmoon.maafw.MaaDispatchers
import com.aliothmoon.maafw.R
import com.aliothmoon.maafw.constant.AppFiles
import com.aliothmoon.maafw.constant.AppPaths
import com.aliothmoon.maafw.constant.MiscConstants
import com.aliothmoon.maafw.i18n.UiText
import com.aliothmoon.maafw.i18n.uiTextOf
import com.aliothmoon.maafw.project.ProjectRepository
import com.aliothmoon.maafw.util.HttpClientHelper
import com.aliothmoon.maafw.util.await
import com.aliothmoon.maafw.util.parseJsonObject
import com.aliothmoon.maafw.util.readBody
import com.aliothmoon.maafw.util.string
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.Request
import timber.log.Timber
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import java.util.Locale
import java.util.zip.ZipInputStream
import kotlin.coroutines.coroutineContext

data class ResourceUpdateManifest(
    val version: String,
    val downloadUrl: String,
    val sha256: String,
)

sealed interface ResourceUpdateCheckResult {
    data class UpdateAvailable(
        val manifest: ResourceUpdateManifest,
        val currentVersion: String?,
    ) : ResourceUpdateCheckResult

    data class UpToDate(
        val manifest: ResourceUpdateManifest,
        val currentVersion: String?,
    ) : ResourceUpdateCheckResult

    data class Failed(val message: UiText) : ResourceUpdateCheckResult
}

sealed interface ResourceUpdateInstallResult {
    data class Installed(val version: String) : ResourceUpdateInstallResult
    data class Failed(val message: UiText) : ResourceUpdateInstallResult
}

class ResourceUpdateService(
    private val helper: HttpClientHelper,
    private val projectRepository: ProjectRepository,
) {
    fun currentVersion(): String? = runCatching {
        markerFile().takeIf(File::isFile)?.readText()?.trim()?.takeIf(String::isNotEmpty)
    }.getOrNull()

    suspend fun check(repository: String?): ResourceUpdateCheckResult = try {
        val repo = repository?.trim()?.takeIf(REPOSITORY_PATTERN::matches)
            ?: return ResourceUpdateCheckResult.Failed(
                uiTextOf(R.string.resource_update_missing_repository),
            )
        val response = helper.get(
            manifestUrl(repo),
            headers = mapOf(
                "User-Agent" to MiscConstants.BROWSER_UA,
                "Cache-Control" to "no-cache",
            ),
        )
        val code = response.code
        val body = response.readBody()
        if (code == HTTP_NOT_FOUND) {
            return ResourceUpdateCheckResult.Failed(
                uiTextOf(R.string.resource_update_not_published),
            )
        }
        if (code !in 200..299) {
            return ResourceUpdateCheckResult.Failed(
                uiTextOf(R.string.resource_update_http_error, code),
            )
        }
        val raw = parseJsonObject(body)
            ?: return ResourceUpdateCheckResult.Failed(
                uiTextOf(R.string.resource_update_invalid_manifest),
            )
        val version = raw.string("version")?.trim().orEmpty()
        val downloadUrl = raw.string("asset_url")?.trim().orEmpty()
        val sha256 = normalizeDigest(raw.string("sha256"))
        val parsedUrl = downloadUrl.toHttpUrlOrNull()
        if (
            version.isEmpty() ||
            sha256 == null ||
            parsedUrl == null ||
            !parsedUrl.isHttps
        ) {
            return ResourceUpdateCheckResult.Failed(
                uiTextOf(R.string.resource_update_invalid_manifest),
            )
        }
        val manifest = ResourceUpdateManifest(version, downloadUrl, sha256)
        val current = currentVersion()
        if (isOlderResource(version, current)) {
            return ResourceUpdateCheckResult.Failed(uiTextOf(R.string.resource_update_older_than_installed))
        }
        if (current == version) {
            ResourceUpdateCheckResult.UpToDate(manifest, current)
        } else {
            ResourceUpdateCheckResult.UpdateAvailable(manifest, current)
        }
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Timber.tag("ResourceUpdateCheck").w(e, "resource update check failed")
        ResourceUpdateCheckResult.Failed(uiTextOf(R.string.resource_update_network_error))
    }

    suspend fun install(
        manifest: ResourceUpdateManifest,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit = { _, _ -> },
    ): ResourceUpdateInstallResult = try {
        // 检查后可能已有较新的资源装入；下载入口也防止旧清单回退本地资源。
        if (isOlderResource(manifest.version, currentVersion())) {
            return ResourceUpdateInstallResult.Failed(uiTextOf(R.string.resource_update_older_than_installed))
        }
        val archive = withContext(MaaDispatchers.IO) {
            downloadArchive(manifest, onProgress)
        }
        try {
            withContext(MaaDispatchers.IO) {
                installArchive(archive, manifest.version)
            }
            projectRepository.reload()
        } finally {
            archive.delete()
        }
        ResourceUpdateInstallResult.Installed(manifest.version)
    } catch (e: CancellationException) {
        throw e
    } catch (e: ResourceUpdateException) {
        Timber.tag("ResourceUpdateInstall").w(e, "resource update failed")
        ResourceUpdateInstallResult.Failed(e.uiMessage)
    } catch (e: Exception) {
        Timber.tag("ResourceUpdateInstall").w(e, "resource update failed")
        ResourceUpdateInstallResult.Failed(uiTextOf(R.string.resource_update_storage_error))
    }

    private suspend fun downloadArchive(
        manifest: ResourceUpdateManifest,
        onProgress: (downloadedBytes: Long, totalBytes: Long) -> Unit,
    ): File {
        val url = manifest.downloadUrl.toHttpUrlOrNull()
            ?: throw ResourceUpdateException(uiTextOf(R.string.resource_update_invalid_manifest))
        if (!url.isHttps) {
            throw ResourceUpdateException(uiTextOf(R.string.resource_update_invalid_manifest))
        }
        val cacheDir = AppPaths.UPDATES_CACHE_DIR
        if (!(cacheDir.mkdirs() || cacheDir.isDirectory)) {
            throw ResourceUpdateException(uiTextOf(R.string.resource_update_storage_error))
        }
        val safeVersion = manifest.version.replace(UNSAFE_FILE_NAME, "_").take(48)
        val target = File(cacheDir, "mbcctools-resource-$safeVersion.zip")
        val part = File(target.path + ".part")
        target.delete()
        part.delete()

        val request = Request.Builder()
            .url(url)
            .header("User-Agent", MiscConstants.BROWSER_UA)
            .header("Accept-Encoding", "identity")
            .get()
            .build()
        val call = helper.rawClient().newCall(request)
        try {
            call.await().use { response ->
                if (response.code != HTTP_OK) {
                    throw ResourceUpdateException(
                        uiTextOf(R.string.resource_update_http_error, response.code),
                    )
                }
                val total = response.body.contentLength()
                val digest = MessageDigest.getInstance(SHA_256)
                var downloaded = 0L
                var lastReported = -PROGRESS_INTERVAL.toLong()
                onProgress(0L, total)
                response.body.byteStream().use { input ->
                    FileOutputStream(part).use { output ->
                        val buffer = ByteArray(BUFFER_SIZE)
                        while (true) {
                            coroutineContext.ensureActive()
                            val read = input.read(buffer)
                            if (read < 0) break
                            if (read == 0) continue
                            output.write(buffer, 0, read)
                            digest.update(buffer, 0, read)
                            downloaded += read
                            if (downloaded - lastReported >= PROGRESS_INTERVAL || downloaded == total) {
                                onProgress(downloaded, total)
                                lastReported = downloaded
                            }
                        }
                    }
                }
                val actual = hex(digest.digest())
                if (actual != manifest.sha256) {
                    throw ResourceUpdateException(
                        uiTextOf(R.string.resource_update_digest_mismatch),
                    )
                }
                if (downloaded != lastReported) onProgress(downloaded, total)
            }
            Files.move(
                part.toPath(),
                target.toPath(),
                StandardCopyOption.REPLACE_EXISTING,
            )
            return target
        } catch (e: CancellationException) {
            call.cancel()
            throw e
        } catch (e: ResourceUpdateException) {
            throw e
        } catch (e: IOException) {
            throw ResourceUpdateException(uiTextOf(R.string.resource_update_network_error), e)
        } finally {
            part.delete()
        }
    }

    private fun installArchive(archive: File, version: String) {
        val root = AppPaths.ROOT
        val target = File(root, AppFiles.PI_DIR)
        val temp = File(root, PI_UPDATE_DIR)
        val backup = File(root, PI_BACKUP_DIR)
        temp.deleteRecursively()
        backup.deleteRecursively()
        if (!temp.mkdirs()) {
            throw ResourceUpdateException(uiTextOf(R.string.resource_update_storage_error))
        }
        try {
            unpack(archive, temp)
            val interfaceFile = File(temp, INTERFACE_JSON)
            val resourceDir = File(temp, RESOURCE_DIR)
            if (!interfaceFile.isFile || !resourceDir.isDirectory) {
                throw ResourceUpdateException(uiTextOf(R.string.resource_update_invalid_archive))
            }
            File(temp, RESOURCE_VERSION_MARKER).writeText(version)

            val hadTarget = target.exists()
            if (hadTarget && !target.renameTo(backup)) {
                throw ResourceUpdateException(uiTextOf(R.string.resource_update_storage_error))
            }
            if (!temp.renameTo(target)) {
                if (hadTarget) backup.renameTo(target)
                throw ResourceUpdateException(uiTextOf(R.string.resource_update_storage_error))
            }
            backup.deleteRecursively()
        } catch (e: ResourceUpdateException) {
            temp.deleteRecursively()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            throw e
        } catch (e: Exception) {
            temp.deleteRecursively()
            if (!target.exists() && backup.exists()) backup.renameTo(target)
            throw ResourceUpdateException(uiTextOf(R.string.resource_update_storage_error), e)
        }
    }

    private fun unpack(archive: File, destination: File) {
        val root = destination.canonicalFile
        ZipInputStream(archive.inputStream().buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                val name = normalizeZipName(entry.name)
                val output = File(destination, name).canonicalFile
                if (output != root && !output.path.startsWith(root.path + File.separator)) {
                    throw ResourceUpdateException(uiTextOf(R.string.resource_update_invalid_archive))
                }
                if (entry.isDirectory) {
                    output.mkdirs()
                } else {
                    output.parentFile?.mkdirs()
                    output.outputStream().use { stream ->
                        zip.copyTo(stream, BUFFER_SIZE)
                    }
                }
                zip.closeEntry()
            }
        }
    }

    private fun normalizeZipName(raw: String): String {
        val name = raw.replace('\\', '/').trimStart('/')
        if (name.isEmpty() || name.split('/').any { it == ".." }) {
            throw ResourceUpdateException(uiTextOf(R.string.resource_update_invalid_archive))
        }
        return name
    }

    private fun markerFile(): File =
        File(File(AppPaths.ROOT, AppFiles.PI_DIR), RESOURCE_VERSION_MARKER)

    private fun manifestUrl(repository: String): String =
        "https://raw.githubusercontent.com/$repository/$MANIFEST_BRANCH/$MANIFEST_FILE"

    private fun isOlderResource(remote: String, current: String?): Boolean {
        // 兼容现有 yyyyMMdd-HHmmss 发布版本以及语义版本；无版本标记的旧 APK 仍可升级。
        val installed = current?.let(UpdateVersion::parse) ?: return false
        val available = UpdateVersion.parse(remote) ?: return false
        return available < installed
    }

    private fun normalizeDigest(raw: String?): String? {
        val value = raw?.trim().orEmpty().removePrefix("sha256:").lowercase(Locale.US)
        return value.takeIf(DIGEST_PATTERN::matches)
    }

    private fun hex(value: ByteArray): String = value.joinToString("") {
        String.format(Locale.US, "%02x", it)
    }

    private class ResourceUpdateException(
        val uiMessage: UiText,
        cause: Throwable? = null,
    ) : Exception(cause)

    private companion object {
        const val MANIFEST_BRANCH = "resource-manifest"
        const val MANIFEST_FILE = "resource-update.json"
        const val RESOURCE_VERSION_MARKER = ".resource-version"
        const val PI_UPDATE_DIR = "pi-resource-update"
        const val PI_BACKUP_DIR = "pi-resource-backup"
        const val INTERFACE_JSON = "interface.json"
        const val RESOURCE_DIR = "resource"
        const val HTTP_OK = 200
        const val HTTP_NOT_FOUND = 404
        const val BUFFER_SIZE = 64 * 1024
        const val PROGRESS_INTERVAL = 512 * 1024
        const val SHA_256 = "SHA-256"
        val REPOSITORY_PATTERN = Regex("""^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$""")
        val DIGEST_PATTERN = Regex("""^[0-9a-f]{64}$""")
        val UNSAFE_FILE_NAME = Regex("""[^A-Za-z0-9._-]""")
    }
}
