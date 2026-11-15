package com.chessvision.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.Executors

class ScreenCaptureService : Service() {

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"
        const val ACTION_STOP = "com.chessvision.app.STOP_CAPTURE"
        const val ACTION_STATE_UPDATE =
            "com.chessvision.app.CHESS_STATE_UPDATE"

        const val EXTRA_MESSAGE = "message"
        const val EXTRA_TRACKER_MESSAGE = "tracker_message"
        const val EXTRA_ENGINE_MESSAGE = "engine_message"
        const val EXTRA_FEN = "fen"
        const val EXTRA_PLAYER_COLOR = "player_color"
        const val EXTRA_GAME_STARTED = "game_started"
        const val EXTRA_TOTAL_MOVES = "total_moves"
        const val EXTRA_BEST_MOVE = "best_move"
        const val EXTRA_EVALUATION = "evaluation"
        const val EXTRA_IS_DETECTING = "is_detecting"

        private const val CHANNEL_ID = "chess_capture_channel"
        private const val NOTIFICATION_ID = 1001
        private const val FRAME_INTERVAL_MS = 1000L
    }

    private var mediaProjection: MediaProjection? = null
    private var imageReader: ImageReader? = null
    private var virtualDisplay: VirtualDisplay? = null

    private val mainHandler = Handler(Looper.getMainLooper())

    // Распознавание выполняется отдельно от главного потока Android.
    private val processingExecutor = Executors.newSingleThreadExecutor()

    // Stockfish работает отдельно, чтобы не задерживать распознавание кадров.
    private val engineExecutor = Executors.newSingleThreadExecutor()

    private var recognizer: ChessPieceRecognizer? = null
    private val tracker = ChessGameTracker()

    private var lastQueuedAt = 0L
    private var candidatePlacement = ""
    private var candidateCount = 0
    private var lastProcessedPlacement = ""

    private var gameStarted = false
    private var trackedFen: String? = null
    private var trackedPlayerColor: String? = null
    private var totalMoves = 0

    @Volatile
    private var latestFen: String? = null

    @Volatile
    private var lastSubmittedFen = ""

    private var trackerMessage = "Ожидаю начальную позицию партии"
    private var engineMessage = "Stockfish ожидает начала партии"
    private var lastBestMove = ""
    private var lastEvaluation = ""
    private var recognitionMessage = "Ожидание захвата экрана"

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
    }

    override fun onStartCommand(
        intent: Intent?,
        flags: Int,
        startId: Int
    ): Int {

        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }

        // Не создаём второй MediaProjection при повторном запуске сервиса.
        if (mediaProjection != null) {
            return START_NOT_STICKY
        }

        val notification = createNotification()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                android.content.pm.ServiceInfo
                    .FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }

        val resultCode = intent?.getIntExtra(EXTRA_RESULT_CODE, 0)
            ?: run {
                stopSelf()
                return START_NOT_STICKY
            }

        val data: Intent? = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(EXTRA_DATA, Intent::class.java)
        } else {
            @Suppress("DEPRECATION")
            intent.getParcelableExtra(EXTRA_DATA)
        }

        if (data == null) {
            stopSelf()
            return START_NOT_STICKY
        }

        try {
            val manager = getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            val projection = manager.getMediaProjection(resultCode, data)

            if (projection == null) {
                stopSelf()
                return START_NOT_STICKY
            }

            mediaProjection = projection
            projection.registerCallback(
                projectionCallback,
                mainHandler
            )

            val metrics = resources.displayMetrics
            val width = metrics.widthPixels
            val height = metrics.heightPixels
            val density = metrics.densityDpi

            imageReader = ImageReader.newInstance(
                width,
                height,
                PixelFormat.RGBA_8888,
                2
            )

            imageReader?.setOnImageAvailableListener(
                { reader ->
                    receiveFrame(reader)
                },
                mainHandler
            )

            virtualDisplay = projection.createVirtualDisplay(
                "ChessVisionCapture",
                width,
                height,
                density,
                DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                imageReader?.surface,
                null,
                mainHandler
            )

            recognitionMessage = "Захват активен. Ищу шахматную доску..."
            publishState()

        } catch (e: Exception) {
            e.printStackTrace()
            recognitionMessage =
                "Ошибка запуска захвата: ${e.message}"
            publishState()
            stopSelf()
        }

        return START_NOT_STICKY
    }

    /**
     * Получает кадр с ImageReader.
     * Частота обработки ограничена одним кадром в секунду.
     */
    private fun receiveFrame(reader: ImageReader) {
        val now = System.currentTimeMillis()

        if (now - lastQueuedAt < FRAME_INTERVAL_MS) {
            val skipped = reader.acquireLatestImage()
            skipped?.close()
            return
        }

        val image = reader.acquireLatestImage() ?: return
        lastQueuedAt = now

        val bitmap = try {
            imageToBitmap(image)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        } finally {
            image.close()
        }

        if (bitmap == null) return

        try {
            processingExecutor.execute {
                try {
                    saveFrame(bitmap)
                    processFrame(bitmap)
                } catch (e: Exception) {
                    e.printStackTrace()
                    recognitionMessage =
                        "Ошибка обработки кадра: ${e.message}"
                    publishState()
                } finally {
                    if (!bitmap.isRecycled) {
                        bitmap.recycle()
                    }
                }
            }
        } catch (e: Exception) {
            if (!bitmap.isRecycled) {
                bitmap.recycle()
            }
        }
    }

    /**
     * Преобразует ImageReader Image в обычный Bitmap.
     */
    private fun imageToBitmap(image: Image): Bitmap {
        val plane = image.planes[0]
        val buffer = plane.buffer
        val pixelStride = plane.pixelStride
        val rowStride = plane.rowStride
        val rowPadding = rowStride - pixelStride * image.width

        val paddedWidth =
            image.width + rowPadding / pixelStride

        val paddedBitmap = Bitmap.createBitmap(
            paddedWidth,
            image.height,
            Bitmap.Config.ARGB_8888
        )

        buffer.rewind()
        paddedBitmap.copyPixelsFromBuffer(buffer)

        val result = Bitmap.createBitmap(
            paddedBitmap,
            0,
            0,
            image.width,
            image.height
        )

        if (result !== paddedBitmap) {
            paddedBitmap.recycle()
        }

        return result
    }

    /**
     * Сохраняет последний кадр для превью в MainActivity.
     */
    private fun saveFrame(bitmap: Bitmap) {
        val file = File(filesDir, "last_screen_capture.png")

        FileOutputStream(file).use { output ->
            bitmap.compress(
                Bitmap.CompressFormat.PNG,
                100,
                output
            )
        }
    }

    /**
     * Полный цикл распознавания и отслеживания позиции.
     */
    private fun processFrame(source: Bitmap) {

        val currentRecognizer = recognizer ?: run {
            val created = ChessPieceRecognizer(applicationContext)
            recognizer = created
            created
        }

        if (!ChessBoardDetector.initialize()) {
            recognitionMessage = "Ошибка инициализации OpenCV"
            publishState()
            return
        }

        val bounds = ChessBoardDetector.detect(source)

        if (bounds == null) {
            recognitionMessage =
                "Доска не найдена. Продолжаю поиск..."
            publishState()
            return
        }

        val left = bounds.x.coerceIn(0, source.width - 1)
        val top = bounds.y.coerceIn(0, source.height - 1)

        val width = bounds.width.coerceAtMost(
            source.width - left
        )

        val height = bounds.height.coerceAtMost(
            source.height - top
        )

        if (width <= 0 || height <= 0) {
            recognitionMessage = "Некорректные размеры доски"
            publishState()
            return
        }

        val crop = Bitmap.createBitmap(
            source,
            left,
            top,
            width,
            height
        )

        val recognized = try {
            currentRecognizer.recognize(crop)
        } finally {
            crop.recycle()
        }

        recognitionMessage = if (recognized.reliable) {
            "Позиция распознана. Уверенность: ${
                (recognized.meanConfidence * 100).toInt()
            }%"
        } else {
            "Недостаточная уверенность распознавания"
        }

        if (!recognized.reliable) {
            candidatePlacement = ""
            candidateCount = 0
            publishState()
            return
        }

        val placement = recognized.placement

        if (placement == candidatePlacement) {
            candidateCount++
        } else {
            candidatePlacement = placement
            candidateCount = 1
        }

        // Подтверждаем позицию двумя одинаковыми распознаваниями.
        if (
            candidateCount >= 2 &&
            placement != lastProcessedPlacement
        ) {
            lastProcessedPlacement = placement

            val wasStarted = gameStarted

            val outcome = if (wasStarted) {
                tracker.update(placement)
            } else {
                tracker.start(
                    initialPlacement = placement,
                    detectedPlayerColor = recognized.playerColor
                )
            }

            trackerMessage = outcome.status

            if (outcome.fen != null) {
                trackedFen = outcome.fen
                latestFen = outcome.fen

                trackedPlayerColor =
                    outcome.playerColor ?: recognized.playerColor

                if (!wasStarted) {
                    gameStarted = true
                    totalMoves = 0
                    lastSubmittedFen = ""
                    engineMessage = "Партия началась"
                } else if (outcome.advanced) {
                    totalMoves += outcome.movesDetected
                }

                // Проверяем, не наступила ли очередь игрока.
                scheduleStockfishIfNeeded()

            } else if (wasStarted) {
                // Обнаружена новая начальная позиция.
                gameStarted = false
                trackedFen = null
                latestFen = null
                trackedPlayerColor = null
                totalMoves = 0
                lastSubmittedFen = ""

                trackerMessage = outcome.status
                engineMessage = "Ожидаю начала новой партии"

                lastProcessedPlacement = ""
                candidateCount = 0
            }
        }

        publishState()
    }

    /**
     * Запускает Stockfish только тогда, когда очередь хода за игроком.
     */
    private fun scheduleStockfishIfNeeded() {
        val fen = trackedFen ?: return
        val playerColor = trackedPlayerColor ?: return

        if (!gameStarted) return

        val parts = fen.trim().split(Regex("\\s+"))

        if (parts.size < 6) {
            engineMessage = "Некорректная FEN-запись"
            return
        }

        val sideToMove = parts[1]

        if (sideToMove != playerColor) {
            engineMessage = if (playerColor == "w") {
                "Ход чёрных. Ожидаю ответ соперника..."
            } else {
                "Ход белых. Ожидаю ответ соперника..."
            }
            return
        }

        if (fen == lastSubmittedFen) return

        // Сохраняем позицию до запуска анализа,
        // чтобы не запустить несколько одинаковых задач.
        lastSubmittedFen = fen
        engineMessage = "Stockfish анализирует позицию..."
        publishState()

        try {
            engineExecutor.execute {
                analyzeWithStockfish(fen)
            }
        } catch (e: Exception) {
            lastSubmittedFen = ""
            engineMessage =
                "Не удалось запустить анализ: ${e.message}"
            publishState()
        }
    }

    /**
     * Анализирует позицию и отправляет уведомление.
     */
    private fun analyzeWithStockfish(fen: String) {
        try {
            val result = StockfishEngine(applicationContext).use {
                it.analyze(fen)
            }

            // Если за время анализа позиция изменилась,
            // старый результат не отправляем.
            if (latestFen != fen) {
                return
            }

            if (
                result.bestMove.isBlank() ||
                result.bestMove == "(none)" ||
                result.bestMove == "0000"
            ) {
                engineMessage = "Stockfish не нашёл доступный ход"
                publishState()
                return
            }

            val notificationStatus =
                MoveNotifier.show(applicationContext, result)

            lastBestMove =
                MoveNotifier.formatMove(result.bestMove)

            lastEvaluation = result.evaluation

            if (notificationStatus == "Уведомление отправлено") {
                engineMessage =
                    "Лучший ход отправлен. Глубина: ${result.depth}"
            } else {
                engineMessage = notificationStatus
                lastSubmittedFen = ""
            }

            publishState()

        } catch (e: Exception) {
            e.printStackTrace()

            if (latestFen == fen) {
                engineMessage =
                    "Ошибка Stockfish: ${e.message ?: "неизвестная ошибка"}"
                lastSubmittedFen = ""
                publishState()
            }
        }
    }

    /**
     * Передаёт актуальное состояние MainActivity.
     */
    private fun publishState() {
        val intent = Intent(ACTION_STATE_UPDATE).apply {
            setPackage(packageName)

            putExtra(EXTRA_MESSAGE, recognitionMessage)
            putExtra(EXTRA_TRACKER_MESSAGE, trackerMessage)
            putExtra(EXTRA_ENGINE_MESSAGE, engineMessage)
            putExtra(EXTRA_FEN, trackedFen)
            putExtra(EXTRA_PLAYER_COLOR, trackedPlayerColor)
            putExtra(EXTRA_GAME_STARTED, gameStarted)
            putExtra(EXTRA_TOTAL_MOVES, totalMoves)
            putExtra(EXTRA_BEST_MOVE, lastBestMove)
            putExtra(EXTRA_EVALUATION, lastEvaluation)
            putExtra(EXTRA_IS_DETECTING, false)
        }

        sendBroadcast(intent)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "CHESS VISION",
                NotificationManager.IMPORTANCE_LOW
            )

            val manager = getSystemService(
                NotificationManager::class.java
            )

            manager.createNotificationChannel(channel)
        }
    }

    private fun createNotification(): Notification {
        val stopIntent = Intent(
            this,
            ScreenCaptureService::class.java
        ).apply {
            action = ACTION_STOP
        }

        val pendingIntent = PendingIntent.getService(
            this,
            1,
            stopIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or
                PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("CHESS VISION")
            .setContentText("Захват и анализ экрана активны")
            .setSmallIcon(android.R.drawable.ic_menu_camera)
            .setOngoing(true)
            .addAction(
                android.R.drawable.ic_media_pause,
                "Остановить",
                pendingIntent
            )
            .build()
    }

    override fun onDestroy() {
        imageReader?.setOnImageAvailableListener(null, null)

        virtualDisplay?.release()
        virtualDisplay = null

        imageReader?.close()
        imageReader = null

        mediaProjection?.unregisterCallback(projectionCallback)
        mediaProjection?.stop()
        mediaProjection = null

        processingExecutor.shutdownNow()
        engineExecutor.shutdownNow()

        try {
            recognizer?.close()
        } catch (e: Exception) {
            e.printStackTrace()
        }

        recognizer = null

        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
