package com.freecaster.app

import android.content.Context
import android.net.ConnectivityManager
import java.net.Inet4Address

object NetUtils {
    /** IP телефона в домашній мережі (його бачить Chromecast). */
    fun localIpv4(context: Context): String? {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return null
        val props = cm.getLinkProperties(cm.activeNetwork) ?: return null
        return props.linkAddresses
            .map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }
            ?.hostAddress
    }
}
