package com.xylo.watchapp.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverter
import androidx.room.TypeConverters

class Converters {
    @TypeConverter fun metric(value: MetricType) = value.name
    @TypeConverter fun metric(value: String) = MetricType.valueOf(value)
    @TypeConverter fun rawMetric(value: RawMetricType) = value.name
    @TypeConverter fun rawMetric(value: String) = RawMetricType.valueOf(value)
    @TypeConverter fun quality(value: SampleQuality) = value.name
    @TypeConverter fun quality(value: String) = SampleQuality.valueOf(value)
    @TypeConverter fun queue(value: QueueState) = value.name
    @TypeConverter fun queue(value: String) = QueueState.valueOf(value)
}

@Database(entities = [MeasurementEntity::class, SyncQueueEntity::class, RawSessionEntity::class, RawChunkEntity::class, EnrollmentEntity::class, ChannelStateEntity::class], version = 1, exportSchema = true)
@TypeConverters(Converters::class)
abstract class WatchDatabase : RoomDatabase() {
    abstract fun dao(): WatchDao
    companion object {
        @Volatile private var instance: WatchDatabase? = null
        fun get(context: Context): WatchDatabase = instance ?: synchronized(this) {
            instance ?: Room.databaseBuilder(context.applicationContext, WatchDatabase::class.java, "watchapp.db").build().also { instance = it }
        }
    }
}
