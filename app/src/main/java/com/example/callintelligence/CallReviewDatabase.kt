package com.example.callintelligence

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

/**
 * Independent additive storage: never opens or migrates the legacy property/CRM store.
 * Call reviews are only created after an explicit user action.
 */
@Database(entities = [CallReviewEntity::class], version = 2, exportSchema = false)
abstract class CallReviewDatabase : RoomDatabase() {
    abstract fun callReviewDao(): CallReviewDao

    companion object {
        val AUDIT_MIGRATION = object : androidx.room.migration.Migration(1, 2) {
            override fun migrate(db: androidx.sqlite.db.SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE tlc_call_reviews ADD COLUMN updatedBy TEXT NOT NULL DEFAULT ''")
                db.execSQL("ALTER TABLE tlc_call_reviews ADD COLUMN updatedAt TEXT NOT NULL DEFAULT ''")
            }
        }
        @Volatile private var instance: CallReviewDatabase? = null

        fun get(context: Context): CallReviewDatabase =
            instance ?: synchronized(this) {
                instance ?: Room.databaseBuilder(
                    context.applicationContext,
                    CallReviewDatabase::class.java,
                    "tlc_call_intelligence_v1.db"
                ).addMigrations(AUDIT_MIGRATION).build().also { instance = it }
            }
    }
}

