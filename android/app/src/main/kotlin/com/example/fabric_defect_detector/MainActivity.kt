package com.example.fabric_defect_detector

import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodChannel
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.Core
import org.opencv.core.CvType
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.MatOfPoint
import org.opencv.core.Point
import org.opencv.core.Scalar
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream

class MainActivity: FlutterActivity() {
    private val CHANNEL = "opencv_processing"
    private var lowerColor = Scalar(30.0, 100.0, 100.0)
    private var upperColor = Scalar(90.0, 255.0, 255.0)
    private var matImage = Mat()



    private fun getHsvRange(image: Mat, hValue: Int, sValue: Int, vValue: Int): Pair<Scalar, Scalar> {
        val height = image.rows()
        val width = image.cols()

        // Get the pixel at the center of the image
        val centerPixel = Mat(1, 1, image.type())
        Core.extractChannel(image.submat(height / 2, height / 2 + 1, width / 2, width / 2 + 1), centerPixel, 0)

        // Convert the pixel to HSV color space
        val hsvPixel = Mat(1, 1, image.type())
        Imgproc.cvtColor(centerPixel, hsvPixel, Imgproc.COLOR_BGR2HSV)

        val hsvValues = hsvPixel.get(0, 0)

        // Create upper and lower color bounds using hValue, sValue, and vValue
        val upperColor = Scalar(
            hsvValues[0] + hValue,
            hsvValues[1] + sValue,
            hsvValues[2] + vValue
        )

        val lowerColor = Scalar(
            hsvValues[0] - hValue,
            hsvValues[1] - sValue,
            hsvValues[2] - vValue
        )

        return Pair(lowerColor, upperColor)
    }



    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!OpenCVLoader.initLocal()) {
            Log.e("OpenCV", "OpenCV initialization failed")
        } else {
            Log.d("OpenCV", "OpenCV initialized successfully")
        }


        flutterEngine?.dartExecutor?.binaryMessenger?.let {
            MethodChannel(it, CHANNEL).setMethodCallHandler { call, result ->
                if (call.method == "processFrame") {
                    val byteArray = call.arguments as ByteArray
                    val processedFrame = processFrameWithOpenCV(byteArray)
                    result.success(processedFrame)
                }

                else if (call.method == "calibrateColor"){
                    // Step 3: Create a mask based on the specified color range
                    val (newLowerColor, newUpperColor) = getHsvRange(matImage, 5, 30, 60)
                    lowerColor = newLowerColor
                    upperColor = newUpperColor
                    result.success(listOf(newLowerColor, newUpperColor))
                }
            }
        }
    }


    private fun processFrameWithOpenCV(frameData: ByteArray): ByteArray {
        Log.d("OpenCV", "Received frame data length: ${frameData.size}")

        try {
            // Decode the byte array to a Mat
            matImage = Imgcodecs.imdecode(MatOfByte(*frameData), Imgcodecs.IMREAD_UNCHANGED)
            val mat = matImage.clone()

            if (mat.empty()) {
                Log.e("OpenCV", "Decoded Mat is empty")
                throw IllegalArgumentException("Failed to decode byte array to Mat")
            }

            // Step 1: Convert the image to HSV
            val hsvMat = Mat()
            Imgproc.cvtColor(mat, hsvMat, Imgproc.COLOR_BGR2HSV)

            // Step 2: Blur the image
            val blurredMat = Mat()
            Imgproc.GaussianBlur(hsvMat, blurredMat, org.opencv.core.Size(15.0, 15.0), 0.0)

            // Step 3: Mask with the color
            val mask = Mat()
            Core.inRange(blurredMat, lowerColor, upperColor, mask)

            // Step 4: Bitwise AND the mask with the original image
            val maskedMat = Mat()
            Core.bitwise_and(mat, mat, maskedMat, mask)

            // Step 5: Convert the image to grayscale
            val grayMat = Mat()
            Imgproc.cvtColor(maskedMat, grayMat, Imgproc.COLOR_BGR2GRAY)

            // Step 6: Sharpen the image
            val kernel = Mat(3, 3, CvType.CV_32F)
            kernel.put(0, 0, 0.0, -1.0, 0.0, -1.0, 6.0, -1.0, 0.0, -1.0, 0.0)
            val sharpenedMat = Mat()
            Imgproc.filter2D(grayMat, sharpenedMat, -1, kernel)

            // Step 7: Apply Gaussian blur
            val finalBlurredMat = Mat()
            Imgproc.GaussianBlur(sharpenedMat, finalBlurredMat, org.opencv.core.Size(5.0, 5.0), 0.0)

            // Step 8: Apply Canny edge detection
            val edges = Mat()
            Imgproc.Canny(finalBlurredMat, edges, 100.0, 150.0)

            // Step 9: Find contours
            val contours = mutableListOf<MatOfPoint>()
            val hierarchy = Mat()
            Imgproc.findContours(edges, contours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

            // Step 10: Draw the largest contour
            if (contours.isNotEmpty()) {
                val largestContour = contours.maxByOrNull { Imgproc.contourArea(it) }
                if (largestContour != null) {
                    Imgproc.drawContours(edges, listOf(largestContour), -1, Scalar(0.0, 0.0, 0.0), 30)
                }
            }

            // Step 11: Internal edge detection
            val internalEdges = Mat()
            Imgproc.Canny(edges, internalEdges, 0.0, 150.0)

            // Step 12: Annotate the original image if defects are found
            if (Core.countNonZero(internalEdges) > 0) {
                // Play a beep sound if defect is found
                playBeepSound()

                // Draw "Defective" text on the original image
                Imgproc.putText(mat, "Defective", Point(30.0, 40.0), Imgproc.FONT_HERSHEY_SIMPLEX, 1.0, Scalar(0.0, 0.0, 255.0), 2)

                // Find contours of the internal edges
                val defectContours = mutableListOf<MatOfPoint>()
                Imgproc.findContours(internalEdges, defectContours, hierarchy, Imgproc.RETR_EXTERNAL, Imgproc.CHAIN_APPROX_SIMPLE)

                // Draw the defect contours on the original image
                for (contour in defectContours) {
                    Imgproc.drawContours(mat, listOf(contour), -1, Scalar(0.0, 255.0, 0.0), 2)
                }
            }

            // Convert the processed Mat back to Bitmap for returning
            val processedBitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(mat, processedBitmap)
            val stream = ByteArrayOutputStream()
            processedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)

            return stream.toByteArray()
        } catch (e: Exception) {
            Log.e("OpenCV", "Exception occurred during conversion: ${e.message}")
            throw e // Re-throw to handle it higher up if needed
        }
    }

    // Function to play the beep sound
    private fun playBeepSound() {
        val mediaPlayer = MediaPlayer.create(this, R.raw.beep)
        mediaPlayer.start()

        // Specify the type explicitly for the parameter in the lambda
        mediaPlayer.setOnCompletionListener { mp: MediaPlayer ->
            mp.release() // Release the media player after the sound finishes
        }
    }



}
