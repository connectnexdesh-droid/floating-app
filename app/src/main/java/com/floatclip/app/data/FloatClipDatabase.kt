package com.floatclip.app.data

import android.content.Context
import androidx.room.ColumnInfo
import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Transaction
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.Update
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

@Entity(tableName = "quick_texts")
data class QuickTextEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val title: String,
    val content: String,
    val position: Int,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "''") val colC: String = "",
    @ColumnInfo(defaultValue = "''") val colD: String = "",
    @ColumnInfo(defaultValue = "1") val queue: Int = 1,
    @ColumnInfo(defaultValue = "0") val lastCopied: Boolean = false
)

@Entity(tableName = "saved_clipboard")
data class SavedClipboardEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val slot: String,
    val content: String,
    val createdAt: Long = System.currentTimeMillis(),
    @ColumnInfo(defaultValue = "0") val position: Int = 0,
    @ColumnInfo(defaultValue = "''") val colB: String = "",
    @ColumnInfo(defaultValue = "''") val colC: String = "",
    @ColumnInfo(defaultValue = "''") val colD: String = ""
)

@Entity(tableName = "clipboard_sheet")
data class ClipboardSheetEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    @ColumnInfo(defaultValue = "0") val position: Int = 0,
    @ColumnInfo(defaultValue = "''") val colA: String = "",
    @ColumnInfo(defaultValue = "''") val colB: String = "",
    @ColumnInfo(defaultValue = "''") val colC: String = "",
    @ColumnInfo(defaultValue = "''") val colD: String = "",
    val createdAt: Long = System.currentTimeMillis()
)

@Dao
interface FloatClipDao {
    @Query("SELECT * FROM quick_texts WHERE queue = 1 ORDER BY position ASC, id ASC")
    fun observeQuickTexts(): Flow<List<QuickTextEntity>>

    @Query("SELECT * FROM quick_texts WHERE queue = :queue ORDER BY position ASC, id ASC")
    fun observeQuickTexts(queue: Int): Flow<List<QuickTextEntity>>

    @Query("SELECT * FROM quick_texts WHERE queue = 1 ORDER BY position ASC, id ASC")
    suspend fun getQuickTextsOnce(): List<QuickTextEntity>

    @Query("SELECT * FROM quick_texts WHERE queue = :queue ORDER BY position ASC, id ASC")
    suspend fun getQuickTextsOnce(queue: Int): List<QuickTextEntity>

    @Query("SELECT * FROM quick_texts WHERE id = :id LIMIT 1")
    suspend fun getQuickTextById(id: Long): QuickTextEntity?

    @Query("SELECT MAX(position) FROM quick_texts WHERE queue = 1")
    suspend fun maxQuickTextPosition(): Int?

    @Query("SELECT MAX(position) FROM quick_texts WHERE queue = :queue")
    suspend fun maxQuickTextPosition(queue: Int): Int?

    @Insert
    suspend fun insertQuickText(item: QuickTextEntity): Long

    @Update
    suspend fun updateQuickText(item: QuickTextEntity)

    @Query("UPDATE quick_texts SET position = :position WHERE id = :id")
    suspend fun updateQuickTextPosition(id: Long, position: Int)

    @Query("DELETE FROM quick_texts WHERE id = :id")
    suspend fun deleteQuickText(id: Long)

    @Query("DELETE FROM quick_texts WHERE queue = 1")
    suspend fun deleteAllQuickTexts()

    @Query("DELETE FROM quick_texts WHERE queue = :queue")
    suspend fun deleteQuickTextQueue(queue: Int)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertQuickTexts(items: List<QuickTextEntity>)

    @Transaction
    suspend fun restoreQuickTextQueue(queue: Int, items: List<QuickTextEntity>) {
        deleteQuickTextQueue(queue)
        if (items.isNotEmpty()) insertQuickTexts(items)
    }

    @Query("UPDATE quick_texts SET lastCopied = 0 WHERE queue = :queue")
    suspend fun clearQuickTextLastCopied(queue: Int)

    @Query("UPDATE quick_texts SET lastCopied = 1 WHERE queue = :queue AND id = :id")
    suspend fun markQuickTextLastCopied(queue: Int, id: Long)

    @Transaction
    suspend fun setQuickTextLastCopied(queue: Int, id: Long) {
        clearQuickTextLastCopied(queue)
        markQuickTextLastCopied(queue, id)
    }

    @Query("SELECT * FROM saved_clipboard WHERE slot = :slot ORDER BY position ASC, id ASC")
    fun observeSaved(slot: String): Flow<List<SavedClipboardEntity>>

    @Query("SELECT * FROM saved_clipboard WHERE slot = :slot ORDER BY position ASC, id ASC")
    suspend fun getSavedOnce(slot: String): List<SavedClipboardEntity>

    @Query("SELECT MAX(position) FROM saved_clipboard WHERE slot = :slot")
    suspend fun maxSavedPosition(slot: String): Int?

    @Insert
    suspend fun insertSaved(item: SavedClipboardEntity): Long

    @Update
    suspend fun updateSaved(item: SavedClipboardEntity)

    @Query("DELETE FROM saved_clipboard WHERE id = :id")
    suspend fun deleteSaved(id: Long)

    @Query("DELETE FROM saved_clipboard WHERE slot = :slot")
    suspend fun deleteAllSaved(slot: String)

    @Query("SELECT COUNT(*) FROM saved_clipboard WHERE slot = :slot")
    suspend fun countSaved(slot: String): Int

    @Query("SELECT * FROM clipboard_sheet ORDER BY position ASC, id ASC")
    fun observeClipboardSheet(): Flow<List<ClipboardSheetEntity>>

    @Query("SELECT * FROM clipboard_sheet ORDER BY position ASC, id ASC")
    suspend fun getClipboardSheetOnce(): List<ClipboardSheetEntity>

    @Query("SELECT MAX(position) FROM clipboard_sheet")
    suspend fun maxClipboardSheetPosition(): Int?

    @Insert
    suspend fun insertClipboardSheet(item: ClipboardSheetEntity): Long

    @Update
    suspend fun updateClipboardSheet(item: ClipboardSheetEntity)

    @Query("DELETE FROM clipboard_sheet WHERE id = :id")
    suspend fun deleteClipboardSheet(id: Long)

    @Query("DELETE FROM clipboard_sheet")
    suspend fun deleteAllClipboardSheet()

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertClipboardSheets(items: List<ClipboardSheetEntity>)

    @Transaction
    suspend fun restoreClipboardSheet(items: List<ClipboardSheetEntity>) {
        deleteAllClipboardSheet()
        if (items.isNotEmpty()) insertClipboardSheets(items)
    }
}

@Database(
    entities = [QuickTextEntity::class, SavedClipboardEntity::class, ClipboardSheetEntity::class],
    version = 4,
    exportSchema = false
)
abstract class FloatClipDatabase : RoomDatabase() {
    abstract fun dao(): FloatClipDao

    companion object {
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE quick_texts ADD COLUMN colC TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE quick_texts ADD COLUMN colD TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE saved_clipboard ADD COLUMN position INTEGER NOT NULL DEFAULT 0")
                database.execSQL("ALTER TABLE saved_clipboard ADD COLUMN colB TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE saved_clipboard ADD COLUMN colC TEXT NOT NULL DEFAULT ''")
                database.execSQL("ALTER TABLE saved_clipboard ADD COLUMN colD TEXT NOT NULL DEFAULT ''")
                database.execSQL(
                    "UPDATE saved_clipboard SET position = " +
                        "(SELECT COUNT(*) - 1 FROM saved_clipboard AS older " +
                        "WHERE older.slot = saved_clipboard.slot AND older.id <= saved_clipboard.id)"
                )
            }
        }

        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL(
                    "CREATE TABLE IF NOT EXISTS clipboard_sheet (" +
                        "id INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, " +
                        "position INTEGER NOT NULL DEFAULT 0, " +
                        "colA TEXT NOT NULL DEFAULT '', " +
                        "colB TEXT NOT NULL DEFAULT '', " +
                        "colC TEXT NOT NULL DEFAULT '', " +
                        "colD TEXT NOT NULL DEFAULT '', " +
                        "createdAt INTEGER NOT NULL)"
                )
                database.execSQL(
                    "INSERT INTO clipboard_sheet (position, colA, colB, colC, colD, createdAt) " +
                        "SELECT position, " +
                        "MAX(CASE WHEN slot = 'A' THEN content ELSE '' END), '', " +
                        "MAX(CASE WHEN slot = 'C' THEN content ELSE '' END), '', " +
                        "MAX(createdAt) FROM saved_clipboard GROUP BY position ORDER BY position ASC"
                )
            }
        }

        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(database: SupportSQLiteDatabase) {
                database.execSQL("ALTER TABLE quick_texts ADD COLUMN queue INTEGER NOT NULL DEFAULT 1")
                database.execSQL("ALTER TABLE quick_texts ADD COLUMN lastCopied INTEGER NOT NULL DEFAULT 0")
            }
        }

        @Volatile
        private var instance: FloatClipDatabase? = null

        fun get(context: Context): FloatClipDatabase {
            return instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    FloatClipDatabase::class.java,
                    "floatclip.db"
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4)
                    .build()
                    .also { instance = it }
            }
        }
    }
}
