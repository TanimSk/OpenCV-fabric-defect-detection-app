package com.example.fabric_defect_detector

import android.os.Bundle
import android.util.Log
import io.flutter.embedding.android.FlutterActivity
import io.flutter.plugin.common.MethodChannel
import java.io.File
import java.io.FileOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.Array
import org.opencv.android.OpenCVLoader
import org.opencv.core.CvType
import org.opencv.core.CvType.CV_32F
import org.opencv.core.Mat
import org.opencv.core.MatOfByte
import org.opencv.core.Rect
import org.opencv.core.Scalar
import org.opencv.core.Size
import org.opencv.imgcodecs.Imgcodecs
import org.opencv.imgproc.Imgproc
import org.tensorflow.lite.Interpreter
import org.tensorflow.lite.gpu.CompatibilityList
import org.tensorflow.lite.gpu.GpuDelegate

class MainActivity : FlutterActivity() {
    private lateinit var mat: Mat
    private val CHANNEL = "opencv_processing"
    private val labels = listOf("ripped", "stain")
    private lateinit var processedFrame: ByteArray
    private lateinit var interpreter: Interpreter
    private lateinit var inputShape: IntArray
    private lateinit var outputShape: IntArray
    private var tfInitialized = false

    private var frameCount: Int = 0
    private val FRAME_SKIP: Int = 3

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!OpenCVLoader.initLocal()) {
            Log.e("OpenCV", "OpenCV initialization failed")
        } else {
            Log.d("OpenCV", "OpenCV initialized successfully")
        }

        // val options = Interpreter.Options().apply { setUseNNAPI(true) }
        // val options =
        //         Interpreter.Options().apply {
        //             setUseNNAPI(false) // Ensure NNAPI is off if using XNNPACK
        //             setNumThreads(4) // Use multi-threading
        //             setUseXNNPACK(true) // Enable XNNPACK for optimization
        //         }
        val compatList = CompatibilityList()

        val options =
                Interpreter.Options().apply {
                    if (compatList.isDelegateSupportedOnThisDevice) {
                        // if the device has a supported GPU, add the GPU delegate
                        val delegateOptions = compatList.bestOptionsForThisDevice
                        this.addDelegate(GpuDelegate(delegateOptions))
                    } else {
                        // if the GPU is not supported, run on 4 threads
                        this.setNumThreads(4)
                    }
                }
        interpreter = Interpreter(loadModelFile("model.tflite"), options)

        flutterEngine?.dartExecutor?.binaryMessenger?.let {
            MethodChannel(it, CHANNEL).setMethodCallHandler { call, result ->
                if (call.method == "processFrame") {
                    val byteArray = call.arguments as ByteArray
                    if (frameCount % FRAME_SKIP == 0) {
                        processedFrame = processFrameWithOpenCV(byteArray)
                    }
                    frameCount++
                    result.success(processedFrame)
                }
            }
        }
    }

    private fun loadModelFile(modelName: String): ByteBuffer {
        val assetManager = assets
        val fileDescriptor = assetManager.openFd(modelName)
        val inputStream = fileDescriptor.createInputStream()
        val file = File(context.cacheDir, modelName)
        val outputStream = FileOutputStream(file)
        inputStream.copyTo(outputStream)
        inputStream.close()
        outputStream.close()

        val modelBuffer =
                file.readBytes().copyOf().let {
                    ByteBuffer.allocateDirect(it.size).apply {
                        order(ByteOrder.nativeOrder())
                        put(it)
                    }
                }
        return modelBuffer
    }

    private fun processFrameWithOpenCV(byteArray: ByteArray): ByteArray {
        if (!::interpreter.isInitialized) {
            Log.e("OpenCV", "Interpreter is not initialized")
            return byteArray
        }

        // set input and output shapes just after the interpreter is initialized once
        if (!tfInitialized) {
            inputShape = interpreter.getInputTensor(0).shape()
            outputShape = interpreter.getOutputTensor(0).shape()
            tfInitialized = true
        }

        mat = Imgcodecs.imdecode(MatOfByte(*byteArray), Imgcodecs.IMREAD_COLOR)
        // Decode the byte array to a Mat
        if (mat.empty()) {
            Log.e("OpenCV", "Decoded Mat is empty")
            throw IllegalArgumentException("Failed to decode byte array to Mat")
        }

        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2RGB)
        val preprocessedImage = preprocessImage(mat, inputShape)
        Log.d("Input", preprocessedImage.size.toString())
        val outputData = runInference(preprocessedImage) // Extract batch 0
        Log.d("Output", outputData.size.toString())
        var boxes = postprocessOutput(outputData[0], mat)
        boxes = nms(boxes, 0.5f)
        Log.d("Boxes", boxes.size.toString())
        return getAnnotateImage(mat, boxes)
    }

    private fun preprocessImage(
            imageMat: Mat,
            inputShape: IntArray
    ): Array<Array<Array<FloatArray>>> {
        val resizedImage = Mat()

        // Resize to (640, 640)
        Imgproc.resize(
                imageMat,
                resizedImage,
                Size(inputShape[2].toDouble(), inputShape[1].toDouble())
        )

        // Convert to float32 and normalize to [0,1]
        resizedImage.convertTo(resizedImage, CvType.CV_32F, 1.0 / 255.0)

        // Convert Mat to a 4D array (1, 640, 640, 3)
        val height = resizedImage.rows()
        val width = resizedImage.cols()
        val channels = resizedImage.channels()

        // Convert Mat to a float array
        val floatArray = FloatArray(height * width * channels)
        resizedImage.get(0, 0, floatArray)

        // Reshape to [1, 640, 640, 3]
        val outputArray = Array(1) { Array(height) { Array(width) { FloatArray(channels) } } }
        var index = 0
        for (h in 0 until height) {
            for (w in 0 until width) {
                for (c in 0 until channels) {
                    outputArray[0][h][w][c] = floatArray[index++]
                }
            }
        }

        return outputArray
    }

    private fun runInference(
            inputImage: Array<Array<Array<FloatArray>>>
    ): Array<Array<FloatArray>> {
        // outputData to match the expected shape
        val outputData =
                Array(outputShape[0]) { Array(outputShape[1]) { FloatArray(outputShape[2]) } }
        interpreter.run(inputImage, outputData)
        return outputData
    }

    private fun postprocessOutput(
            outputData: Array<FloatArray>,
            image: Mat
    ): ArrayList<Array<Float>> {
        val imageHeight = image.rows()
        val imageWidth = image.cols()
        val boxes = ArrayList<Array<Float>>()
        // val detections = outputData[0]
        val xCenters = outputData[0]
        val yCenters = outputData[1]
        val widths = outputData[2]
        val heights = outputData[3]
        val confs = outputData.takeLast(2)

        // iterate over the confs to form array of [x_min, y_min, x_max, y_max, class_id,
        // confidence]
        for (i in confs.indices) {
            for (j in 0 until confs[i].size) {
                if (confs[i][j] > 0.5) {
                    val xMin = ((xCenters[j] - (widths[j] / 2)) * imageWidth)
                    val yMin = ((yCenters[j] - (heights[j] / 2)) * imageHeight)
                    val xMax = ((xCenters[j] + (widths[j] / 2)) * imageWidth)
                    val yMax = ((yCenters[j] + (heights[j] / 2)) * imageHeight)
                    boxes.add(arrayOf(xMin, yMin, xMax, yMax, i.toFloat(), confs[i][j]))
                }
            }
        }

        return boxes
    }

    private fun iou(box1: Array<Float>, box2: Array<Float>): Float {
        val xMin = maxOf(box1[0], box2[0])
        val yMin = maxOf(box1[1], box2[1])
        val xMax = minOf(box1[2], box2[2])
        val yMax = minOf(box1[3], box2[3])

        val intersection = maxOf(0f, xMax - xMin) * maxOf(0f, yMax - yMin)
        val area1 = (box1[2] - box1[0]) * (box1[3] - box1[1])
        val area2 = (box2[2] - box2[0]) * (box2[3] - box2[1])

        return intersection / (area1 + area2 - intersection)
    }

    private fun nms(boxes: ArrayList<Array<Float>>, iouThreshold: Float): ArrayList<Array<Float>> {
        if (boxes.isEmpty()) return arrayListOf()

        // Sort boxes by confidence score in descending order
        val sortedBoxes = boxes.sortedByDescending { it[5] }.toMutableList()

        val selectedBoxes = arrayListOf<Array<Float>>()

        while (sortedBoxes.isNotEmpty()) {
            val chosenBox = sortedBoxes.removeAt(0)
            selectedBoxes.add(chosenBox)

            // Remove boxes with high IoU
            sortedBoxes.removeAll { iou(chosenBox, it) > iouThreshold }
        }

        return selectedBoxes
    }

    private fun getAnnotateImage(image: Mat, boxes: ArrayList<Array<Float>>): ByteArray {
        for (box in boxes) {
            val xMin = box[0].toInt()
            val yMin = box[1].toInt()
            val xMax = box[2].toInt()
            val yMax = box[3].toInt()
            val classId = box[4].toInt()
            val confidence = box[5]

            Imgproc.rectangle(
                    image,
                    Rect(xMin, yMin, xMax - xMin, yMax - yMin),
                    Scalar(0.0, 255.0, 0.0),
                    2
            )

            Imgproc.putText(
                    image,
                    "${labels[classId]}: ${String.format("%.2f", confidence)}",
                    org.opencv.core.Point(xMin.toDouble(), yMin.toDouble()),
                    Imgproc.FONT_HERSHEY_SIMPLEX,
                    0.5,
                    Scalar(0.0, 255.0, 0.0),
                    2
            )
        }

        return matToByteArray(image)
    }

    private fun matToByteArray(mat: Mat): ByteArray {
        // Create a Bitmap from the Mat
        // val processedBitmap = Bitmap.createBitmap(mat.cols(), mat.rows(),
        // Bitmap.Config.ARGB_8888)

        // // Convert the Mat to Bitmap
        // Utils.matToBitmap(mat, processedBitmap)

        // // Prepare an output stream to hold the compressed image data
        // val stream = ByteArrayOutputStream()

        // // Compress the Bitmap to JPEG format with 100% quality
        // // processedBitmap.compress(Bitmap.CompressFormat.JPEG, 100, stream)
        // Imgcodecs.imencode(".jpg", mat, buffer)

        // // Return the byte array from the stream
        // return stream.toByteArray()
        val buffer = MatOfByte()
        Imgproc.cvtColor(mat, mat, Imgproc.COLOR_BGR2RGB)
        Imgcodecs.imencode(".jpg", mat, buffer)
        return buffer.toArray()
    }
}
