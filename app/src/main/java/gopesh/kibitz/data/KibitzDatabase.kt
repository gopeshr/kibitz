package gopesh.kibitz.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [GameRecord::class, MoveRecord::class],
    version = 1,
    exportSchema = false,
)
abstract class KibitzDatabase : RoomDatabase() {

    abstract fun historyDao(): HistoryDao

    companion object {
        @Volatile
        private var instance: KibitzDatabase? = null

        fun get(context: Context): KibitzDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    KibitzDatabase::class.java,
                    "kibitz-history.db",
                ).build().also { instance = it }
            }
    }
}
