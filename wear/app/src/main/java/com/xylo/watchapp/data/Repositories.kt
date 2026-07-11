package com.xylo.watchapp.data

import androidx.room.withTransaction
import com.xylo.watchapp.ble.MeasurementPayloadCbor
import kotlinx.coroutines.flow.Flow
import java.util.UUID
import kotlin.math.min

class MeasurementRepository(private val database: WatchDatabase) {
    fun latest(limit: Int = 100): Flow<List<MeasurementEntity>> = database.dao().observeLatest(limit)
    suspend fun record(value: MeasurementEntity) = database.withTransaction {
        val sequenced = if (value.sequence == 0L) value.copy(sequence = database.dao().nextSequence()) else value
        if (database.dao().insertMeasurement(sequenced) != -1L) {
            database.dao().enqueue(SyncQueueEntity(sequenced.id, sequenced.id, MeasurementPayloadCbor.encode(sequenced)))
        }
    }
    suspend fun acknowledge(id: String) = database.withTransaction {
        database.dao().updateQueue(id, QueueState.ACKNOWLEDGED, 0, 0, null)
        database.dao().acknowledgeMeasurement(id)
    }
}

class DurableSyncQueue(private val dao: WatchDao, private val clock: () -> Long = System::currentTimeMillis) {
    suspend fun claim(limit: Int = 20): List<SyncQueueEntity> {
        val values = dao.pending(clock(), limit)
        values.forEach { dao.updateQueue(it.messageId, QueueState.IN_FLIGHT, it.attemptCount + 1, clock() + 30_000, null) }
        return values
    }
    suspend fun retry(value: SyncQueueEntity, error: String) {
        val attempts = value.attemptCount + 1
        if (attempts >= 8) dao.updateQueue(value.messageId, QueueState.DEAD_LETTER, attempts, 0, error.take(160))
        else dao.updateQueue(value.messageId, QueueState.PENDING, attempts, clock() + min(300_000L, 2_000L * (1L shl min(attempts, 7))), error.take(160))
    }
}
