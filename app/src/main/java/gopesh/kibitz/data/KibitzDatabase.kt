package gopesh.kibitz.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

@Database(
    entities = [GameRecord::class, MoveRecord::class, DrillAttempt::class],
    version = 2,
    exportSchema = false,
)
abstract class KibitzDatabase : RoomDatabase() {

    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var instance: KibitzDatabase? = null

        /**
         * Adds drill attempts.
         *
         * Written out rather than falling back to a destructive migration: the whole point of
         * the history is that it accumulates, and wiping it on an upgrade would throw away the
         * only thing that makes the coaching personal. The DDL has to match what Room generates
         * for the entity exactly, or Room refuses to open the database — which is precisely why
         * there is a test that opens it after migrating.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL(
                    """
                    CREATE TABLE IF NOT EXISTS `drill_attempts` (
                        `id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL,
                        `moveId` INTEGER NOT NULL,
                        `attemptedAt` INTEGER NOT NULL,
                        `correct` INTEGER NOT NULL,
                        FOREIGN KEY(`moveId`) REFERENCES `moves`(`id`)
                            ON UPDATE NO ACTION ON DELETE CASCADE
                    )
                    """.trimIndent()
                )
                db.execSQL(
                    "CREATE INDEX IF NOT EXISTS `index_drill_attempts_moveId` " +
                        "ON `drill_attempts` (`moveId`)"
                )
            }
        }

        fun get(context: Context): KibitzDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    KibitzDatabase::class.java,
                    "kibitz-history.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { instance = it }
            }
    }
}
