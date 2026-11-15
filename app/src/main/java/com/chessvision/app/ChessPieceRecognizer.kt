
package com.chessvision.app

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.lang.reflect.Array as ReflectArray
import java.nio.FloatBuffer
import kotlin.math.exp

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

        private val LABELS = charArrayOf(
            '.', 'K', 'Q', 'R', 'B', 'N', 'P',
            'k', 'q', 'r', 'b', 'n', 'p'
        )

        private const val CONFIDENCE_LIMIT = 0.70f
    }

    private val environment = OrtEnvironment.getEnvironment()

    private val session: OrtSession

    private val inputName: String

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

        inputName = session.inputNames.first()
    }

    @Synchronized
    fun recognize(boardBitmap: Bitmap): PieceRecognitionResult {

        require(!boardBitmap.isRecycled) {
            "Изображение доски уже освобождено"
        }

        val resized = Bitmap.createScaledBitmap(
            boardBitmap,
            256,
            256,
            true
        )

        try {
            val input = FloatArray(64 * 1024)

            // Порядок клеток: A1, B1 ... H1, A2 ... H8.
            // Белые фигуры находятся снизу.

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

                            val red = (color shr 16) and 0xFF
                            val green = (color shr 8) and 0xFF
                            val blue = color and 0xFF

                            val gray =
                                (0.299f * red +
                                 0.587f * green +
                                 0.114f * blue) / 255f

                            input[tileIndex * 1024 + pixelIndex] = gray

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
                val output = session.run(
                    mapOf(inputName to tensor)
                )

                try {
                    val raw = flatten(output[0].value)

                    require(raw.size == 64 * 13) {
                        "Неожиданный размер результата модели: ${raw.size}"
                    }

                    val board = Array(8) { CharArray(8) { '.' } }
                    val confidences = mutableListOf<Float>()
                    val squareResults = mutableListOf<String>()

                    for (rank in 0 until 8) {
                        for (file in 0 until 8) {

                            val index = rank * 8 + file
                            val offset = index * 13

                            val values = raw.subList(
                                offset,
                                offset + 13
                            )

                            val probabilities = probabilities(values)

                            var bestClass = 0

                            for (i in 1 until 13) {
                                if (probabilities[i] >
                                    probabilities[bestClass]) {
                                    bestClass = i
                                }
                            }

                            val piece = LABELS[bestClass]

                            // rank 0 соответствует нижнему ряду.
                            board[7 - rank][file] = piece

                            val confidence = probabilities[bestClass]
                            confidences.add(confidence)

                            val squareName =
                                "${'a' + file}${rank + 1}"

                            squareResults.add(
                                "$squareName: $piece (${String.format("%.2f", confidence)})"
                            )
                        }
                    }

                    val placement = buildPlacement(board)
                    val mean = confidences.average().toFloat()
                    val minimum = confidences.minOrNull() ?: 0f

                    val castling = inferCastling(board)

                    val fen = buildString {
                        append(placement)
                        append(" w ")
                        append(castling)
                        append(" - 0 1")
                    }

                    return PieceRecognitionResult(
                        placement = placement,
                        fen = fen,
                        meanConfidence = mean,
                        minConfidence = minimum,
                        reliable = minimum >= CONFIDENCE_LIMIT,
                        squares = squareResults
                    )

                } finally {
                    output.close()
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

    private fun probabilities(values: List<Float>): FloatArray {

        val sum = values.sum()

        // Если модель уже вернула вероятности,
        // сохраняем их без повторной нормализации.

        if (values.all { it >= 0f && it <= 1f } &&
            kotlin.math.abs(sum - 1f) < 0.01f) {
            return values.toFloatArray()
        }

        // Иначе преобразуем выходные значения через softmax.

        val maxValue = values.maxOrNull() ?: 0f

        val exponents = values.map {
            exp((it - maxValue).coerceIn(-80f, 0f))
        }

        val total = exponents.sum()

        return FloatArray(13) { i ->
            exponents[i] / total
        }
    }

    private fun flatten(value: Any): List<Float> {

        val result = mutableListOf<Float>()

        fun visit(item: Any?) {
            if (item == null) return

            if (item is Number) {
                result.add(item.toFloat())
                return
            }

            if (item.javaClass.isArray) {
                val length = ReflectArray.getLength(item)

                for (i in 0 until length) {
                    visit(ReflectArray.get(item, i))
                }

                return
            }

            throw IllegalArgumentException(
                "Неизвестный тип выхода ONNX: ${item.javaClass.name}"
            )
        }

        visit(value)
        return result
    }

    private fun buildPlacement(board: Array<CharArray>): String {

        return (0 until 8).joinToString("/") { row ->

            val result = StringBuilder()
            var empty = 0

            for (piece in board[row]) {

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

    private fun inferCastling(board: Array<CharArray>): String {

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
