import 'dart:async';
import 'dart:ffi';
import 'package:fabric_defect_detector/utils/settings_preferences.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

class ProcessStream extends StatefulWidget {
  const ProcessStream({super.key});

  @override
  _ProcessStream createState() => _ProcessStream();
}

class _ProcessStream extends State<ProcessStream> {
  late WebSocketChannel _channel;
  final ValueNotifier<Uint8List?> _imageDataNotifier =
      ValueNotifier<Uint8List?>(null);
  Timer? retryTimer;
  final SettingsPreferences _settingsPreferences = SettingsPreferences();
  bool _isDetectionStarted = false;

  // Platform channel to communicate with native code
  static const platform = MethodChannel('opencv_processing');

  @override
  void initState() {
    super.initState();
    connectWebSocket();
  }

  void connectWebSocket() async {
    try {
      Map<String, dynamic> settings = await _settingsPreferences.getSettings();
      _channel = WebSocketChannel.connect(
        Uri.parse('ws://${settings["device_ip"]}'),
      );
      _channel.stream.listen(
        (data) {
          // Assuming image bytes received
          Uint8List imageData = data as Uint8List;
          // Process the image using OpenCV
          processFrame(imageData).then((processedImage) {
            if (processedImage != null) {
              _imageDataNotifier.value =
                  processedImage; // Update the notifier value
            }
          });
        },
        onError: (error) {
          print('WebSocket error: $error');
          retryConnection();
        },
        onDone: () {
          Navigator.pushReplacementNamed(context, '/home');
          print('WebSocket connection closed');
        },
      );
    } catch (e) {
      print('WebSocket connection failed: $e');
      retryTimer = Timer(const Duration(seconds: 2), connectWebSocket);
    }
  }

  // Process frame using OpenCV via platform channels
  Future<Uint8List?> processFrame(Uint8List? frame) async {
    try {
      return await platform.invokeMethod('processFrame', frame);
    } on PlatformException catch (e) {
      print("Failed to process frame: ${e.message}");
      return frame;
    }
  }

  void retryConnection() {
    retryTimer?.cancel();
    retryTimer = Timer(const Duration(seconds: 5), connectWebSocket);
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('WebSocket Image Stream'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () {
            Navigator.pushReplacementNamed(
                context, '/home'); // Navigate back when the button is pressed
          },
        ),
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            ValueListenableBuilder<Uint8List?>(
              valueListenable: _imageDataNotifier,
              builder: (context, imageData, child) {
                if (imageData == null) {
                  return const CircularProgressIndicator();
                }
                return Image.memory(
                  imageData,
                  gaplessPlayback: true,
                  fit: BoxFit.cover,
                );
              },
            ),
            const SizedBox(height: 30),
            // Button is now outside the ValueListenableBuilder, so it won't rebuild unnecessarily
            ElevatedButton(
              onPressed: () async {
                _isDetectionStarted = !_isDetectionStarted;
                bool state = await platform.invokeMethod(
                  'startDetection',
                  _isDetectionStarted,
                );
                setState(() {
                  _isDetectionStarted = state;
                });
              },
              style: ElevatedButton.styleFrom(
                backgroundColor: Colors.black,
                shape: RoundedRectangleBorder(
                  borderRadius: BorderRadius.circular(4.0),
                ),
              ),
              child: Text(
                _isDetectionStarted ? "Stop detection" : "Start detection",
                style: const TextStyle(fontSize: 12, color: Colors.white),
              ),
            ),
          ],
        ),
      ),
    );
  }

  @override
  void dispose() {
    _channel.sink
        .close(); // Close the WebSocket connection when the widget is disposed
    retryTimer?.cancel();
    super.dispose();
  }
}
