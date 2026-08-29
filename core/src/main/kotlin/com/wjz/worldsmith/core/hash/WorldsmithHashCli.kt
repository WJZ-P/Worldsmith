package com.wjz.worldsmith.core.hash

import com.wjz.worldsmith.core.pack.WorldsmithPackLoader
import java.nio.file.Path

object WorldsmithHashCli {
    @JvmStatic
    fun main(args: Array<String>) {
        require(args.size == 1) { "Usage: WorldsmithHashCli <pack-directory>" }
        println(WorldsmithPackLoader.loadDirectory(Path.of(args.single())).computedId)
    }
}
