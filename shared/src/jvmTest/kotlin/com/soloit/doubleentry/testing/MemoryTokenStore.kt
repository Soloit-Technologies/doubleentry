package com.soloit.doubleentry.testing

import com.soloit.doubleentry.sync.TokenStore

/** Session tokens kept in memory, unencrypted. */
class MemoryTokenStore : TokenStore {
    private val values = mutableMapOf<String, String>()
    override fun get(name: String) = values[name]
    override fun put(name: String, value: String?) {
        if (value == null) values.remove(name) else values[name] = value
    }
    override fun clear() = values.clear()
}
