package com.aliothmoon.maafw.gacha

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import java.io.File

enum class UpStatus { UP, GUARANTEED, OFF }

/**
 * Shared UP banner catalog (`resource/base/gacha/up_banners.json`) + optional avatar lookup.
 * Missing catalog or unknown pools simply yield null so old records are never mislabeled as off-banner.
 */
data class UpBannerCatalog(
    val standardPools: Set<String> = emptySet(),
    val banners: Map<String, Set<String>> = emptyMap(),
) {
    fun classify(pool: String, gameType: String?, name: String): UpStatus? {
        if (pool.isBlank() || name.isBlank() || banners.isEmpty()) return null
        if (gameType == "常驻") return null
        val normPool = normalizeKey(pool)
        if (normPool.isEmpty()) return null
        if (standardPools.any { it.isNotEmpty() && (normPool == it || it in normPool) }) return null
        val ups = banners[normPool]
            ?: banners.entries.firstOrNull { (key, _) -> key.isNotEmpty() && key in normPool }?.value
            ?: return null
        if (ups.isEmpty()) return null
        val normName = normalizeKey(name)
        return if (normName in ups) UpStatus.UP else UpStatus.OFF
    }

    companion object {
        private val SEP_REGEX = Regex("""[\s·.•\u2022\u25CF\u00B7\-—_]+""")

        fun normalizeKey(text: String): String = SEP_REGEX.replace(text.trim(), "")

        fun load(piRoot: File): UpBannerCatalog = runCatching {
            val file = File(piRoot, "resource/base/gacha/up_banners.json")
            if (!file.isFile || file.length() > 1024 * 1024) return@runCatching UpBannerCatalog()
            parse(file.readText(Charsets.UTF_8))
        }.getOrDefault(UpBannerCatalog())

        fun parse(raw: String): UpBannerCatalog = runCatching {
            val obj = Json.parseToJsonElement(raw.removePrefix("\uFEFF")) as? JsonObject
                ?: return@runCatching UpBannerCatalog()
            val standard = (obj["standard_pools"] as? JsonArray).orEmpty()
                .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::normalizeKey)?.takeIf(String::isNotEmpty) }
                .toSet()
            val banners = (obj["banners"] as? JsonObject).orEmpty().entries.mapNotNull { (pool, value) ->
                val normPool = normalizeKey(pool)
                val ups = (value as? JsonArray).orEmpty()
                    .mapNotNull { (it as? JsonPrimitive)?.contentOrNull?.let(::normalizeKey)?.takeIf(String::isNotEmpty) }
                    .toSet()
                if (normPool.isNotEmpty() && ups.isNotEmpty()) normPool to ups else null
            }.toMap()
            UpBannerCatalog(standard, banners)
        }.getOrDefault(UpBannerCatalog())

        /** Optional square portrait under `resource/base/image/avatars/<name>.webp|.png`; null triggers char fallback. */
        fun resolveAvatar(piRoot: File, sinnerName: String): File? {
            if (sinnerName.isBlank()) return null
            val avatarRoot = runCatching { File(piRoot, "resource/base/image/avatars").canonicalFile }.getOrNull()
                ?: return null
            if (!avatarRoot.isDirectory) return null
            val basePath = avatarRoot.toPath()
            for (ext in listOf("webp", "png", "jpg", "jpeg")) {
                val candidate = runCatching { File(avatarRoot, "$sinnerName.$ext").canonicalFile }.getOrNull() ?: continue
                if (candidate.toPath().startsWith(basePath) && candidate.isFile) return candidate
            }
            return null
        }
    }
}
