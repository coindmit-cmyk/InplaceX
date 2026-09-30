package com.mirkori.inplacex.platform.online

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities

class AndroidConnectivityGate(context: Context) : ConnectivityGate {
    private val connectivityManager =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    override fun isOnline(): Boolean {
        val manager = connectivityManager ?: return false
        val network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
