package com.example.nirduino_android_app_v2.participant_manager.data.local

import android.content.Context
import androidx.room.*
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKeys
import java.util.*

// ─────────────────────────  Room Entity  ──────────────────────────────
@Entity(tableName = "participants")
data class Participant(
    @PrimaryKey val subjectId: String,                  //  ← unique key
    val age: Int?,
    val sex: String?,
    val handedness: String?,
    val weight: Float?,
    val height: Float?,
    val ethnicity: String?,
    val comments: String?,
    val addedOn: Long = System.currentTimeMillis(),
    val lastUpdated: Long = System.currentTimeMillis()
)

// ─────────────────────────────  DAO  ──────────────────────────────────
@Dao
interface ParticipantDao {

    @Insert(onConflict = OnConflictStrategy.REPLACE)    // UPSERT
    suspend fun upsert(p: Participant)

    @Query("SELECT * FROM participants ORDER BY addedOn DESC")
    suspend fun getAll(): List<Participant>

    @Query("SELECT * FROM participants WHERE subjectId = :sid LIMIT 1")
    suspend fun findById(sid: String): Participant?

    @Delete
    suspend fun delete(p: Participant)
}

// ───────────────  Encrypted Room singleton  ───────────────────────────
@Database(entities = [Participant::class], version = 2)                  // ↑ bump
abstract class SecureParticipantDb : RoomDatabase() {
    abstract fun participantDao(): ParticipantDao

    companion object {
        @Volatile private var INSTANCE: SecureParticipantDb? = null
        private const val DB_NAME = "participants_secure.db"

        fun get(context: Context): SecureParticipantDb =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: build(context).also { INSTANCE = it }
            }

        private fun build(ctx: Context): SecureParticipantDb {
            val passphrase = getOrCreatePassphrase(ctx)
            val factory    = SupportFactory(SQLiteDatabase.getBytes(passphrase.toCharArray()))
            return Room.databaseBuilder(ctx, SecureParticipantDb::class.java, DB_NAME)
                .openHelperFactory(factory)
                .fallbackToDestructiveMigration()       // fine during dev
                .build()
        }

        /** one-time random key, encrypted via Jetpack Security */
        private fun getOrCreatePassphrase(ctx: Context): String {
            val masterKey = MasterKeys.getOrCreate(MasterKeys.AES256_GCM_SPEC)
            val prefs = EncryptedSharedPreferences.create(
                "db_key_store", masterKey, ctx,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            val k = "db_key"
            return prefs.getString(k, null) ?: UUID.randomUUID().toString()
                .also { prefs.edit().putString(k, it).apply() }
        }
    }
}
