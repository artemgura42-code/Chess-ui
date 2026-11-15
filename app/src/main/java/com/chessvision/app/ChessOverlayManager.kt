
package com.chessvision.app

import android.content.Context
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

class ChessOverlayManager(
    context: Context,
    private val onStop: () -> Unit
) {

    private val appContext = context.applicationContext

    private val windowManager =
        appContext.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private val mainHandler = Handler(Looper.getMainLooper())

    private var rootView: LinearLayout? = null
    private var contentView: LinearLayout? = null

    private var moveView: TextView? = null
    private var evaluationView: TextView? = null
    private var statusView: TextView? = null
    private var collapseButton: TextView? = null

    private var collapsed = false

    private lateinit var layoutParams: WindowManager.LayoutParams

    private var bestMove = "Ожидание хода..."
    private var evaluation = ""
    private var status = "Stockfish ожидает позицию"

    fun hasPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
            Settings.canDrawOverlays(appContext)
    }

    fun show() {
        mainHandler.post {
            if (!hasPermission()) return@post

            if (rootView != null) {
                updateViews()
                return@post
            }

            createPanel()
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

    fun hide() {
        mainHandler.post {
            val view = rootView ?: return@post

            try {
                windowManager.removeView(view)
            } catch (_: Exception) {
            }

            rootView = null
            contentView = null
            moveView = null
            evaluationView = null
            statusView = null
            collapseButton = null
        }
    }

    private fun createPanel() {
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
            setTextColor(0xFFFFFFFF.toInt())
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
            0xFFFFFFFF.toInt()
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

        val stopButton = TextView(appContext).apply {
            text = "ОСТАНОВИТЬ АНАЛИЗ"
            textSize = 12f
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
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

        val stopParams = LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT,
            LinearLayout.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(12)
        }

        content.addView(stopButton, stopParams)
        root.addView(content)

        contentView = content
        moveView = move
        evaluationView = eval
        statusView = statusText
        collapseButton = collapse

        collapse.setOnClickListener {
            collapsed = !collapsed

            content.visibility = if (collapsed) {
                View.GONE
            } else {
                View.VISIBLE
            }

            collapse.text = if (collapsed) "+" else "—"
        }

        // Перемещение панели пальцем за её заголовок.
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
            updateViews()
        } catch (e: Exception) {
            rootView = null
            e.printStackTrace()
        }
    }

    private var initialX = 0
    private var initialY = 0
    private var initialTouchX = 0f
    private var initialTouchY = 0f

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
        return (value * appContext.resources.displayMetrics.density).toInt()
    }
}
