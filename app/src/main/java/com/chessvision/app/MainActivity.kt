
package com.chessvision.app

import android.app.Activity
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.delay
import java.io.File

private val Background = Color(0xFF090D0B)
private val Panel = Color(0xFF151D18)
private val Lime = Color(0xFFB7F34A)
private val Muted = Color(0xFF9AA69D)

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

                    if (Build.VERSION.SDK_INT >= 26) {
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
    onStartCapture: (Int, Intent) -> Unit,
    onStopCapture: () -> Unit
) {
    var capturing by remember { mutableStateOf(false) }
    var message by remember {
        mutableStateOf("Захват экрана пока не запущен.")
    }

    var previewBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var detectedBitmap by remember { mutableStateOf<Bitmap?>(null) }

    var detectionMessage by remember {
        mutableStateOf("Доска ещё не проверялась.")
    }

    var isDetecting by remember { mutableStateOf(false) }

    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val captureLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK &&
            result.data != null
        ) {
            onStartCapture(result.resultCode, result.data!!)
            capturing = true
            message = "Захват экрана активен."
        } else {
            message = "Разрешение на захват не предоставлено."
        }
    }

    // Получение последнего кадра
    LaunchedEffect(capturing) {
        while (capturing) {
            val file = File(
                context.filesDir,
                "last_screen_capture.png"
            )

            if (file.exists() && file.length() > 0) {
                val bitmap = withContext(Dispatchers.IO) {
                    BitmapFactory.decodeFile(file.absolutePath)
                }

                if (bitmap != null) {
                    previewBitmap = bitmap
                }
            }

            delay(1000)
        }
    }

    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Background,
            surface = Panel,
            primary = Lime,
            onPrimary = Color.Black,
            onBackground = Color.White,
            onSurface = Color.White
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(Background)
                .verticalScroll(rememberScrollState())
                .padding(22.dp)
        ) {

            Text(
                "CHESS VISION",
                color = Lime,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )

            Spacer(Modifier.height(28.dp))

            Text(
                "Компьютерное зрение",
                color = Color.White,
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(10.dp))

            Text(
                "Захват изображения и обнаружение шахматной доски.",
                color = Muted,
                fontSize = 14.sp
            )

            Spacer(Modifier.height(24.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Panel
                )
            ) {
                Column(Modifier.padding(18.dp)) {

                    Text(
                        "ЗАХВАТ ЭКРАНА",
                        color = Lime,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(12.dp))

                    Text(
                        if (capturing) "Захват активен"
                        else "Ожидание запуска",
                        color = Color.White,
                        fontSize = 21.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(message, color = Muted, fontSize = 13.sp)

                    Spacer(Modifier.height(18.dp))

                    Button(
                        onClick = {
                            val manager = context.getSystemService(
                                Activity.MEDIA_PROJECTION_SERVICE
                            ) as MediaProjectionManager

                            captureLauncher.launch(
                                manager.createScreenCaptureIntent()
                            )
                        },
                        enabled = !capturing,
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(
                            containerColor = Lime,
                            contentColor = Color.Black
                        )
                    ) {
                        Text("Начать захват экрана")
                    }

                    Spacer(Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = {
                            onStopCapture()
                            capturing = false
                            message = "Захват остановлен."
                        },
                        enabled = capturing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Остановить захват", color = Lime)
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "ПОСЛЕДНИЙ КАДР",
                color = Lime,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(12.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(18.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Panel
                )
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                        .padding(10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val bitmap = previewBitmap

                    if (bitmap != null) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "Захваченный экран",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Fit
                        )
                    } else {
                        Text(
                            "Ожидание изображения...",
                            color = Muted
                        )
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "РАСПОЗНАВАНИЕ ДОСКИ",
                color = Lime,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(10.dp))

            Text(
                detectionMessage,
                color = Muted,
                fontSize = 14.sp
            )

            Spacer(Modifier.height(14.dp))

            Button(
                onClick = {
                    val source = previewBitmap

                    if (source == null) {
                        detectionMessage =
                            "Сначала необходимо получить изображение экрана."
                    } else if (!isDetecting) {

                        isDetecting = true
                        detectedBitmap = null
                        detectionMessage = "Ищем шахматную доску..."

                        scope.launch {
                            try {
                                val result = withContext(Dispatchers.Default) {
                                    if (!ChessBoardDetector.initialize()) {
                                        throw IllegalStateException(
                                            "Не удалось загрузить OpenCV."
                                        )
                                    }

                                    ChessBoardDetector.detect(source)
                                }

                                if (result != null) {
                                    val left = result.x.coerceIn(
                                        0,
                                        source.width - 1
                                    )
                                    val top = result.y.coerceIn(
                                        0,
                                        source.height - 1
                                    )

                                    val width = result.width.coerceAtMost(
                                        source.width - left
                                    )

                                    val height = result.height.coerceAtMost(
                                        source.height - top
                                    )

                                    if (width > 0 && height > 0) {
                                        detectedBitmap = Bitmap.createBitmap(
                                            source,
                                            left,
                                            top,
                                            width,
                                            height
                                        )

                                        detectionMessage =
                                            "Найдена квадратная область: " +
                                            "${width} × ${height} px. " +
                                            "Координаты: $left, $top."
                                    } else {
                                        detectionMessage =
                                            "Обнаруженная область имеет неверные размеры."
                                    }
                                } else {
                                    detectionMessage =
                                        "Подходящая квадратная область не найдена."
                                }

                            } catch (e: Exception) {
                                detectionMessage =
                                    "Ошибка распознавания: ${e.message}"
                            } finally {
                                isDetecting = false
                            }
                        }
                    }
                },
                enabled = previewBitmap != null && !isDetecting,
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Lime,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(13.dp)
            ) {
                Text(
                    if (isDetecting) "Выполняется поиск..."
                    else "Найти шахматную доску",
                    fontWeight = FontWeight.Bold
                )
            }

            Spacer(Modifier.height(16.dp))

            if (detectedBitmap != null) {
                Text(
                    "НАЙДЕННАЯ ОБЛАСТЬ",
                    color = Lime,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(10.dp))

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = Panel
                    ),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Image(
                        bitmap = detectedBitmap!!.asImageBitmap(),
                        contentDescription = "Обнаруженная квадратная область",
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(260.dp)
                            .padding(10.dp),
                        contentScale = ContentScale.Fit
                    )
                }
            }

            Spacer(Modifier.height(28.dp))

            Text(
                "СЛЕДУЮЩИЕ ЭТАПЫ",
                color = Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(12.dp))

            InfoItem("01", "Обнаружение доски", "Поиск квадратной области.")
            InfoItem("02", "Разделение клеток", "Создание сетки 8 × 8.")
            InfoItem("03", "Распознавание фигур", "Определение типа и цвета фигур.")
            InfoItem("04", "Формирование FEN", "Подготовка позиции для анализа.")

            Spacer(Modifier.height(20.dp))

            Text(
                "CHESS VISION",
                color = Muted,
                fontSize = 11.sp
            )
        }
    }
}

@Composable
fun InfoItem(
    number: String,
    title: String,
    description: String
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            number,
            color = Lime,
            fontWeight = FontWeight.Bold,
            fontSize = 16.sp
        )

        Spacer(Modifier.width(14.dp))

        Column {
            Text(
                title,
                color = Color.White,
                fontWeight = FontWeight.Bold
            )

            Text(
                description,
                color = Muted,
                fontSize = 12.sp
            )
        }
    }
}
