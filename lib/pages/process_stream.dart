import 'dart:async';
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
  SettingsPreferences _settingsPreferences = SettingsPreferences();

  @override
  void initState() {
    super.initState();
    connectWebSocket();
  }

  void connectWebSocket() async {
    try {
      final settings = await _settingsPreferences.getSettings();
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

  // Platform channel to communicate with native code
  static const platform = MethodChannel('opencv_processing');

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
      ),
      body: Center(
        child: ValueListenableBuilder<Uint8List?>(
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
