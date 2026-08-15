package me.sd_master92.customvoting

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import me.sd_master92.core.file.PlayerFile
import me.sd_master92.core.infoLog
import me.sd_master92.customvoting.constants.enumerations.Data
import me.sd_master92.customvoting.constants.enumerations.Setting
import me.sd_master92.customvoting.constants.interfaces.TopVoter
import me.sd_master92.customvoting.constants.interfaces.Voter
import me.sd_master92.customvoting.constants.models.VoteHistory
import me.sd_master92.customvoting.constants.models.VoteSiteUUID
import org.bukkit.configuration.file.YamlConfiguration
import org.bukkit.entity.Player
import java.io.File
import java.util.*
import java.util.concurrent.ConcurrentHashMap

class VoteFile : Voter
{
    private val plugin: CV
    private val playerFile: PlayerFile

    private constructor(uuid: UUID, plugin: CV, name: String? = null)
    {
        playerFile = PlayerFile.getByUuid(plugin, uuid, name)
        this.plugin = plugin
        runBlocking {
            register()
        }
    }

    private constructor(player: Player, plugin: CV)
    {
        playerFile = player.getPlayerFile(plugin)
        this.plugin = plugin
        runBlocking {
            register()
        }
    }

    private suspend fun register()
    {
        migrate()
        if (getVotes() == 0)
        {
            setVotes(0, false)
        }
        syncSnapshot()
    }

    /**
     * Previously applied to every player file eagerly at startup; now applied to each
     * file the first time it is actually loaded.
     */
    private fun migrate()
    {
        playerFile.keyMigrations(KEY_MIGRATIONS)
        if (playerFile.contains("last"))
        {
            playerFile.delete("last")
        }
    }

    /**
     * Writes this voter's current values into the lightweight snapshot registry that
     * backs [getAll] and the top-voter list.
     */
    internal suspend fun syncSnapshot()
    {
        SNAPSHOTS[getUuid()] = VoterSnapshot(
            plugin,
            getUuid(),
            getName(),
            getVotes(),
            getVotesMonthly(),
            getVotesWeekly(),
            getVotesDaily(),
            getStreakDaily(),
            getPower(),
            getLast()
        )
    }

    override suspend fun getUuid(): UUID
    {
        return playerFile.uuid
    }

    override suspend fun getName(): String
    {
        return playerFile.name
    }

    override suspend fun setName(name: String): Boolean
    {
        val result = playerFile.setName(name)
        syncSnapshot()
        return result
    }

    override suspend fun setNameIfChanged(name: String): Boolean
    {
        val originalName = getName()
        if (originalName != name)
        {
            plugin.infoLog("changing name of ${getUuid()} from $originalName to $name")
            return setName(name)
        }
        return true
    }

    override suspend fun getVotes(): Int
    {
        return playerFile.getNumber(VOTES)
    }

    override suspend fun getVotesMonthly(): Int
    {
        return playerFile.getNumber(VOTES_MONTHLY)
    }

    override suspend fun getVotesWeekly(): Int
    {
        return playerFile.getNumber(VOTES_WEEKLY)
    }

    override suspend fun getVotesDaily(): Int
    {
        return playerFile.getNumber(VOTES_DAILY)
    }

    override suspend fun getLast(): Long
    {
        return getHistory().maxByOrNull { it.time }?.time ?: 0
    }

    override suspend fun getPower(): Boolean
    {
        return playerFile.getBoolean(POWER)
    }

    override suspend fun getHistory(): List<VoteHistory>
    {
        val history = mutableListOf<VoteHistory>()
        for (key in playerFile.getConfigurationSection(Data.VOTE_HISTORY.path)?.getKeys(false) ?: listOf())
        {
            val site = playerFile.getString(Data.VOTE_HISTORY.path + ".$key.site")
            val time = playerFile.getTimeStamp(Data.VOTE_HISTORY.path + ".$key.timestamp")
            val queued = playerFile.getBoolean(Data.VOTE_HISTORY.path + ".$key.queued")
            if (site != null)
            {
                history.add(VoteHistory(key.toInt(), getUuid(), VoteSiteUUID(site), time, queued))
            }
        }
        return history
    }

    override suspend fun getStreakDaily(): Int
    {
        return playerFile.getNumber(STREAK_DAILY)
    }

    override suspend fun setStreakDaily(n: Int): Boolean
    {
        playerFile.set(STREAK_DAILY, n)
        val result = playerFile.saveConfig()
        syncSnapshot()
        return result
    }

    suspend fun delete(): Boolean
    {
        if (playerFile.delete())
        {
            ALL.remove(getUuid())
            SNAPSHOTS.remove(getUuid())
            Voter.getTopVoters(plugin, true)
            return true
        }
        return false
    }

    override suspend fun setVotes(n: Int, update: Boolean)
    {
        playerFile.setNumber(VOTES, n)
        playerFile.setNumber(VOTES_MONTHLY, 0)
        playerFile.setNumber(VOTES_WEEKLY, 0)
        playerFile.setNumber(VOTES_DAILY, 0)

        if (n == 0)
        {
            clearStreak()
        }

        syncSnapshot()
        if (update)
        {
            Voter.getTopVoters(plugin, true)
        }
    }

    override suspend fun clearMonthlyVotes()
    {
        playerFile.setNumber(VOTES_MONTHLY, 0)
        syncSnapshot()
    }

    override suspend fun clearWeeklyVotes()
    {
        playerFile.setNumber(VOTES_WEEKLY, 0)
        syncSnapshot()
    }

    override suspend fun clearDailyVotes()
    {
        playerFile.setNumber(VOTES_DAILY, 0)
        syncSnapshot()
    }

    override suspend fun addVote(site: VoteSiteUUID, queued: Boolean): Boolean
    {
        val beforeVotes = getVotes()
        addStreak()
        addHistory(site, queued)
        playerFile.addNumber(VOTES)
        playerFile.addNumber(VOTES_MONTHLY)
        playerFile.addNumber(VOTES_WEEKLY)
        playerFile.addNumber(VOTES_DAILY)

        syncSnapshot()
        Voter.getTopVoters(plugin, true)

        return beforeVotes < getVotes()
    }

    override suspend fun setPower(power: Boolean): Boolean
    {
        playerFile.set(POWER, power)
        val result = playerFile.saveConfig()
        syncSnapshot()
        return result
    }

    override suspend fun addHistory(site: VoteSiteUUID, queued: Boolean): Boolean
    {
        val key = getHistory().size
        playerFile.set(Data.VOTE_HISTORY.path + ".$key.site", site.toString())
        playerFile.setTimeStamp(Data.VOTE_HISTORY.path + ".$key.timestamp")
        playerFile.set(Data.VOTE_HISTORY.path + ".$key.queued", queued)
        val result = playerFile.saveConfig()
        syncSnapshot()
        return result
    }

    override suspend fun clearQueue(): Boolean
    {
        for (vote in getHistory().filter { it.queued })
        {
            playerFile.set(Data.VOTE_HISTORY.path + ".${vote.id}.queued", false)
        }
        return playerFile.saveConfig()
    }

    override suspend fun addStreak(): Boolean
    {
        val diff = getLast().dayDifference()
        if (!plugin.config.getBoolean(Setting.VOTE_STREAK_CONSECUTIVE.path) || diff == 1 || getVotes() == 0)
        {
            val result = playerFile.addNumber(STREAK_DAILY, 1)
            syncSnapshot()
            return result
        } else if (diff > 1)
        {
            clearStreak()
        }
        return false
    }

    override suspend fun clearStreak(): Boolean
    {
        val result = playerFile.setNumber(STREAK_DAILY, 0)
        syncSnapshot()
        return result
    }

    companion object : TopVoter
    {
        private const val VOTES = "votes"
        private const val VOTES_MONTHLY = "votes_monthly"
        private const val VOTES_WEEKLY = "votes_weekly"
        private const val VOTES_DAILY = "votes_daily"
        private const val STREAK_DAILY = "streak_daily"
        private const val POWER = "power"

        private var ALL: MutableMap<UUID, VoteFile> = ConcurrentHashMap()
        private var SNAPSHOTS: MutableMap<UUID, VoterSnapshot> = ConcurrentHashMap()
        private lateinit var pluginRef: CV

        private val KEY_MIGRATIONS = mapOf(
            Pair("period", "monthly_votes"),
            Pair("monthly_votes", VOTES_MONTHLY),
            Pair("opUser", POWER)
        )

        suspend fun init(plugin: CV)
        {
            pluginRef = plugin
            ALL = ConcurrentHashMap()
            // Build lightweight snapshots by reading each player file once and letting go of
            // the parsed YAML immediately. Full VoteFile/PlayerFile instances (which stay in
            // memory) are only created on demand for players that are actually interacted with.
            SNAPSHOTS = withContext(Dispatchers.IO) {
                val snapshots = ConcurrentHashMap<UUID, VoterSnapshot>()
                val files = File(plugin.dataFolder, "players").listFiles { file -> file.name.endsWith(".yml") }
                for (file in files ?: emptyArray())
                {
                    val uuid = try
                    {
                        UUID.fromString(file.name.removeSuffix(".yml"))
                    } catch (e: IllegalArgumentException)
                    {
                        continue
                    }
                    val config = YamlConfiguration.loadConfiguration(file)
                    var last = 0L
                    val history = config.getConfigurationSection(Data.VOTE_HISTORY.path)
                    if (history != null)
                    {
                        for (key in history.getKeys(false))
                        {
                            last = maxOf(last, history.getLong("$key.timestamp"))
                        }
                    }
                    // pre-migration keys are read here directly; the real migration happens
                    // lazily when the file is first loaded as a VoteFile
                    val monthly = when
                    {
                        config.contains(VOTES_MONTHLY) -> config.getInt(VOTES_MONTHLY)
                        config.contains("monthly_votes") -> config.getInt("monthly_votes")
                        else -> config.getInt("period")
                    }
                    snapshots[uuid] = VoterSnapshot(
                        plugin,
                        uuid,
                        config.getString("name") ?: "unknown",
                        config.getInt(VOTES),
                        monthly,
                        config.getInt(VOTES_WEEKLY),
                        config.getInt(VOTES_DAILY),
                        config.getInt(STREAK_DAILY),
                        config.getBoolean(POWER) || config.getBoolean("opUser") || config.getBoolean("opuser"),
                        last
                    )
                }
                snapshots
            }
        }

        suspend fun mergeDuplicates(): Int
        {
            if (!::pluginRef.isInitialized)
            {
                return 0
            }
            val voters = getAll()
            var deleted = 0
            for (voter in voters.filter { it ->
                it.getUuid() !in voters.distinctBy { it.getName() }.map { it.getUuid() }
            })
            {
                if (getByUuid(pluginRef, voter.getUuid()).delete())
                {
                    deleted++
                }
            }
            return deleted
        }

        override fun getAll(): MutableList<Voter>
        {
            return SNAPSHOTS.values.toMutableList()
        }

        /**
         * Drops the cached VoteFile (and its underlying PlayerFile) for a player, e.g. when
         * they leave the server. The lightweight snapshot stays.
         */
        fun unload(uuid: UUID)
        {
            ALL.remove(uuid)
            PlayerFileCache.evict(uuid)
        }

        suspend fun get(plugin: CV, player: Player): VoteFile
        {
            return if (plugin.config.getBoolean(Setting.UUID_STORAGE.path)) getByUuid(plugin, player) else getByName(
                plugin,
                player
            )
        }

        private fun getByUuid(plugin: CV, player: Player): VoteFile
        {
            val voteFile = ALL.getOrElse(player.uniqueId) {
                val voter = VoteFile(player, plugin)
                ALL[player.uniqueId] = voter
                return voter
            }
            runBlocking {
                voteFile.setNameIfChanged(player.name)
            }
            return voteFile
        }

        fun getByUuid(plugin: CV, uniqueId: UUID, name: String? = null): VoteFile
        {
            return ALL.getOrElse(uniqueId) {
                val voter = VoteFile(uniqueId, plugin, name)
                ALL[uniqueId] = voter
                return voter
            }
        }

        private suspend fun getByName(plugin: CV, player: Player): VoteFile
        {
            val ignoreCase = plugin.config.getBoolean(Setting.IGNORE_PLAYERNAME_CASING.path)
            val loaded = ALL.values.firstOrNull { file -> file.getName().equals(player.name, ignoreCase) }
            if (loaded != null)
            {
                return loaded
            }
            // resolve the uuid of an existing file through the snapshot registry instead of
            // requiring every file to be loaded
            val uuid = SNAPSHOTS.entries.firstOrNull { entry -> entry.value.name.equals(player.name, ignoreCase) }?.key
            if (uuid != null)
            {
                return getByUuid(plugin, uuid, player.name)
            }
            val voter = VoteFile(player, plugin)
            ALL[player.uniqueId] = voter
            return voter
        }

    }
}