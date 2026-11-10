package app.fluff.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        SessionEntity::class, SetEntity::class, SignalsEntity::class, ActivityEntity::class,
        ReadinessEntity::class, CheckInEntity::class, PlanEntity::class, GoalEntity::class,
    ],
    version = 1,
    exportSchema = true,
)
abstract class FluffDatabase : RoomDatabase() {
    abstract fun sessions(): SessionDao
    abstract fun signals(): SignalsDao
    abstract fun activities(): ActivityDao
    abstract fun readiness(): ReadinessDao
    abstract fun checkIns(): CheckInDao
    abstract fun plans(): PlanDao
    abstract fun goals(): GoalDao

    companion object {
        fun create(context: Context): FluffDatabase =
            Room.databaseBuilder(context, FluffDatabase::class.java, "fluff.db").build()
    }
}
