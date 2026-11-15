
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

    private data class BoardOverlayState(
        val left: Int,
        val top: Int,
        val width: Int,
        val height: Int,
        val whiteAtBottom: Boolean
    )

    companion object {
        const val EXTRA_RESULT_CODE = "result_code"
        const val EXTRA_DATA = "projection_data"

        const val ACTION_STOP =
            "com.chessvision.app.STOP_CAPTURE"

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

    private val processingExecutor = Executors.newSingleThreadExecutor()
    private val engineExecutor = Executors.newSingleThreadExecutor()

    private var recognizer: ChessPieceRecognizer? = null
    private val tracker = ChessGameTracker()

    private var overlayManager: ChessOverlayManager? = null

    private var lastQueuedAt = 0L

    private var candidatePlacement = ""
    private var candidateCount = 0
    private var lastProcessedPlacement = ""

    private var gameStarted = false
    private var trackedFen: String? = null
    private var trackedPlayerColor: String? = null
    private var totalMoves = 0

    // Ожидание ручного выбора стороны хода.
    @Volatile
    private var awaitingTurnSelection = false

    private var pendingPlacement: String? = null
    private var pendingPlayerColor: String? = null

    @Volatile
    private var latestFen: String? = null

    @Volatile
    private var lastSubmittedFen = ""

    @Volatile
    private var latestBoardOverlay: BoardOverlayState? = null

    private var trackerMessage =
        "Ожидаю начальную позицию партии"

    private var engineMessage =
        "Stockfish ожидает начала партии"

    private var lastBestMove = ""

    @Volatile
    private var lastBestMoveUci = ""

    private var lastEvaluation = ""

    private var recognitionMessage =
        "Ожидание захвата экрана"

    private val projectionCallback =
        object : MediaProjection.Callback() {
            override fun onStop() {
                stopSelf()
            }
        }

    override fun onCreate() {
        super.onCreate()

        createNotificationChannel()

        overlayManager = ChessOverlayManager(
            context = this,
            onStop = {
                stopSelf()
            },
            onSideToMoveSelected = { side ->
                try {
                    processingExecutor.execute {
                        acceptTurnSelection(side)
                    }
                } catch (e: Exception) {
                    e.printStackTrace()
                }
            }
        )
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

        // Не создаём второй MediaProjection при повторном запуске.
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

            val projection = manager.getMediaProjection(
                resultCode,
                data
            )

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

            recognitionMessage =
                "Захват активен. Ищу шахматную доску..."

            overlayManager?.show()

            overlayManager?.update(
                lastBestMove,
                lastEvaluation,
                engineMessage
            )

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
     * Основной цикл распознавания.
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
            latestBoardOverlay = null
            overlayManager?.clearArrow()

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
            latestBoardOverlay = null
            overlayManager?.clearArrow()

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

            latestBoardOverlay = null
            overlayManager?.clearArrow()

            publishState()
            return
        }

        val bottomColor =
            trackedPlayerColor ?: recognized.playerColor

        latestBoardOverlay = BoardOverlayState(
            left = left,
            top = top,
            width = width,
            height = height,
            whiteAtBottom = bottomColor == "w"
        )

        val placement = recognized.placement

        if (placement == candidatePlacement) {
            candidateCount++
        } else {
            candidatePlacement = placement
            candidateCount = 1
        }

        // Требуем два последовательных одинаковых распознавания.
        if (
            candidateCount < 2 ||
            placement == lastProcessedPlacement
        ) {
            publishState()
            return
        }

        /*
         * Если ждём выбора стороны хода, обновляем ожидаемую
         * позицию. Если появилась начальная расстановка,
         * отменяем запрос и автоматически начинаем новую партию.
         */
        if (awaitingTurnSelection) {

            if (placement == ChessGameTracker.START_PLACEMENT) {
                awaitingTurnSelection = false
                pendingPlacement = null
                pendingPlayerColor = null

                overlayManager?.hideTurnSelection()

                tracker.reset()
                resetGameState()

                trackerMessage =
                    "Обнаружена новая партия. Начинаю отслеживание..."

            } else {
                pendingPlacement = placement
                pendingPlayerColor = recognized.playerColor
                lastProcessedPlacement = placement

                trackerMessage =
                    "Контекст партии потерян. Укажите, чей сейчас ход."

                publishState()
                return
            }
        }

        /*
         * Если доска вернулась к начальной расстановке,
         * это новая партия. Сбрасываем старую историю.
         */
        if (
            gameStarted &&
            placement == ChessGameTracker.START_PLACEMENT
        ) {
            tracker.reset()
            resetGameState()

            trackerMessage =
                "Обнаружена новая партия. Начинаю отслеживание..."
        }

        lastProcessedPlacement = placement

        val outcome = if (gameStarted) {
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

            latestBoardOverlay = BoardOverlayState(
                left = left,
                top = top,
                width = width,
                height = height,
                whiteAtBottom = trackedPlayerColor == "w"
            )

            if (!gameStarted) {
                gameStarted = true
                totalMoves = outcome.movesDetected

                lastSubmittedFen = ""

                engineMessage =
                    "Позиция восстановлена. Проверяю очередь хода..."

                overlayManager?.hideTurnSelection()

            } else if (outcome.advanced) {
                totalMoves += outcome.movesDetected
            }

            scheduleStockfishIfNeeded()

        } else {
            /*
             * Историю восстановить не получилось.
             * Просим пользователя указать, чей сейчас ход.
             */
            enterTurnSelection(
                placement = placement,
                playerColor = recognized.playerColor
            )
        }

        publishState()
    }

    /**
     * Включает режим восстановления контекста партии.
     */
    private fun enterTurnSelection(
        placement: String,
        playerColor: String
    ) {
        awaitingTurnSelection = true

        pendingPlacement = placement
        pendingPlayerColor = playerColor

        tracker.reset()

        gameStarted = false
        trackedFen = null
        latestFen = null
        trackedPlayerColor = playerColor
        totalMoves = 0

        lastSubmittedFen = ""

        lastBestMove = ""
        lastBestMoveUci = ""
        lastEvaluation = ""

        overlayManager?.clearArrow()
        overlayManager?.showTurnSelection()

        trackerMessage =
            "Контекст партии потерян. Укажите, чей сейчас ход."

        engineMessage =
            "Ожидаю выбора очереди хода"
    }

    /**
     * Пользователь нажал «Ход белых» или «Ход чёрных».
     */
    private fun acceptTurnSelection(sideToMove: String) {

        if (!awaitingTurnSelection) return

        val placement = pendingPlacement ?: return
        val playerColor = pendingPlayerColor ?: return

        val result = tracker.startFromPosition(
            initialPlacement = placement,
            detectedPlayerColor = playerColor,
            sideToMove = sideToMove
        )

        if (result.fen == null) {
            trackerMessage = result.status
            engineMessage = "Не удалось восстановить позицию"

            publishState()
            return
        }

        awaitingTurnSelection = false

        pendingPlacement = null
        pendingPlayerColor = null

        gameStarted = true

        trackedFen = result.fen
        latestFen = result.fen
        trackedPlayerColor = result.playerColor

        totalMoves = 0

        lastProcessedPlacement = placement
        candidatePlacement = placement
        candidateCount = 2

        lastSubmittedFen = ""

        lastBestMove = ""
        lastBestMoveUci = ""
        lastEvaluation = ""

        trackerMessage = result.status

        engineMessage =
            "Позиция восстановлена. Запускаю Stockfish..."

        overlayManager?.hideTurnSelection()

        scheduleStockfishIfNeeded()
        publishState()
    }

    /**
     * Сбрасывает состояние старой партии.
     */
    private fun resetGameState() {

        gameStarted = false

        trackedFen = null
        latestFen = null
        trackedPlayerColor = null

        totalMoves = 0

        lastSubmittedFen = ""

        lastBestMove = ""
        lastBestMoveUci = ""
        lastEvaluation = ""

        latestBoardOverlay = null

        overlayManager?.clearArrow()
    }

    /**
     * Запускает Stockfish только тогда, когда очередь хода за игроком.
     */
    private fun scheduleStockfishIfNeeded() {

        val fen = trackedFen ?: return
        val playerColor = trackedPlayerColor ?: return

        if (!gameStarted || awaitingTurnSelection) return

        val parts = fen.trim().split(Regex("\\s+"))

        if (parts.size < 6) {
            engineMessage = "Некорректная FEN-запись"
            return
        }

        val sideToMove = parts[1]

        if (sideToMove != playerColor) {

            lastBestMove = ""
            lastBestMoveUci = ""
            lastEvaluation = ""

            overlayManager?.clearArrow()

            engineMessage = if (playerColor == "w") {
                "Ход чёрных. Ожидаю ответ соперника..."
            } else {
                "Ход белых. Ожидаю ответ соперника..."
            }

            return
        }

        if (fen == lastSubmittedFen) return

        lastBestMove = ""
        lastBestMoveUci = ""
        lastEvaluation = ""

        overlayManager?.clearArrow()

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
     * Анализ Stockfish.
     */
    private fun analyzeWithStockfish(fen: String) {
        try {
            val result = StockfishEngine(applicationContext).use {
                it.analyze(fen)
            }

            if (latestFen != fen) {
                return
            }

            if (
                result.bestMove.isBlank() ||
                result.bestMove == "(none)" ||
                result.bestMove == "0000"
            ) {
                lastBestMove = ""
                lastBestMoveUci = ""
                lastEvaluation = ""

                engineMessage =
                    "Stockfish не нашёл доступный ход"

                publishState()
                return
            }

            lastBestMoveUci = result.bestMove

            lastBestMove =
                MoveNotifier.formatMove(result.bestMove)

            lastEvaluation = result.evaluation

            engineMessage =
                "Лучший ход обновлён. Глубина: ${result.depth}"

            publishState()

        } catch (e: Exception) {
            e.printStackTrace()

            if (latestFen == fen) {
                lastBestMove = ""
                lastBestMoveUci = ""
                lastEvaluation = ""

                engineMessage =
                    "Ошибка Stockfish: ${e.message ?: "неизвестная ошибка"}"

                lastSubmittedFen = ""

                publishState()
            }
        }
    }

    /**
     * Обновляет панель, стрелку и MainActivity.
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

        overlayManager?.update(
            lastBestMove,
            lastEvaluation,
            engineMessage
        )

        val board = latestBoardOverlay
        val move = lastBestMoveUci

        if (
            board != null &&
            move.length >= 4 &&
            gameStarted &&
            !awaitingTurnSelection
        ) {
            overlayManager?.updateArrow(
                move = move,
                boardLeft = board.left,
                boardTop = board.top,
                boardWidth = board.width,
                boardHeight = board.height,
                whiteAtBottom = board.whiteAtBottom
            )
        } else {
            overlayManager?.clearArrow()
        }
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

        overlayManager?.hide()
        overlayManager = null

        latestBoardOverlay = null
        lastBestMoveUci = ""

        awaitingTurnSelection = false
        pendingPlacement = null
        pendingPlayerColor = null

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
