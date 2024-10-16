package com.example.fabric_defect_detector

import android.graphics.Bitmap
import android.os.Bundle
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodChannel
import org.opencv.android.OpenCVLoader
import org.opencv.android.Utils
import org.opencv.core.MatOfByte
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import java.io.ByteArrayOutputStream

class MainActivity: FlutterActivity() {
    private val CHANNEL = "opencv_processing"

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
            }
        }
    }

    private fun processFrameWithOpenCV(frameData: ByteArray): ByteArray {
        Log.d("OpenCV", "Received frame data length: ${frameData.size}")

        try {
            val mat = Imgcodecs.imdecode(MatOfByte(*frameData), Imgcodecs.IMREAD_UNCHANGED)
            if (mat.empty()) {
                Log.e("OpenCV", "Decoded Mat is empty")
                throw IllegalArgumentException("Failed to decode byte array to Mat")
            }

            Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGB2GRAY)
            val processedBitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
            Utils.matToBitmap(mat, processedBitmap)
            val stream = ByteArrayOutputStream()
            processedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)

            // Here you can process the Mat if needed, e.g., apply filters
            // For demonstration, let's just return the original frame data
            // return frameData
            return stream.toByteArray()
        } catch (e: Exception) {
            Log.e("OpenCV", "Exception occurred during conversion: ${e.message}")
            throw e // Re-throw to handle it higher up if needed
        }
    }


    // Process the Mat using OpenCV (e.g., apply grayscale)
//        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_RGB2GRAY)
//
//        Log.i("data 1: ", frameData.size.toString())
//
//        // Convert processed Mat back to bitmap
//        val processedBitmap = Bitmap.createBitmap(mat.cols(), mat.rows(), Bitmap.Config.ARGB_8888)
//        Utils.matToBitmap(mat, processedBitmap)
//        // Convert bitmap back to byte array
//        val stream = ByteArrayOutputStream()
//        processedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
//
//        Log.i("data 2: ", stream.toByteArray().toString())


}
