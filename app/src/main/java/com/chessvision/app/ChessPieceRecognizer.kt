
package com.chessvision.app

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer
import kotlin.math.abs

data class PieceRecognitionResult(
    val placement: String,
    val fen: String,
    val meanConfidence: Float,
    val minConfidence: Float,
    val reliable: Boolean,
    val squares: List<String>
)

class ChessPieceRecognizer(context: Context) : Closeable {

    companion object {
        private const val MODEL_NAME = "chess-tiles-v2.onnx"
        private const val CONFIDENCE_LIMIT = 0.70f

        // Точный порядок классов из Fenshot.
        // 1 = пустая клетка.
        private const val LABELS = "1KQRBNPkqrbnp"
    }

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    init {
        val modelBytes = context.applicationContext.assets
            .open(MODEL_NAME)
            .use { it.readBytes() }

        val options = OrtSession.SessionOptions()

        session = try {
            environment.createSession(modelBytes, options)
        } finally {
            options.close()
        }

        require(session.inputNames.contains("tiles")) {
            "В модели отсутствует вход tiles"
        }

        require(session.outputNames.contains("probs")) {
            "В модели отсутствует выход probs"
        }
    }

    @Synchronized
    fun recognize(boardBitmap: Bitmap): PieceRecognitionResult {

        require(!boardBitmap.isRecycled) {
            "Изображение доски освобождено"
        }

        val resized = Bitmap.createScaledBitmap(
            boardBitmap,
            256,
            256,
            true
        )

        try {
            // Формат модели: [64, 1024].
            // Клетки идут от A1 до H8.
            // Каждая клетка — 32x32 пикселя.
            val input = FloatArray(64 * 1024)

            for (rank in 0 until 8) {
                for (file in 0 until 8) {

                    val tileIndex = rank * 8 + file

                    val startX = file * 32
                    val startY = (7 - rank) * 32

                    var pixelIndex = 0

                    for (y in 0 until 32) {
                        for (x in 0 until 32) {

                            val color = resized.getPixel(
                                startX + x,
                                startY + y
                            )

                            val red = (color shr 16) and 255
                            val green = (color shr 8) and 255
                            val blue = color and 255

                            val gray = (
                                0.299f * red +
                                0.587f * green +
                                0.114f * blue
                            ) / 255f

                            input[
                                tileIndex * 1024 + pixelIndex
                            ] = gray

                            pixelIndex++
                        }
                    }
                }
            }

            val tensor = OnnxTensor.createTensor(
                environment,
                FloatBuffer.wrap(input),
                longArrayOf(64, 1024)
            )

            try {
                val results = session.run(
                    mapOf("tiles" to tensor)
                )

                try {
                    val outputTensor =
                        results.get("probs").orElseThrow {
                            IllegalStateException(
                                "Модель не вернула probs"
                            )
                        } as OnnxTensor

                    val probabilities =
                        outputTensor.floatBuffer

                    require(probabilities.remaining() == 64 * 13) {
                        "Неверный размер выхода модели"
                    }

                    val board = Array(8) {
                        CharArray(8) { '.' }
                    }

                    val confidences = mutableListOf<Float>()
                    val squareResults = mutableListOf<String>()

                    for (rank in 0 until 8) {
                        for (file in 0 until 8) {

                            val tileIndex = rank * 8 + file

                            var bestClass = 0
                            var bestProbability = -1f

                            for (classIndex in 0 until 13) {

                                val probability =
                                    probabilities.get(
                                        tileIndex * 13 + classIndex
                                    )

                                if (probability > bestProbability) {
                                    bestProbability = probability
                                    bestClass = classIndex
                                }
                            }

                            val label = LABELS[bestClass]

                            val piece = if (label == '1') {
                                '.'
                            } else {
                                label
                            }

                            board[rank][file] = piece

                            val confidence =
                                bestProbability.coerceIn(0f, 1f)

                            confidences.add(confidence)

                            val squareName =
                                "${'a' + file}${rank + 1}"

                            squareResults.add(
                                "$squareName: $piece " +
                                "(${String.format("%.2f", confidence)})"
                            )
                        }
                    }

                    val placement = buildPlacement(board)

                    val meanConfidence =
                        confidences.average().toFloat()

                    val minConfidence =
                        confidences.minOrNull() ?: 0f

                    val castling = inferCastling(board)

                    val fen = "$placement w $castling - 0 1"

                    return PieceRecognitionResult(
                        placement = placement,
                        fen = fen,
                        meanConfidence = meanConfidence,
                        minConfidence = minConfidence,
                        reliable = minConfidence >= CONFIDENCE_LIMIT,
                        squares = squareResults
                    )

                } finally {
                    results.close()
                }

            } finally {
                tensor.close()
            }

        } finally {
            if (resized !== boardBitmap) {
                resized.recycle()
            }
        }
    }

    private fun buildPlacement(
        board: Array<CharArray>
    ): String {

        return (7 downTo 0).joinToString("/") { rank ->

            val result = StringBuilder()
            var empty = 0

            for (piece in board[rank]) {

                if (piece == '.') {
                    empty++
                } else {
                    if (empty > 0) {
                        result.append(empty)
                        empty = 0
                    }

                    result.append(piece)
                }
            }

            if (empty > 0) {
                result.append(empty)
            }

            result.toString()
        }
    }

    private fun inferCastling(
        board: Array<CharArray>
    ): String {

        val rights = StringBuilder()

        if (board[7][4] == 'K') {
            if (board[7][7] == 'R') rights.append('K')
            if (board[7][0] == 'R') rights.append('Q')
        }

        if (board[0][4] == 'k') {
            if (board[0][7] == 'r') rights.append('k')
            if (board[0][0] == 'r') rights.append('q')
        }

        return if (rights.isEmpty()) "-" else rights.toString()
    }

    override fun close() {
        session.close()
    }
}
