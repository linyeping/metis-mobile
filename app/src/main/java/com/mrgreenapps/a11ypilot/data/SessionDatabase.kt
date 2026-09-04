package com.mrgreenapps.a11ypilot.data

import android.content.Context
import androidx.room.*
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.mrgreenapps.a11ypilot.EventLog

/**
 * DEAD CODE — DO NOT USE.
 *
 * Persistence is implemented entirely in [SessionRepository] via DataStore JSON blobs. This Room
 * schema is never instantiated anywhere and exists only as a historical artifact. The ksp/Room
 * compiler keeps processing it, so it remains as a build-time no-op. Any future migration to Room
 * should replace [SessionRepository]'s DataStore storage wholesale rather than revive this class.
 */
@Database(
    entities = [SessionEntity::class, MessageEntity::class],
    version = 2,
    exportSchema = false
)
abstract class SessionDatabase : RoomDatabase() {
    abstract fun sessionDao(): SessionDao
    abstract fun messageDao(): MessageDao

    companion object {
        @Volatile
        private var INSTANCE: SessionDatabase? = null

        fun getDatabase(context: Context): SessionDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    SessionDatabase::class.java,
                    "a11ypilot_sessions.db"
                )
                    .addMigrations(MIGRATION_1_2)
                    // Downgrades may discard only this cache; upgrades preserve the transcript.
                    .fallbackToDestructiveMigrationOnDowngrade()
                    .build()
                INSTANCE = instance
                instance
            }
        }

        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                addColumnIfMissing(database, "sessions", "reasoningIntensity", "TEXT NOT NULL DEFAULT 'MEDIUM'")
                addColumnIfMissing(database, "sessions", "isActive", "INTEGER NOT NULL DEFAULT 0")
                addColumnIfMissing(database, "sessions", "safetyPolicy", "TEXT NOT NULL DEFAULT 'MODERATE'")
                addColumnIfMissing(database, "messages", "metadata", "TEXT")
                addColumnIfMissing(database, "messages", "thinkingState", "TEXT")
                EventLog.append("database> migrated session schema 1->2")
            }
        }

        private fun addColumnIfMissing(
            database: SupportSQLiteDatabase,
            table: String,
            column: String,
            definition: String
        ) {
            val cursor = database.query("PRAGMA table_info($table)")
            var present = false
            cursor.use {
                val nameIndex = it.getColumnIndex("name")
                while (it.moveToNext()) {
                    if (nameIndex >= 0 && it.getString(nameIndex).equals(column, ignoreCase = true)) {
                        present = true
                        break
                    }
                }
            }
            if (!present) database.execSQL("ALTER TABLE $table ADD COLUMN $column $definition")
        }
    }
}

@Entity(tableName = "sessions")
data class SessionEntity(
    @PrimaryKey val id: String,
    val mode: String,
    val model: String,
    val reasoningIntensity: String,
    val title: String,
    val createdAt: Long,
    val updatedAt: Long,
    val isActive: Boolean = false,
    val safetyPolicy: String = "MODERATE"
)

@Entity(
    tableName = "messages",
    foreignKeys = [
        ForeignKey(
            entity = SessionEntity::class,
            parentColumns = ["id"],
            childColumns = ["sessionId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("sessionId")]
)
data class MessageEntity(
    @PrimaryKey val id: String,
    val sessionId: String,
    val role: String,
    val content: String,
    val timestamp: Long,
    val status: String,
    val metadata: String? = null,
    val thinkingState: String? = null
)

@Dao
interface SessionDao {
    @Query("SELECT * FROM sessions WHERE mode = :mode ORDER BY updatedAt DESC")
    fun observeSessionsByMode(mode: String): kotlinx.coroutines.flow.Flow<List<SessionEntity>>

    @Query("SELECT * FROM sessions WHERE isActive = 1 LIMIT 1")
    fun observeActiveSession(): kotlinx.coroutines.flow.Flow<SessionEntity?>

    @Query("SELECT * FROM sessions WHERE id = :id")
    suspend fun getSessionById(id: String): SessionEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSession(session: SessionEntity)

    @Update
    suspend fun updateSession(session: SessionEntity)

    @Query("DELETE FROM sessions WHERE id = :id")
    suspend fun deleteSession(id: String)

    @Query("UPDATE sessions SET isActive = 0")
    suspend fun clearActiveFlag()

    @Query("UPDATE sessions SET isActive = 1 WHERE id = :id")
    suspend fun setActive(id: String)
}

@Dao
interface MessageDao {
    @Query("SELECT * FROM messages WHERE sessionId = :sessionId ORDER BY timestamp ASC")
    suspend fun getMessagesBySession(sessionId: String): List<MessageEntity>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertMessage(message: MessageEntity)

    @Update
    suspend fun updateMessage(message: MessageEntity)

    @Query("DELETE FROM messages WHERE sessionId = :sessionId")
    suspend fun deleteMessagesBySession(sessionId: String)
}
