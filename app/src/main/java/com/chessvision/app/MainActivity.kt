
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
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

    // Игровые параметры FEN.
    var sideToMove by remember { mutableStateOf("w") }

    var whiteKingSide by remember { mutableStateOf(false) }
    var whiteQueenSide by remember { mutableStateOf(false) }
    var blackKingSide by remember { mutableStateOf(false) }
    var blackQueenSide by remember { mutableStateOf(false) }

    var enPassant by remember { mutableStateOf("-") }
    var halfmoveClock by remember { mutableStateOf("0") }
    var fullmoveNumber by remember { mutableStateOf("1") }

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

    // Автоматический поиск доски и распознавание новой позиции.
    LaunchedEffect(capturing) {

        if (!capturing) {
            isDetecting = false
            return@LaunchedEffect
        }

        var lastSignature = ""
        var recognizer: ChessPieceRecognizer? = null

        try {
            while (isActive) {

                val file = java.io.File(
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

                            } catch (e: Exception) {
                                message = "Ошибка: ${e.message}"
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

    // Проверка игровых параметров.
    val halfmove = halfmoveClock.toIntOrNull()
    val fullmove = fullmoveNumber.toIntOrNull()

    val validEnPassant =
        enPassant == "-" ||
        enPassant.matches(Regex("[a-h][36]"))

    val validCounters =
        halfmove != null &&
        halfmove >= 0 &&
        fullmove != null &&
        fullmove >= 1

    val castling = buildString {
        if (whiteKingSide) append("K")
        if (whiteQueenSide) append("Q")
        if (blackKingSide) append("k")
        if (blackQueenSide) append("q")
    }.ifEmpty { "-" }

    val finalFen = recognitionResult?.let { recognized ->
        if (validEnPassant && validCounters) {
            "${recognized.placement} $sideToMove $castling " +
                "$enPassant $halfmove $fullmove"
        } else {
            null
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
            "Распознавание шахматных позиций",
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

            Spacer(Modifier.height(24.dp))

            SectionTitle("ПАРАМЕТРЫ ПОЗИЦИИ FEN")

            Text(
                "Чей сейчас ход?",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                RadioButton(
                    selected = sideToMove == "w",
                    onClick = { sideToMove = "w" },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = LimeColor
                    )
                )
                Text("Белые", color = Color.White)

                Spacer(Modifier.width(20.dp))

                RadioButton(
                    selected = sideToMove == "b",
                    onClick = { sideToMove = "b" },
                    colors = RadioButtonDefaults.colors(
                        selectedColor = LimeColor
                    )
                )
                Text("Чёрные", color = Color.White)
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "Права рокировки",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Text(
                "Отметь только те права, которые сохранились в партии.",
                color = Color.Gray,
                fontSize = 12.sp
            )

            CastlingCheckbox(
                "Белые: короткая (K)",
                whiteKingSide
            ) { whiteKingSide = it }

            CastlingCheckbox(
                "Белые: длинная (Q)",
                whiteQueenSide
            ) { whiteQueenSide = it }

            CastlingCheckbox(
                "Чёрные: короткая (k)",
                blackKingSide
            ) { blackKingSide = it }

            CastlingCheckbox(
                "Чёрные: длинная (q)",
                blackQueenSide
            ) { blackQueenSide = it }

            Spacer(Modifier.height(12.dp))

            Text(
                "Взятие на проходе",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = enPassant,
                onValueChange = {
                    enPassant = it.lowercase().take(2)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                placeholder = { Text("- или, например, e3") },
                isError = !validEnPassant,
                colors = fenFieldColors()
            )

            if (!validEnPassant) {
                InfoText("Допустимы '-' или клетка e3/e6.")
            }

            Spacer(Modifier.height(12.dp))

            Text(
                "Счётчик полуходов",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = halfmoveClock,
                onValueChange = {
                    halfmoveClock = it.filter(Char::isDigit).take(6)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number
                ),
                colors = fenFieldColors()
            )

            Spacer(Modifier.height(12.dp))

            Text(
                "Номер полного хода",
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            OutlinedTextField(
                value = fullmoveNumber,
                onValueChange = {
                    fullmoveNumber = it.filter(Char::isDigit).take(6)
                },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Number
                ),
                colors = fenFieldColors()
            )

            if (!validCounters) {
                InfoText(
                    "Полуходы должны быть неотрицательными, номер хода — от 1."
                )
            }

            Spacer(Modifier.height(22.dp))

            SectionTitle("ПОЛНАЯ FEN-ЗАПИСЬ")

            Surface(
                color = PanelBackground,
                shape = RoundedCornerShape(10.dp),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = finalFen ?: "Проверь игровые параметры",
                    color = if (finalFen != null) LimeColor
                    else Color(0xFFFFB45C),
                    fontSize = 14.sp,
                    modifier = Modifier.padding(14.dp)
                )
            }

            Spacer(Modifier.height(10.dp))

            Button(
                onClick = {
                    finalFen?.let {
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
                enabled = finalFen != null,
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
private fun CastlingCheckbox(
    label: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        verticalAlignment = Alignment.CenterVertically
    ) {
        Checkbox(
            checked = checked,
            onCheckedChange = onCheckedChange,
            colors = CheckboxDefaults.colors(
                checkedColor = LimeColor,
                checkmarkColor = Color.Black
            )
        )

        Text(
            label,
            color = Color.White,
            fontSize = 14.sp
        )
    }
}

@Composable
private fun fenFieldColors() = OutlinedTextFieldDefaults.colors(
    focusedTextColor = Color.White,
    unfocusedTextColor = Color.White,
    focusedBorderColor = LimeColor,
    unfocusedBorderColor = Color.Gray,
    cursorColor = LimeColor,
    focusedLabelColor = LimeColor
)

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
