package com.signify.hue.flutterreactiveble.model

import com.polidea.rxandroidble2.RxBleConnection

@Suppress("Detekt.MagicNumber")
enum class ConnectionState(val code: Int) {
    CONNECTING(0),
    CONNECTED(1),
    DISCONNECTING(2),
    DISCONNECTED(3),
    UNKNOWN(4),
    FORCEDISCONNECTED(5),
    BONDING(6),
    BONDED(7),
    UPDATE(8)
}

fun RxBleConnection.RxBleConnectionState.toConnectionState(): ConnectionState =
    when (this.name) {
        "DISCONNECTED" -> ConnectionState.DISCONNECTED
        "CONNECTING" -> ConnectionState.CONNECTING
        "CONNECTED" -> ConnectionState.CONNECTED
        "DISCONNECTING" -> ConnectionState.DISCONNECTING
        "FORCEDISCONNECTED" -> ConnectionState.FORCEDISCONNECTED
        "BONDING" -> ConnectionState.BONDING
        "BONDED" -> ConnectionState.BONDED
        "UPDATE" -> ConnectionState.UPDATE
        else -> ConnectionState.UNKNOWN
    }
