package app.fwchat.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import androidx.annotation.RequiresApi
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import app.fwchat.FwChatApp
import app.fwchat.MainActivity
import app.fwchat.R
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Service de premier plan (type dataSync) qui protège le processus pendant une génération: sans lui,
 * Android gèle l'app dès qu'elle passe en arrière-plan et la connexion au flux est coupée.
 *
 * Aucune logique métier: il affiche une notification discrète et s'arrête de lui-même quand le moteur n'a
 * plus de génération en cours. Il est démarré par [app.fwchat.AppContainer]. Il fonctionne même si la
 * permission POST_NOTIFICATIONS est refusée (la notification est alors invisible, le processus reste protégé).
 */
class GenerationService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var watcher: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Doit être appelé immédiatement après startForegroundService().
        try {
            ensureChannel()
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, buildNotification(), ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } catch (e: Exception) {
            // Premier plan refusé par le système: on s'arrête sans planter.
            stopSelf()
            return START_NOT_STICKY
        }
        // Un seul observateur, même si le service reçoit plusieurs commandes de démarrage.
        watcher?.cancel()
        watcher = scope.launch {
            val engine = (application as FwChatApp).container.engine
            engine.generatingChats.first { it.isEmpty() }
            stopForegroundAndSelf()
        }
        // Si le processus est tué, la génération est perdue de toute façon: pas de redémarrage automatique.
        return START_NOT_STICKY
    }

    /** Android 15: le type dataSync a un budget de temps. À l'expiration, on s'arrête proprement. */
    @RequiresApi(35)
    override fun onTimeout(startId: Int, fgsType: Int) {
        stopForegroundAndSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun stopForegroundAndSelf() {
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_REMOVE)
        stopSelf()
    }

    private fun ensureChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID, getString(R.string.generation_channel_name), NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = getString(R.string.generation_channel_description)
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_generation)
            .setContentTitle(getString(R.string.generation_notification_title))
            .setContentIntent(open)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "generation"
        const val NOTIFICATION_ID = 1
    }
}
