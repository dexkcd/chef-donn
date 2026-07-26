package ph.chefdonn.app

import android.app.Application
import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ph.chefdonn.client.ChefDonnClient
import ph.chefdonn.domain.Command
import ph.chefdonn.domain.Role
import ph.chefdonn.protocol.DEFAULT_HUB_PORT
import ph.chefdonn.protocol.NSD_SERVICE_TYPE
import java.util.UUID

private val Context.dataStore by preferencesDataStore("chefdonn")

private val KEY_DEVICE_ID = stringPreferencesKey("deviceId")
private val KEY_DEVICE_NAME = stringPreferencesKey("deviceName")
private val KEY_ROLE = stringPreferencesKey("role")
private val KEY_HUB_HOST = stringPreferencesKey("hubHost")

data class DeviceConfig(
    val deviceId: String,
    val deviceName: String,
    val role: Role,
    val hubHost: String,
)

data class DiscoveredHub(val name: String, val host: String, val port: Int)

class AppViewModel(app: Application) : AndroidViewModel(app) {

    private val _config = MutableStateFlow<DeviceConfig?>(null)
    val config: StateFlow<DeviceConfig?> = _config.asStateFlow()

    private val _loaded = MutableStateFlow(false)
    val loaded: StateFlow<Boolean> = _loaded.asStateFlow()

    private val _client = MutableStateFlow<ChefDonnClient?>(null)
    val client: StateFlow<ChefDonnClient?> = _client.asStateFlow()

    private val _hubs = MutableStateFlow<List<DiscoveredHub>>(emptyList())
    val discoveredHubs: StateFlow<List<DiscoveredHub>> = _hubs.asStateFlow()

    private var discoveryListener: NsdManager.DiscoveryListener? = null

    init {
        viewModelScope.launch {
            val prefs = app.dataStore.data.first()
            val role = prefs[KEY_ROLE]?.let { runCatching { Role.valueOf(it) }.getOrNull() }
            val host = prefs[KEY_HUB_HOST]
            if (role != null && host != null) {
                val cfg = DeviceConfig(
                    deviceId = prefs[KEY_DEVICE_ID] ?: UUID.randomUUID().toString(),
                    deviceName = prefs[KEY_DEVICE_NAME] ?: "Device",
                    role = role,
                    hubHost = host,
                )
                _config.value = cfg
                startClient(cfg)
            }
            _loaded.value = true
        }
    }

    fun saveConfig(role: Role, deviceName: String, hubHost: String) {
        viewModelScope.launch {
            val app = getApplication<Application>()
            val existingId = app.dataStore.data.first()[KEY_DEVICE_ID]
            val deviceId = existingId ?: UUID.randomUUID().toString()
            app.dataStore.edit {
                it[KEY_DEVICE_ID] = deviceId
                it[KEY_DEVICE_NAME] = deviceName
                it[KEY_ROLE] = role.name
                it[KEY_HUB_HOST] = hubHost
            }
            val cfg = DeviceConfig(deviceId, deviceName, role, hubHost)
            _config.value = cfg
            startClient(cfg)
        }
    }

    /** Forget role + hub (device being reassigned); order data lives on the hub. */
    fun resetConfig() {
        viewModelScope.launch {
            getApplication<Application>().dataStore.edit {
                it.remove(KEY_ROLE)
                it.remove(KEY_HUB_HOST)
            }
            _client.value?.close()
            _client.value = null
            _config.value = null
        }
    }

    private fun startClient(cfg: DeviceConfig) {
        _client.value?.close()
        val c = ChefDonnClient(
            viewModelScope, cfg.deviceId, cfg.deviceName, cfg.role,
            store = PersistentPendingStore(getApplication()),
        )
        c.connect(cfg.hubHost, DEFAULT_HUB_PORT)
        _client.value = c
    }

    fun submit(command: Command) {
        val c = _client.value ?: return
        viewModelScope.launch { c.submit(command) }
    }

    fun startDiscovery() {
        stopDiscovery()
        val nsd = getApplication<Application>().getSystemService(Context.NSD_SERVICE) as NsdManager
        val listener = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(type: String) {}
            override fun onDiscoveryStopped(type: String) {}
            override fun onStartDiscoveryFailed(type: String, err: Int) {}
            override fun onStopDiscoveryFailed(type: String, err: Int) {}
            override fun onServiceLost(info: NsdServiceInfo) {
                _hubs.value = _hubs.value.filter { it.name != info.serviceName }
            }
            override fun onServiceFound(info: NsdServiceInfo) {
                @Suppress("DEPRECATION")
                nsd.resolveService(info, object : NsdManager.ResolveListener {
                    override fun onResolveFailed(i: NsdServiceInfo, err: Int) {}
                    override fun onServiceResolved(i: NsdServiceInfo) {
                        val host = i.host?.hostAddress ?: return
                        val hub = DiscoveredHub(i.serviceName, host, i.port)
                        _hubs.value = _hubs.value.filter { it.name != hub.name } + hub
                    }
                })
            }
        }
        discoveryListener = listener
        nsd.discoverServices(NSD_SERVICE_TYPE, NsdManager.PROTOCOL_DNS_SD, listener)
    }

    fun stopDiscovery() {
        val nsd = getApplication<Application>().getSystemService(Context.NSD_SERVICE) as NsdManager
        discoveryListener?.let { runCatching { nsd.stopServiceDiscovery(it) } }
        discoveryListener = null
    }

    override fun onCleared() {
        stopDiscovery()
        _client.value?.close()
    }
}
