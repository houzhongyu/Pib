package com.houxinzhe.pib.data

import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Crash-safe file writer: writes to sibling `.tmp`, fsyncs file descriptor, then atomically renames. */
object AtomicFile {
    fun writeText(file: File, content: String) {
        val parent = file.parentFile
        if (parent != null && !parent.exists()) parent.mkdirs()
        val tmp = File(parent, "${file.name}.${System.nanoTime()}.tmp")
        val bytes = content.toByteArray(Charsets.UTF_8)
        FileOutputStream(tmp).use { fos ->
            fos.write(bytes)
            fos.flush()
            runCatching { fos.fd.sync() }
        }
        val moved = runCatching {
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
            true
        }.getOrElse {
            runCatching {
                Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
                true
            }.getOrElse { tmp.renameTo(file) }
        }
        if (!moved) tmp.delete()
    }
}
