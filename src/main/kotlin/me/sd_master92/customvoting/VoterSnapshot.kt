package me.sd_master92.customvoting

import me.sd_master92.customvoting.constants.interfaces.Voter
import me.sd_master92.customvoting.constants.models.VoteHistory
import me.sd_master92.customvoting.constants.models.VoteSiteUUID
import java.util.*

/**
 * A lightweight, in-memory view of a voter backed by a player file on disk.
 *
 * The top-voter list used to be built from [VoteFile] instances, which keep every
 * player's parsed YAML file in memory forever (1.4 GB for ~45k players on a large
 * server). Reads on a snapshot are served from plain fields; writes load the real
 * [VoteFile] on demand, whose mutators write the updated values back into this
 * registry via [VoteFile.syncSnapshot].
 */
class VoterSnapshot(
    private val plugin: CV,
    private val uuid: UUID,
    @Volatile var name: String,
    @Volatile var votes: Int,
    @Volatile var votesMonthly: Int,
    @Volatile var votesWeekly: Int,
    @Volatile var votesDaily: Int,
    @Volatile var streakDaily: Int,
    @Volatile var power: Boolean,
    @Volatile var last: Long,
) : Voter
{
    private suspend fun file(): VoteFile
    {
        return VoteFile.getByUuid(plugin, uuid, name)
    }

    override suspend fun getUuid(): UUID
    {
        return uuid
    }

    override suspend fun getName(): String
    {
        return name
    }

    override suspend fun setName(name: String): Boolean
    {
        return file().setName(name)
    }

    override suspend fun setNameIfChanged(name: String): Boolean
    {
        return file().setNameIfChanged(name)
    }

    override suspend fun getVotes(): Int
    {
        return votes
    }

    override suspend fun getVotesMonthly(): Int
    {
        return votesMonthly
    }

    override suspend fun getVotesWeekly(): Int
    {
        return votesWeekly
    }

    override suspend fun getVotesDaily(): Int
    {
        return votesDaily
    }

    override suspend fun setVotes(n: Int, update: Boolean)
    {
        file().setVotes(n, update)
    }

    override suspend fun addVote(site: VoteSiteUUID, queued: Boolean): Boolean
    {
        return file().addVote(site, queued)
    }

    override suspend fun getLast(): Long
    {
        return last
    }

    override suspend fun getHistory(): List<VoteHistory>
    {
        return file().getHistory()
    }

    override suspend fun addHistory(site: VoteSiteUUID, queued: Boolean): Boolean
    {
        return file().addHistory(site, queued)
    }

    override suspend fun clearMonthlyVotes()
    {
        if (votesMonthly == 0)
        {
            return
        }
        file().clearMonthlyVotes()
    }

    override suspend fun clearWeeklyVotes()
    {
        if (votesWeekly == 0)
        {
            return
        }
        file().clearWeeklyVotes()
    }

    override suspend fun clearDailyVotes()
    {
        if (votesDaily == 0)
        {
            return
        }
        file().clearDailyVotes()
    }

    override suspend fun clearQueue(): Boolean
    {
        return file().clearQueue()
    }

    override suspend fun getPower(): Boolean
    {
        return power
    }

    override suspend fun setPower(power: Boolean): Boolean
    {
        return file().setPower(power)
    }

    override suspend fun getStreakDaily(): Int
    {
        return streakDaily
    }

    override suspend fun setStreakDaily(n: Int): Boolean
    {
        return file().setStreakDaily(n)
    }

    override suspend fun addStreak(): Boolean
    {
        return file().addStreak()
    }

    override suspend fun clearStreak(): Boolean
    {
        return file().clearStreak()
    }
}
