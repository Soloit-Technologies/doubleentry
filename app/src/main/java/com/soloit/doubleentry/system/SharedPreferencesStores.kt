package com.soloit.doubleentry.system

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.soloit.doubleentry.platform.KeyValueStore
import com.soloit.doubleentry.platform.KeyValueStores

/** Each store is the SharedPreferences file of the same name. */
class SharedPreferencesStores(private val context: Context) : KeyValueStores {
    override fun open(name: String): KeyValueStore = Store(context.getSharedPreferences(name, Context.MODE_PRIVATE))

    private class Store(private val prefs: SharedPreferences) : KeyValueStore {
        override fun getString(key: String, default: String?) = prefs.getString(key, default)
        override fun getBoolean(key: String, default: Boolean) = prefs.getBoolean(key, default)
        override fun getInt(key: String, default: Int) = prefs.getInt(key, default)
        override fun getLong(key: String, default: Long) = prefs.getLong(key, default)

        override fun edit(block: KeyValueStore.Editor.() -> Unit) = prefs.edit {
            val editor = this
            object : KeyValueStore.Editor {
                override fun putString(key: String, value: String?) { editor.putString(key, value) }
                override fun putBoolean(key: String, value: Boolean) { editor.putBoolean(key, value) }
                override fun putInt(key: String, value: Int) { editor.putInt(key, value) }
                override fun putLong(key: String, value: Long) { editor.putLong(key, value) }
                override fun remove(key: String) { editor.remove(key) }
                override fun clear() { editor.clear() }
            }.block()
        }
    }
}
