package app.redlight.hanmadi.data

import app.redlight.hanmadi.domain.model.DriveSignal
import app.redlight.hanmadi.domain.model.Gear
import app.redlight.hanmadi.domain.model.TurnSignal
import app.redlight.hanmadi.domain.repository.VehicleRepository
import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.DoorArea
import ai.pleos.playground.vehicle.constant.area.DoorValue
import ai.pleos.playground.vehicle.constant.area.TurnSignalValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.listener.BrakePedalStatusListener
import ai.pleos.playground.vehicle.listener.DoorPositionListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.TurnSignalStateListener
import android.car.Car
import android.car.VehiclePropertyIds
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.callbackFlow
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.retryWhen

/**
 * Pleos Vehicle SDK(기어, 브레이크, 문, 방향지시등)와 AAOS Car API(속도)를 하나의 DriveSignal로 묶는다.
 * 각 신호는 실패해도 나머지 신호는 계속 흐르도록 따로 catch한다.
 */
class PleosVehicleRepository(private val context: Context) : VehicleRepository {

    private val vehicle = Vehicle(context).apply { initialize() }
    private val driverDoor = DoorArea(DoorValue.ROW_1_LEFT)

    override fun driveSignal(): Flow<DriveSignal> = combine(
        gear().withDefault("기어", Gear.UNKNOWN),
        brakePressed().withDefault("브레이크", false),
        speedKmh().withDefault("속도", null),
        driverDoorOpen().withDefault("문", false),
        turnSignal().withDefault("방향지시등", TurnSignal.OFF),
    ) { values ->
        DriveSignal(
            gear = values[0] as Gear,
            brakePressed = values[1] as Boolean,
            speedKmh = values[2] as Float?,
            driverDoorOpen = values[3] as Boolean,
            turnSignal = values[4] as TurnSignal,
        )
    }.distinctUntilChanged()

    private fun gear(): Flow<Gear> = callbackFlow {
        val api = vehicle.getGear()
        val listener = object : GearSelectionListener {
            override fun onSelected(gear: VehicleGear?) { trySend(gear.toDomain()) }
            override fun onFailed(e: Exception) { close(e) }
        }
        api.getCurrentGearSelectionState({ trySend(it.toDomain()) }, { close(it) })
        api.registerGearSelection(listener)
        awaitClose { api.unregisterGearSelection(listener) }
    }

    private fun brakePressed(): Flow<Boolean> = callbackFlow {
        val api = vehicle.getBrake()
        val listener = object : BrakePedalStatusListener {
            override fun onBrakePedalStatusUpdated(pressed: Boolean?) { trySend(pressed == true) }
            override fun onFailed(e: Exception) { close(e) }
        }
        api.getCurrentBrakePedalStatus({ trySend(it == true) }, { close(it) })
        api.registerBrakePedalStatus(listener)
        awaitClose { api.unregisterBrakePedalStatus(listener) }
    }

    private fun speedKmh(): Flow<Float?> = callbackFlow {
        // 차량 서비스가 아직 없으면 null이 온다. 예외로 끝내서 withDefault가 다시 연결하게 한다
        val car = Car.createCar(context) ?: throw IllegalStateException("차량 서비스 연결 전")
        val properties = car.getCarManager(Car.PROPERTY_SERVICE) as CarPropertyManager
        val callback = object : CarPropertyManager.CarPropertyEventCallback {
            override fun onChangeEvent(value: CarPropertyValue<*>) {
                (value.value as? Float)?.let { metersPerSecond -> trySend(metersPerSecond * 3.6f) }
            }
            override fun onErrorEvent(propertyId: Int, zone: Int) {
                close(IllegalStateException("속도 오류 이벤트 zone=$zone"))
            }
        }
        val registered = properties.registerCallback(
            callback, VehiclePropertyIds.PERF_VEHICLE_SPEED, CarPropertyManager.SENSOR_RATE_NORMAL,
        )
        if (!registered) close(IllegalStateException("속도 구독 실패"))
        awaitClose {
            properties.unregisterCallback(callback)
            car.disconnect()
        }
    }

    private fun driverDoorOpen(): Flow<Boolean> = callbackFlow {
        val api = vehicle.getDoor()
        val listener = object : DoorPositionListener {
            override fun onPositionUpdated(position: Int?) { trySend((position ?: 0) > 0) }
            override fun onFailed(e: Exception) { close(e) }
        }
        api.getCurrentDoorPosition(driverDoor, { trySend((it ?: 0) > 0) }, { close(it) })
        api.registerDoorPosition(driverDoor, listener)
        awaitClose { api.unregisterDoorPosition(driverDoor, listener) }
    }

    private fun turnSignal(): Flow<TurnSignal> = callbackFlow {
        val api = vehicle.getTurnSignal()
        val listener = object : TurnSignalStateListener {
            override fun onTurnSignalStateUpdated(value: TurnSignalValue?) { trySend(value.toDomain()) }
            override fun onFailed(e: Exception) { close(e) }
        }
        api.getCurrentTurnSignalState({ trySend(it.toDomain()) }, { close(it) })
        api.registerTurnSignalState(listener)
        awaitClose { api.unregisterTurnSignalState(listener) }
    }

    private fun TurnSignalValue?.toDomain(): TurnSignal = when (this) {
        TurnSignalValue.LEFT -> TurnSignal.LEFT
        TurnSignalValue.RIGHT -> TurnSignal.RIGHT
        else -> TurnSignal.OFF
    }

    /**
     * 처음 값을 정해 두고, 실패하면 기본값으로 돌린 뒤 3초마다 다시 연결한다.
     * 차량 서비스가 늦게 뜨거나 재시작돼도 신호가 영영 끊기지 않게 한다.
     */
    private fun <T> Flow<T>.withDefault(name: String, default: T): Flow<T> =
        retryWhen { cause, attempt ->
            Log.w(TAG, "$name 신호 실패(${attempt + 1}회), 다시 연결: ${cause.message}")
            emit(default)
            delay(RETRY_MILLIS)
            true
        }.onStart { emit(default) }

    private fun VehicleGear?.toDomain(): Gear = when (this) {
        VehicleGear.GEAR_PARK -> Gear.PARK
        VehicleGear.GEAR_REVERSE -> Gear.REVERSE
        VehicleGear.GEAR_NEUTRAL -> Gear.NEUTRAL
        VehicleGear.GEAR_UNKNOWN, null -> Gear.UNKNOWN
        else -> Gear.DRIVE // D와 수동 1~8단
    }

    private companion object {
        const val TAG = "Vehicle"
        const val RETRY_MILLIS = 3_000L
    }
}
