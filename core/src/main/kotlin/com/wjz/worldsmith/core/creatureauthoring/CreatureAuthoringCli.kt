package com.wjz.worldsmith.core.creatureauthoring

import com.wjz.worldsmith.core.content.*
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import java.net.URLClassLoader
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import javax.tools.DiagnosticCollector
import javax.tools.JavaFileObject
import javax.tools.ToolProvider

/** Explicit local authoring entry, not a runtime or MCP source-execution path. */
object CreatureAuthoringCli {
    @JvmStatic fun main(args: Array<String>) {
        require(args.size in 2..3) { "Usage: CreatureAuthoringCli <recipe.json | default-package CreatureProgram.java> <output-directory> [painted-texture.png]" }
        val input = Path.of(args[0]).toAbsolutePath().normalize()
        val output = Path.of(args[1]).toAbsolutePath().normalize()
        require(Files.isRegularFile(input) && Files.size(input) <= 512 * 1024) { "Use one bounded authoring source or recipe file" }
        Files.createDirectories(output)
        val recipe = if (input.fileName.toString().endsWith(".java")) compileJava(input, output)
            else WorldsmithJson.decode<CreatureRecipe>(Files.readString(input))
        val guide = CreatureAuthoring.guide(recipe)
        Files.writeString(output.resolve("recipe.json"), WorldsmithJson.encode(recipe), StandardCharsets.UTF_8)
        Files.writeString(output.resolve("uv-layout.json"), WorldsmithJson.encode(guide.uvLayout), StandardCharsets.UTF_8)
        Files.write(output.resolve("uv-guide.png"), guide.png)
        val definition = if (args.size == 3) {
            val texturePath = Path.of(args[2]).toAbsolutePath().normalize()
            require(Files.size(texturePath) <= ContentAssetValidation.MAX_ASSET_BYTES) { "Painted texture exceeds asset budget" }
            val bytes = Files.readAllBytes(texturePath)
            val hash = ContentAssetValidation.hash(bytes)
            val asset = ContentAsset(hash, hash, "image/png", bytes.size.toLong(), ContentAssetValidation.path(hash))
            val size = ContentAssetValidation.verify(asset, bytes)
            require(size.width == recipe.atlasWidth && size.height == recipe.atlasHeight) { "Painted PNG must match the fixed atlas; scale imported PNG explicitly before compiling" }
            Files.write(output.resolve("texture.png"), bytes)
            Files.writeString(output.resolve("texture-asset.json"), WorldsmithJson.encode(asset), StandardCharsets.UTF_8)
            CreatureAuthoring.compile(recipe, hash).definition
        } else {
            Files.writeString(output.resolve("guide-asset.json"), WorldsmithJson.encode(guide.asset), StandardCharsets.UTF_8)
            guide.definition
        }
        Files.writeString(output.resolve("creature.json"), WorldsmithJson.encode(definition), StandardCharsets.UTF_8)
        Files.writeString(output.resolve("creatures.json"), WorldsmithJson.encode(CreatureLibrary(schemaVersion=recipe.schemaVersion,creatures = listOf(definition))), StandardCharsets.UTF_8)
        Files.writeString(output.resolve("authoring-status.json"), """{"guideOnly":${args.size == 2},"atlasWidth":${recipe.atlasWidth},"atlasHeight":${recipe.atlasHeight},"textureAsset":"${definition.model.texture}","boneCount":${definition.model.bones.size},"cubeCount":${definition.model.bones.sumOf { it.cubes.size }}}""", StandardCharsets.UTF_8)
        println("Creature authoring output: $output")
        println("${recipe.atlasWidth}x${recipe.atlasHeight}; ${definition.model.bones.size} bones; ${definition.model.bones.sumOf { it.cubes.size }} cubes; guideOnly=${args.size == 2}")
    }

    private fun compileJava(source: Path, output: Path): CreatureRecipe {
        val compiler = requireNotNull(ToolProvider.getSystemJavaCompiler()) { "Local Java source authoring needs a JDK; JSON recipes need no compiler" }
        val className = source.fileName.toString().removeSuffix(".java")
        require(className.matches(Regex("[a-zA-Z][a-zA-Z0-9_]{0,95}"))) { "Use a default-package Java program with a simple filename" }
        val classes = output.resolve("source-classes")
        Files.createDirectories(classes)
        val diagnostics = DiagnosticCollector<JavaFileObject>()
        compiler.getStandardFileManager(diagnostics, null, StandardCharsets.UTF_8).use { manager ->
            val units = manager.getJavaFileObjects(source)
            val options = listOf("--release", "21", "-classpath", System.getProperty("java.class.path"), "-d", classes.toString())
            val success = compiler.getTask(null, manager, diagnostics, options, null, units).call()
            require(success) { diagnostics.diagnostics.joinToString("\n") { "${it.kind} ${it.lineNumber}: ${it.getMessage(null)}" } }
        }
        return URLClassLoader(arrayOf(classes.toUri().toURL()), CreatureProgram::class.java.classLoader).use { loader ->
            val program = loader.loadClass(className).getDeclaredConstructor().newInstance()
            require(program is CreatureProgram) { "Java entry class must implement CreatureProgram" }
            program.create().recipe()
        }
    }
}
