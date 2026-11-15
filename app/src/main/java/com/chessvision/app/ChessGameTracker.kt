
package com.chessvision.app

import com.github.bhlangonijr.chesslib.Board

data class ChessTrackerResult(
    val fen: String?,
    val playerColor: String?,
    val status: String,
    val advanced: Boolean,
    val movesDetected: Int
)

class ChessGameTracker {

    companion object {
        // До 12 полуходов — примерно 6 полных ходов.
        private const val MAX_SEARCH_DEPTH = 12

        // Увеличиваем ширину поиска для более надёжного
        // восстановления ранней стадии партии.
        private const val BEAM_WIDTH = 1024

        const val START_PLACEMENT =
            "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR"
    }

    private data class SearchNode(
        val board: Board,
        val path: List<String>,
        val score: Int
    )

    private var board: Board? = null
    private var playerColor: String? = null
    private var lastPlacement: String? = null

    @Synchronized
    fun reset() {
        board = null
        playerColor = null
        lastPlacement = null
    }

    /**
     * Пытается восстановить позицию от начальной расстановки.
     * Если позиция найдена, очередь хода определяется автоматически.
     */
    @Synchronized
    fun start(
        initialPlacement: String,
        detectedPlayerColor: String
    ): ChessTrackerResult {

        if (
            detectedPlayerColor != "w" &&
            detectedPlayerColor != "b"
        ) {
            return ChessTrackerResult(
                null,
                null,
                "Не удалось определить цвет игрока",
                false,
                0
            )
        }

        val initialBoard = Board()

        val found = if (initialPlacement == START_PLACEMENT) {
            SearchNode(
                board = initialBoard,
                path = emptyList(),
                score = 0
            )
        } else {
            findPosition(
                startBoard = initialBoard,
                targetPlacement = initialPlacement
            )
        }

        if (found == null) {
            return ChessTrackerResult(
                null,
                null,
                "Не удалось восстановить историю первых ходов",
                false,
                0
            )
        }

        board = found.board
        playerColor = detectedPlayerColor
        lastPlacement = initialPlacement

        val sideToMove =
            found.board.fen.trim().split(Regex("\\s+")).getOrNull(1)

        val sideText = if (sideToMove == "w") {
            "белых"
        } else {
            "чёрных"
        }

        val colorText = if (detectedPlayerColor == "w") {
            "белыми"
        } else {
            "чёрными"
        }

        val status = if (found.path.isEmpty()) {
            "Новая партия. Ход белых. Вы играете $colorText"
        } else {
            "Автоматически восстановлено ${found.path.size} полуходов. " +
                "Ход $sideText. Вы играете $colorText"
        }

        return ChessTrackerResult(
            fen = found.board.fen,
            playerColor = playerColor,
            status = status,
            advanced = true,
            movesDetected = found.path.size
        )
    }

    /**
     * Восстановление позиции без истории.
     */
    @Synchronized
    fun startFromPosition(
        initialPlacement: String,
        detectedPlayerColor: String,
        sideToMove: String
    ): ChessTrackerResult {

        if (
            detectedPlayerColor != "w" &&
            detectedPlayerColor != "b"
        ) {
            return ChessTrackerResult(
                null,
                null,
                "Не удалось определить цвет игрока",
                false,
                0
            )
        }

        if (sideToMove != "w" && sideToMove != "b") {
            return ChessTrackerResult(
                null,
                null,
                "Не удалось определить очередь хода",
                false,
                0
            )
        }

        return try {
            val recoveredBoard = Board()

            val recoveredFen =
                "$initialPlacement $sideToMove - - 0 1"

            recoveredBoard.loadFromFen(recoveredFen)

            board = recoveredBoard
            playerColor = detectedPlayerColor
            lastPlacement = initialPlacement

            val sideText = if (sideToMove == "w") {
                "белых"
            } else {
                "чёрных"
            }

            val playerText = if (detectedPlayerColor == "w") {
                "белыми"
            } else {
                "чёрными"
            }

            ChessTrackerResult(
                fen = recoveredBoard.fen,
                playerColor = detectedPlayerColor,
                status = "Позиция восстановлена без истории. " +
                    "Ход $sideText. Вы играете $playerText",
                advanced = false,
                movesDetected = 0
            )

        } catch (e: Exception) {
            ChessTrackerResult(
                null,
                null,
                "Не удалось создать позицию: ${e.message}",
                false,
                0
            )
        }
    }

    @Synchronized
    fun update(
        observedPlacement: String
    ): ChessTrackerResult {

        val currentBoard = board

        if (currentBoard == null) {
            return ChessTrackerResult(
                null,
                null,
                "Ожидаю восстановления позиции партии",
                false,
                0
            )
        }

        if (observedPlacement == lastPlacement) {
            return ChessTrackerResult(
                currentBoard.fen,
                playerColor,
                "Позиция не изменилась",
                false,
                0
            )
        }

        if (observedPlacement == START_PLACEMENT) {
            reset()

            return ChessTrackerResult(
                null,
                null,
                "Обнаружена новая начальная позиция",
                false,
                0
            )
        }

        val found = findPosition(
            startBoard = currentBoard,
            targetPlacement = observedPlacement
        )

        if (found == null) {
            return ChessTrackerResult(
                null,
                playerColor,
                "Контекст партии потерян. Не удалось подтвердить последовательность ходов",
                false,
                0
            )
        }

        board = found.board
        lastPlacement = observedPlacement

        return ChessTrackerResult(
            fen = found.board.fen,
            playerColor = playerColor,
            status = "Обнаружено новых полуходов: ${found.path.size}",
            advanced = true,
            movesDetected = found.path.size
        )
    }

    /**
     * Поиск легальной последовательности ходов.
     */
    private fun findPosition(
        startBoard: Board,
        targetPlacement: String
    ): SearchNode? {

        if (startBoard.fen.substringBefore(" ") == targetPlacement) {
            return SearchNode(
                board = startBoard,
                path = emptyList(),
                score = 0
            )
        }

        var beam = listOf(
            SearchNode(
                board = startBoard,
                path = emptyList(),
                score = difference(
                    startBoard.fen.substringBefore(" "),
                    targetPlacement
                )
            )
        )

        val visited = HashSet<String>()
        visited.add(startBoard.fen)

        for (depth in 1..MAX_SEARCH_DEPTH) {

            val nextBeam = mutableListOf<SearchNode>()

            for (node in beam) {

                val legalMoves = node.board.legalMoves()

                for (move in legalMoves) {

                    val nextBoard = node.board.clone()
                    nextBoard.doMove(move)

                    val fen = nextBoard.fen
                    val placement = fen.substringBefore(" ")

                    val path = node.path + move.toString()

                    // Точное совпадение расстановки.
                    if (placement == targetPlacement) {
                        return SearchNode(
                            board = nextBoard,
                            path = path,
                            score = 0
                        )
                    }

                    if (!visited.add(fen)) {
                        continue
                    }

                    val score = difference(
                        placement,
                        targetPlacement
                    )

                    nextBeam.add(
                        SearchNode(
                            board = nextBoard,
                            path = path,
                            score = score
                        )
                    )
                }
            }

            if (nextBeam.isEmpty()) {
                return null
            }

            beam = nextBeam
                .sortedBy { it.score }
                .take(BEAM_WIDTH)
        }

        return null
    }

    private fun difference(
        first: String,
        second: String
    ): Int {

        val a = expandPlacement(first) ?: return 64
        val b = expandPlacement(second) ?: return 64

        var differences = 0

        for (index in 0 until 64) {
            if (a[index] != b[index]) {
                differences++
            }
        }

        return differences
    }

    private fun expandPlacement(
        placement: String
    ): CharArray? {

        val rows = placement.split("/")

        if (rows.size != 8) return null

        val result = CharArray(64)
        var index = 0

        for (row in rows) {

            val rowStart = index

            for (character in row) {

                if (character.isDigit()) {

                    val count = character.digitToInt()

                    if (
                        count !in 1..8 ||
                        index + count > 64
                    ) {
                        return null
                    }

                    repeat(count) {
                        result[index++] = '.'
                    }

                } else {

                    if (character !in "KQRBNPkqrbnp") {
                        return null
                    }

                    if (index >= 64) return null

                    result[index++] = character
                }
            }

            if (index - rowStart != 8) {
                return null
            }
        }

        return if (index == 64) result else null
    }
}
