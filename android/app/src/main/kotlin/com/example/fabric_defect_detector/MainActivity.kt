package com.example.fabric_defect_detector

import android.R.attr.x
import android.R.attr.y
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
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream


class MainActivity: FlutterActivity() {
    private val CHANNEL = "opencv_processing"
    private var lowerColor = Scalar(30.0, 100.0, 100.0)
    private var upperColor = Scalar(90.0, 255.0, 255.0)
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var mat:Mat


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
                    val (newLowerColor, newUpperColor) = getHsvRange(mat, 5, 30, 60)
                    lowerColor = newLowerColor
                    upperColor = newUpperColor
                    result.success(listOf(newLowerColor.toString(), newUpperColor.toString()))
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        Log.i("MainActivity", "App paused")
        stopProcessing()  // Add this method to stop any ongoing tasks
    }

    override fun onStop() {
        super.onStop()
        Log.i("MainActivity", "App stopped")
        stopProcessing()  // Ensure everything stops when the app is stopped
    }

    override fun onDestroy() {
        super.onDestroy()
        Log.i("MainActivity", "App destroyed")
        stopProcessing()  // Ensure everything is released when the app is destroyed
    }

    private fun stopProcessing() {
        // Stop any ongoing image processing, threads, and release resources
        if (::mat.isInitialized) {
            mat.release()
        }
        mediaPlayer.release()  // Release media player if playing a beep sound
    }

    private fun getHsvRange(image: Mat, hValue: Int, sValue: Int, vValue: Int): Pair<Scalar, Scalar> {

        // Ensure the image has 3 channels (BGR or HSV)
        if (image.channels() != 3) {
            Log.e("OpenCV", "Expected 3-channel image but got ${image.channels()}-channel image")
            throw IllegalArgumentException("Input image must be a 3-channel BGR image")
        }

        // Get image dimensions
        val width = image.width()
        val height = image.height()

        // Calculate the center coordinates
        val centerX = width / 2
        val centerY = height / 2

        // Ensure the center point is valid
        if (centerX <= 0 || centerY <= 0) {
            Log.e("OpenCV", "Invalid center point for image dimensions: (${centerX}, ${centerY})")
            throw IllegalArgumentException("Center coordinates are out of bounds.")
        }

        // Convert the image to HSV
        val hsvImage = Mat()
        Imgproc.cvtColor(image, hsvImage, Imgproc.COLOR_BGR2HSV)

        // Extract the HSV values of the center pixel
        val centerPixel = hsvImage[centerY, centerX]
        if (centerPixel == null || centerPixel.size < 3) {
            Log.e("OpenCV", "Failed to get pixel value at center.")
            throw IllegalArgumentException("Could not retrieve pixel data at center.")
        }

        Log.i("OpenCV", "Center pixel HSV values: H=${centerPixel[0]}, S=${centerPixel[1]}, V=${centerPixel[2]}")

        // Create upper and lower color bounds using hValue, sValue, and vValue
        val upperColor = Scalar(
            centerPixel[0] + hValue,
            centerPixel[1] + sValue,
            centerPixel[2] + vValue
        )

        val lowerColor = Scalar(
            if (centerPixel[0] - hValue >= 0) centerPixel[0] - hValue else 0.0,
            if (centerPixel[1] - sValue >= 0) centerPixel[1] - sValue else 0.0,
            if (centerPixel[2] - vValue >= 0) centerPixel[2] - vValue else 0.0
        )

        return Pair(lowerColor, upperColor)
    }


    private fun processFrameWithOpenCV(frameData: ByteArray): ByteArray {
        Log.d("OpenCV", "Received frame data length: ${frameData.size}")

        try {
            // Decode the byte array to a Mat
            mat = Imgcodecs.imdecode(MatOfByte(*frameData), Imgcodecs.IMREAD_UNCHANGED)

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
        mediaPlayer = MediaPlayer.create(this, R.raw.beep)
        mediaPlayer.start()

        // Specify the type explicitly for the parameter in the lambda
        mediaPlayer.setOnCompletionListener { mp: MediaPlayer ->
            mp.release() // Release the media player after the sound finishes
        }
    }
}
