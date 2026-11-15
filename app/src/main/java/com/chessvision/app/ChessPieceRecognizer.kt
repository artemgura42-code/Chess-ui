
package com.chessvision.app

import android.content.Context
import android.graphics.Bitmap
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.io.Closeable
import java.nio.FloatBuffer

data class PieceRecognitionResult(
    val placement: String,
    val fen: String,
    val meanConfidence: Float,
    val minConfidence: Float,
    val reliable: Boolean,
    val squares: List<String>,
    val boardFlipped: Boolean = false,
    val playerColor: String = "w"
)

class ChessPieceRecognizer(context: Context) : Closeable {

    companion object {
        private const val MODEL_NAME = "chess-tiles-v2.onnx"

        private const val MIN_MEAN_CONFIDENCE = 0.75f
        private const val LOW_CONFIDENCE_LIMIT = 0.30f
        private const val MIN_SINGLE_CONFIDENCE = 0.10f
        private const val MAX_LOW_CONFIDENCE_SQUARES = 8

        private const val ORIENTATION_CONFIDENCE = 0.35f

        // Порядок классов модели Fenshot.
        // 1 — пустая клетка.
        private const val LABELS = "1KQRBNPkqrbnp"
    }

    private val environment = OrtEnvironment.getEnvironment()
    private val session: OrtSession

    // Последняя достоверно определённая ориентация.
    private var lastKnownBoardFlipped = false

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
    fun recognize(
        boardBitmap: Bitmap
    ): PieceRecognitionResult {

        require(!boardBitmap.isRecycled) {
            "Изображение доски недоступно"
        }

        val resized = Bitmap.createScaledBitmap(
            boardBitmap,
            256,
            256,
            true
        )

        try {

            val input = FloatArray(64 * 1024)

            // Модель получает клетки в порядке A1-H8.
            // rank 0 — нижний ряд изображения.
            for (rank in 0 until 8) {
                for (file in 0 until 8) {

                    val tileIndex = rank * 8 + file

                    val startX = file * 32
                    val startY = (7 - rank) * 32

                    val offset = tileIndex * 1024

                    for (y in 0 until 32) {
                        for (x in 0 until 32) {

                            val pixel = resized.getPixel(
                                startX + x,
                                startY + y
                            )

                            val red = (pixel shr 16) and 0xFF
                            val green = (pixel shr 8) and 0xFF
                            val blue = pixel and 0xFF

                            val gray = (
                                red * 0.299f +
                                green * 0.587f +
                                blue * 0.114f
                            ) / 255f

                            input[offset + y * 32 + x] = gray
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
                    mapOf("tiles" to tensor)
                )

                try {

                    val outputTensor = output[0] as OnnxTensor
                    val probabilities = outputTensor.floatBuffer

                    probabilities.rewind()

                    require(probabilities.remaining() == 64 * 13) {
                        "Некорректный размер результата модели"
                    }

                    // Распознавание в экранной ориентации.
                    val screenBoard = Array(8) {
                        CharArray(8) { '.' }
                    }

                    val screenConfidence = Array(8) {
                        FloatArray(8)
                    }

                    val allConfidences = mutableListOf<Float>()

                    for (rank in 0 until 8) {
                        for (file in 0 until 8) {

                            var bestClass = 0
                            var bestProbability = -1f

                            for (classIndex in 0 until 13) {

                                val probability =
                                    probabilities.get()

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

                            screenBoard[rank][file] = piece

                            screenConfidence[rank][file] =
                                bestProbability

                            allConfidences.add(bestProbability)
                        }
                    }

                    // Определяем цвет фигур одновременно
                    // по нижнему и верхнему рядам.
                    var whiteAtBottom = 0
                    var blackAtBottom = 0

                    var whiteAtTop = 0
                    var blackAtTop = 0

                    for (file in 0 until 8) {

                        val bottomPiece = screenBoard[0][file]
                        val bottomConfidence =
                            screenConfidence[0][file]

                        if (
                            bottomPiece != '.' &&
                            bottomConfidence >= ORIENTATION_CONFIDENCE
                        ) {
                            if (bottomPiece.isUpperCase()) {
                                whiteAtBottom++
                            } else {
                                blackAtBottom++
                            }
                        }

                        val topPiece = screenBoard[7][file]
                        val topConfidence =
                            screenConfidence[7][file]

                        if (
                            topPiece != '.' &&
                            topConfidence >= ORIENTATION_CONFIDENCE
                        ) {
                            if (topPiece.isUpperCase()) {
                                whiteAtTop++
                            } else {
                                blackAtTop++
                            }
                        }
                    }

                    // Белые снизу, чёрные сверху.
                    if (
                        whiteAtBottom >= 5 &&
                        blackAtTop >= 5
                    ) {
                        lastKnownBoardFlipped = false
                    }

                    // Чёрные снизу, белые сверху.
                    else if (
                        blackAtBottom >= 5 &&
                        whiteAtTop >= 5
                    ) {
                        lastKnownBoardFlipped = true
                    }

                    val boardFlipped = lastKnownBoardFlipped

                    // Переводим экранные координаты
                    // в стандартные шахматные координаты.
                    val board = Array(8) {
                        CharArray(8) { '.' }
                    }

                    val squareResults = mutableListOf<String>()

                    for (screenRank in 0 until 8) {
                        for (screenFile in 0 until 8) {

                            val standardRank =
                                if (boardFlipped) {
                                    7 - screenRank
                                } else {
                                    screenRank
                                }

                            val standardFile =
                                if (boardFlipped) {
                                    7 - screenFile
                                } else {
                                    screenFile
                                }

                            val piece =
                                screenBoard[screenRank][screenFile]

                            val confidence =
                                screenConfidence[screenRank][screenFile]

                            board[standardRank][standardFile] = piece

                            val squareName =
                                "${('a'.code + standardFile).toChar()}" +
                                "${standardRank + 1}"

                            squareResults.add(
                                "$squareName: $piece " +
                                "(${String.format("%.2f", confidence)})"
                            )
                        }
                    }

                    val placement = buildPlacement(board)

                    val meanConfidence =
                        allConfidences.average().toFloat()

                    val minConfidence =
                        allConfidences.minOrNull() ?: 0f

                    val lowConfidenceCount =
                        allConfidences.count {
                            it < LOW_CONFIDENCE_LIMIT
                        }

                    // Не требуем, чтобы каждая клетка
                    // обязательно имела уверенность 0.70.
                    val reliable =
                        meanConfidence >= MIN_MEAN_CONFIDENCE &&
                        minConfidence >= MIN_SINGLE_CONFIDENCE &&
                        lowConfidenceCount <= MAX_LOW_CONFIDENCE_SQUARES

                    val castling = inferCastling(board)

                    // Предварительная FEN.
                    // Очередь хода и исторические права
                    // восстанавливаются ChessGameTracker.
                    val fen = "$placement w $castling - 0 1"

                    return PieceRecognitionResult(
                        placement = placement,
                        fen = fen,
                        meanConfidence = meanConfidence,
                        minConfidence = minConfidence,
                        reliable = reliable,
                        squares = squareResults,
                        boardFlipped = boardFlipped,
                        playerColor = if (boardFlipped) "b" else "w"
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

    private fun buildPlacement(
        board: Array<CharArray>
    ): String {

        return (7 downTo 0).joinToString("/") { rank ->

            val row = board[rank]
            val result = StringBuilder()

            var emptyCount = 0

            for (file in 0 until 8) {

                val piece = row[file]

                if (piece == '.') {
                    emptyCount++
                } else {

                    if (emptyCount > 0) {
                        result.append(emptyCount)
                        emptyCount = 0
                    }

                    result.append(piece)
                }
            }

            if (emptyCount > 0) {
                result.append(emptyCount)
            }

            result.toString()
        }
    }

    private fun inferCastling(
        board: Array<CharArray>
    ): String {

        val rights = StringBuilder()

        // Белые фигуры.
        if (
            board[7][4] == 'K' &&
            board[7][7] == 'R'
        ) {
            rights.append('K')
        }

        if (
            board[7][4] == 'K' &&
            board[7][0] == 'R'
        ) {
            rights.append('Q')
        }

        // Чёрные фигуры.
        if (
            board[0][4] == 'k' &&
            board[0][7] == 'r'
        ) {
            rights.append('k')
        }

        if (
            board[0][4] == 'k' &&
            board[0][0] == 'r'
        ) {
            rights.append('q')
        }

        return rights.toString().ifEmpty { "-" }
    }

    override fun close() {
        session.close()
    }
}
