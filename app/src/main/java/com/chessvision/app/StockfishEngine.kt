
package com.chessvision.app

import android.content.Context
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.util.Locale
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

data class StockfishResult(
    val bestMove: String,
    val evaluation: String,
    val depth: Int
)

class StockfishEngine(
    private val context: Context
) : Closeable {

    private var process: Process? = null
    private var writer: BufferedWriter? = null

    private val output = LinkedBlockingQueue<String>()

    companion object {
        private const val SEARCH_DEPTH = 12
        private const val SEARCH_TIMEOUT_MS = 30000L
    }

    private fun startEngine() {
        if (process?.isAlive == true) return

        process = null
        writer = null
        output.clear()

        val engineFile = File(
            context.applicationInfo.nativeLibraryDir,
            "libstockfish.so"
        )

        if (!engineFile.exists()) {
            throw IllegalStateException(
                "Stockfish не найден: ${engineFile.absolutePath}"
            )
        }

        if (!engineFile.canExecute()) {
            throw IllegalStateException(
                "Нет разрешения на запуск Stockfish: " +
                    engineFile.absolutePath
            )
        }

        val started = try {
            ProcessBuilder(engineFile.absolutePath)
                .directory(engineFile.parentFile)
                .redirectErrorStream(true)
                .start()
        } catch (e: Exception) {
            throw IllegalStateException(
                "Не удалось запустить ARM64 Stockfish: ${e.message}",
                e
            )
        }

        process = started

        writer = BufferedWriter(
            OutputStreamWriter(started.outputStream)
        )

        val reader = BufferedReader(
            InputStreamReader(started.inputStream)
        )

        Thread {
            try {
                while (true) {
                    val line = reader.readLine() ?: break
                    output.offer(line)
                }
            } catch (_: Exception) {
            }
        }.apply {
            isDaemon = true
            name = "StockfishOutput"
            start()
        }

        send("uci")
        waitFor("uciok", 30)

        send("setoption name Threads value 2")
        send("setoption name Hash value 64")
        send("isready")

        waitFor("readyok", 30)
    }

    private fun send(command: String) {
        writer?.apply {
            write(command)
            newLine()
            flush()
        } ?: throw IllegalStateException(
            "Движок не запущен"
        )
    }

    private fun waitFor(
        expected: String,
        timeoutSeconds: Long
    ) {
        val deadline = System.currentTimeMillis() +
            timeoutSeconds * 1000L

        while (System.currentTimeMillis() < deadline) {
            val line = output.poll(1, TimeUnit.SECONDS)
                ?: continue

            if (line.trim() == expected) return
        }

        throw IllegalStateException(
            "Stockfish не ответил: $expected"
        )
    }

    @Synchronized
    fun analyze(fen: String): StockfishResult {

        require(fen.trim().split(Regex("\\s+")).size == 6) {
            "Некорректная FEN-запись"
        }

        startEngine()

        output.clear()

        send("position fen $fen")
        send("go depth $SEARCH_DEPTH")

        var bestMove: String? = null
        var evaluation = "Оценка недоступна"
        var depth = 0

        val deadline = System.currentTimeMillis() +
            SEARCH_TIMEOUT_MS

        while (System.currentTimeMillis() < deadline) {

            val line = output.poll(1, TimeUnit.SECONDS)
                ?: continue

            if (line.startsWith("info ")) {

                Regex("""\bdepth (\d+)""")
                    .find(line)
                    ?.groupValues
                    ?.get(1)
                    ?.toIntOrNull()
                    ?.let { depth = it }

                val score = Regex(
                    """\bscore (cp|mate) (-?\d+)"""
                ).find(line)

                if (score != null) {
                    val type = score.groupValues[1]
                    val value = score.groupValues[2].toInt()

                    evaluation = if (type == "mate") {
                        "Мат в $value"
                    } else {
                        val pawns = value / 100.0

                        "%+.2f".format(
                            Locale.US,
                            pawns
                        )
                    }
                }
            }

            if (line.startsWith("bestmove ")) {
                bestMove = line
                    .removePrefix("bestmove ")
                    .substringBefore(" ")
                break
            }
        }

        if (bestMove == null) {
            try {
                send("stop")
            } catch (_: Exception) {
            }

            throw IllegalStateException(
                "Stockfish не завершил анализ за отведённое время"
            )
        }

        val move = bestMove

        if (move == "(none)" || move == "0000") {
            throw IllegalStateException(
                "Нет доступных ходов"
            )
        }

        return StockfishResult(
            bestMove = move,
            evaluation = evaluation,
            depth = depth
        )
    }

    @Synchronized
    override fun close() {
        try {
            writer?.write("quit")
            writer?.newLine()
            writer?.flush()
        } catch (_: Exception) {
        }

        try {
            process?.destroy()
        } catch (_: Exception) {
        }

        process = null
        writer = null
        output.clear()
    }
}
