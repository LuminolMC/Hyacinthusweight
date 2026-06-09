/*
 * paperweight is a Gradle plugin for the PaperMC project.
 *
 * Copyright (c) 2023 Kyle Wood (DenWav)
 *                    Contributors
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation;
 * version 2.1 only, no later versions.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301
 * USA
 */

package io.papermc.paperweight.core.tasks

import io.papermc.paperweight.tasks.*
import io.papermc.paperweight.util.*
import io.papermc.paperweight.util.constants.*
import java.io.File
import kotlin.io.path.*
import org.gradle.StartParameter
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.internal.StartParameterInternal
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import org.gradle.api.tasks.UntrackedTask
import org.gradle.internal.build.NestedRootBuildRunner
import org.gradle.internal.classpath.ClassPath
import org.gradle.internal.service.ServiceRegistry

@UntrackedTask(because = "Nested build does it's own up-to-date checking")
abstract class RunNestedBuild : BaseTask() {

    @get:Input
    abstract val tasks: SetProperty<String>

    @get:InputDirectory
    abstract val projectDir: DirectoryProperty

    @get:Internal
    abstract val workDir: DirectoryProperty

    override fun init() {
        super.init()
        workDir.convention(project.upstreamsDirectory())
    }

    @TaskAction
    fun run() {
        val params = NestedRootBuildRunner.createStartParameterForNewBuild(services)
        params.projectDir = projectDir.get().asFile

        params.setTaskNames(tasks.get())

        params.projectProperties[UPSTREAM_WORK_DIR_PROPERTY] = workDir.path.absolutePathString()

        params.systemPropertiesArgs[PAPERWEIGHT_DEBUG] = System.getProperty(PAPERWEIGHT_DEBUG, "false")

        try {
            runTask(params)
        } catch (_: Exception) {
            try {
                params.projectDir?.let { modifyDownstreamSettings(it) }
            } catch (modifyException: Exception) {
                logger.warn("Failed to modify downstream build.gradle.kts: ${modifyException.message}")
            }
            runTask(params)
        }
    }

    fun runTask(params: StartParameter) {
        NestedRootBuildRunner::class.java.getDeclaredMethod(
            "runNestedRootBuild",
            String::class.java,
            StartParameterInternal::class.java,
            ServiceRegistry::class.java,
            ClassPath::class.java
        ).invoke(null, null, params, services, null)
    }

    private fun modifyDownstreamSettings(projectDir: File) {
        val buildFile = File(projectDir, "build.gradle.kts")
        if (!buildFile.exists()) {
            return
        }

        var content = buildFile.readText()

        val currentVersion = project.version.toString()

        val oldPatcherPluginId = """id\(["']io\.papermc\.paperweight\.patcher["']\)\s+version\s+["'][^"']+["']"""
        val newPatcherPluginId = """id("moe.luminolmc.hyacinthusweight.patcher") version "$currentVersion""""

        val oldCorePluginId = """id\(["']io\.papermc\.paperweight\.core["']\)\s+version\s+["'][^"']+["']"""
        val newCorePluginId = """id("moe.luminolmc.hyacinthusweight.core") version "$currentVersion""""

        var modified = false

        if (content.contains(Regex(oldPatcherPluginId))) {
            content = content.replace(Regex(oldPatcherPluginId), newPatcherPluginId)
            modified = true
        }

        if (content.contains(Regex(oldCorePluginId))) {
            content = content.replace(Regex(oldCorePluginId), newCorePluginId)
            modified = true
        }

        val weightVersionPattern = """version\s+weightVersion"""
        if (content.contains(Regex(weightVersionPattern))) {
            content = content.replace(Regex(weightVersionPattern), """version "$currentVersion"""")
            modified = true
        }

        if (modified) {
            val repositoriesMatch = Regex("""repositories\s*\{""").find(content)
            if (repositoriesMatch != null && !content.contains("repo.menthamc.org")) {
                content = content.substring(0, repositoriesMatch.range.last + 1) +
                    "\n        maven(\"https://repo.menthamc.org/repository/maven-public/\")" +
                    content.substring(repositoriesMatch.range.last + 1)
            }

            buildFile.writeText(content)
            logger.lifecycle("Modified downstream build.gradle.kts to use hyacinthusweight plugins version $currentVersion")
        }
    }
}
