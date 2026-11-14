package com.chessvision.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
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
            ChessVisionApp()
        }
    }
}

@Composable
fun ChessVisionApp() {
    var selectedTab by remember { mutableIntStateOf(0) }
    val tabs = listOf("Главная", "Тренировка", "История", "Настройки")

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
        Scaffold(
            containerColor = Background,
            bottomBar = {
                NavigationBar(
                    containerColor = Panel
                ) {
                    tabs.forEachIndexed { index, tab ->
                        NavigationBarItem(
                            selected = selectedTab == index,
                            onClick = { selectedTab = index },
                            icon = {
                                Text(
                                    text = listOf("♟", "♞", "◷", "⚙")[index],
                                    fontSize = 20.sp
                                )
                            },
                            label = { Text(tab) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Background)
                    .padding(padding)
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

                when (selectedTab) {
                    0 -> HomeScreen()
                    1 -> InfoScreen(
                        "Тренировка",
                        "Анализируй позиции, изучай лучшие продолжения и развивай шахматное мышление."
                    )
                    2 -> InfoScreen(
                        "История партий",
                        "Здесь появится история твоих завершённых партий."
                    )
                    3 -> InfoScreen(
                        "Настройки",
                        "Настройки приложения и шахматного движка."
                    )
                }
            }
        }
    }
}

@Composable
fun HomeScreen() {
    Text(
        "Твой шахматный\nанализатор.",
        color = Color.White,
        fontSize = 32.sp,
        lineHeight = 39.sp,
        fontWeight = FontWeight.Bold
    )

    Spacer(Modifier.height(12.dp))

    Text(
        "Анализируй партии. Изучай ошибки. Становись сильнее.",
        color = Muted,
        fontSize = 15.sp
    )

    Spacer(Modifier.height(30.dp))

    Card(
        colors = CardDefaults.cardColors(containerColor = Panel),
        shape = RoundedCornerShape(22.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(Modifier.padding(20.dp)) {
            Text(
                "АНАЛИЗ ПОЗИЦИИ",
                color = Lime,
                fontSize = 12.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.5.sp
            )

            Spacer(Modifier.height(12.dp))

            Text(
                "Stockfish 10",
                color = Color.White,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold
            )

            Spacer(Modifier.height(8.dp))

            Text(
                "Шахматный движок для анализа завершённых партий и тренировочных позиций.",
                color = Muted,
                fontSize = 14.sp
            )

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = {},
                modifier = Modifier.fillMaxWidth(),
                colors = ButtonDefaults.buttonColors(
                    containerColor = Lime,
                    contentColor = Color.Black
                ),
                shape = RoundedCornerShape(13.dp)
            ) {
                Text(
                    "Начать анализ",
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }

    Spacer(Modifier.height(20.dp))

    Text(
        "ВОЗМОЖНОСТИ",
        color = Muted,
        fontSize = 12.sp,
        fontWeight = FontWeight.Bold,
        letterSpacing = 2.sp
    )

    Spacer(Modifier.height(12.dp))

    FeatureCard("♟", "Анализ партий", "Разбор завершённых шахматных партий")
    Spacer(Modifier.height(10.dp))
    FeatureCard("♞", "Stockfish 10", "Локальный шахматный движок")
    Spacer(Modifier.height(10.dp))
    FeatureCard("◈", "Lichess", "Работа с историей партий и тренировками")
}

@Composable
fun FeatureCard(icon: String, title: String, description: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Panel, RoundedCornerShape(15.dp))
            .padding(15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(icon, color = Lime, fontSize = 25.sp)
        Spacer(Modifier.width(14.dp))
        Column {
            Text(title, color = Color.White, fontWeight = FontWeight.Bold)
            Text(description, color = Muted, fontSize = 12.sp)
        }
    }
}

@Composable
fun InfoScreen(title: String, description: String) {
    Text(
        title,
        color = Color.White,
        fontSize = 30.sp,
        fontWeight = FontWeight.Bold
    )
    Spacer(Modifier.height(14.dp))
    Text(description, color = Muted, fontSize = 15.sp)
}


on:
  push:
    branches: [main, master]
  workflow_dispatch:

permissions:
  contents: read

jobs:
  build:
    runs-on: ubuntu-latest

    steps:
      - name: Checkout
        uses: actions/checkout@v4

      - name: Set up JDK 17
        uses: actions/setup-java@v4
        with:
          distribution: temurin
          java-version: 17

      - name: Set up Gradle
        uses: gradle/actions/setup-gradle@v4
        with:
          gradle-version: 8.9

      - name: Build APK
        run: gradle assembleDebug

      - name: Upload APK
        uses: actions/upload-artifact@v4
        with:
          name: chess-vision-debug-apk
          path: app/build/outputs/apk/debug/app-debug.apk
