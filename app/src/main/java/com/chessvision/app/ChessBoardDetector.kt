
package com.chessvision.app

import android.graphics.Bitmap
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.Mat
import org.opencv.core.MatOfPoint
import org.opencv.core.MatOfPoint2f
import org.opencv.core.Point
import org.opencv.core.Size
import org.opencv.imgproc.Imgproc
import kotlin.math.abs

data class BoardDetection(
    val x: Int,
    val y: Int,
    val width: Int,
    val height: Int
)

object ChessBoardDetector {

    fun initialize(): Boolean {
        return try {
            OpenCVLoader.initLocal()
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    fun detect(bitmap: Bitmap): BoardDetection? {

        if (bitmap.isRecycled) return null

        val source = Mat()
        val gray = Mat()
        val blurred = Mat()
        val edges = Mat()

        val contours = ArrayList<MatOfPoint>()
        val hierarchy = Mat()

        try {
            Utils.bitmapToMat(bitmap, source)

            Imgproc.cvtColor(
                source,
                gray,
                Imgproc.COLOR_RGBA2GRAY
            )

            Imgproc.GaussianBlur(
                gray,
                blurred,
                Size(5.0, 5.0),
                0.0
            )

            Imgproc.Canny(
                blurred,
                edges,
                50.0,
                150.0
            )

            Imgproc.findContours(
                edges,
                contours,
                hierarchy,
                Imgproc.RETR_LIST,
                Imgproc.CHAIN_APPROX_SIMPLE
            )

            val imageArea =
                bitmap.width.toDouble() * bitmap.height.toDouble()

            var bestBoard: BoardDetection? = null
            var bestScore = 0.0

            for (contour in contours) {

                val area = Imgproc.contourArea(contour)

                if (area < imageArea * 0.025 ||
                    area > imageArea * 0.90
                ) {
                    continue
                }

                val curve = MatOfPoint2f(*contour.toArray())
                val approx = MatOfPoint2f()

                val perimeter = Imgproc.arcLength(
                    curve,
                    true
                )

                Imgproc.approxPolyDP(
                    curve,
                    approx,
                    perimeter * 0.02,
                    true
                )

                val points = approx.toArray()

                if (points.size == 4) {

                    val polygon = MatOfPoint(*points)

                    if (Imgproc.isContourConvex(polygon)) {

                        val rect = Imgproc.boundingRect(polygon)

                        if (rect.width > 0 && rect.height > 0) {

                            val ratio =
                                rect.width.toDouble() / rect.height

                            if (ratio in 0.75..1.25) {

                                val squareScore =
                                    1.0 - abs(1.0 - ratio)

                                val areaScore =
                                    area / imageArea

                                val score =
                                    squareScore * areaScore

                                if (score > bestScore) {

                                    bestScore = score

                                    bestBoard = BoardDetection(
                                        x = rect.x,
                                        y = rect.y,
                                        width = rect.width,
                                        height = rect.height
                                    )
                                }
                            }
                        }
                    }

                    polygon.release()
                }

                curve.release()
                approx.release()
            }

            return bestBoard

        } catch (e: Exception) {
            e.printStackTrace()
            return null

        } finally {
            source.release()
            gray.release()
            blurred.release()
            edges.release()
            hierarchy.release()

            contours.forEach {
                it.release()
            }
        }
    }
}
