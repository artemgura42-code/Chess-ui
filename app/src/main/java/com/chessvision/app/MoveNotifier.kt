
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
    ): String {
        val move = formatMove(result.bestMove)

        return post(
            context = context,
            title = "CHESS VISION — лучший ход",
            text = "Лучший ход: $move",
            details = "Лучший ход: $move\n" +
                "Оценка: ${result.evaluation}\n" +
                "Глубина анализа: ${result.depth}"
        )
    }

    fun showTest(context: Context): String {
        return post(
            context = context,
            title = "CHESS VISION",
            text = "Уведомления работают!",
            details = "Тестовое уведомление успешно доставлено."
        )
    }

    fun formatMove(move: String): String {
        if (!move.matches(Regex("[a-h][1-8][a-h][1-8][qrbn]?"))) {
            return move
        }

        val from = move.substring(0, 2)
        val to = move.substring(2, 4)

        val promotion = if (move.length == 5) {
            val piece = when (move[4]) {
                'q' -> "Ф"
                'r' -> "Л"
                'b' -> "С"
                'n' -> "К"
                else -> ""
            }
            "=$piece"
        } else {
            ""
        }

        return "$from–$to$promotion"
    }

    private fun post(
        context: Context,
        title: String,
        text: String,
        details: String
    ): String {

        if (
            Build.VERSION.SDK_INT >= 33 &&
            context.checkSelfPermission(
                Manifest.permission.POST_NOTIFICATIONS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            return "Нет разрешения Android на уведомления"
        }

        val notificationManager =
            NotificationManagerCompat.from(context)

        if (!notificationManager.areNotificationsEnabled()) {
            return "Уведомления приложения отключены в настройках Android"
        }

        createChannel(context)

        if (Build.VERSION.SDK_INT >= 26) {
            val manager = context.getSystemService(
                NotificationManager::class.java
            )

            val channel = manager.getNotificationChannel(CHANNEL_ID)

            if (
                channel == null ||
                channel.importance == NotificationManager.IMPORTANCE_NONE
            ) {
                return "Канал уведомлений заблокирован в настройках Android"
            }
        }

        val notification = NotificationCompat.Builder(
            context,
            CHANNEL_ID
        )
            .setSmallIcon(android.R.drawable.ic_menu_info_details)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(details)
            )
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setAutoCancel(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .build()

        return try {
            notificationManager.notify(
                NOTIFICATION_ID,
                notification
            )
            "Уведомление отправлено"
        } catch (e: SecurityException) {
            "Android заблокировал уведомление: ${e.message}"
        } catch (e: Exception) {
            "Ошибка отправки уведомления: ${e.message}"
        }
    }

    private fun createChannel(context: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Лучшие шахматные ходы",
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "Лучшие ходы Stockfish"
                enableVibration(true)
            }

            val manager = context.getSystemService(
                NotificationManager::class.java
            )

            manager.createNotificationChannel(channel)
        }
    }
}
