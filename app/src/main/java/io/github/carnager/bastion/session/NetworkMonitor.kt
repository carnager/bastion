package io.github.carnager.bastion.session

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * The phone's default network. [network] is null while offline; [changes] counts switches
 * (Wi-Fi to mobile and back), after which open connections may be dead without knowing it.
 */
class NetworkMonitor(context: Context) {
    private val manager = context.getSystemService(ConnectivityManager::class.java)
    private val _network = MutableStateFlow<Network?>(manager.activeNetwork)
    val network: StateFlow<Network?> = _network.asStateFlow()
    private val _changes = MutableStateFlow(0)
    val changes: StateFlow<Int> = _changes.asStateFlow()

    init {
        manager.registerDefaultNetworkCallback(object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                val previous = _network.value
                _network.value = network
                if (previous != null && previous != network) _changes.value++
            }

            override fun onLost(network: Network) {
                if (_network.value == network) _network.value = null
            }

            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
                _network.value = network
            }
        })
    }

    val online: Boolean get() = _network.value != null
}
