package ph.chefdonn.hub

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.IBinder
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import io.ktor.server.engine.ApplicationEngine
import ph.chefdonn.hubserver.HubEngine
import ph.chefdonn.hubserver.SessionRegistry
import ph.chefdonn.hubserver.db.HubDatabase
import ph.chefdonn.hubserver.hubDatabase
import ph.chefdonn.hubserver.startHubServer
import ph.chefdonn.protocol.DEFAULT_HUB_PORT
import ph.chefdonn.protocol.NSD_SERVICE_TYPE

/**
 * Process-wide handle to the running engine. The admin UI talks to the engine
 * directly (same process); staff devices go through the WebSocket server.
 */
object HubRuntime {
    @Volatile var engine: HubEngine? = null
    @Volatile var registry: SessionRegistry? = null

    fun ensureStarted(context: Context) {
        if (engine == null) {
            context.startForegroundService(Intent(context, HubService::class.java))
        }
    }
}

class HubService : Service() {

    private var server: ApplicationEngine? = null
    private var nsd: NsdManager? = null
    private var nsdListener: NsdManager.RegistrationListener? = null

    override fun onCreate() {
        super.onCreate()
        startForeground(NOTIF_ID, buildNotification())

        val driver = AndroidSqliteDriver(HubDatabase.Schema, this, "chefdonn-hub.db")
        val engine = HubEngine(hubDatabase(driver))
        val registry = SessionRegistry()
        HubRuntime.engine = engine
        HubRuntime.registry = registry
        server = startHubServer(engine, DEFAULT_HUB_PORT, "Chef Donn's Hub", registry)
        registerNsd()
    }

    private fun registerNsd() {
        nsd = getSystemService(NSD_SERVICE) as NsdManager
        val info = NsdServiceInfo().apply {
            serviceName = "ChefDonnHub"
            serviceType = NSD_SERVICE_TYPE
            port = DEFAULT_HUB_PORT
        }
        nsdListener = object : NsdManager.RegistrationListener {
            override fun onServiceRegistered(i: NsdServiceInfo) {}
            override fun onRegistrationFailed(i: NsdServiceInfo, err: Int) {}
            override fun onServiceUnregistered(i: NsdServiceInfo) {}
            override fun onUnregistrationFailed(i: NsdServiceInfo, err: Int) {}
        }
        nsd?.registerService(info, NsdManager.PROTOCOL_DNS_SD, nsdListener)
    }

    private fun buildNotification(): Notification {
        val channel = NotificationChannel(CHANNEL_ID, "Hub", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        return Notification.Builder(this, CHANNEL_ID)
            .setContentTitle("Chef Donn's Hub")
            .setContentText("Order stream running on port $DEFAULT_HUB_PORT")
            .setSmallIcon(android.R.drawable.stat_notify_sync_noanim)
            .setOngoing(true)
            .build()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        nsdListener?.let { nsd?.unregisterService(it) }
        server?.stop(500, 1000)
        HubRuntime.engine = null
        HubRuntime.registry = null
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private companion object {
        const val CHANNEL_ID = "hub"
        const val NOTIF_ID = 1
    }
}
