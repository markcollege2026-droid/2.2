package com.campmeds.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.campmeds.app.data.converter.Converters
import com.campmeds.app.data.dao.DoseLogDao
import com.campmeds.app.data.dao.MedicationDao
import com.campmeds.app.data.dao.PatientDao
import com.campmeds.app.data.dao.UserDao
import com.campmeds.app.data.entity.DoseLog
import com.campmeds.app.data.entity.Medication
import com.campmeds.app.data.entity.Patient
import com.campmeds.app.data.entity.User
import net.sqlcipher.database.SQLiteDatabase
import net.sqlcipher.database.SupportFactory

@Database(
    entities = [Patient::class, Medication::class, DoseLog::class, User::class],
    version = 2,
    exportSchema = true
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun patientDao(): PatientDao
    abstract fun medicationDao(): MedicationDao
    abstract fun doseLogDao(): DoseLogDao
    abstract fun userDao(): UserDao

    companion object {
        private const val DB_NAME = "campmeds_encrypted.db"

        @Volatile private var instance: AppDatabase? = null

        fun getInstance(context: Context): AppDatabase =
            instance ?: synchronized(this) {
                instance ?: build(context.applicationContext).also { instance = it }
            }

        /** For instrumented tests: closes the singleton so the next getInstance() re-opens the DB file with the stored key. */
        fun closeAndResetForTesting() {
            synchronized(this) {
                instance?.close()
                instance = null
            }
        }

        private fun build(context: Context): AppDatabase {
            // Loads the native SQLCipher libs before Room opens the DB.
            SQLiteDatabase.loadLibs(context)

            val passphrase = DatabaseKeyProvider.getOrCreatePassphrase(context)
            val bytes = SQLiteDatabase.getBytes(passphrase)
            val factory = SupportFactory(bytes)

            return Room.databaseBuilder(context, AppDatabase::class.java, DB_NAME)
                .openHelperFactory(factory) // <-- this is what makes the DB file SQLCipher-encrypted
                // Real, data-preserving migrations only. There is deliberately NO destructive fallback.
                .addMigrations(MIGRATION_1_2)
                .build()
        }
    }
}
