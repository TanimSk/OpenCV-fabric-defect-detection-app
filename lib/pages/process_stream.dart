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
  static const platform = MethodChannel('opencv_processing');
  late Map<String, dynamic> _settings;
  final SettingsPreferences _settingsPreferences = SettingsPreferences();
  late WebSocketChannel _channel;
  final ValueNotifier<Uint8List?> _imageDataNotifier =
      ValueNotifier<Uint8List?>(null);

  @override
  void initState() {
    super.initState();
    connectWebSocket();
  }


  void connectWebSocket() async {
    try {
      _settings = await _settingsPreferences.getSettings();
      _channel = WebSocketChannel.connect(
          Uri.parse('ws://${_settings["device_ip"]}')); // Change to your WebSocket URL
      _channel.stream.listen(
        (data) async {
          // Assuming image bytes received
          Uint8List imageData = data as Uint8List;
          // Process the image from kotlin
          processFrame(imageData).then((processedImage) async {
            if (processedImage != null) {
              _imageDataNotifier.value = processedImage;              
            }
          });
        },
        onError: (error) => print('WebSocket error: $error'),
        onDone: () => print('WebSocket connection closed'),
      );
    } catch (e) {
      print('WebSocket connection failed: $e');
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

  @override
  void dispose() {
    _channel.sink.close();    
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      body: Center(
        child: ValueListenableBuilder<Uint8List?>(
          valueListenable: _imageDataNotifier,
          builder: (context, imageData, child) {
            return imageData == null
                ? const CircularProgressIndicator()
                : Image.memory(imageData);
          },
        ),
      ),
    );
  }
}
