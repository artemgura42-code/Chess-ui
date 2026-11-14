
package com.chessvision.app

import android.app.Activity
import android.content.Intent
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

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

    val context = androidx.compose.ui.platform.LocalContext.current

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
                .padding(22.dp)
        ) {
            Text(
                "CHESS VISION",
                color = Lime,
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 3.sp
            )

            Spacer(Modifier.height(35.dp))

            Text(
                "Захват экрана",
                color = Color.White,
                fontSize = 30.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(12.dp))

            Text(
                "Получение изображения шахматной партии с экрана телефона.",
                color = Muted,
                fontSize = 15.sp
            )

            Spacer(Modifier.height(28.dp))

            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(20.dp),
                colors = CardDefaults.cardColors(
                    containerColor = Panel
                )
            ) {
                Column(
                    modifier = Modifier.padding(20.dp)
                ) {
                    Text(
                        "СОСТОЯНИЕ СИСТЕМЫ",
                        color = Lime,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.5.sp
                    )

                    Spacer(Modifier.height(15.dp))

                    Text(
                        if (capturing) "Захват активен" else "Ожидание запуска",
                        color = Color.White,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )

                    Spacer(Modifier.height(8.dp))

                    Text(
                        message,
                        color = Muted,
                        fontSize = 14.sp
                    )

                    Spacer(Modifier.height(22.dp))

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
                        ),
                        shape = RoundedCornerShape(13.dp)
                    ) {
                        Text(
                            "Начать захват экрана",
                            fontWeight = FontWeight.Bold
                        )
                    }

                    Spacer(Modifier.height(10.dp))

                    OutlinedButton(
                        onClick = {
                            onStopCapture()
                            capturing = false
                            message = "Захват остановлен."
                        },
                        enabled = capturing,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Остановить захват")
                    }
                }
            }

            Spacer(Modifier.height(24.dp))

            Text(
                "КАК ЭТО РАБОТАЕТ",
                color = Muted,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )

            Spacer(Modifier.height(12.dp))

            InfoItem("01", "Разрешение Android", "Ты подтверждаешь системный запрос.")
            InfoItem("02", "Захват изображения", "Приложение получает кадры экрана.")
            InfoItem("03", "Сохранение кадра", "Последний кадр хранится внутри приложения.")

            Spacer(Modifier.weight(1f))

            Text(
                "Захват можно остановить в любой момент.",
                color = Muted,
                fontSize = 12.sp
            )
        }
    }
}

@Composable
fun InfoItem(number: String, title: String, description: String) {
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
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(description, color = Muted, fontSize = 12.sp)
        }
    }
}
