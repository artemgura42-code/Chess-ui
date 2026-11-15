
package com.chessvision.app

import android.app.Activity
import android.content.Context
import android.content.Intent
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
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.withContext

private val DarkBackground = Color(0xFF101510)
private val PanelBackground = Color(0xFF1B241C)
private val LimeColor = Color(0xFFB7F36B)

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

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
    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var detectedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var recognitionResult by remember {
        mutableStateOf<PieceRecognitionResult?>(null)
    }

    var detectionMessage by remember {
        mutableStateOf("Ожидание захвата экрана")
    }

    var isDetecting by remember { mutableStateOf(false) }

    val captureLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.StartActivityForResult()
    ) { result ->

        if (result.resultCode == Activity.RESULT_OK && result.data != null) {
            onStartCapture(result.resultCode, result.data)
            capturing = true
            detectionMessage = "Захват запущен. Ищу шахматную доску..."
        } else {
            detectionMessage = "Разрешение на захват не получено"
        }
    }

    /*
     * Автоматическое обнаружение доски.
     * Распознавание запускается при появлении нового кадра.
     */
    LaunchedEffect(capturing) {

        if (!capturing) {
            isDetecting = false
            return@LaunchedEffect
        }

        var lastSignature = ""
        var recognizer: ChessPieceRecognizer? = null

        try {
            while (isActive) {

                val captureFile = java.io.File(
                    context.filesDir,
                    "last_screen_capture.png"
                )

                if (captureFile.exists() && captureFile.length() > 0) {

                    val signature =
                        "${captureFile.lastModified()}_${captureFile.length()}"

                    if (signature != lastSignature) {

                        lastSignature = signature

                        val source = withContext(Dispatchers.IO) {
                            BitmapFactory.decodeFile(
                                captureFile.absolutePath
                            )
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
                                            "Не удалось инициализировать OpenCV"
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
                                            "Некорректные координаты доски"
                                        )
                                    }

                                    val crop = Bitmap.createBitmap(
                                        source,
                                        left,
                                        top,
                                        width,
                                        height
                                    )

                                    val recognition =
                                        recognizer!!.recognize(crop)

                                    Triple(
                                        crop,
                                        recognition,
                                        "Доска найдена. Распознаны 64 клетки."
                                    )
                                }

                                detectedBitmap = result.first
                                recognitionResult = result.second
                                detectionMessage = result.third

                            } catch (e: Exception) {
                                detectionMessage =
                                    "Ошибка: ${e.message}"

                                recognitionResult = null
                                detectedBitmap = null

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

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBackground)
            .verticalScroll(rememberScrollState())
            .padding(16.dp)
    ) {

        Text(
            text = "CHESS VISION",
            color = LimeColor,
            fontSize = 28.sp,
            fontWeight = FontWeight.Black
        )

        Text(
            text = "Автоматическое распознавание шахматных позиций",
            color = Color.LightGray,
            fontSize = 13.sp
        )

        Spacer(modifier = Modifier.height(20.dp))

        Text(
            text = "ЗАХВАТ ЭКРАНА",
            color = LimeColor,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        Button(
            onClick = {
                if (capturing) {
                    onStopCapture()
                    capturing = false
                    detectionMessage = "Захват остановлен"
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
                text = if (capturing) {
                    "ОСТАНОВИТЬ ЗАХВАТ"
                } else {
                    "НАЧАТЬ ЗАХВАТ ЭКРАНА"
                },
                fontWeight = FontWeight.Bold
            )
        }

        Spacer(modifier = Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {
            Box(
                modifier = Modifier
                    .size(9.dp)
                    .background(
                        if (capturing) LimeColor else Color.Gray,
                        RoundedCornerShape(50)
                    )
            )

            Spacer(modifier = Modifier.width(8.dp))

            Text(
                text = if (capturing) {
                    "Захват активен"
                } else {
                    "Захват не запущен"
                },
                color = Color.LightGray,
                fontSize = 13.sp
            )
        }

        Spacer(modifier = Modifier.height(22.dp))

        Text(
            text = "ПОСЛЕДНИЙ КАДР",
            color = LimeColor,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        previewBitmap?.let { bitmap ->
            Image(
                bitmap = bitmap.asImageBitmap(),
                contentDescription = "Последний кадр экрана",
                modifier = Modifier
                    .fillMaxWidth()
                    .height(210.dp)
                    .background(Color.Black),
                contentScale = ContentScale.Fit
            )
        } ?: Text(
            text = "Здесь появится изображение экрана",
            color = Color.Gray
        )

        Spacer(modifier = Modifier.height(22.dp))

        Text(
            text = "РАСПОЗНАВАНИЕ ДОСКИ",
            color = LimeColor,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(10.dp))

        Row(verticalAlignment = Alignment.CenterVertically) {

            if (isDetecting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(18.dp),
                    color = LimeColor,
                    strokeWidth = 2.dp
                )

                Spacer(modifier = Modifier.width(10.dp))
            }

            Text(
                text = detectionMessage,
                color = Color.LightGray,
                fontSize = 13.sp
            )
        }

        detectedBitmap?.let { bitmap ->

            Spacer(modifier = Modifier.height(14.dp))

            Text(
                text = "НАЙДЕННАЯ ДОСКА",
                color = LimeColor,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                Image(
                    bitmap = bitmap.asImageBitmap(),
                    contentDescription = "Обнаруженная шахматная доска",
                    modifier = Modifier.fillMaxSize(),
                    contentScale = ContentScale.FillBounds
                )

                Canvas(modifier = Modifier.fillMaxSize()) {
                    for (i in 1..7) {
                        val x = size.width * i / 8f
                        val y = size.height * i / 8f

                        drawLine(
                            color = Color.White.copy(alpha = 0.75f),
                            start = Offset(x, 0f),
                            end = Offset(x, size.height),
                            strokeWidth = 1.dp.toPx()
                        )

                        drawLine(
                            color = Color.White.copy(alpha = 0.75f),
                            start = Offset(0f, y),
                            end = Offset(size.width, y),
                            strokeWidth = 1.dp.toPx()
                        )
                    }
                }
            }
        }

        recognitionResult?.let { result ->

            Spacer(modifier = Modifier.height(22.dp))

            Text(
                text = "РАСПОЗНАННЫЕ ФИГУРЫ — 8×8",
                color = LimeColor,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(10.dp))

            RecognizedBoard(
                placement = result.placement
            )

            Spacer(modifier = Modifier.height(18.dp))

            Text(
                text = "FEN",
                color = LimeColor,
                fontWeight = FontWeight.Bold
            )

            Spacer(modifier = Modifier.height(8.dp))

            Surface(
                color = PanelBackground,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = result.fen,
                    color = Color.White,
                    fontSize = 13.sp,
                    modifier = Modifier.padding(12.dp)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Text(
                text = "Средняя уверенность: ${
                    String.format("%.1f", result.meanConfidence * 100)
                }%",
                color = Color.LightGray,
                fontSize = 13.sp
            )

            Text(
                text = "Минимальная уверенность: ${
                    String.format("%.1f", result.minConfidence * 100)
                }%",
                color = Color.LightGray,
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = if (result.reliable) {
                    "Распознавание прошло проверку уверенности."
                } else {
                    "Низкая уверенность: возможны ошибки распознавания."
                },
                color = if (result.reliable) {
                    LimeColor
                } else {
                    Color(0xFFFFB45C)
                },
                fontSize = 13.sp
            )

            Spacer(modifier = Modifier.height(6.dp))

            Text(
                text = "Права рокировки и остальные игровые поля FEN пока предполагаются автоматически.",
                color = Color.Gray,
                fontSize = 11.sp
            )
        }

        Spacer(modifier = Modifier.height(30.dp))
    }
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
                    val isLight = (rowIndex + columnIndex) % 2 == 0

                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .fillMaxHeight()
                            .background(
                                if (isLight) {
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
