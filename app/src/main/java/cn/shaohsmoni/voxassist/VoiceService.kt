package cn.shaohsmoni.voxassist

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.os.Build
import android.os.IBinder

/** Foreground service host: keeps the mic loop alive with a status-bar notification. */
class VoiceService : Service() {

    private var engine: VoiceEngine? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val channelId = "vox"
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(channelId, "语音助手", NotificationManager.IMPORTANCE_LOW))
        }
        val n: Notification = android.app.Notification.Builder(this, channelId)
            .setContentTitle("Vox助手运行中")
            .setContentText("说\"你好小爱\"开始对话")
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()
        startForeground(1, n)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (engine == null) {
            val act = runCatching {
                // recover MainActivity reference for logging via application context
                null
            }
            engine = VoiceEngine(applicationContext, object : VoiceEngine.Callbacks {
                override fun onState(state: String) {
                    android.util.Log.i("VoxAssist", "state=$state")
                    AppLog.add("· $state\n")
                }
                override fun onPartial(text: String) { android.util.Log.d("VoxAssist", "partial=$text") }
                override fun onFinal(text: String) {
                    android.util.Log.i("VoxAssist", "final=$text")
                    AppLog.add("你说: $text\n")
                }
                override fun onAnswer(text: String) {
                    android.util.Log.i("VoxAssist", "answer=$text")
                    AppLog.add("她答: $text\n")
                }
                override fun onError(msg: String) {
                    android.util.Log.e("VoxAssist", "error=$msg")
                    AppLog.add("✗ $msg\n")
                }
            })
            engine?.start()
        }
        return START_STICKY
    }

    override fun onDestroy() {
        engine?.stop()
        engine = null
        super.onDestroy()
    }
}
