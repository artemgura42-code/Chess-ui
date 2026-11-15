
package com.chessvision.app

import android.Manifest
import android.app.Activity
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
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

                    val serviceIntent = Intent(
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
                        startForegroundService(serviceIntent)
                    } else {
                        startService(serviceIntent)
                    }
                },

                onStopCapture = {

                    val stopIntent = Intent(
                        this,
                        ScreenCaptureService::class.java
                    ).apply {
                        action = ScreenCaptureService.ACTION_STOP
                    }

                    startService(stopIntent)
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

    var capturing by remember {
        mutableStateOf(false)
    }

    var isDetecting by remember {
        mutableStateOf(false)
    }

    var previewBitmap by remember {
        mutableStateOf<Bitmap?>(null)
    }

    var message by remember {
        mutableStateOf("Ожидание захвата экрана")
    }

    var trackerMessage by remember {
        mutableStateOf("Ожидаю начальную позицию партии")
    }

    var engineMessage by remember {
        mutableStateOf("Stockfish ожидает начала партии")
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

    var totalMoves by remember {
        mutableIntStateOf(0)
    }

    var lastBestMove by remember {
        mutableStateOf("")
    }

    var lastEvaluation by remember {
        mutableStateOf("")
    }

    // Запоминаем, что пользователь собирался запустить захват,
    // пока Android открывает настройки специального разрешения.
    var pendingStartCapture by remember {
        mutableStateOf(false)
    }

    /*
     * Запрос разрешения MediaProjection.
     * Вызывается только после проверки разрешения overlay.
     */
    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->

        if (
            result.resultCode == Activity.RESULT_OK &&
            result.data != null
        ) {
            trackedFen = null
            trackedPlayerColor = null
            gameStarted = false
            totalMoves = 0
            lastBestMove = ""
            lastEvaluation = ""

            trackerMessage = "Ожидаю начальную позицию партии"
            engineMessage = "Stockfish ожидает начала партии"

            onStartCapture(
                result.resultCode,
                result.data
            )

            capturing = true
            message = "Захват запущен. Ищу доску..."

        } else {
            capturing = false
            message = "Не получено разрешение на захват экрана"
        }
    }

    /*
     * Возвращение из настроек Android после запроса
     * разрешения на отображение поверх других приложений.
     */
    val overlayPermissionLauncher =
        rememberLauncherForActivityResult(
            ActivityResultContracts.StartActivityForResult()
        ) {

            pendingStartCapture = false

            if (Settings.canDrawOverlays(context)) {

                message =
                    "Разрешение получено. Запрашиваю захват экрана..."

                val manager = context.getSystemService(
                    Context.MEDIA_PROJECTION_SERVICE
                ) as MediaProjectionManager

                captureLauncher.launch(
                    manager.createScreenCaptureIntent()
                )

            } else {
                message =
                    "Разрешение на отображение поверх приложений не предоставлено"
            }
        }

    /*
     * Единая функция запуска.
     * Сначала проверяем разрешение overlay,
     * затем запрашиваем доступ к захвату экрана.
     */
    val requestCapture = {

        if (capturing) {
            Unit
        } else if (
            Build.VERSION.SDK_INT >= Build.VERSION_CODES.M &&
            !Settings.canDrawOverlays(context)
        ) {

            pendingStartCapture = true

            message =
                "Предоставьте разрешение на отображение поверх приложений"

            val settingsIntent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )

            overlayPermissionLauncher.launch(settingsIntent)

        } else {

            val manager = context.getSystemService(
                Context.MEDIA_PROJECTION_SERVICE
            ) as MediaProjectionManager

            captureLauncher.launch(
                manager.createScreenCaptureIntent()
            )
        }
    }

    /*
     * Получаем обновления от ScreenCaptureService.
     * Вся обработка шахматной позиции выполняется в сервисе.
     */
    DisposableEffect(context) {

        val receiver = object : BroadcastReceiver() {

            override fun onReceive(
                receiverContext: Context?,
                intent: Intent?
            ) {
                if (
                    intent?.action !=
                    ScreenCaptureService.ACTION_STATE_UPDATE
                ) {
                    return
                }

                message =
                    intent.getStringExtra(
                        ScreenCaptureService.EXTRA_MESSAGE
                    ) ?: message

                trackerMessage =
                    intent.getStringExtra(
                        ScreenCaptureService.EXTRA_TRACKER_MESSAGE
                    ) ?: trackerMessage

                engineMessage =
                    intent.getStringExtra(
                        ScreenCaptureService.EXTRA_ENGINE_MESSAGE
                    ) ?: engineMessage

                trackedFen =
                    intent.getStringExtra(
                        ScreenCaptureService.EXTRA_FEN
                    )

                trackedPlayerColor =
                    intent.getStringExtra(
                        ScreenCaptureService.EXTRA_PLAYER_COLOR
                    )

                gameStarted =
                    intent.getBooleanExtra(
                        ScreenCaptureService.EXTRA_GAME_STARTED,
                        false
                    )

                totalMoves =
                    intent.getIntExtra(
                        ScreenCaptureService.EXTRA_TOTAL_MOVES,
                        0
                    )

                lastBestMove =
                    intent.getStringExtra(
                        ScreenCaptureService.EXTRA_BEST_MOVE
                    ) ?: ""

                lastEvaluation =
                    intent.getStringExtra(
                        ScreenCaptureService.EXTRA_EVALUATION
                    ) ?: ""

                isDetecting =
                    intent.getBooleanExtra(
                        ScreenCaptureService.EXTRA_IS_DETECTING,
                        false
                    )
            }
        }

        val filter = IntentFilter(
            ScreenCaptureService.ACTION_STATE_UPDATE
        )

        ContextCompat.registerReceiver(
            context,
            receiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )

        onDispose {
            try {
                context.unregisterReceiver(receiver)
            } catch (_: Exception) {
            }
        }
    }

    /*
     * Превью последнего кадра.
     * Здесь нет распознавания доски и запуска Stockfish.
     */
    LaunchedEffect(capturing) {

        if (!capturing) {
            previewBitmap = null
            return@LaunchedEffect
        }

        var lastSignature = ""

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

                    val bitmap = withContext(Dispatchers.IO) {
                        BitmapFactory.decodeFile(file.absolutePath)
                    }

                    if (bitmap != null) {
                        previewBitmap = bitmap
                    }
                }
            }

            delay(1200)
        }
    }

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

                    gameStarted = false
                    trackedFen = null
                    trackedPlayerColor = null
                    totalMoves = 0
                    lastBestMove = ""
                    lastEvaluation = ""

                    message = "Захват остановлен"

                    trackerMessage =
                        "Ожидаю начальную позицию партии"

                    engineMessage =
                        "Stockfish ожидает начала партии"

                } else {
                    requestCapture()
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
                if (capturing) {
                    "ОСТАНОВИТЬ ЗАХВАТ"
                } else {
                    "НАЧАТЬ ЗАХВАТ"
                },
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(Modifier.height(10.dp))

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {
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
                if (capturing) {
                    "Захват и фоновый анализ активны"
                } else {
                    "Захват не запущен"
                },
                color = Color.LightGray
            )
        }

        Spacer(Modifier.height(14.dp))

        Text(
            text = if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(context)
            ) {
                "✓ Разрешение плавающей панели предоставлено"
            } else {
                "Для работы плавающей панели потребуется разрешение Android"
            },
            color = if (
                Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                Settings.canDrawOverlays(context)
            ) {
                LimeColor
            } else {
                Color(0xFFFFB45C)
            },
            fontSize = 12.sp
        )

        Spacer(Modifier.height(22.dp))

        SectionTitle("ПОСЛЕДНИЙ КАДР")

        previewBitmap?.let { bitmap ->

            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Кадр экрана",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(200.dp)
                    .background(Color.Black),
                contentScale = ContentScale.Fit
            )

        } ?: InfoText("Здесь появится изображение экрана")

        Spacer(Modifier.height(22.dp))

        SectionTitle("ФОНОВОЕ РАСПОЗНАВАНИЕ")

        Row(
            verticalAlignment = Alignment.CenterVertically
        ) {

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

        Spacer(Modifier.height(22.dp))

        SectionTitle("РАСПОЗНАННАЯ ПОЗИЦИЯ")

        val placement = trackedFen?.substringBefore(" ")

        if (gameStarted && placement != null) {

            RecognizedBoard(placement)

            Spacer(Modifier.height(12.dp))

            Text(
                "Позиция получена из истории ходов",
                color = Color.LightGray,
                fontSize = 12.sp
            )

        } else {

            InfoText(
                "Доска появится после распознавания начальной позиции партии"
            )
        }

        Spacer(Modifier.height(22.dp))

        SectionTitle("ЦВЕТ ИГРОКА")

        Text(
            when (trackedPlayerColor) {
                "w" -> "Вы играете белыми"
                "b" -> "Вы играете чёрными"
                else -> "Цвет игрока не определён"
            },
            color = LimeColor,
            fontSize = 17.sp,
            fontWeight = FontWeight.Bold
        )

        Text(
            when (trackedPlayerColor) {
                "w" -> "Ориентация: белые снизу"
                "b" -> "Ориентация: чёрные снизу"
                else -> "Ориентация доски не определена"
            },
            color = Color.LightGray,
            fontSize = 13.sp
        )

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
                    if (gameStarted) {
                        "ПАРТИЯ ОТСЛЕЖИВАЕТСЯ"
                    } else {
                        "ОЖИДАНИЕ НАЧАЛА ПАРТИИ"
                    },
                    color = if (gameStarted) {
                        LimeColor
                    } else {
                        Color(0xFFFFB45C)
                    },
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

        SectionTitle("STOCKFISH — ФОНОВЫЙ АНАЛИЗ")

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

                    Text(
                        "✓",
                        color = LimeColor,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.width(8.dp))

                    Text(
                        "Анализ выполняется в сервисе",
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
                text = trackedFen
                    ?: "FEN появится после подтверждения начала партии",

                color = if (trackedFen != null) {
                    LimeColor
                } else {
                    Color(0xFFFFB45C)
                },

                fontSize = 14.sp,
                modifier = Modifier.padding(14.dp)
            )
        }

        Spacer(Modifier.height(10.dp))

        Button(
            onClick = {

                trackedFen?.let { fen ->

                    val clipboard =
                        context.getSystemService(
                            Context.CLIPBOARD_SERVICE
                        ) as android.content.ClipboardManager

                    clipboard.setPrimaryClip(
                        android.content.ClipData.newPlainText(
                            "Chess FEN",
                            fen
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

                    val light =
                        (rowIndex + columnIndex) % 2 == 0

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                if (light) {
                                    Color(0xFFEEEED2)
                                } else {
                                    Color(0xFF769656)
                                }
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
