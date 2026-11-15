
package com.chessvision.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat

object MoveNotifier {

    private const val CHANNEL_ID = "chess_best_move"
    private const val NOTIFICATION_ID = 2001

    fun show(
        context: Context,
        result: StockfishResult
    ) {
        if (
            Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        createChannel(context)

        val notification = NotificationCompat.Builder(
            context,
            CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle("CHESS VISION — лучший ход")
            .setContentText(
                "${result.bestMove}  •  ${result.evaluation}"
            )
            .setStyle(
                NotificationCompat.BigTextStyle()
                    .bigText(
                        "Лучший ход: ${result.bestMove}\n" +
                        "Оценка: ${result.evaluation}\n" +
                        "Глубина: ${result.depth}"
                    )
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat
            .from(context)
            .notify(NOTIFICATION_ID, notification)
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Лучшие шахматные ходы",
                NotificationManager.IMPORTANCE_HIGH
            )

            val manager = context.getSystemService(
                NotificationManager::class.java
            )

            manager.createNotificationChannel(channel)
        }
    }
}
