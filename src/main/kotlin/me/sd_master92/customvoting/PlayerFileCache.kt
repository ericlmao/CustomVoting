package me.sd_master92.customvoting

import me.sd_master92.core.file.PlayerFile
import java.util.*

/**
 * PluginCore's [PlayerFile] registers every constructed instance in a private static map
 * with no eviction API, so every player file ever touched stays in memory for the lifetime
 * of the server. Reflection is the only way to release entries from this side.
 *
 * If the field lookup fails (e.g. a future PluginCore version renames it), eviction is
 * silently skipped - the cache then only grows with players actually touched since the
 * last restart, which is still bounded compared to loading all files eagerly.
 */
object PlayerFileCache
{
    private val cache: MutableMap<UUID, PlayerFile>? by lazy {
        try
        {
            val field = PlayerFile::class.java.getDeclaredField("ALL")
            field.isAccessible = true
            @Suppress("UNCHECKED_CAST")
            field.get(null) as? MutableMap<UUID, PlayerFile>
        } catch (e: Exception)
        {
            null
        }
    }

    fun evict(uuid: UUID)
    {
        cache?.remove(uuid)
    }
}
