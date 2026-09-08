package com.wjz.worldsmith.core.drawhost

import com.wjz.worldsmith.core.draw.DrawSnapshotCodec
import java.nio.file.*
import java.nio.file.attribute.FileTime
import java.util.Properties

/** Only compiled classes are cached; generated geometry is never assumed to be a pure function. */
class DrawingCompileCache(private val root:Path,private val runtime:DrawingRuntime,private val maxEntries:Int=256,private val maxBytes:Long=512L*1024*1024) {
    private val fingerprint by lazy {
        listOf("draw-sdk.jar","authoring-sdk.jar","ecj.jar","draw-worker.jar").joinToString("\n") {name->name+":"+DrawSnapshotCodec.hash(Files.readAllBytes(runtime.directory.resolve(name)))}+
            "\n${runtime.javaHome.toAbsolutePath()}\n"+Files.readString(runtime.javaHome.resolve("release"))+"\n"+runtime.jvmArguments.joinToString(" ")
    }
    fun key(r:DrawingRequest):String=DrawSnapshotCodec.hash(buildString {append(fingerprint);append("\n-source21-target21-proc:none\n");append(r.entryClass)
        r.sources.toSortedMap().forEach {(name,source)->append("\n$name:${DrawSnapshotCodec.hash(source.toByteArray(Charsets.UTF_8))}")}
    }.toByteArray(Charsets.UTF_8))
    @Synchronized fun restore(key:String,directory:Path):Boolean {
        val entry=root.resolve(key);if(!Files.isRegularFile(entry.resolve("manifest.properties")))return false
        return runCatching {
            val props=Properties();Files.newBufferedReader(entry.resolve("manifest.properties")).use(props::load)
            require(props.size in 1..8192)
            for(name in props.stringPropertyNames()){val file=relative(entry,name);require(Files.isRegularFile(file)&&!Files.isSymbolicLink(file));require(DrawSnapshotCodec.hash(Files.readAllBytes(file))==props.getProperty(name))}
            for(name in props.stringPropertyNames()){val target=relative(directory,name);Files.createDirectories(target.parent);Files.copy(relative(entry,name),target,StandardCopyOption.REPLACE_EXISTING)}
            Files.setLastModifiedTime(entry,FileTime.fromMillis(System.currentTimeMillis()));true
        }.getOrElse {delete(entry);false}
    }
    @Synchronized fun store(key:String,classes:Path) {
        if(!Files.isDirectory(classes))return
        Files.createDirectories(root);val dest=root.resolve(key)
        if(Files.exists(dest))return
        val pending=Files.createTempDirectory(root,".pending-")
        try {
            val entries=Files.walk(classes).use {it.filter {p->Files.isRegularFile(p)&&p.toString().endsWith(".class")}.toList()}
            if(entries.isEmpty()||entries.size>8192||entries.sumOf(Files::size)>64L*1024*1024)return
            val props=Properties()
            for(p in entries){require(!Files.isSymbolicLink(p));val name=classes.relativize(p).toString().replace('\\','/');val target=relative(pending,name);Files.createDirectories(target.parent);Files.copy(p,target);props.setProperty(name,DrawSnapshotCodec.hash(Files.readAllBytes(target)))}
            Files.newBufferedWriter(pending.resolve("manifest.properties")).use {props.store(it,"Compiled drawing cache")}
            try{Files.move(pending,dest,StandardCopyOption.ATOMIC_MOVE)}catch(_:AtomicMoveNotSupportedException){Files.move(pending,dest)}
            trim()
        } finally {delete(pending)}
    }
    private fun relative(root:Path,name:String):Path {require(name.matches(Regex("([A-Za-z0-9_$-]+/)*[A-Za-z0-9_$-]+\\.class")));return root.resolve(name)}
    private fun trim(){
        val entries=Files.list(root).use {it.filter {p->p.fileName.toString().matches(Regex("[a-f0-9]{64}"))}.sorted(compareBy {p->Files.getLastModifiedTime(p).toMillis()}).toList()}
        val sizes=entries.associateWith {p->Files.walk(p).use {it.filter(Files::isRegularFile).mapToLong(Files::size).sum()}}
        var bytes=sizes.values.sum();var count=entries.size
        for(p in entries){if(count<=maxEntries&&bytes<=maxBytes)break;delete(p);bytes-=sizes.getValue(p);count--}
    }
    private fun delete(path:Path){if(!Files.exists(path))return;require(path.toAbsolutePath().normalize().startsWith(root.toAbsolutePath().normalize()));Files.walk(path).use {it.sorted(Comparator.reverseOrder()).forEach(Files::deleteIfExists)}}
}
