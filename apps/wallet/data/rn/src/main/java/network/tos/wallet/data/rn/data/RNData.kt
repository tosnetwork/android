package network.tos.wallet.data.rn.data

import org.json.JSONObject

abstract class RNData {

    abstract fun toJSON(): JSONObject
}