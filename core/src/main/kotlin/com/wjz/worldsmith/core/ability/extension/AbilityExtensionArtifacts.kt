package com.wjz.worldsmith.core.ability.extension

import com.wjz.worldsmith.core.ability.AbilityCapabilities
import com.wjz.worldsmith.core.ability.AbilityPrograms
import com.wjz.worldsmith.core.serialization.WorldsmithJson
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.security.MessageDigest
import java.util.Collections
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream
import java.util.zip.ZipFile

/** Read-only integrity checks; no class loading and no provider initialization. */
object AbilityExtensionArtifacts {
    const val MANIFEST = "worldsmith-extension.json"
    const val SOURCES = "worldsmith-extension-sources.json"
    const val SERVICE = "META-INF/services/com.wjz.worldsmith.core.ability.extension.AbilityExtension"
    const val MAX_SOURCE_BYTES = 1024 * 1024
    const val MAX_SOURCE_RECORD_BYTES = 8 * 1024 * 1024
    const val MAX_ARTIFACT_BYTES = 8 * 1024 * 1024
    const val MAX_ENTRIES = 512
    private val canonical = Json { encodeDefaults = true; explicitNulls = false; classDiscriminator = "kind" }

    @JvmStatic fun hash(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    @JvmStatic fun sourceHash(project: AbilityExtensionProject): String = hash(sourceBytes(project))

    internal fun sourceBytes(project: AbilityExtensionProject): ByteArray = canonical.encodeToString(AbilityExtensionProject.serializer(), freeze(project).copy(requestId = "")).toByteArray(StandardCharsets.UTF_8)

    @JvmStatic fun validate(project: AbilityExtensionProject) = validateProject(project, true)

    private fun validateProject(project: AbilityExtensionProject, requestIdRequired: Boolean) {
        require(AbilityPrograms.validId(project.id)) { "Invalid extension id." }
        require(project.name.isNotBlank() && project.name.length <= 128) { "Extension name must contain 1..128 characters." }
        require(!requestIdRequired && project.requestId.isEmpty() || project.requestId.matches(Regex("[a-zA-Z0-9_-]{1,128}"))) { "Invalid extension requestId." }
        require(project.entryClasses.size in 1..16 && project.entryClasses.distinct().size == project.entryClasses.size) { "Declare 1..16 unique provider classes." }
        require(project.entryClasses.all(::validClass)) { "Invalid or protected provider class name." }
        require(project.declaredSpecs.keys == project.entryClasses.toSet()) { "declaredSpecs and entryClasses must match one to one." }
        var registry = AbilityCapabilities.standard()
        project.entryClasses.forEach { registry = registry.extend(project.declaredSpecs.getValue(it)) }
        require(project.sources.size in 1..16) { "Use 1..16 relative Java sources." }
        require(project.sources.keys.map { it.lowercase() }.distinct().size == project.sources.size) { "Java source paths collide on a case-insensitive filesystem." }
        require(project.sources.keys.all { name -> name.matches(Regex("([A-Za-z_$][A-Za-z0-9_$]*/)*[A-Za-z_$][A-Za-z0-9_$]*\\.java")) && name.length <= 240 }) { "Source names must be bounded, relative forward-slash Java paths." }
        var bytes = 0L
        project.sources.values.forEach { source ->
            require(source.length <= MAX_SOURCE_BYTES) { "Source exceeds 1 MiB." }
            bytes += source.toByteArray(StandardCharsets.UTF_8).size
            require(bytes <= MAX_SOURCE_BYTES) { "Project source exceeds 1 MiB." }
        }
    }

    @JvmStatic fun validClass(name: String): Boolean = name.length <= 240 && name.matches(Regex("[A-Za-z_$][A-Za-z0-9_$]*(\\.[A-Za-z_$][A-Za-z0-9_$]*)*")) &&
        listOf("java.", "javax.", "jdk.", "sun.", "kotlin.", "kotlinx.", "com.wjz.worldsmith.").none(name::startsWith)

    internal fun freeze(project: AbilityExtensionProject): AbilityExtensionProject = project.copy(
        entryClasses = Collections.unmodifiableList(project.entryClasses.sorted()),
        declaredSpecs = Collections.unmodifiableMap(project.declaredSpecs.toSortedMap().mapValues { (_, spec) -> spec.copy(arguments = Collections.unmodifiableList(ArrayList(spec.arguments))) }),
        sources = Collections.unmodifiableMap(project.sources.toSortedMap()),
    )

    internal fun build(project: AbilityExtensionProject, classDirectory: Path): Pair<ByteArray, AbilityExtensionManifest> {
        validate(project)
        val frozen = freeze(project)
        val manifest = AbilityExtensionManifest(id = frozen.id, name = frozen.name, sourceHash = sourceHash(frozen), entryClasses = frozen.entryClasses, declaredSpecs = frozen.declaredSpecs)
        val files = Files.walk(classDirectory).use { stream -> stream.filter { Files.isRegularFile(it, NOFOLLOW_LINKS) }.limit(MAX_ENTRIES.toLong()).sorted().toList() }
        require(files.size in 1..MAX_ENTRIES - 3) { "Compiled class count exceeds the bounded artifact limit." }
        val entries = linkedMapOf<String, ByteArray>()
        var total = 0L
        files.forEach { file ->
            require(!Files.isSymbolicLink(file) && file.toRealPath().startsWith(classDirectory.toRealPath())) { "Compiled output escaped the class directory." }
            val name = classDirectory.relativize(file).toString().replace('\\', '/')
            require(name.endsWith(".class") && validClass(name.removeSuffix(".class").replace('/', '.'))) { "Compiled output contains a protected or invalid class: $name" }
            val size = Files.size(file)
            require(size <= MAX_ARTIFACT_BYTES && total + size <= MAX_ARTIFACT_BYTES) { "Compiled output exceeds 8 MiB." }
            total += size
            entries[name] = Files.readAllBytes(file)
        }
        frozen.entryClasses.forEach { require(it.replace('.', '/') + ".class" in entries) { "Provider class '$it' was not compiled." } }
        entries[MANIFEST] = canonical.encodeToString(AbilityExtensionManifest.serializer(), manifest).toByteArray(StandardCharsets.UTF_8)
        entries[SOURCES] = sourceBytes(frozen)
        entries[SERVICE] = (frozen.entryClasses.joinToString("\n") + "\n").toByteArray(StandardCharsets.UTF_8)
        require(entries.values.sumOf { it.size.toLong() } <= MAX_ARTIFACT_BYTES) { "Uncompressed extension artifact exceeds 8 MiB." }
        val buffer = ByteArrayOutputStream()
        JarOutputStream(buffer).use { jar -> entries.toSortedMap().forEach { (name, bytes) ->
            val entry = JarEntry(name).apply { time = 0L }
            jar.putNextEntry(entry); jar.write(bytes); jar.closeEntry()
        } }
        val bytes = buffer.toByteArray()
        require(bytes.size <= MAX_ARTIFACT_BYTES) { "Extension JAR exceeds 8 MiB." }
        return bytes to manifest
    }

    @JvmStatic fun inspect(path: Path): AbilityExtensionArtifact {
        require(Files.isRegularFile(path, NOFOLLOW_LINKS) && !Files.isSymbolicLink(path) && Files.size(path) in 1..MAX_ARTIFACT_BYTES.toLong()) { "Invalid extension artifact file or size." }
        val archive = Files.readAllBytes(path)
        val documents = mutableMapOf<String, ByteArray>()
        val names = mutableSetOf<String>()
        var total = 0L
        ZipFile(path.toFile()).use { zip ->
            val entries = zip.entries().asSequence().take(MAX_ENTRIES + 1).toList()
            require(entries.size in 4..MAX_ENTRIES) { "Invalid extension archive entry count." }
            entries.forEach { entry ->
                val name = entry.name
                require(!entry.isDirectory && names.add(name) && !name.contains('\\') && !name.startsWith('/') && name.split('/').none { it == ".." || it.isEmpty() }) { "Invalid or duplicate archive path." }
                require(name in setOf(MANIFEST, SOURCES, SERVICE) || name.endsWith(".class") && validClass(name.removeSuffix(".class").replace('/', '.'))) { "Unexpected extension artifact entry." }
                require(entry.size in 0..MAX_ARTIFACT_BYTES.toLong() && total + entry.size <= MAX_ARTIFACT_BYTES) { "Uncompressed extension artifact exceeds 8 MiB." }
                total += entry.size
                if (name in setOf(MANIFEST, SOURCES, SERVICE)) {
                    val limit = if (name == SOURCES) MAX_SOURCE_RECORD_BYTES else 65536
                    require(entry.size <= limit) { "Extension metadata exceeds its byte limit." }
                    documents[name] = zip.getInputStream(entry).use { it.readNBytes(limit + 1) }.also { require(it.size <= limit) { "Extension metadata exceeds its byte limit." } }
                } else {
                    var actual = 0L
                    zip.getInputStream(entry).use { input ->
                        val buffer = ByteArray(8192)
                        while (true) { val count = input.read(buffer); if (count < 0) break; actual += count; require(actual <= entry.size && actual <= MAX_ARTIFACT_BYTES) { "Compiled class exceeds its declared bounded size." } }
                    }
                    require(actual == entry.size) { "Truncated compiled class." }
                }
            }
        }
        val project = canonical.decodeFromString(AbilityExtensionProject.serializer(), requireNotNull(documents[SOURCES]) { "Missing source record." }.toString(StandardCharsets.UTF_8))
        validateProject(project, false)
        val manifest = canonical.decodeFromString(AbilityExtensionManifest.serializer(), requireNotNull(documents[MANIFEST]) { "Missing extension manifest." }.toString(StandardCharsets.UTF_8))
        require(manifest.apiVersion == 1 && manifest.javaTarget == 21 && manifest.validation == "static_abi_only") { "Unsupported extension ABI/validation metadata." }
        require(manifest.id == project.id && manifest.name == project.name && manifest.entryClasses == project.entryClasses && manifest.declaredSpecs == project.declaredSpecs) { "Manifest does not match its source declaration." }
        require(manifest.sourceHash == sourceHash(project)) { "Extension source hash mismatch." }
        val service = requireNotNull(documents[SERVICE]) { "Missing extension service declaration." }.toString(StandardCharsets.UTF_8).lineSequence().filter { it.isNotBlank() }.toList()
        require(service == manifest.entryClasses && service.all { it.replace('.', '/') + ".class" in names }) { "Service providers do not match the compiled declaration." }
        return AbilityExtensionArtifact(manifest, hash(archive), archive.size.toLong())
    }

    /** Only for an already verified artifact; still checks identity before returning its source record. */
    @JvmStatic fun readSource(path: Path): AbilityExtensionProject {
        inspect(path)
        return ZipFile(path.toFile()).use { zip ->
            val source = zip.getInputStream(zip.getEntry(SOURCES)).use { it.readNBytes(MAX_SOURCE_RECORD_BYTES + 1) }
            require(source.size <= MAX_SOURCE_RECORD_BYTES)
            freeze(canonical.decodeFromString(AbilityExtensionProject.serializer(), source.toString(StandardCharsets.UTF_8)))
        }
    }
}
