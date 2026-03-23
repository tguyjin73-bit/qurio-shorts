package com.guardianai.assistant.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        ActivityLogEntity::class,
        UserPatternEntity::class,
        AiSuggestionEntity::class
    ],
    version = 2,
    exportSchema = false
)
abstract class GuardianDatabase : RoomDatabase() {

    abstract fun activityLogDao(): ActivityLogDao
    abstract fun userPatternDao(): UserPatternDao
    abstract fun aiSuggestionDao(): AiSuggestionDao

    companion object {
        @Volatile
        private var INSTANCE: GuardianDatabase? = null

        fun getInstance(context: Context): GuardianDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    GuardianDatabase::class.java,
                    "guardian_ai_db"
                )
                    .fallbackToDestructiveMigration()
                    .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
