package bluetoothchat.presentation

import bluetoothchat.domain.chat.BluetoothDevice
import bluetoothchat.domain.chat.BluetoothMessage

data class BluetoothUiState(
    val scannedDevices: List<BluetoothDevice> = emptyList(),
    val pairedDevices: List<BluetoothDevice> = emptyList(),
    val isConnected: Boolean = false,
    val isConnecting: Boolean = false,
    val errorMessage: String? = null,
    val currentConnectedDevice: BluetoothDevice? = null,
    val messages: List<BluetoothMessage> = emptyList()
)