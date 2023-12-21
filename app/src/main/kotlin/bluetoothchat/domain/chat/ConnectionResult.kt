package bluetoothchat.domain.chat

sealed interface ConnectionResult {
    data class ConnectionEstablished(val bluetoothDevice: BluetoothDevice?): ConnectionResult
    data class TransferSucceeded(val message: BluetoothMessage): ConnectionResult
    data class Error(val message: String): ConnectionResult
}