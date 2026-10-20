package com.soloit.doubleentry.platform

/**
 * Small device-local settings, one named store per concern (Android: SharedPreferences; desktop:
 * a file per store). Edits made in [edit] are applied together.
 */
interface KeyValueStore {
    fun getString(key: String, default: String? = null): String?
    fun getBoolean(key: String, default: Boolean): Boolean
    fun getInt(key: String, default: Int): Int
    fun getLong(key: String, default: Long): Long
    fun edit(block: Editor.() -> Unit)

    interface Editor {
        fun putString(key: String, value: String?)
        fun putBoolean(key: String, value: Boolean)
        fun putInt(key: String, value: Int)
        fun putLong(key: String, value: Long)
        fun remove(key: String)
        fun clear()
    }
}

/** Opens the store called [name]; the same name always reaches the same values. */
fun interface KeyValueStores {
    fun open(name: String): KeyValueStore
}

/** A [KeyValueStore] kept in memory: for tests, and the base of file-backed stores. */
open class MemoryKeyValueStore(initial: Map<String, Any> = emptyMap()) : KeyValueStore {
    protected val values = initial.toMutableMap()
    private val lock = Any()

    override fun getString(key: String, default: String?) = synchronized(lock) { values[key] as? String ?: default }
    override fun getBoolean(key: String, default: Boolean) = synchronized(lock) { values[key] as? Boolean ?: default }
    override fun getInt(key: String, default: Int) = synchronized(lock) { values[key] as? Int ?: default }
    override fun getLong(key: String, default: Long) = synchronized(lock) { values[key] as? Long ?: default }

    override fun edit(block: KeyValueStore.Editor.() -> Unit) {
        synchronized(lock) {
            val editor = object : KeyValueStore.Editor {
                override fun putString(key: String, value: String?) {
                    if (value == null) values.remove(key) else values[key] = value
                }
                override fun putBoolean(key: String, value: Boolean) { values[key] = value }
                override fun putInt(key: String, value: Int) { values[key] = value }
                override fun putLong(key: String, value: Long) { values[key] = value }
                override fun remove(key: String) { values.remove(key) }
                override fun clear() = values.clear()
            }
            editor.block()
            onChanged(values.toMap())
        }
    }

    /** Called after every [edit] with the new contents. */
    protected open fun onChanged(contents: Map<String, Any>) {}
}

/** In-memory [KeyValueStores]: each name gets its own store for as long as this object lives. */
class MemoryKeyValueStores : KeyValueStores {
    private val stores = mutableMapOf<String, KeyValueStore>()
    override fun open(name: String): KeyValueStore = synchronized(stores) { stores.getOrPut(name) { MemoryKeyValueStore() } }
}
