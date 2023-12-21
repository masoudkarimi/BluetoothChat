package bluetoothchat.data.chat

import android.bluetooth.BluetoothSocket
import bluetoothchat.domain.chat.BluetoothMessage
import bluetoothchat.domain.chat.TransferFailedException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.withContext
import java.io.IOException

class BluetoothDataTransferService(
    private val socket: BluetoothSocket
) {

    fun listenForIncomingMessages(): Flow<BluetoothMessage> = flow {
        if (!socket.isConnected) {
            return@flow
        }

        val buffer = ByteArray(1024)
        while (true) {
            val byteCount = try {
                socket.inputStream.read(buffer)
            } catch (e: IOException) {
                e.printStackTrace()
                throw TransferFailedException()
            }

            val message = buffer.decodeToString(endIndex = byteCount).toBluetoothMessage(
                isFromLocalUser = false
            )

            emit(message)
        }
    }.flowOn(Dispatchers.IO)

    suspend fun sendMessage(message: ByteArray): Boolean {
        return withContext(Dispatchers.IO) {
            try {
                socket.outputStream.write(message)
            } catch (e: IOException) {
                e.printStackTrace()
                return@withContext false
            }

            true
        }
    }
}