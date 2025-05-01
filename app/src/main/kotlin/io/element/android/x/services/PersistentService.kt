package io.element.android.x.services

import android.app.Service
import android.content.Intent
import android.os.IBinder
import io.element.android.x.notifications.NotificationUtils

class PersistentService : Service() {
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        NotificationUtils.initSystemChannel(this)
        startForeground(
            NotificationUtils.PERSISTENT_NOTIFICATION_ID,
            NotificationUtils.buildPersistentNotification(this)
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        return START_STICKY
    }
	private fun checkManufacturerRestrictions() {
	    when (Build.MANUFACTURER.lowercase()) {
	        "xiaomi" -> {
	            Intent("miui.intent.action.OP_AUTO_START").apply {
	                setClassName(
	                    "com.miui.securitycenter", 
	                    "com.miui.permcenter.autostart.AutoStartManagementActivity"
	                )
	                startActivity(this)
	            }
	        }
	        "huawei" -> {
	            // 华为后台启动管理
	        }
	    }
	}
}