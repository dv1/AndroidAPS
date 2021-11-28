package info.nightscout.androidaps.plugins.pump.combov2.stateStore

import info.nightscout.comboctl.base.BluetoothAddress
import info.nightscout.comboctl.base.InvariantPumpData
import info.nightscout.comboctl.base.Nonce
import info.nightscout.comboctl.base.PumpStateStore
import info.nightscout.comboctl.base.toBluetoothAddress
import info.nightscout.comboctl.base.toCipher
import info.nightscout.comboctl.base.toNonce
import info.nightscout.shared.sharedPreferences.SP
import info.nightscout.shared.sharedPreferences.SPDelegateInt
import info.nightscout.shared.sharedPreferences.SPDelegateString

/**
 * AndroidAPS [SP] based pump state store.
 *
 * This store is set up to contain a single paired pump. AndroidAPS is not
 * designed to handle multiple pumps, so this simplification makes sense.
 * This affects all accessors, which
 */
class SPPumpStateStore(private val sp: SP) : PumpStateStore {
    private var btAddress: String
        by SPDelegateString(sp, BT_ADDRESS_KEY, "")

    // The nonce is updated with commit instead of apply to make sure
    // is atomically written to storage synchronously, minimizing
    // the likelihood that it could be lost due to app crashes etc.
    // It is very important to not lose the nonce, hence that choice.
    private var nonceString: String
        by SPDelegateString(sp, NONCE_KEY, Nonce.nullNonce().toString(), commit = true)

    private var cpCipherString: String
        by SPDelegateString(sp, CP_CIPHER_KEY, "")
    private var pcCipherString: String
        by SPDelegateString(sp, PC_CIPHER_KEY, "")
    private var keyResponseAddressInt: Int
        by SPDelegateInt(sp, KEY_RESPONSE_ADDRESS_KEY, 0)
    private var pumpID: String
        by SPDelegateString(sp, PUMP_ID_KEY, "")

    override fun createPumpState(pumpAddress: BluetoothAddress, invariantPumpData: InvariantPumpData) {
        // Write these values via edit() instead of using the delegates
        // above to be able to write all of them with a single commit.
        sp.edit(commit = true) {
            putString(BT_ADDRESS_KEY, pumpAddress.toString().uppercase())
            putString(CP_CIPHER_KEY, invariantPumpData.clientPumpCipher.toString())
            putString(PC_CIPHER_KEY, invariantPumpData.pumpClientCipher.toString())
            putInt(KEY_RESPONSE_ADDRESS_KEY, invariantPumpData.keyResponseAddress.toInt() and 0xFF)
            putString(PUMP_ID_KEY, invariantPumpData.pumpID)
        }
    }

    override fun deletePumpState(pumpAddress: BluetoothAddress): Boolean {
        val hasState = sp.contains(NONCE_KEY)

        sp.edit(commit = true) {
            remove(BT_ADDRESS_KEY)
            remove(NONCE_KEY)
            remove(CP_CIPHER_KEY)
            remove(PC_CIPHER_KEY)
            remove(KEY_RESPONSE_ADDRESS_KEY)
        }

        return hasState
    }

    override fun hasPumpState(pumpAddress: BluetoothAddress) =
        sp.contains(NONCE_KEY)

    override fun getAvailablePumpStateAddresses() =
        if (btAddress.isBlank()) setOf() else setOf(btAddress.toBluetoothAddress())

    override fun getInvariantPumpData(pumpAddress: BluetoothAddress) = InvariantPumpData(
        clientPumpCipher = cpCipherString.toCipher(),
        pumpClientCipher = pcCipherString.toCipher(),
        keyResponseAddress = keyResponseAddressInt.toByte(),
        pumpID = pumpID
    )

    override fun getCurrentTxNonce(pumpAddress: BluetoothAddress) = nonceString.toNonce()

    override fun setCurrentTxNonce(pumpAddress: BluetoothAddress, currentTxNonce: Nonce) {
        nonceString = currentTxNonce.toString()
    }

    companion object {
        const val BT_ADDRESS_KEY = "combov2-bt-address-key"
        const val NONCE_KEY = "combov2-nonce-key"
        const val CP_CIPHER_KEY = "combov2-cp-cipher-key"
        const val PC_CIPHER_KEY = "combov2-pc-cipher-key"
        const val KEY_RESPONSE_ADDRESS_KEY = "combov2-key-response-address-key"
        const val PUMP_ID_KEY = "combov2-pump-id-key"
    }
}
