package com.wjz.worldsmith.core.drawhost

import java.nio.file.*

/** Durable file replacement is the commit point, not the earlier in-memory update. */
object DurableFiles {
    @JvmStatic fun write(path:Path,bytes:ByteArray) {
        Files.createDirectories(path.parent)
        val temp=Files.createTempFile(path.parent,".pending-",".tmp")
        try {
            Files.newByteChannel(temp,StandardOpenOption.WRITE,StandardOpenOption.SYNC).use { channel->
                val buffer=java.nio.ByteBuffer.wrap(bytes);while(buffer.hasRemaining())channel.write(buffer)
            }
            var attempt=0
            while(true)try {
                try {Files.move(temp,path,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING)}
                catch(_:AtomicMoveNotSupportedException){Files.move(temp,path,StandardCopyOption.REPLACE_EXISTING)}
                break
            } catch(e:FileSystemException) {
                if(++attempt>=3 || e is NoSuchFileException || e is FileAlreadyExistsException)throw PersistenceException(path,e)
                Thread.sleep(50L*attempt)
            }
        } finally {Files.deleteIfExists(temp)}
    }
}
class PersistenceException(path:Path,cause:Throwable):java.io.IOException("PERSISTENCE_ERROR: could not commit $path: ${cause.message}",cause)
