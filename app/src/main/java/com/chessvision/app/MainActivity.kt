
package com.chessvision.app

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext
import java.io.File

private val DarkBackground = Color(0xFF101510)
private val PanelBackground = Color(0xFF1B241C)
private val LimeColor = Color(0xFFB7F36B)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (
            Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                200
            )
        }

        setContent {
            ChessVisionApp(
                onStartCapture = { resultCode, data ->
                    val intent = Intent(
                        this,
                        ScreenCaptureService::class.java
                    ).apply {
                        putExtra(
                            ScreenCaptureService.EXTRA_RESULT_CODE,
                            resultCode
                        )
                        putExtra(
                            ScreenCaptureService.EXTRA_DATA,
                            data
                        )
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                        startForegroundService(intent)
                    } else {
                        startService(intent)
                    }
                },
                onStopCapture = {
                    stopService(
                        Intent(this, ScreenCaptureService::class.java)
                    )
                }
            )
        }
    }
}

@Composable
fun ChessVisionApp(
    onStartCapture: (Int, Intent?) -> Unit,
    onStopCapture: () -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current

    var capturing by remember { mutableStateOf(false) }
    var isDetecting by remember { mutableStateOf(false) }

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var detectedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var recognitionResult by remember {
        mutableStateOf<PieceRecognitionResult?>(null)
    }

    var message by remember {
        mutableStateOf("Ожидание захвата экрана")
    }

    // Объект ведения шахматной партии.
    val tracker = remember {
        ChessGameTracker()
    }

    var gameStarted by remember {
        mutableStateOf(false)
    }

    var trackedFen by remember {
        mutableStateOf<String?>(null)
    }

    var trackedPlayerColor by remember {
        mutableStateOf<String?>(null)
    }

    var trackerMessage by remember {
        mutableStateOf("Ожидаю начальную позицию партии")
    }

    var totalMoves by remember {
        mutableIntStateOf(0)
    }

    // Stockfish.
    var lastNotifiedFen by remember {
        mutableStateOf("")
    }

    var engineMessage by remember {
        mutableStateOf("Stockfish ожидает начала партии")
    }

    var lastBestMove by remember {
        mutableStateOf("")
    }

    var lastEvaluation by remember {
        mutableStateOf("")
    }

    var notificationTestMessage by remember {
        mutableStateOf("Уведомления ещё не проверялись")
    }

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->

        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            onStartCapture(result.resultCode, result.data)
            capturing = true
            message = "Захват запущен. Ищу доску..."
        } else {
            message = "Не получено разрешение на захват экрана"
        }
    }

    // Распознавание доски и автоматическое ведение партии.
    LaunchedEffect(capturing) {

        if (!capturing) {
            isDetecting = false
            tracker.reset()

            gameStarted = false
            trackedFen = null
            trackedPlayerColor = null
            totalMoves = 0

            trackerMessage = "Ожидаю начальную позицию партии"
            engineMessage = "Stockfish ожидает начала партии"

            return@LaunchedEffect
        }

        // При каждом новом захвате начинаем отслеживание заново.
        tracker.reset()

        gameStarted = false
        trackedFen = null
        trackedPlayerColor = null
        totalMoves = 0
        lastNotifiedFen = ""

        var lastSignature = ""
        var candidatePlacement = ""
        var candidateCount = 0
        var lastProcessedPlacement = ""

        var recognizer: ChessPieceRecognizer? = null

        try {
            while (isActive) {

                val file = File(
                    context.filesDir,
                    "last_screen_capture.png"
                )

                if (file.exists() && file.length() > 0) {

                    val signature =
                        "${file.lastModified()}_${file.length()}"

                    if (signature != lastSignature) {

                        lastSignature = signature

                        val source = withContext(Dispatchers.IO) {
                            BitmapFactory.decodeFile(file.absolutePath)
                        }

                        if (source != null) {

                            previewBitmap = source
                            isDetecting = true

                            try {
                                if (recognizer == null) {
                                    recognizer = withContext(Dispatchers.IO) {
                                        ChessPieceRecognizer(context)
                                    }
                                }

                                val result = withContext(Dispatchers.Default) {

                                    if (!ChessBoardDetector.initialize()) {
                                        throw IllegalStateException(
                                            "Ошибка инициализации OpenCV"
                                        )
                                    }

                                    val bounds =
                                        ChessBoardDetector.detect(source)

                                    if (bounds == null) {
                                        return@withContext Triple<
                                            Bitmap?,
                                            PieceRecognitionResult?,
                                            String
                                        >(
                                            null,
                                            null,
                                            "Доска не найдена. Продолжаю поиск..."
                                        )
                                    }

                                    val left = bounds.x.coerceIn(
                                        0,
                                        source.width - 1
                                    )

                                    val top = bounds.y.coerceIn(
                                        0,
                                        source.height - 1
                                    )

                                    val width = bounds.width.coerceAtMost(
                                        source.width - left
                                    )

                                    val height = bounds.height.coerceAtMost(
                                        source.height - top
                                    )

                                    if (width <= 0 || height <= 0) {
                                        return@withContext Triple<
                                            Bitmap?,
                                            PieceRecognitionResult?,
                                            String
                                        >(
                                            null,
                                            null,
                                            "Некорректные размеры доски"
                                        )
                                    }

                                    val crop = Bitmap.createBitmap(
                                        source,
                                        left,
                                        top,
                                        width,
                                        height
                                    )

                                    val recognized =
                                        recognizer!!.recognize(crop)

                                    Triple(
                                        crop,
                                        recognized,
                                        "Позиция распознана"
                                    )
                                }

                                detectedBitmap = result.first
                                recognitionResult = result.second
                                message = result.third

                                val recognized = result.second

                                // Для отслеживания используем только
                                // надёжно распознанные позиции.
                                if (
                                    recognized == null ||
                                    !recognized.reliable
                                ) {
                                    candidatePlacement = ""
                                    candidateCount = 0

                                } else {

                                    val placement = recognized.placement

                                    if (placement == candidatePlacement) {
                                        candidateCount++
                                    } else {
                                        candidatePlacement = placement
                                        candidateCount = 1
                                    }

                                    // Два одинаковых распознавания подряд.
                                    if (
                                        candidateCount >= 2 &&
                                        placement != lastProcessedPlacement
                                    ) {

                                        lastProcessedPlacement = placement

                                        val wasStarted = gameStarted

                                        val outcome = withContext(
                                            Dispatchers.Default
                                        ) {
                                            if (wasStarted) {
                                                tracker.update(placement)
                                            } else {
                                                tracker.start(
                                                    initialPlacement = placement,
                                                    detectedPlayerColor =
                                                        recognized.playerColor
                                                )
                                            }
                                        }

                                        trackerMessage = outcome.status

                                        if (outcome.fen != null) {

                                            trackedFen = outcome.fen

                                            trackedPlayerColor =
                                                outcome.playerColor
                                                    ?: recognized.playerColor

                                            if (!wasStarted) {
                                                gameStarted = true
                                                totalMoves = 0
                                            } else if (outcome.advanced) {
                                                totalMoves +=
                                                    outcome.movesDetected
                                            }

                                        } else if (wasStarted) {

                                            // Трекер обнаружил новую
                                            // начальную позицию.
                                            gameStarted = false
                                            trackedFen = null
                                            trackedPlayerColor = null
                                            totalMoves = 0

                                            lastProcessedPlacement = ""
                                            candidateCount = 0
                                        }
                                    }
                                }

                            } catch (e: Exception) {
                                message = "Ошибка: ${e.message}"
                                recognitionResult = null
                                detectedBitmap = null

                                candidatePlacement = ""
                                candidateCount = 0

                            } finally {
                                isDetecting = false
                            }
                        }
                    }
                }

                delay(1000)
            }

        } finally {
            withContext(Dispatchers.IO) {
                recognizer?.close()
            }
        }
    }

    // Автоматический анализ Stockfish.
    // Анализируем только тогда, когда очередь хода за игроком.
    LaunchedEffect(
        trackedFen,
        trackedPlayerColor
    ) {

        val fen = trackedFen
        val playerColor = trackedPlayerColor

        if (!gameStarted || fen == null || playerColor == null) {
            engineMessage = "Ожидаю подтверждённую партию"
            return@LaunchedEffect
        }

        val fenParts = fen.trim().split(Regex("\\s+"))

        if (fenParts.size < 6) {
            engineMessage = "Некорректная FEN-запись"
            return@LaunchedEffect
        }

        val sideToMove = fenParts[1]

        if (sideToMove != playerColor) {
            engineMessage = if (playerColor == "w") {
                "Ход чёрных. Ожидаю ответ соперника..."
            } else {
                "Ход белых. Ожидаю ответ соперника..."
            }

            return@LaunchedEffect
        }

        if (fen == lastNotifiedFen) {
            engineMessage = "Эта позиция уже проанализирована"
            return@LaunchedEffect
        }

        // Небольшая задержка для стабилизации позиции.
        delay(900)

        if (
            fen != trackedFen ||
            playerColor != trackedPlayerColor
        ) {
            return@LaunchedEffect
        }

        try {
            engineMessage = "Stockfish анализирует позицию..."

            val result = withContext(Dispatchers.IO) {
                StockfishEngine(context).use { engine ->
                    engine.analyze(fen)
                }
            }

            // Не отправляем устаревший результат.
            if (
                fen != trackedFen ||
                playerColor != trackedPlayerColor
            ) {
                return@LaunchedEffect
            }

            if (
                result.bestMove.isBlank() ||
                result.bestMove == "(none)" ||
                result.bestMove == "0000"
            ) {
                engineMessage = "Stockfish не нашёл доступный ход"
                return@LaunchedEffect
            }

            engineMessage = "Отправляю лучший ход..."

            val notificationStatus =
                MoveNotifier.show(context, result)

            lastBestMove =
                MoveNotifier.formatMove(result.bestMove)

            lastEvaluation = result.evaluation

            if (notificationStatus == "Уведомление отправлено") {

                lastNotifiedFen = fen

                engineMessage =
                    "Лучший ход отправлен. Глубина: ${result.depth}"

            } else {
                engineMessage = notificationStatus
            }

        } catch (e: Exception) {
            engineMessage =
                "Ошибка Stockfish: ${e.message ?: "неизвестная ошибка"}"
        }
    }

    // Интерфейс приложения.
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {

        Text(
            "CHESS VISION",
            color = LimeColor,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black
        )

        Text(
            "Автоматическое распознавание и анализ",
            color = Color.LightGray,
            fontSize = 13.sp
        )

        Spacer(Modifier.height(22.dp))

        SectionTitle("ЗАХВАТ ЭКРАНА")

        Button(
            onClick = {
                if (capturing) {
                    onStopCapture()
                    capturing = false
                    message = "Захват остановлен"
                } else {
                    val manager = context.getSystemService(
                        Context.MEDIA_PROJECTION_SERVICE
                    ) as MediaProjectionManager

                    captureLauncher.launch(
                        manager.createScreenCaptureIntent()
                    )
                }
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = if (capturing) {
                    Color(0xFF9B3939)
                } else {
                    LimeColor
                },
                contentColor = if (capturing) {
                    Color.White
                } else {
                    Color.Black
                }
            )
        ) {
            Text(
                if (capturing) "ОСТАНОВИТЬ ЗАХВАТ"
                else "НАЧАТЬ ЗАХВАТ",
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                Modifier
                    .size(9.dp)
                    .background(
                        if (capturing) LimeColor else Color.Gray,
                        RoundedCornerShape(50)
                    )
            )

            Spacer(Modifier.width(8.dp))

            Text(
                if (capturing) "Захват активен"
                else "Захват не запущен",
                color = Color.LightGray
            )
        }

        Spacer(Modifier.height(14.dp))

        Button(
            onClick = {
                notificationTestMessage =
                    MoveNotifier.showTest(context)
            },
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = LimeColor,
                contentColor = Color.Black
            )
        ) {
            Text(
                "ПРОВЕРИТЬ УВЕДОМЛЕНИЯ",
                fontWeight = FontWeight.Bold
            )
        }

        Text(
            notificationTestMessage,
            color = Color.LightGray,
            fontSize = 12.sp
        )

        Spacer(Modifier.height(22.dp))

        SectionTitle("ПОСЛЕДНИЙ КАДР")

        previewBitmap?.let {
            Image(
                bitmap = it.asImageBitmap(),
                contentDescription = "Кадр экрана",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color.Black),
                contentScale = ContentScale.Fit
            )
        } ?: InfoText("Здесь появится изображение экрана")

        Spacer(Modifier.height(22.dp))

        SectionTitle("АВТОМАТИЧЕСКОЕ РАСПОЗНАВАНИЕ")

        Row(verticalAlignment = Alignment.CenterVertically) {
            if (isDetecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = LimeColor,
                    strokeWidth = 2.dp
                )

                Spacer(Modifier.width(10.dp))
            }

            Text(
                message,
                color = Color.LightGray,
                fontSize = 13.sp
            )
        }

        detectedBitmap?.let {
            Spacer(Modifier.height(14.dp))

            SectionTitle("ОБНАРУЖЕННАЯ ДОСКА")

            Box(
                Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = "Шахматная доска",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )

                Canvas(Modifier.fillMaxSize()) {
                    for (i in 1..7) {
                        val x = size.width * i / 8f
                        val y = size.height * i / 8f

                        drawLine(
                            Color.White.copy(alpha = 0.8f),
                            Offset(x, 0f),
                            Offset(x, size.height),
                            1.dp.toPx()
                        )

                        drawLine(
                            Color.White.copy(alpha = 0.8f),
                            Offset(0f, y),
                            Offset(size.width, y),
                            1.dp.toPx()
                        )
                    }
                }
            }
        }

        recognitionResult?.let { result ->

            Spacer(Modifier.height(22.dp))

            SectionTitle("РАСПОЗНАННАЯ ПОЗИЦИЯ")

            RecognizedBoard(result.placement)

            Spacer(Modifier.height(12.dp))

            Text(
                "Средняя уверенность: ${
                    String.format("%.1f", result.meanConfidence * 100)
                }%",
                color = Color.LightGray
            )

            Text(
                "Минимальная уверенность: ${
                    String.format("%.1f", result.minConfidence * 100)
                }%",
                color = Color.LightGray
            )

            Text(
                if (result.reliable) {
                    "Уверенность распознавания высокая"
                } else {
                    "Внимание: есть клетки с низкой уверенностью"
                },
                color = if (result.reliable) LimeColor
                else Color(0xFFFFB45C),
                fontSize = 13.sp
            )

            Spacer(Modifier.height(18.dp))

            SectionTitle("ЦВЕТ ИГРОКА")

            val colorText = when (result.playerColor) {
                "w" -> "Вы играете белыми"
                "b" -> "Вы играете чёрными"
                else -> "Цвет игрока не определён"
            }

            Text(
                colorText,
                color = LimeColor,
                fontSize = 17.sp,
                fontWeight = FontWeight.Bold
            )

            Text(
                if (result.boardFlipped) {
                    "Ориентация доски: чёрные снизу"
                } else {
                    "Ориентация доски: белые снизу"
                },
                color = Color.LightGray,
                fontSize = 13.sp
            )
        }

        Spacer(Modifier.height(22.dp))

        SectionTitle("АВТОМАТИЧЕСКОЕ ВЕДЕНИЕ ПАРТИИ")

        Surface(
            color = PanelBackground,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp)
            ) {

                Text(
                    if (gameStarted) "ПАРТИЯ ОТСЛЕЖИВАЕТСЯ"
                    else "ОЖИДАНИЕ НАЧАЛА ПАРТИИ",
                    color = if (gameStarted) LimeColor
                    else Color(0xFFFFB45C),
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(10.dp))

                Text(
                    trackerMessage,
                    color = Color.White,
                    fontSize = 14.sp
                )

                Spacer(Modifier.height(12.dp))

                Text(
                    "Подтверждённых ходов: $totalMoves",
                    color = Color.LightGray
                )

                Text(
                    "Цвет игрока: ${
                        when (trackedPlayerColor) {
                            "w" -> "белые"
                            "b" -> "чёрные"
                            else -> "не определён"
                        }
                    }",
                    color = Color.LightGray
                )

                Text(
                    "Очередь хода: ${
                        when (trackedFen?.split(" ")?.getOrNull(1)) {
                            "w" -> "белые"
                            "b" -> "чёрные"
                            else -> "не определена"
                        }
                    }",
                    color = Color.LightGray
                )
            }
        }

        Spacer(Modifier.height(22.dp))

        SectionTitle("STOCKFISH — АВТОМАТИЧЕСКИЙ АНАЛИЗ")

        Surface(
            color = PanelBackground,
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(14.dp)
            ) {

                Row(
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        imageVector = androidx.compose.material.icons.Icons.Default.CheckCircle,
                        contentDescription = null,
                        tint = LimeColor
                    )

                    Spacer(Modifier.width(8.dp))

                    Text(
                        "Автоматический режим активен",
                        color = LimeColor,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(12.dp))

                Text(
                    engineMessage,
                    color = Color.White,
                    fontSize = 13.sp
                )

                if (lastBestMove.isNotEmpty()) {

                    Spacer(Modifier.height(14.dp))

                    Text(
                        "Последний лучший ход",
                        color = Color.Gray,
                        fontSize = 12.sp
                    )

                    Text(
                        lastBestMove,
                        color = LimeColor,
                        fontSize = 25.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Text(
                        "Оценка: $lastEvaluation",
                        color = Color.White,
                        fontSize = 14.sp
                    )
                }
            }
        }

        Spacer(Modifier.height(22.dp))

        SectionTitle("ПОЛНАЯ FEN-ЗАПИСЬ")

        Surface(
            color = PanelBackground,
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                text = trackedFen ?: "FEN появится после подтверждения начала партии",
                color = if (trackedFen != null) LimeColor
                else Color(0xFFFFB45C),
                fontSize = 14.sp,
                modifier = Modifier.padding(14.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {
                trackedFen?.let {
                    val clipboard =
                        context.getSystemService(
                            Context.CLIPBOARD_SERVICE
                        ) as android.content.ClipboardManager

                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "Chess FEN",
                            it
                        )
                    )
                }
            },
            enabled = trackedFen != null,
            modifier = Modifier.fillMaxWidth(),
            colors = ButtonDefaults.buttonColors(
                containerColor = LimeColor,
                contentColor = Color.Black
            )
        ) {
            Text(
                "КОПИРОВАТЬ FEN",
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(30.dp))
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text = text,
        color = LimeColor,
        fontWeight = FontWeight.Bold,
        fontSize = 14.sp
    )
}

@Composable
private fun InfoText(text: String) {
    Text(
        text = text,
        color = Color.Gray,
        fontSize = 12.sp
    )
}

@Composable
fun RecognizedBoard(placement: String) {

    val rows = placement.split("/")

    val symbols = mapOf(
        'K' to "♔",
        'Q' to "♕",
        'R' to "♖",
        'B' to "♗",
        'N' to "♘",
        'P' to "♙",
        'k' to "♚",
        'q' to "♛",
        'r' to "♜",
        'b' to "♝",
        'n' to "♞",
        'p' to "♟"
    )

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        rows.take(8).forEachIndexed { rowIndex, row ->

            val cells = mutableListOf<Char>()

            row.forEach { character ->
                if (character.isDigit()) {
                    repeat(character.digitToInt()) {
                        cells.add('.')
                    }
                } else {
                    cells.add(character)
                }
            }

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                (0 until 8).forEach { columnIndex ->

                    val piece = cells.getOrElse(columnIndex) { '.' }
                    val light = (rowIndex + columnIndex) % 2 == 0

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                if (light) Color(0xFFEEEED2)
                                else Color(0xFF769656)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (piece != '.') {
                            Text(
                                text = symbols[piece] ?: "",
                                fontSize = 25.sp,
                                color = if (piece.isUpperCase()) {
                                    Color.White
                                } else {
                                    Color(0xFF171717)
                                }
                            )
                        }
                    }
                }
            }
        }
    }
}
