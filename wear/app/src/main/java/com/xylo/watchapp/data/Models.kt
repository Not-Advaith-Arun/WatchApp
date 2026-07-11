package com.xylo.watchapp.data

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.util.UUID

enum class MetricType { HEART_RATE, IBI, STEPS, DISTANCE, ACTIVE_ENERGY, WORKOUT_SUMMARY, OXYGEN_SATURATION, SKIN_TEMPERATURE, BODY_COMPOSITION }
enum class RawMetricType { ECG, PPG_GREEN, PPG_RED_IR, ACCELEROMETER }
enum class SampleQuality { GOOD, UNCERTAIN, POOR, UNKNOWN }
enum class QueueState { PENDING, IN_FLIGHT, ACKNOWLEDGED, DEAD_LETTER }

@Entity(tableName = "measurements", indices = [Index("capturedAtEpochMs"), Index("syncState")])
data class MeasurementEntity(
    @PrimaryKey val id: String = UUID.randomUUID().toString(),
    val type: MetricType,
    val value: Double,
    val unit: String,
    val capturedAtEpochMs: Long,
    val endAtEpochMs: Long? = null,
    val quality: SampleQuality = SampleQuality.GOOD,
    val source: String,
    val deviceId: String = "wa_unenrolled_device_placeholder",
    val sequence: Long = 0,
    val timezoneOffsetMinutes: Int = 0,
    val durationMillis: Long? = null,
    val statusFlags: String = "[]",
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val syncState: QueueState = QueueState.PENDING,
)

@Entity(tableName = "sync_queue", indices = [Index(value = ["state", "nextAttemptAtEpochMs"])])
data class SyncQueueEntity(
    @PrimaryKey val messageId: String,
    val aggregateId: String,
    val payloadCbor: ByteArray,
    val state: QueueState = QueueState.PENDING,
    val attemptCount: Int = 0,
    val nextAttemptAtEpochMs: Long = 0,
    val createdAtEpochMs: Long = System.currentTimeMillis(),
    val lastError: String? = null,
)

@Entity(tableName = "raw_sessions")
data class RawSessionEntity(
    @PrimaryKey val id: String,
    val type: RawMetricType,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long? = null,
    val sampleRateHz: Int,
    val chunkCount: Int = 0,
    val sampleCount: Int = 0,
    val sessionSha256: ByteArray? = null,
    val state: String,
)

@Entity(tableName = "raw_chunks", primaryKeys = ["sessionId", "sequence"])
data class RawChunkEntity(
    val sessionId: String,
    val sequence: Int,
    val firstSampleEpochMs: Long,
    val sampleStartIndex: Int,
    val sampleCount: Int,
    val encodedSamples: ByteArray,
    val uncompressedLength: Int,
    val uncompressedSha256: ByteArray,
)

@Entity(tableName = "enrollment")
data class EnrollmentEntity(
    @PrimaryKey val singletonId: Int = 1,
    val phoneId: String,
    val phoneName: String,
    val keyAlias: String,
    val enrolledAtEpochMs: Long,
    val protocolVersion: Int,
)

@Entity(tableName = "channel_state")
data class ChannelStateEntity(@PrimaryKey val epoch: Long, val outboundCounter: Long, val inboundHighWater: Long)
