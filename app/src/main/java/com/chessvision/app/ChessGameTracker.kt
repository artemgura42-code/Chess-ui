
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
        private const val MAX_SEARCH_DEPTH = 6
        private const val BEAM_WIDTH = 64

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

    @Synchronized
    fun start(
        initialPlacement: String,
        detectedPlayerColor: String
    ): ChessTrackerResult {

        if (initialPlacement != START_PLACEMENT) {
            return ChessTrackerResult(
                null,
                null,
                "Ожидаю начальную позицию партии",
                false,
                0
            )
        }

        if (detectedPlayerColor != "w" &&
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

        board = Board()
        playerColor = detectedPlayerColor
        lastPlacement = START_PLACEMENT

        return ChessTrackerResult(
            fen = board!!.fen,
            playerColor = playerColor,
            status = if (playerColor == "w") {
                "Партия начата. Вы играете белыми"
            } else {
                "Партия начата. Вы играете чёрными"
            },
            advanced = true,
            movesDetected = 0
        )
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
                "Сначала дождитесь начальной позиции",
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
                "Обнаружена новая начальная позиция. Начните новую партию",
                false,
                0
            )
        }

        val found = findPosition(
            currentBoard,
            observedPlacement
        )

        if (found == null) {
            return ChessTrackerResult(
                currentBoard.fen,
                playerColor,
                "Не удалось подтвердить легальную последовательность ходов",
                false,
                0
            )
        }

        board = found.board
        lastPlacement = observedPlacement

        return ChessTrackerResult(
            fen = found.board.fen,
            playerColor = playerColor,
            status = "Обнаружено ходов: ${found.path.size}",
            advanced = true,
            movesDetected = found.path.size
        )
    }

    private fun findPosition(
        startBoard: Board,
        targetPlacement: String
    ): SearchNode? {

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

                    if (placement == targetPlacement) {
                        return SearchNode(
                            board = nextBoard,
                            path = path,
                            score = 0
                        )
                    }

                    val key = fen

                    if (!visited.add(key)) {
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
            for (character in row) {

                if (character.isDigit()) {

                    val count = character.digitToInt()

                    if (count !in 1..8 || index + count > 64) {
                        return null
                    }

                    repeat(count) {
                        result[index++] = '.'
                    }

                } else {

                    if (
                        character !in
                        "KQRBNPkqrbnp"
                    ) {
                        return null
                    }

                    if (index >= 64) return null

                    result[index++] = character
                }
            }
        }

        return if (index == 64) result else null
    }
}
