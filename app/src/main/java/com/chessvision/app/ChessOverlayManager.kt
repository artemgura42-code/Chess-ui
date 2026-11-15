
package com.chessvision.app

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import android.os.Build
import android.os.Handler
import android.os.Looper
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

class ChessOverlayManager(
    context: Context,
    private val onStop: () -> Unit,
    private val onSideToMoveSelected: (String) -> Unit = {}
) {

    private val appContext = context.applicationContext

    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: LinearLayout? = null
    private var arrowView: ChessArrowView? = null

    private var contentView: LinearLayout? = null
    private var moveView: TextView? = null
    private var evaluationView: TextView? = null
    private var statusView: TextView? = null
    private var collapseButton: TextView? = null
    private var turnSelectionView: LinearLayout? = null

    private var collapsed = false

    @Volatile
    private var turnSelectionRequested = false

    private lateinit var layoutParams: WindowManager.LayoutParams

    private var bestMove = "Ожидание хода..."
    private var evaluation = ""
    private var status = "Stockfish ожидает позицию"

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

    fun hasPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(appContext)
    }

    fun show() {
        mainHandler.post {
            if (!hasPermission()) return@post

            if (rootView == null) {
                createArrowLayer()
                createPanel()
            } else if (arrowView == null) {
                createArrowLayer()
            }

            updateViews()

            if (turnSelectionRequested) {
                displayTurnSelection()
            }
        }
    }

    fun update(
        move: String,
        newEvaluation: String,
        newStatus: String
    ) {
        mainHandler.post {
            bestMove = move
            evaluation = newEvaluation
            status = newStatus
            updateViews()
        }
    }

    fun updateArrow(
        move: String,
        boardLeft: Int,
        boardTop: Int,
        boardWidth: Int,
        boardHeight: Int,
        whiteAtBottom: Boolean
    ) {
        mainHandler.post {
            val view = arrowView ?: return@post

            if (
                move.length < 4 ||
                boardWidth <= 0 ||
                boardHeight <= 0
            ) {
                view.clearArrow()
                return@post
            }

            view.setArrow(
                move = move,
                boardLeft = boardLeft.toFloat(),
                boardTop = boardTop.toFloat(),
                boardWidth = boardWidth.toFloat(),
                boardHeight = boardHeight.toFloat(),
                whiteAtBottom = whiteAtBottom
            )
        }
    }

    fun clearArrow() {
        mainHandler.post {
            arrowView?.clearArrow()
        }
    }

    fun showTurnSelection() {
        turnSelectionRequested = true

        mainHandler.post {
            if (!hasPermission()) return@post

            if (rootView == null) {
                createArrowLayer()
                createPanel()
            }

            displayTurnSelection()
        }
    }

    private fun displayTurnSelection() {
        val content = contentView ?: return
        val selection = turnSelectionView ?: return

        collapsed = false
        content.visibility = View.VISIBLE
        collapseButton?.text = "—"

        selection.visibility = View.VISIBLE

        status = "Контекст партии потерян. Укажите очередь хода."
        updateViews()
    }

    fun hideTurnSelection() {
        turnSelectionRequested = false

        mainHandler.post {
            turnSelectionView?.visibility = View.GONE
        }
    }

    fun hide() {
        turnSelectionRequested = false

        mainHandler.post {
            try {
                rootView?.let {
                    windowManager.removeView(it)
                }
            } catch (_: Exception) {
            }

            try {
                arrowView?.let {
                    windowManager.removeView(it)
                }
            } catch (_: Exception) {
            }

            rootView = null
            arrowView = null
            contentView = null
            moveView = null
            evaluationView = null
            statusView = null
            collapseButton = null
            turnSelectionView = null
            collapsed = false
        }
    }

    private fun createArrowLayer() {
        if (arrowView != null || !hasPermission()) return

        val view = ChessArrowView(appContext)

        val windowType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = 0
            y = 0
        }

        try {
            windowManager.addView(view, params)
            arrowView = view
        } catch (e: Exception) {
            e.printStackTrace()
            arrowView = null
        }
    }

    private fun createPanel() {
        if (rootView != null || !hasPermission()) return

        val root = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(12), dp(9), dp(12), dp(10))
            background = roundedBackground(
                color = 0xF01B241C.toInt(),
                radius = 16
            )
        }

        val header = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        val title = TextView(appContext).apply {
            text = "CHESS VISION"
            textSize = 14f
            setTextColor(0xFFB7F36B.toInt())
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(0, dp(5), 0, dp(5))
        }

        val collapse = TextView(appContext).apply {
            text = "—"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setPadding(dp(12), 0, dp(4), 0)
        }

        header.addView(
            title,
            LinearLayout.LayoutParams(0, dp(36), 1f)
        )

        header.addView(
            collapse,
            LinearLayout.LayoutParams(dp(40), dp(36))
        )

        root.addView(header)

        val content = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
        }

        val moveLabel = createText(
            "ЛУЧШИЙ ХОД",
            10f,
            0xFFAAAAAA.toInt()
        )

        val move = createText(
            bestMove,
            27f,
            0xFFB7F36B.toInt(),
            bold = true
        )

        val eval = createText(
            "Оценка: $evaluation",
            14f,
            Color.WHITE
        )

        val statusText = createText(
            status,
            11f,
            0xFFCCCCCC.toInt()
        )

        content.addView(moveLabel)
        content.addView(move)
        content.addView(eval)
        content.addView(statusText)

        val turnSelection = LinearLayout(appContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(10), 0, 0)
            visibility = if (turnSelectionRequested) {
                View.VISIBLE
            } else {
                View.GONE
            }
        }

        val turnTitle = createText(
            "КОНТЕКСТ ПАРТИИ ПОТЕРЯН",
            12f,
            0xFFFFD166.toInt(),
            bold = true
        )

        val turnDescription = createText(
            "Партия уже началась. Чей сейчас ход?",
            12f,
            Color.WHITE
        )

        turnSelection.addView(turnTitle)
        turnSelection.addView(turnDescription)

        val choices = LinearLayout(appContext).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
        }

        val whiteButton = createChoiceButton(
            "ХОД БЕЛЫХ",
            0xFF526B48.toInt()
        ) {
            onSideToMoveSelected("w")
        }

        val blackButton = createChoiceButton(
            "ХОД ЧЁРНЫХ",
            0xFF4B5265.toInt()
        ) {
            onSideToMoveSelected("b")
        }

        choices.addView(
            whiteButton,
            LinearLayout.LayoutParams(
                0,
                dp(42),
                1f
            ).apply {
                marginEnd = dp(4)
            }
        )

        choices.addView(
            blackButton,
            LinearLayout.LayoutParams(
                0,
                dp(42),
                1f
            ).apply {
                marginStart = dp(4)
            }
        )

        turnSelection.addView(
            choices,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(8)
            }
        )

        content.addView(turnSelection)

        val stopButton = TextView(appContext).apply {
            text = "ОСТАНОВИТЬ АНАЛИЗ"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            setPadding(dp(8), dp(10), dp(8), dp(10))
            background = roundedBackground(
                color = 0xFF9B3939.toInt(),
                radius = 9
            )

            setOnClickListener {
                hide()
                onStop()
            }
        }

        content.addView(
            stopButton,
            LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = dp(12)
            }
        )

        root.addView(content)

        contentView = content
        moveView = move
        evaluationView = eval
        statusView = statusText
        collapseButton = collapse
        turnSelectionView = turnSelection

        collapse.setOnClickListener {
            if (turnSelectionRequested) return@setOnClickListener

            collapsed = !collapsed

            content.visibility = if (collapsed) {
                View.GONE
            } else {
                View.VISIBLE
            }

            collapse.text = if (collapsed) "+" else "—"
        }

        title.setOnTouchListener { _, event ->
            when (event.action) {
                MotionEvent.ACTION_DOWN -> {
                    initialX = layoutParams.x
                    initialY = layoutParams.y
                    initialTouchX = event.rawX
                    initialTouchY = event.rawY
                    true
                }

                MotionEvent.ACTION_MOVE -> {
                    layoutParams.x =
                        initialX + (event.rawX - initialTouchX).toInt()

                    layoutParams.y =
                        initialY + (event.rawY - initialTouchY).toInt()

                    try {
                        windowManager.updateViewLayout(
                            root,
                            layoutParams
                        )
                    } catch (_: Exception) {
                    }

                    true
                }

                else -> false
            }
        }

        val windowType =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            } else {
                @Suppress("DEPRECATION")
                WindowManager.LayoutParams.TYPE_PHONE
            }

        layoutParams = WindowManager.LayoutParams(
            dp(260),
            WindowManager.LayoutParams.WRAP_CONTENT,
            windowType,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16)
            y = dp(150)
        }

        try {
            windowManager.addView(root, layoutParams)
            rootView = root

            if (turnSelectionRequested) {
                displayTurnSelection()
            } else {
                updateViews()
            }
        } catch (e: Exception) {
            rootView = null
            contentView = null
            turnSelectionView = null
            e.printStackTrace()
        }
    }

    private fun createChoiceButton(
        label: String,
        color: Int,
        onClick: () -> Unit
    ): TextView {
        return TextView(appContext).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            setTypeface(null, android.graphics.Typeface.BOLD)
            background = roundedBackground(color, 8)

            setOnClickListener {
                onClick()
            }
        }
    }

    private fun updateViews() {
        moveView?.text = bestMove.ifBlank {
            "Ожидание хода..."
        }

        evaluationView?.text = if (evaluation.isNotBlank()) {
            "Оценка: $evaluation"
        } else {
            "Оценка: —"
        }

        statusView?.text = status
    }

    private fun createText(
        value: String,
        size: Float,
        color: Int,
        bold: Boolean = false
    ): TextView {
        return TextView(appContext).apply {
            text = value
            textSize = size
            setTextColor(color)

            if (bold) {
                setTypeface(null, android.graphics.Typeface.BOLD)
            }

            setPadding(0, dp(3), 0, dp(3))
        }
    }

    private fun roundedBackground(
        color: Int,
        radius: Int
    ): GradientDrawable {
        return GradientDrawable().apply {
            setColor(color)
            cornerRadius = dp(radius).toFloat()
        }
    }

    private fun dp(value: Int): Int {
        return (
            value * appContext.resources.displayMetrics.density
        ).toInt()
    }

    private inner class ChessArrowView(
        context: Context
    ) : View(context) {

        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(90, 220, 75)
            style = Paint.Style.STROKE
            strokeWidth = dp(6).toFloat()
            strokeCap = Paint.Cap.ROUND
            strokeJoin = Paint.Join.ROUND
        }

        private val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(90, 220, 75)
            style = Paint.Style.FILL
        }

        private var move: String? = null
        private var boardLeft = 0f
        private var boardTop = 0f
        private var boardWidth = 0f
        private var boardHeight = 0f
        private var whiteAtBottom = true

        fun setArrow(
            move: String,
            boardLeft: Float,
            boardTop: Float,
            boardWidth: Float,
            boardHeight: Float,
            whiteAtBottom: Boolean
        ) {
            this.move = move
            this.boardLeft = boardLeft
            this.boardTop = boardTop
            this.boardWidth = boardWidth
            this.boardHeight = boardHeight
            this.whiteAtBottom = whiteAtBottom
            invalidate()
        }

        fun clearArrow() {
            move = null
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val currentMove = move ?: return

            if (currentMove.length < 4) return
            if (boardWidth <= 0f || boardHeight <= 0f) return

            val fromFile = currentMove[0] - 'a'
            val fromRank = currentMove[1] - '1'
            val toFile = currentMove[2] - 'a'
            val toRank = currentMove[3] - '1'

            if (
                fromFile !in 0..7 ||
                fromRank !in 0..7 ||
                toFile !in 0..7 ||
                toRank !in 0..7
            ) {
                return
            }

            val squareWidth = boardWidth / 8f
            val squareHeight = boardHeight / 8f

            val fromColumn: Int
            val fromRow: Int
            val toColumn: Int
            val toRow: Int

            if (whiteAtBottom) {
                fromColumn = fromFile
                fromRow = 7 - fromRank

                toColumn = toFile
                toRow = 7 - toRank
            } else {
                fromColumn = 7 - fromFile
                fromRow = fromRank

                toColumn = 7 - toFile
                toRow = toRank
            }

            val startX =
                boardLeft + (fromColumn + 0.5f) * squareWidth
            val startY =
                boardTop + (fromRow + 0.5f) * squareHeight

            val endX =
                boardLeft + (toColumn + 0.5f) * squareWidth
            val endY =
                boardTop + (toRow + 0.5f) * squareHeight

            val angle = atan2(
                (endY - startY).toDouble(),
                (endX - startX).toDouble()
            )

            val arrowLength = dp(23).toFloat()
            val arrowAngle = Math.toRadians(30.0)

            val path = Path().apply {
                moveTo(startX, startY)
                lineTo(endX, endY)
            }

            canvas.drawPath(path, arrowPaint)

            val leftX = endX -
                arrowLength * cos(angle - arrowAngle).toFloat()
            val leftY = endY -
                arrowLength * sin(angle - arrowAngle).toFloat()

            val rightX = endX -
                arrowLength * cos(angle + arrowAngle).toFloat()
            val rightY = endY -
                arrowLength * sin(angle + arrowAngle).toFloat()

            val head = Path().apply {
                moveTo(endX, endY)
                lineTo(leftX, leftY)
                lineTo(rightX, rightY)
                close()
            }

            canvas.drawPath(head, headPaint)
        }
    }
}
