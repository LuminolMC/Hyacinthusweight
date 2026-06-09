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

import io.papermc.paperweight.tasks.BaseTask
import io.papermc.paperweight.util.constants.PAPERWEIGHT_DEBUG
import io.papermc.paperweight.util.constants.UPSTREAM_WORK_DIR_PROPERTY
import io.papermc.paperweight.util.path
import io.papermc.paperweight.util.upstreamsDirectory
import kotlin.collections.set
import kotlin.io.path.absolutePathString
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

        // Gradle's internal API has changed across versions: the
        // signature for runNestedRootBuild may be either
        // (String, StartParameterInternal, ServiceRegistry) or
        // (String, StartParameterInternal, ServiceRegistry, ClassPath).
        // Try to find a matching overload and invoke it so the plugin
        // works across multiple Gradle versions.
        val runnerClass = NestedRootBuildRunner::class.java
        val methods = runnerClass.declaredMethods.filter { it.name == "runNestedRootBuild" }

        val method = methods.find { m ->
            val paramsTypes = m.parameterTypes
            paramsTypes.size == 3 &&
                paramsTypes[0] == String::class.java &&
                paramsTypes[1] == StartParameterInternal::class.java &&
                paramsTypes[2] == ServiceRegistry::class.java
        } ?: methods.find { m ->
            val paramsTypes = m.parameterTypes
            paramsTypes.size == 4 &&
                paramsTypes[0] == String::class.java &&
                paramsTypes[1] == StartParameterInternal::class.java &&
                paramsTypes[2] == ServiceRegistry::class.java &&
                paramsTypes[3] == ClassPath::class.java
        }

        if (method == null) {
            throw NoSuchMethodException("Could not find a compatible runNestedRootBuild method on NestedRootBuildRunner")
        }

        if (method.parameterTypes.size == 3) {
            method.invoke(null, null, params, services)
        } else {
            method.invoke(null, null, params, services, ClassPath.EMPTY)
        }
    }
}
