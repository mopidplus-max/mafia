package com.danya.mafia.server

import java.net.Inet4Address
import java.net.NetworkInterface
import java.util.Collections

object LocalNetwork {
    /** IPv4-адрес телефона в Wi-Fi / точке доступа. */
    fun ipv4(): String? {
        return try {
            val interfaces = Collections.list(NetworkInterface.getNetworkInterfaces())
            val preferred = interfaces.firstOrNull { it.name == "wlan0" && it.isUp }
            val ordered = (listOfNotNull(preferred) + interfaces).distinctBy { it.name }

            for (ni in ordered) {
                if (!ni.isUp || ni.isLoopback) continue
                for (address in Collections.list(ni.inetAddresses)) {
                    if (address is Inet4Address &&
                        !address.isLoopbackAddress &&
                        !address.isLinkLocalAddress &&
                        address.isSiteLocalAddress
                    ) {
                        return address.hostAddress
                    }
                }
            }
            null
        } catch (_: Exception) {
            null
        }
    }

    fun url(port: Int): String? = ipv4()?.let { "http://$it:$port" }
}
