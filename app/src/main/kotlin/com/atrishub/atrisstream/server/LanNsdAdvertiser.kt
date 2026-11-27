package com.atrishub.atrisstream.server

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build

/**
 * Registers the TV's LAN sync HTTP server via Android's [NsdManager] so
 * AtrisStream Desktop can discover it automatically over local Wi-Fi.
 *
 * All NSD operations are wrapped in [runCatching] so devices or emulators
 * without mDNS support never crash.
 */
class LanNsdAdvertiser(private val context: Context) {

    private val lock = Any()
    private var nsdManager: NsdManager? = null
    private var registrationListener: NsdManager.RegistrationListener? = null

    fun register(port: Int) {
        synchronized(lock) {
            if (registrationListener != null) return
            runCatching {
                val manager = context.getSystemService(Context.NSD_SERVICE) as? NsdManager ?: return
                val modelName = Build.MODEL?.takeIf { it.isNotBlank() } ?: "Android TV"
                val serviceInfo = NsdServiceInfo().apply {
                    serviceName = "AtrisStream-$modelName"
                    serviceType = SERVICE_TYPE
                    setPort(port)
                }
                val listener = object : NsdManager.RegistrationListener {
                    override fun onServiceRegistered(info: NsdServiceInfo) = Unit
                    override fun onRegistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                    override fun onServiceUnregistered(info: NsdServiceInfo) = Unit
                    override fun onUnregistrationFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                }
                nsdManager = manager
                registrationListener = listener
                manager.registerService(serviceInfo, NsdManager.PROTOCOL_DNS_SD, listener)
            }
        }
    }

    fun unregister() {
        synchronized(lock) {
            val listener = registrationListener ?: return
            val manager = nsdManager
            registrationListener = null
            nsdManager = null
            runCatching {
                manager?.unregisterService(listener)
            }
        }
    }

    companion object {
        const val SERVICE_TYPE = "_atrisstream._tcp."
    }
}
