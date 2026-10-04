package app.fwchat.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [ChatEntity::class, MessageEntity::class, SystemPromptEntity::class],
    version = 1,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun chatDao(): ChatDao
    abstract fun messageDao(): MessageDao
    abstract fun systemPromptDao(): SystemPromptDao

    companion object {
        const val FILE_NAME = "fwchat.db"
    }
}

/** Base de production (fichier). */
fun AppDatabase.Companion.build(context: Context): AppDatabase =
    Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, AppDatabase.FILE_NAME).build()

/** Base en mémoire (tests). */
fun AppDatabase.Companion.inMemory(context: Context): AppDatabase =
    Room.inMemoryDatabaseBuilder(context.applicationContext, AppDatabase::class.java).build()
