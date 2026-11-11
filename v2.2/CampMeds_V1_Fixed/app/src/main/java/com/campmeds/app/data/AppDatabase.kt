package com.campmeds.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.campmeds.app.data.converter.Converters
import com.campmeds.app.data.dao.AdministrationTimeDao
import com.campmeds.app.data.dao.DoseLogDao
import com.campmeds.app.data.dao.ImportHistoryDao
import com.campmeds.app.data.dao.MedicationDao
import com.campmeds.app.data.dao.PatientDao
import com.campmeds.app.data.dao.UserDao
import com.campmeds.app.data.entity.AdministrationTime
import com.campmeds.app.data.entity.ChangeHistoryEntry
import com.campmeds.app.data.entity.ImportHistoryEntry
import com.campmeds.app.data.entity.DoseLog
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Patient
import com.campmeds.app.data.entity.User
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory
import java.io.File

/**
 * The current database schema version.
 *
 * It is a single constant so that the @Database annotation, the restore validator and the JVM
 * migration-registry test cannot drift apart. Bump it together with a new migration in Migrations.kt.
 */
const val SCHEMA_VERSION = 5

@Database(
    entities = [
        Patient::class, Medication::class, DoseLog::class, User::class, AdministrationTime::class,
        ImportHistoryEntry::class, ChangeHistoryEntry::class
    ],
    version = SCHEMA_VERSION,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun patientDao(): PatientDao
    abstract fun medicationDao(): MedicationDao
    abstract fun doseLogDao(): DoseLogDao
    abstract fun userDao(): UserDao
    abstract fun administrationTimeDao(): AdministrationTimeDao
    abstract fun importHistoryDao(): ImportHistoryDao

    companion object {
        const val DB_NAME = "campmeds_encrypted.db"

        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext, DB_NAME).also { instance = it }
            }

        /** The encrypted database file this app uses. Shared with the backup/restore code. */
        fun databaseFile(context: Context): File = context.getDatabasePath(DB_NAME)

        /**
         * Opens a database with a different file name, for instrumented tests that must not touch
         * the live data (backup/restore tests in particular). The same SQLCipher key is used.
         */
        fun buildForTesting(context: Context, name: String): AppDatabase = build(context.applicationContext, name)

        /** Closes the singleton so the next getInstance() re-opens the (possibly replaced) file. */
        fun closeInstance() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        /** Kept for the existing tests/README wording; identical to [closeInstance]. */
        fun closeAndResetForTesting() = closeInstance()

        private fun build(context: Context, name: String): AppDatabase {
            // A restore that was interrupted between "move the live file aside" and "put the new file
            // in place" is repaired here, before Room ever opens the file. See DatabaseRestoreManager.
            DatabaseRestoreManager.recoverInterruptedRestore(context)

            // Loads the native SQLCipher libs before Room opens the DB.
            SQLiteDatabase.loadLibs(context)

            val passphrase = DatabaseKeyProvider.getOrCreatePassphrase(context)
            val bytes = SQLiteDatabase.getBytes(passphrase)
            val factory = SupportFactory(bytes)

            return Room.databaseBuilder(context, AppDatabase::class.java, name)
                .openHelperFactory(factory) // <-- this is what makes the DB file SQLCipher-encrypted
                // Real, data-preserving migrations only. There is deliberately NO destructive fallback.
                .addMigrations(*ALL_MIGRATIONS)
                .build()
        }
    }
}
