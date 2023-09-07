package com.juul.kable

import android.bluetooth.BluetoothAdapter.STATE_OFF
import android.bluetooth.BluetoothAdapter.STATE_ON
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothDevice.ACTION_BOND_STATE_CHANGED
import android.bluetooth.BluetoothDevice.BOND_BONDED
import android.bluetooth.BluetoothDevice.BOND_BONDING
import android.bluetooth.BluetoothDevice.BOND_NONE
import android.bluetooth.BluetoothDevice.DEVICE_TYPE_CLASSIC
import android.bluetooth.BluetoothDevice.DEVICE_TYPE_DUAL
import android.bluetooth.BluetoothDevice.DEVICE_TYPE_LE
import android.bluetooth.BluetoothDevice.DEVICE_TYPE_UNKNOWN
import android.bluetooth.BluetoothDevice.ERROR
import android.bluetooth.BluetoothDevice.EXTRA_BOND_STATE
import android.bluetooth.BluetoothDevice.EXTRA_DEVICE
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_INDICATE
import android.bluetooth.BluetoothGattCharacteristic.PROPERTY_NOTIFY
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
import android.bluetooth.BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
import android.bluetooth.BluetoothGattDescriptor.DISABLE_NOTIFICATION_VALUE
import android.bluetooth.BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
import android.bluetooth.BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
import android.content.IntentFilter
import androidx.core.content.ContextCompat.RECEIVER_EXPORTED
import androidx.core.content.IntentCompat
import com.benasher44.uuid.uuidFrom
import com.juul.kable.AndroidPeripheral.Bond
import com.juul.kable.AndroidPeripheral.Bond.Bonded
import com.juul.kable.AndroidPeripheral.Bond.Bonding
import com.juul.kable.AndroidPeripheral.Bond.None
import com.juul.kable.AndroidPeripheral.Type
import com.juul.kable.WriteType.WithResponse
import com.juul.kable.WriteType.WithoutResponse
import com.juul.kable.external.CLIENT_CHARACTERISTIC_CONFIG_UUID
import com.juul.kable.gatt.Response.OnCharacteristicRead
import com.juul.kable.gatt.Response.OnCharacteristicWrite
import com.juul.kable.gatt.Response.OnDescriptorRead
import com.juul.kable.gatt.Response.OnDescriptorWrite
import com.juul.kable.gatt.Response.OnReadRemoteRssi
import com.juul.kable.gatt.Response.OnServicesDiscovered
import com.juul.kable.logs.Logger
import com.juul.kable.logs.Logging
import com.juul.kable.logs.detail
import kotlinx.atomicfu.atomic
import kotlinx.atomicfu.updateAndGet
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart.LAZY
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import kotlin.coroutines.CoroutineContext

private val clientCharacteristicConfigUuid = uuidFrom(CLIENT_CHARACTERISTIC_CONFIG_UUID)

// Number of service discovery attempts to make if no services are discovered.
// https://github.com/JuulLabs/kable/issues/295
private const val DISCOVER_SERVICES_RETRIES = 5

internal class BluetoothDeviceAndroidPeripheral(
    parentCoroutineContext: CoroutineContext,
    private val bluetoothDevice: BluetoothDevice,
    private val transport: Transport,
    private val phy: Phy,
    observationExceptionHandler: ObservationExceptionHandler,
    private val onServicesDiscovered: ServicesDiscoveredAction,
    private val logging: Logging,
) : AndroidPeripheral {

    private val logger =
        Logger(logging, tag = "Kable/Peripheral", identifier = bluetoothDevice.address)

    private val _state = MutableStateFlow<State>(State.Disconnected())
    override val state: StateFlow<State> = _state.asStateFlow()

    override val identifier: String = bluetoothDevice.address

    private val job = SupervisorJob(parentCoroutineContext[Job]).apply {
        invokeOnCompletion {
            closeConnection()
            threading.close()
        }
    }
    private val scope = CoroutineScope(parentCoroutineContext + job)

    init {
        bluetoothState
            .filter { state -> state == STATE_OFF }
            .onEach { closeConnection() }
            .launchIn(scope)
    }

    private val threading = bluetoothDevice.threading()

    private val _mtu = MutableStateFlow<Int?>(null)
    override val mtu: StateFlow<Int?> = _mtu.asStateFlow()

    /*
        //This is a hack to get the bond state from the device itself
        val _bondState:MutableStateFlow<Bond> by lazy {
            MutableStateFlow<Bond>(getBondState(bluetoothDevice.bondState))
        }

        fun getBondState(state:Int):Bond = when (state) {
                BOND_NONE -> None
                BOND_BONDING -> Bonding
                BOND_BONDED -> Bonded
                else -> error("Unsupported bond state: $state")
            }
    */


    // First we get the state from the device itself, then we listen for changes.
    override val bondState: Flow<Bond> by lazy {
        merge(
            flowOf(bluetoothDevice.bondState),
            broadcastReceiverFlow(
                IntentFilter(ACTION_BOND_STATE_CHANGED)
            ).filter { intent ->
                    bluetoothDevice == IntentCompat.getParcelableExtra(
                        intent,
                        EXTRA_DEVICE,
                        BluetoothDevice::class.java,
                    )
                }.map { intent ->
                    intent.getIntExtra(EXTRA_BOND_STATE, ERROR)
                },
        ).map { state ->
            when (state) {
                BOND_NONE -> None
                BOND_BONDING -> Bonding
                BOND_BONDED -> Bonded
                else -> error("Unsupported bond state: $state")
            }
        }.onEach {
            logger.info { message = "Bond state $it" }
        }
    }

    private val observers =
        Observers<ByteArray>(this, logging, exceptionHandler = observationExceptionHandler)

    @Volatile
    private var _discoveredServices: List<DiscoveredService>? = null
    private val discoveredServices: List<DiscoveredService>
        get() = _discoveredServices
            ?: throw IllegalStateException("Services have not been discovered for $this")

    override val services: List<DiscoveredService>?
        get() = _discoveredServices?.toList()

    @Volatile
    private var _connection: Connection? = null
    private val connection: Connection
        inline get() = _connection ?: throw NotReadyException(toString())

    private val connectJob = atomic<Deferred<Unit>?>(null)

    override val name: String? get() = bluetoothDevice.name

    private fun establishConnection(): Connection {
        logger.info { message = "Connecting" }
        return bluetoothDevice.connect(
            scope.coroutineContext,
            applicationContext,
            transport,
            phy,
            _state,
            _mtu,
            observers.characteristicChanges,
            logging,
            threading,
            invokeOnClose = { connectJob.value = null },
        ) ?: throw ConnectionRejectedException()
    }

    /** Creates a connect [Job] that completes when connection is established, or failure occurs. */
    private fun connectAsync() = scope.async(start = LAZY) {
        try {
            _connection = establishConnection()
            bluetoothDevice.bondState
            /*     .also {
                     logger.debug { message = "Awaiting bond state" }
                     val bond = bondState.first { it != Bonding }
                     logger.debug { message = "Bond state: $bond" }
                 }*/

            suspendUntilOrThrow<State.Connecting.Services>()
            discoverServices()
            onServicesDiscovered(ServicesDiscoveredPeripheral(this@BluetoothDeviceAndroidPeripheral))
            _state.value = State.Connecting.Observes
            logger.verbose { message = "Configuring characteristic observations" }
            observers.onConnected()
        } catch (t: Throwable) {
            closeConnection()
            logger.error(t) { message = "Failed to connect" }
            throw t
        }

        logger.info { message = "Connected" }
        _state.value = State.Connected
    }

    private fun closeConnection() {
        _connection?.close()
        _connection = null

        // Avoid trampling existing `Disconnected` state (and its properties) by only updating if not already `Disconnected`.
        _state.update { previous -> previous as? State.Disconnected ?: State.Disconnected() }
    }

    override val type: Type
        get() = typeFrom(bluetoothDevice.type)

    override val address: String = bluetoothDevice.address

    override suspend fun connect() {
        checkBluetoothAdapterState(expected = STATE_ON)
        connectJob.updateAndGet { it ?: connectAsync() }!!.await()
    }

    override suspend fun disconnect() {
        try {
            _connection?.apply {
                bluetoothGatt.disconnect()
                suspendUntil<State.Disconnected>()
            }
        } finally {
            closeConnection()
        }
    }

    override fun requestConnectionPriority(priority: Priority): Boolean {
        logger.debug {
            message = "requestConnectionPriority"
            detail("priority", priority.name)
        }
        return connection.bluetoothGatt
            .requestConnectionPriority(priority.intValue)
    }

    override suspend fun rssi(): Int = connection.execute<OnReadRemoteRssi> {
        readRemoteRssi()
    }.rssi

    private suspend fun discoverServices() {
        logger.verbose { message = "discoverServices" }

        repeat(DISCOVER_SERVICES_RETRIES) { attempt ->
            connection.execute<OnServicesDiscovered> {
                discoverServices()
            }
            val services = withContext(connection.dispatcher) {
                connection.bluetoothGatt.services.map(::DiscoveredService)
            }

            if (services.isEmpty()) {
                logger.warn {
                    message =
                        "Empty services (attempt ${attempt + 1} of $DISCOVER_SERVICES_RETRIES)"
                }
            } else {
                logger.verbose { message = "Discovered ${services.count()} services" }
                _discoveredServices = services
                return
            }
        }
        _discoveredServices = emptyList()
    }

    override suspend fun requestMtu(mtu: Int): Int {
        logger.debug {
            message = "requestMtu"
            detail("mtu", mtu)
        }
        return connection.requestMtu(mtu)
    }

    override suspend fun write(
        characteristic: Characteristic,
        data: ByteArray,
        writeType: WriteType,
    ) {
        logger.debug {
            message = "write"
            detail(characteristic)
            detail(writeType)
            detail(data)
        }

        val platformCharacteristic = discoveredServices.obtain(characteristic, writeType.properties)

        val writeFunction = suspend {
            connection.execute<OnCharacteristicWrite> {
                platformCharacteristic.value = data
                platformCharacteristic.writeType = writeType.intValue
                writeCharacteristic(platformCharacteristic)
            }
        }
        try {
            writeFunction()
        } catch (_: BondRequiredException) {
            awaitBond()
            logger.debug {
                message = "Retrying write"
                detail(characteristic)
                detail(writeType)
                detail(data)
            }
            writeFunction()
        }
    }


    override suspend fun read(
        characteristic: Characteristic,
    ): ByteArray {
        logger.debug {
            message = "read"
            detail(characteristic)
        }

        val platformCharacteristic = discoveredServices.obtain(characteristic, Read)

        return try {
            connection.execute<OnCharacteristicRead> {
                readCharacteristic(platformCharacteristic)
            }
        } catch (_: BondRequiredException) {
            awaitBond()
            logger.debug {
                message = "Retrying read"
                detail(characteristic)
            }
            connection.execute<OnCharacteristicRead> {
                readCharacteristic(platformCharacteristic)
            }
        }.value!!
    }

    override suspend fun write(
        descriptor: Descriptor,
        data: ByteArray,
    ) {
        write(discoveredServices.obtain(descriptor), data)
    }

    private suspend fun write(
        platformDescriptor: PlatformDescriptor,
        data: ByteArray,
    ) {
        logger.debug {
            message = "write"
            detail(platformDescriptor)
            detail(data)
        }

        connection.execute<OnDescriptorWrite> {
            platformDescriptor.value = data
            writeDescriptor(platformDescriptor)
        }
    }

    override suspend fun read(
        descriptor: Descriptor,
    ): ByteArray {
        logger.debug {
            message = "read"
            detail(descriptor)
        }

        val platformDescriptor = discoveredServices.obtain(descriptor)
        return connection.execute<OnDescriptorRead> {
            readDescriptor(platformDescriptor)
        }.value!!
    }

    override fun observe(
        characteristic: Characteristic,
        onSubscription: OnSubscriptionAction,
    ): Flow<ByteArray> = observers.acquire(characteristic, onSubscription)

    internal suspend fun startObservation(characteristic: Characteristic) {
        logger.debug {
            message = "setCharacteristicNotification"
            detail(characteristic)
            detail("value", "true")
        }

        val platformCharacteristic = discoveredServices.obtain(characteristic, Notify or Indicate)
        connection
            .bluetoothGatt
            .setCharacteristicNotification(platformCharacteristic, true)
        setConfigDescriptor(platformCharacteristic, enable = true)
    }

    internal suspend fun stopObservation(characteristic: Characteristic) {
        val platformCharacteristic = discoveredServices.obtain(characteristic, Notify or Indicate)
        setConfigDescriptor(platformCharacteristic, enable = false)

        logger.debug {
            message = "setCharacteristicNotification"
            detail(characteristic)
            detail("value", "false")
        }
        connection
            .bluetoothGatt
            .setCharacteristicNotification(platformCharacteristic, false)
    }

    private suspend fun setConfigDescriptor(
        characteristic: PlatformCharacteristic,
        enable: Boolean,
    ) {
        val configDescriptor = characteristic.configDescriptor
        if (configDescriptor != null) {
            if (enable) {
                when {
                    characteristic.supportsNotify -> {
                        logger.verbose {
                            message = "Writing ENABLE_NOTIFICATION_VALUE to CCCD"
                            detail(configDescriptor)
                        }
                        write(configDescriptor, ENABLE_NOTIFICATION_VALUE)
                    }

                    characteristic.supportsIndicate -> {
                        logger.verbose {
                            message = "Writing ENABLE_INDICATION_VALUE to CCCD"
                            detail(configDescriptor)
                        }
                        write(configDescriptor, ENABLE_INDICATION_VALUE)
                    }

                    else -> logger.warn {
                        message = "Characteristic supports neither notification nor indication"
                        detail(characteristic)
                    }
                }
            } else {
                if (characteristic.supportsNotify || characteristic.supportsIndicate) {
                    logger.verbose {
                        message = "Writing DISABLE_NOTIFICATION_VALUE to CCCD"
                        detail(configDescriptor)
                    }
                    write(configDescriptor, DISABLE_NOTIFICATION_VALUE)
                }
            }
        } else {
            logger.warn {
                message = "Characteristic is missing config descriptor."
                detail(characteristic)
            }
        }
    }

    override suspend fun awaitBond() {
        logger.warn { message = "Insufficient authentication, awaiting bond" }
        bondState.first { it == Bonded }
    }


    override fun toString(): String = "Peripheral(bluetoothDevice=$bluetoothDevice)"
}

private val WriteType.intValue: Int
    get() = when (this) {
        WithResponse -> WRITE_TYPE_DEFAULT
        WithoutResponse -> WRITE_TYPE_NO_RESPONSE
    }

private val Priority.intValue: Int
    get() = when (this) {
        Priority.Low -> BluetoothGatt.CONNECTION_PRIORITY_LOW_POWER
        Priority.Balanced -> BluetoothGatt.CONNECTION_PRIORITY_BALANCED
        Priority.High -> BluetoothGatt.CONNECTION_PRIORITY_HIGH
    }

/** @throws GattRequestRejectedException if [BluetoothGatt.setCharacteristicNotification] returns `false`. */
private fun BluetoothGatt.setCharacteristicNotificationOrThrow(
    characteristic: PlatformCharacteristic,
    enable: Boolean,
) {
    setCharacteristicNotification(characteristic, enable) ||
            throw GattRequestRejectedException()
}

private val PlatformCharacteristic.configDescriptor: PlatformDescriptor?
    get() = descriptors.firstOrNull { clientCharacteristicConfigUuid == it.uuid }

private val PlatformCharacteristic.supportsNotify: Boolean
    get() = properties and PROPERTY_NOTIFY != 0

private val PlatformCharacteristic.supportsIndicate: Boolean
    get() = properties and PROPERTY_INDICATE != 0

private fun typeFrom(value: Int): Type = when (value) {
    DEVICE_TYPE_UNKNOWN -> Type.Unknown
    DEVICE_TYPE_CLASSIC -> Type.Classic
    DEVICE_TYPE_DUAL -> Type.DualMode
    DEVICE_TYPE_LE -> Type.LowEnergy
    else -> error("Unsupported device type: $value")
}
