package com.soloit.doubleentry.desktop.platform

import com.soloit.doubleentry.platform.KeyValueStore
import com.soloit.doubleentry.platform.KeyValueStores
import com.soloit.doubleentry.platform.MemoryKeyValueStore
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

/** Each store is a properties file in [directory], rewritten whole (atomically) on every edit. */
class FileKeyValueStores(private val directory: File) : KeyValueStores {
    private val stores = mutableMapOf<String, KeyValueStore>()

    override fun open(name: String): KeyValueStore = synchronized(stores) {
        stores.getOrPut(name) { FileStore(File(directory, "$name.properties")) }
    }

    private class FileStore(private val file: File) : MemoryKeyValueStore(read(file)) {
        override fun onChanged(contents: Map<String, Any>) {
            val properties = Properties()
            contents.forEach { (key, value) ->
                val typed = when (value) {
                    is Boolean -> "b:$value"
                    is Int -> "i:$value"
                    is Long -> "l:$value"
                    else -> "s:$value"
                }
                properties.setProperty(key, typed)
            }
            file.parentFile.mkdirs()
            val temp = File(file.parentFile, "${file.name}.tmp")
            temp.outputStream().use { properties.store(it, null) }
            Files.move(temp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }

        companion object {
            fun read(file: File): Map<String, Any> {
                if (!file.exists()) return emptyMap()
                val properties = Properties().apply { runCatching { file.inputStream().use { load(it) } } }
                return properties.stringPropertyNames().mapNotNull { key ->
                    val raw = properties.getProperty(key)
                    val value: Any? = when (raw.take(2)) {
                        "b:" -> raw.drop(2).toBooleanStrictOrNull()
                        "i:" -> raw.drop(2).toIntOrNull()
                        "l:" -> raw.drop(2).toLongOrNull()
                        "s:" -> raw.drop(2)
                        else -> null
                    }
                    value?.let { key to it }
                }.toMap()
            }
        }
    }
}
