package bluetoothchat.data.chat

import android.Manifest
import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothServerSocket
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.util.Log
import bluetoothchat.domain.chat.BluetoothController
import bluetoothchat.domain.chat.BluetoothDeviceDomain
import bluetoothchat.domain.chat.BluetoothMessage
import bluetoothchat.domain.chat.ConnectionResult
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onCompletion
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.IOException
import java.util.UUID

@SuppressLint("MissingPermission")
class AndroidBluetoothController(
    private val context: Context
) : BluetoothController {

    // This is null when the device doesn't support Bluetooth
    private val bluetoothManager: BluetoothManager? by lazy {
        context.getSystemService(BluetoothManager::class.java)
    }

    private val bluetoothAdapter: BluetoothAdapter? by lazy {
        bluetoothManager?.adapter
    }

    private val _isConnected = MutableStateFlow(false)
    override val isConnected = _isConnected.asStateFlow()

    private val _scannedDevices = MutableStateFlow<List<BluetoothDeviceDomain>>(emptyList())
    override val scannedDevices = _scannedDevices.asStateFlow()

    private val _pairedDevices = MutableStateFlow<List<BluetoothDeviceDomain>>(emptyList())
    override val pairedDevices = _pairedDevices.asStateFlow()

    private val _error = MutableSharedFlow<String>()
    override val error = _error.asSharedFlow()

    private val foundDeviceReceiver = FoundDeviceReceiver { device ->
        Log.d(
            "BluetoothController",
            "Device found ${device.type}, ${device.name}, ${device.address},"
        )
        _scannedDevices.update { devices ->
            val newDevice = device.toBluetoothDeviceDomain()
            if (newDevice in devices) {
                devices
            } else {
                devices + newDevice
            }
        }
    }

    private val bluetoothStateReceiver = BluetoothStateReceiver { isConnected, bluetoothDevice ->
        Log.d("BluetoothController", "Device received $isConnected , $bluetoothDevice")
        if (bluetoothAdapter?.bondedDevices?.contains(bluetoothDevice) == true) {
            _isConnected.update { isConnected }
        } else {
            CoroutineScope(Dispatchers.IO).launch {
                _error.tryEmit("Can't connect to a non-paired device")
            }
        }
    }

    private var currentServerSocket: BluetoothServerSocket? = null
    private var currentClientSocket: BluetoothSocket? = null
    private var dataTransferService: BluetoothDataTransferService? = null

    init {
        updatePairedDevices()
        context.registerReceiver(
            bluetoothStateReceiver,
            IntentFilter().apply {
                addAction(BluetoothAdapter.ACTION_CONNECTION_STATE_CHANGED)
                addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
                addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            }
        )
    }

    override fun startDiscovery() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            return
        }

        context.registerReceiver(
            foundDeviceReceiver,
            IntentFilter(BluetoothDevice.ACTION_FOUND)
        )
        updatePairedDevices()
        val discoveryResult = bluetoothAdapter?.startDiscovery() ?: false
        if (!discoveryResult) {
            CoroutineScope(Dispatchers.IO).launch {
                _error.tryEmit("Starting discovery failed!")
            }
        }
    }

    override fun stopDiscovery() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_SCAN)) {
            return
        }

        bluetoothAdapter?.cancelDiscovery()
    }

    override fun startBluetoothServer(): Flow<ConnectionResult> = flow {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            throw SecurityException("No BLUETOOTH_CONNECT Permission")
        }

        currentServerSocket = bluetoothAdapter?.listenUsingRfcommWithServiceRecord(
            "chat_service",
            UUID.fromString(SERVICE_UUID)
        )

        var shouldLoop = true
        while (shouldLoop) {
            currentClientSocket = try {
                currentServerSocket?.accept()
            } catch (e: IOException) {
                shouldLoop = false
                null
            }
            Log.d("BluetoothController", "Connection Established")

            emit(ConnectionResult.ConnectionEstablished(currentClientSocket?.remoteDevice?.toBluetoothDeviceDomain()))
            currentClientSocket?.let { socket ->
                currentServerSocket?.close()
                val service = BluetoothDataTransferService(socket)
                dataTransferService = service
                emitAll(
                    service
                        .listenForIncomingMessages()
                        .map { message -> ConnectionResult.TransferSucceeded(message) }
                )
            }
        }
    }.onCompletion {
        closeConnection()
        updatePairedDevices()
    }.flowOn(Dispatchers.IO)

    override fun connectToDevice(device: BluetoothDeviceDomain): Flow<ConnectionResult> = flow {
        Log.d("BluetoothController", "Connecting to device $device")

        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            throw SecurityException("No BLUETOOTH_CONNECT Permission")
        }

        val bluetoothDevice = bluetoothAdapter
            ?.getRemoteDevice(device.address)

        currentClientSocket = bluetoothDevice
            ?.createRfcommSocketToServiceRecord(
                UUID.fromString(SERVICE_UUID)
            )

        stopDiscovery()
        currentClientSocket?.let { socket ->
            try {
                socket.connect()
                Log.d("BluetoothController", "Connected to device $device")
                emit(ConnectionResult.ConnectionEstablished(bluetoothDevice?.toBluetoothDeviceDomain()))
                BluetoothDataTransferService(socket).also { service ->
                    dataTransferService = service
                    emitAll(
                        service
                            .listenForIncomingMessages()
                            .map { message -> ConnectionResult.TransferSucceeded(message) }
                    )
                }
            } catch (e: Exception) {
                socket.close()
                currentClientSocket = null
                e.printStackTrace()
                Log.e("BluetoothController", "Connection was interrupted!")

                emit(ConnectionResult.Error("Connection was interrupted"))
            }
        }
    }.onCompletion {
        closeConnection()
        updatePairedDevices()
    }.flowOn(Dispatchers.IO)

    override suspend fun trySendMessage(message: String): BluetoothMessage? {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            return null
        }

        val service = dataTransferService ?: return null

        val bluetoothMessage = BluetoothMessage(
            senderName = bluetoothAdapter?.name ?: "Unknown",
            message = message,
            isFromLocalUser = true
        )

        service.sendMessage(bluetoothMessage.toByteArray())
        return bluetoothMessage
    }

    override fun closeConnection() {
        currentClientSocket?.close()
        currentServerSocket?.close()
        currentClientSocket = null
        currentServerSocket = null
    }

    override fun release() {
        context.unregisterReceiver(foundDeviceReceiver)
        context.unregisterReceiver(bluetoothStateReceiver)
        closeConnection()
    }

    private fun updatePairedDevices() {
        if (!hasPermission(Manifest.permission.BLUETOOTH_CONNECT)) {
            return
        }

        bluetoothAdapter
            ?.bondedDevices
            ?.map(BluetoothDevice::toBluetoothDeviceDomain)
            ?.also { devices ->
                Log.d("BluetoothController", "Paired Devices= $devices")
                _pairedDevices.update { devices }
            }
    }

    private fun hasPermission(permission: String): Boolean {
        return context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    }

    companion object {
        private const val SERVICE_UUID = "8d7d98ff-96a8-415a-b107-d6d2ad2a531c"
    }
}