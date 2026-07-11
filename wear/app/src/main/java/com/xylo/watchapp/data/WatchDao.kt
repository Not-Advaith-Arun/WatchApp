package com.xylo.watchapp.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface WatchDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun insertMeasurement(value: MeasurementEntity): Long
    @Query("SELECT COALESCE(MAX(sequence), 0) + 1 FROM measurements") suspend fun nextSequence(): Long
    @Query("SELECT * FROM measurements ORDER BY capturedAtEpochMs DESC LIMIT :limit") fun observeLatest(limit: Int = 100): Flow<List<MeasurementEntity>>
    @Query("SELECT * FROM measurements WHERE type = :type ORDER BY capturedAtEpochMs DESC LIMIT 1") fun observeLatest(type: MetricType): Flow<MeasurementEntity?>
    @Query("SELECT * FROM measurements WHERE sequence > :sequence ORDER BY sequence LIMIT :limit") suspend fun measurementsAfter(sequence: Long, limit: Int = 256): List<MeasurementEntity>
    @Insert(onConflict = OnConflictStrategy.IGNORE) suspend fun enqueue(value: SyncQueueEntity): Long
    @Query("SELECT * FROM sync_queue WHERE (state = 'PENDING' OR state = 'IN_FLIGHT') AND nextAttemptAtEpochMs <= :now ORDER BY createdAtEpochMs LIMIT :limit") suspend fun pending(now: Long, limit: Int): List<SyncQueueEntity>
    @Query("UPDATE sync_queue SET state = :state, attemptCount = :attempts, nextAttemptAtEpochMs = :next, lastError = :error WHERE messageId = :id") suspend fun updateQueue(id: String, state: QueueState, attempts: Int, next: Long, error: String?)
    @Query("UPDATE measurements SET syncState = 'ACKNOWLEDGED' WHERE id = :id") suspend fun acknowledgeMeasurement(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun upsertSession(value: RawSessionEntity)
    @Insert(onConflict = OnConflictStrategy.ABORT) suspend fun insertChunk(value: RawChunkEntity)
    @Query("SELECT * FROM raw_chunks WHERE sessionId = :sessionId ORDER BY sequence") suspend fun chunks(sessionId: String): List<RawChunkEntity>
    @Query("SELECT * FROM raw_sessions WHERE state = 'COMPLETE'") suspend fun completedSessions(): List<RawSessionEntity>
    @Query("SELECT * FROM raw_sessions WHERE id = :id") suspend fun session(id: String): RawSessionEntity?
    @Query("UPDATE raw_sessions SET state = 'SYNCED' WHERE id = :id") suspend fun markSessionSynced(id: String)
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveEnrollment(value: EnrollmentEntity)
    @Query("SELECT * FROM enrollment WHERE singletonId = 1") fun observeEnrollment(): Flow<EnrollmentEntity?>
    @Query("DELETE FROM enrollment") suspend fun clearEnrollment()
    @Query("SELECT * FROM channel_state WHERE epoch = :epoch") suspend fun channelState(epoch: Long): ChannelStateEntity?
    @Insert(onConflict = OnConflictStrategy.REPLACE) suspend fun saveChannelState(value: ChannelStateEntity)
}
