package com.wjz.worldsmith.core.ability.extension

import com.wjz.worldsmith.core.serialization.WorldsmithJson
import java.lang.reflect.Modifier
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path

/** Separate-process, static ABI probe. Deliberately never constructs a provider or calls spec()/invoke(). */
object AbilityExtensionProbeMain {
    @JvmStatic fun main(arguments: Array<String>) {
        require(arguments.size == 2) { "Expected artifact path and result path." }
        val path = Path.of(arguments[0]).toAbsolutePath().normalize()
        val artifact = AbilityExtensionArtifacts.inspect(path)
        URLClassLoader(arrayOf(path.toUri().toURL()), AbilityExtension::class.java.classLoader).use { loader ->
            artifact.manifest.entryClasses.forEach { name ->
                val type = Class.forName(name, false, loader)
                require(type.classLoader === loader) { "Provider '$name' resolved outside its artifact." }
                require(AbilityExtension::class.java.isAssignableFrom(type) && !type.isInterface && !Modifier.isAbstract(type.modifiers) && Modifier.isPublic(type.modifiers)) { "Provider '$name' must be a public concrete AbilityExtension." }
                require(Modifier.isPublic(type.getConstructor().modifiers)) { "Provider '$name' needs a public no-argument constructor." }
            }
        }
        Files.writeString(Path.of(arguments[1]), WorldsmithJson.encode(artifact))
        println("STATIC_ABI_VERIFIED; provider constructors, static initializers, spec() and invoke() were not executed.")
    }
}
