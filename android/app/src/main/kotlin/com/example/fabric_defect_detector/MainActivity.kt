package com.example.fabric_defect_detector

import android.R.attr.x
import android.R.attr.y
import android.graphics.Bitmap
import android.media.MediaPlayer
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.EventChannel
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


class MainActivity : FlutterActivity() {
    private val CHANNEL = "opencv_processing"
    private val EVENT_CHANNEL = "com.example.fabric_defect_detector/events"

    private var lowerColor = Scalar(30.0, 100.0, 100.0)
    private var upperColor = Scalar(106.0, 140.0, 171.0)

    //    private var isCalibrated: Boolean = false
    private var startDetection: Boolean = false
    private var audioFinishedPlaying: Boolean = true
    private lateinit var mediaPlayer: MediaPlayer
    private lateinit var mat: Mat

    private var totalDefect: Int = 0
    private var lastDetectedOn: Int = 0
    private var eventSink: EventChannel.EventSink? = null

//    timers
    private var detectionLastTime: Int = 0
    private var waitTime: Int = 0
    private var qcWait: Int = 5000
    private var defectWait: Int = 5000


    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!OpenCVLoader.initLocal()) {
            Log.e("OpenCV", "OpenCV initialization failed")
        } else {
            Log.d("OpenCV", "OpenCV initialized successfully")
        }

//        event channel
        val eventChannel = EventChannel(flutterEngine?.dartExecutor?.binaryMessenger, EVENT_CHANNEL)
        eventChannel.setStreamHandler(object : EventChannel.StreamHandler {
            override fun onListen(arguments: Any?, events: EventChannel.EventSink?) {
                eventSink = events
            }

            override fun onCancel(arguments: Any?) {
                eventSink = null
            }
        })


        flutterEngine?.dartExecutor?.binaryMessenger?.let {
            MethodChannel(it, CHANNEL).setMethodCallHandler { call, result ->
                if (call.method == "processFrame") {
                    val byteArray = call.arguments as ByteArray
                    val processedFrame = processFrameWithOpenCV(byteArray)
                    result.success(processedFrame)
                } else if (call.method == "startDetection") {
                    startDetection = call.arguments as Boolean
                    result.success(startDetection)
                }
                else if(call.method == "defectionCount"){
                    Log.i("TOTAL", totalDefect.toString())
                    result.success(totalDefect)
                }
                else if(call.method == "setQCWait"){
                    qcWait = call.arguments as Int
                    result.success(qcWait)
                    Log.i("QCWait", qcWait.toString())
                }
                else if(call.method == "setDefectWait"){                    
                    defectWait = call.arguments as Int
                    result.success(defectWait)
                    Log.i("DefectWait", defectWait.toString())
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

    private fun processFrameWithOpenCV(frameData: ByteArray): ByteArray {
        try {
            // Decode the byte array to a Mat
            mat = Imgcodecs.imdecode(MatOfByte(*frameData), Imgcodecs.IMREAD_UNCHANGED)

            if (mat.empty()) {
                Log.e("OpenCV", "Decoded Mat is empty")
                throw IllegalArgumentException("Failed to decode byte array to Mat")
            }

            if (!startDetection) {
                return matToByteArray(mat)
            }
            
            // pause detection
            if(System.currentTimeMillis().toInt() - detectionLastTime < waitTime){
                return matToByteArray(mat)
            }
            detectionLastTime = System.currentTimeMillis().toInt()
            

            // crop image
            val croppingRect = Rect(0, 100, mat.cols(), mat.rows() - 100)
            mat = Mat(mat, croppingRect)


            // Convert the image from BGR to HSV
            val hsvImg = Mat()
            Imgproc.cvtColor(mat, hsvImg, Imgproc.COLOR_BGR2HSV)

            // Apply bilateral filter
            val mask = Mat()
            Imgproc.bilateralFilter(hsvImg, mask, 4, 15.0, 15.0)

            // Apply Canny edge detection
            val internalEdges = Mat()
            Imgproc.Canny(mask, internalEdges, 100.0, 150.0)

            // Check if any edges are detected
            val nonZeroPixels = Core.countNonZero(internalEdges)
            if (nonZeroPixels > 0) {
                // apply pause time
                waitTime = defectWait

                // Annotate the original image with "Defective"
                Imgproc.putText(
                    mat,
                    "Defective",
                    Point(30.0, 40.0),
                    Imgproc.FONT_HERSHEY_SIMPLEX,
                    1.0,
                    Scalar(0.0, 0.0, 255.0),
                    2
                )

                // Find contours of the internal edges to draw a boundary
                val defectContours = ArrayList<MatOfPoint>()
                val hierarchy = Mat()
                Imgproc.findContours(
                    internalEdges,
                    defectContours,
                    hierarchy,
                    Imgproc.RETR_EXTERNAL,
                    Imgproc.CHAIN_APPROX_SIMPLE
                )

                // notification
                if (audioFinishedPlaying)
                    playBeepSound()

                if(System.currentTimeMillis().toInt() - lastDetectedOn > 1000){
                    Log.i("INCREASE","----------------------------------------")
                    totalDefect++
                    eventSink?.success(totalDefect) ?: Log.i("Error","EventSink is not available")
                    Toast.makeText(this, "Defect Detected!", Toast.LENGTH_SHORT).show()
                }
                // Update the last detected time
                lastDetectedOn = System.currentTimeMillis().toInt()


                // Draw the contours on the original image in green
                Imgproc.drawContours(mat, defectContours, -1, Scalar(0.0, 255.0, 0.0), 2)
            }

            else {
                // apply pause time
                waitTime = qcWait

                Imgproc.putText(
                    mat,
                    "QC Passed",
                    Point(30.0, 40.0),
                    Imgproc.FONT_HERSHEY_SIMPLEX,
                    1.0,
                    Scalar(0.0, 0.0, 255.0),
                    2
                )
            }

            return matToByteArray(mat)

        } catch (e: Exception) {
            Log.e("OpenCV", "Exception occurred during conversion: ${e.message}")
            throw e // Re-throw to handle it higher up if needed
        }
    }

    private fun matToByteArray(mat: Mat): ByteArray {
        // Create a Bitmap from the Mat
        val processedBitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)

        // Convert the Mat to Bitmap
        Utils.matToBitmap(mat, processedBitmap)

        // Prepare an output stream to hold the compressed image data
        val stream = ByteArrayOutputStream()

        // Compress the Bitmap to JPEG format with 100% quality
        processedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)

        // Return the byte array from the stream
        return stream.toByteArray()
    }


    // Function to play the beep sound
    private fun playBeepSound() {
        audioFinishedPlaying = false
        mediaPlayer = MediaPlayer.create(this, R.raw.beep)
        mediaPlayer.start()

        // Specify the type explicitly for the parameter in the lambda
        mediaPlayer.setOnCompletionListener { mp: MediaPlayer ->
            audioFinishedPlaying = true
            mp.release() // Release the media player after the sound finishes
        }
    }
}
