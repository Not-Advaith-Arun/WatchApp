package com.xylo.watchapp

import android.app.Application
import com.xylo.watchapp.ble.WatchGattServer
import com.xylo.watchapp.crypto.AndroidEnrollmentStore
import com.xylo.watchapp.data.*
import com.xylo.watchapp.health.*
import com.xylo.watchapp.crypto.SessionFrameProtector
import com.xylo.watchapp.crypto.RoomCounterStore
import com.xylo.watchapp.crypto.EnrollmentCoordinator
import com.xylo.watchapp.raw.RawRecordingCoordinator
import com.xylo.watchapp.ble.MessageRouter

class WatchApplication : Application() {
    val graph by lazy { AppGraph(this) }
}

class AppGraph(application: Application) {
    val database = WatchDatabase.get(application)
    val measurements = MeasurementRepository(database)
    val queue = DurableSyncQueue(database.dao())
    val enrollment = AndroidEnrollmentStore(database.dao())
    val frameProtector = SessionFrameProtector()
    val counters = RoomCounterStore(database.dao())
    val enrollmentCoordinator = EnrollmentCoordinator(enrollment, counters, frameProtector)
    val health = WearHealthServicesProvider(application)
    val activeMeasure = WearActiveMeasureProvider(WearHeartRateMeasureFacade(application))
    val workout = WearWorkoutProvider(WearExerciseFacade(application))
    val samsungBridge: SamsungTrackerBridge = NoOpSamsungTrackerBridge()
    val samsungRaw = SamsungRawSensorProvider(samsungBridge)
    val rawRecorder = RawRecordingCoordinator(database.dao(), samsungRaw)
    val router = MessageRouter(database.dao(), measurements, activeMeasure, rawRecorder, workout)
    val gatt = WatchGattServer(application, queue, measurements, frameProtector, enrollmentCoordinator, router)
}
