package app.fluff.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Update
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow

data class SessionWithSets(
    @androidx.room.Embedded val session: SessionEntity,
    @androidx.room.Relation(parentColumn = "id", entityColumn = "sessionId") val sets: List<SetEntity>,
)

@Dao
interface SessionDao {
    @Insert suspend fun insert(session: SessionEntity): Long
    @Update suspend fun update(session: SessionEntity)
    @Insert suspend fun insertSets(sets: List<SetEntity>): List<Long>
    @Update suspend fun updateSet(set: SetEntity)
    @Insert suspend fun insertSet(set: SetEntity): Long
    @Query("DELETE FROM sets WHERE id = :id") suspend fun deleteSet(id: Long)

    @Transaction
    @Query("SELECT * FROM sessions WHERE status = 'COMPLETED' ORDER BY startedAt ASC")
    fun observeCompleted(): Flow<List<SessionWithSets>>

    @Transaction
    @Query("SELECT * FROM sessions WHERE status = 'COMPLETED' ORDER BY startedAt ASC")
    suspend fun completed(): List<SessionWithSets>

    @Transaction
    @Query("SELECT * FROM sessions WHERE status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1")
    fun observeActive(): Flow<SessionWithSets?>

    @Transaction
    @Query("SELECT * FROM sessions WHERE status = 'ACTIVE' ORDER BY startedAt DESC LIMIT 1")
    suspend fun active(): SessionWithSets?

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :id")
    fun observe(id: Long): Flow<SessionWithSets?>

    @Transaction
    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun get(id: Long): SessionWithSets?

    @Transaction
    @Query("SELECT * FROM sessions WHERE status != 'DISCARDED' AND epochDay BETWEEN :from AND :to ORDER BY startedAt")
    suspend fun inRange(from: Long, to: Long): List<SessionWithSets>

    @Query("SELECT MIN(epochDay) FROM sessions") suspend fun firstDay(): Long?

    /** "count:sum of ids" of sessions before a day: changes when old sessions are imported or deleted. */
    @Query("SELECT COUNT(*) || ':' || COALESCE(SUM(id), 0) FROM sessions WHERE status != 'DISCARDED' AND epochDay < :beforeDay")
    suspend fun fingerprintBefore(beforeDay: Long): String

    @Query("DELETE FROM sessions WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT COUNT(*) FROM sessions WHERE epochDay = :day AND status = 'COMPLETED'") suspend fun countCompletedOn(day: Long): Int
    @Query("SELECT startedAt FROM sessions WHERE source = :source") suspend fun startTimesFrom(source: String): List<Long>
}

@Dao
interface SignalsDao {
    @Upsert suspend fun upsert(row: SignalsEntity)
    @Query("SELECT * FROM signals WHERE epochDay = :day") suspend fun get(day: Long): SignalsEntity?
    @Query("SELECT * FROM signals WHERE epochDay BETWEEN :from AND :to ORDER BY epochDay") suspend fun range(from: Long, to: Long): List<SignalsEntity>
    @Query("SELECT MIN(epochDay) FROM signals") suspend fun firstDay(): Long?
    @Query("SELECT * FROM signals WHERE epochDay BETWEEN :from AND :to ORDER BY epochDay") fun observeRange(from: Long, to: Long): Flow<List<SignalsEntity>>
}

@Dao
interface ActivityDao {
    @Upsert suspend fun upsertAll(rows: List<ActivityEntity>)
    @Query("SELECT * FROM activities WHERE startEpochMs >= :fromMs ORDER BY startEpochMs") suspend fun since(fromMs: Long): List<ActivityEntity>
    @Query("SELECT * FROM activities WHERE startEpochMs >= :fromMs ORDER BY startEpochMs DESC") fun observeSince(fromMs: Long): Flow<List<ActivityEntity>>
    @Query("DELETE FROM activities WHERE id = :id") suspend fun delete(id: String)
    @Query("SELECT MIN(startEpochMs) FROM activities") suspend fun firstStartMs(): Long?
}

@Dao
interface ReadinessDao {
    @Upsert suspend fun upsert(row: ReadinessEntity)
    @Query("SELECT * FROM readiness WHERE epochDay = :day") fun observe(day: Long): Flow<ReadinessEntity?>
    @Query("SELECT * FROM readiness WHERE epochDay = :day") suspend fun get(day: Long): ReadinessEntity?
    @Query("SELECT * FROM readiness WHERE epochDay BETWEEN :from AND :to ORDER BY epochDay") suspend fun range(from: Long, to: Long): List<ReadinessEntity>
    @Query("SELECT * FROM readiness WHERE epochDay BETWEEN :from AND :to ORDER BY epochDay") fun observeRange(from: Long, to: Long): Flow<List<ReadinessEntity>>
}

@Dao
interface CheckInDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun put(row: CheckInEntity)
    @Query("SELECT * FROM checkins WHERE epochDay = :day") suspend fun get(day: Long): CheckInEntity?
    @Query("SELECT * FROM checkins WHERE epochDay = :day") fun observe(day: Long): Flow<CheckInEntity?>
    @Query("SELECT * FROM checkins WHERE epochDay BETWEEN :from AND :to ORDER BY epochDay") suspend fun range(from: Long, to: Long): List<CheckInEntity>
}

@Dao
interface PlanDao {
    @Upsert suspend fun upsert(row: PlanEntity)
    @Query("SELECT * FROM plans WHERE epochDay = :day") fun observe(day: Long): Flow<PlanEntity?>
    @Query("SELECT * FROM plans WHERE epochDay = :day") suspend fun get(day: Long): PlanEntity?
    @Query("DELETE FROM plans WHERE epochDay < :beforeDay") suspend fun prune(beforeDay: Long)
    @Query("SELECT * FROM plans WHERE epochDay BETWEEN :from AND :to ORDER BY epochDay") suspend fun range(from: Long, to: Long): List<PlanEntity>
}

@Dao
interface GoalDao {
    @Insert suspend fun insert(goal: GoalEntity): Long
    @Update suspend fun update(goal: GoalEntity)
    @Query("DELETE FROM goals WHERE id = :id") suspend fun delete(id: Long)
    @Query("SELECT * FROM goals ORDER BY createdEpochDay DESC") fun observeAll(): Flow<List<GoalEntity>>
    @Query("SELECT * FROM goals") suspend fun all(): List<GoalEntity>
}
