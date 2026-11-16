package com.example.data.pune

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        LocationEntity::class,
        CommercialProjectEntity::class,
        ResidentialProjectEntity::class,
        DeveloperEntity::class,
        MetroStationEntity::class,
        PostalOfficeEntity::class,
        RailwayStationEntity::class,
        SearchAliasEntity::class,
        RoadCandidateEntity::class,
        LegacyEnrichmentEntity::class
    ],
    version = 1,
    exportSchema = false
)
abstract class PuneIntelligenceDatabase : RoomDatabase() {

    abstract fun puneIntelligenceDao(): PuneIntelligenceDao

    companion object {
        @Volatile
        private var INSTANCE: PuneIntelligenceDatabase? = null

        fun getDatabase(context: Context): PuneIntelligenceDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    PuneIntelligenceDatabase::class.java,
                    "pune_real_estate_intelligence_v2.db"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}

