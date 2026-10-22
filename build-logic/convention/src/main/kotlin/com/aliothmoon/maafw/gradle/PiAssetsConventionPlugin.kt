package com.aliothmoon.maafw.gradle

import com.android.build.api.variant.ApplicationAndroidComponentsExtension
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.Sync
import org.gradle.api.tasks.bundling.Zip
import org.gradle.kotlin.dsl.configure
import org.gradle.kotlin.dsl.register
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

/**
 * The chain that syncs the external PI project into a single archive; apply after
 * maafw.android.application. Which PI and which parts of it come from the build profile,
 * see [BuildProfile]
 *
 * Loose assets are rewritten by AAPT (`<dir>_*` dropped, `.*` dropped, `.gz` decompressed).
 * Same reason agent runtimes ship as bundle.zip: syncPiAssets -> packPiArchive -> assets/pi.zip
 */
class PiAssetsConventionPlugin : Plugin<Project> {
    override fun apply(target: Project) {
        with(target) {
            val profile = buildProfile()
            val assetsSourceDir = profile.assetsDir
            val piAssetsDir = layout.buildDirectory.dir("generated/piAssets")
            val piDir = piAssetsDir.map { it.dir("pi") }
            val packedDir = piAssetsDir.map { it.dir("packed") }
            val includePatterns = profile.piInclude
            val excludePatterns = profile.piExclude

            val syncPiAssets = tasks.register<Sync>("syncPiAssets") {
                group = "build"
                description = "Sync the configured PI project into the generated PI tree"
                inputs.property("stampResourceVersion", profile.stampResourceVersion)
                into(piDir)
                if (assetsSourceDir != null) {
                    from(assetsSourceDir) {
                        includePatterns.forEach { include(it) }
                        // Exclude wins over include in Gradle, which is what carves heavy files
                        // back out of an otherwise wholesale directory
                        excludePatterns.forEach { exclude(it) }
                        // Always on, on top of whatever the profile says: the include list already
                        // keeps them out, pruning explicitly only avoids walking .git and other
                        // large directories of the upstream repo
                        exclude(".git/**", "node_modules/**", ".venv/**", "__pycache__/**")
                    }
                } else {
                    // Soft failure: unit tests read src/test/fixtures and must not be blocked by PI config
                    // A package without a PI ends up in ProjectState.Error at runtime
                    doFirst {
                        logger.warn("no PI configured (pi.profile in local.properties or PI_PROFILE), the build output will not contain a PI")
                    }
                }
                doLast {
                    val root = piDir.get().asFile
                    if (profile.stampResourceVersion && root.resolve("interface.json").isFile) {
                        // 内置资源也需要版本基准，否则旧远端清单会覆盖新 APK 的资源。
                        // 仅资源重新同步时生成，增量构建复用标记；与发布清单统一使用 UTC。
                        val marker = root.resolve(".resource-version")
                        if (!marker.isFile) {
                            marker.writeText(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")
                                .withZone(ZoneOffset.UTC).format(Instant.now()) + "\n")
                        }
                    }
                }
            }

            val packPiArchive = tasks.register<Zip>("packPiArchive") {
                group = "build"
                description = "Pack the synced PI tree into assets/pi.zip"
                dependsOn(syncPiAssets)
                from(piDir)
                destinationDirectory.set(packedDir)
                archiveFileName.set("pi.zip")
                includeEmptyDirs = false
            }

            tasks.named("preBuild") {
                dependsOn(packPiArchive)
            }

            packedDir.get().asFile.mkdirs()

            extensions.configure<ApplicationAndroidComponentsExtension> {
                onVariants { variant ->
                    variant.sources.assets?.addStaticSourceDirectory(
                        packedDir.get().asFile.absolutePath
                    )
                }
            }

            tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
                .configureEach {
                    inputs.files(packedDir.map { it.asFileTree })
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                }
        }
    }
}
