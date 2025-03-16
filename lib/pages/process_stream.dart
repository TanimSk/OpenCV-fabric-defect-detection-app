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
  late Map<String, dynamic> _settings;
  final SettingsPreferences _settingsPreferences = SettingsPreferences();
  late WebSocketChannel _channel;
  final ValueNotifier<Uint8List?> _imageDataNotifier =
      ValueNotifier<Uint8List?>(null);

  // show on display
  List<dynamic> data = ["", ""];
  String defectStatus = "";
  String totalDefectStatus = "";
  bool readyToReceiveFrame = true;

  // Communicate with Kotlin via platform channels
  static const platform = MethodChannel('opencv_processing');
  static const EventChannel _eventChannel =
      EventChannel('com.example.fabric_defect_detector/events');

  @override
  void initState() {
    super.initState();
    connectWebSocket();
    _eventChannel.receiveBroadcastStream().listen((event) async {
      print("---------- Received event from native: $event ----------");
      await _settingsPreferences.setSettings(_settings);
      setState(() {
        data = event as List<dynamic>;
        defectStatus = data[0].toString();
        totalDefectStatus = data[1].toString();
      });
    });
  }

  void connectWebSocket() async {
    try {
      _settings = await _settingsPreferences.getSettings();
      _channel = WebSocketChannel.connect(Uri.parse(
          'ws://${_settings["device_ip"]}')); // Change to your WebSocket URL

      DateTime? lastReceivedTime;

      _channel.stream.listen(
        (data) async {
          DateTime currentTime = DateTime.now();

          if (lastReceivedTime != null) {
            Duration timeDifference = currentTime.difference(lastReceivedTime!);
            print(
                'Time since last data received: ${timeDifference.inMilliseconds} ms');
          }

          lastReceivedTime = currentTime;

          // Assuming image bytes received
          Uint8List imageData = data as Uint8List;

          // Process the image from Kotlin
          if (readyToReceiveFrame) {
            readyToReceiveFrame = false;
            await processFrame(imageData).then((processedImage) async {
              if (processedImage != null) {
                _imageDataNotifier.value = processedImage;
                readyToReceiveFrame = true;
              }
            });
          }
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
    _eventChannel.receiveBroadcastStream().listen(null);
    // _imageDataNotifier.dispose();
    super.dispose();
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('WebSocket Image Stream'),
        leading: IconButton(
          icon: const Icon(Icons.arrow_back),
          onPressed: () {
            _channel.sink.close();
            Navigator.pushReplacementNamed(
                context, '/home'); // Navigate back when the button is pressed
          },
        ),
      ),
      body: Center(
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: [
            Text(
              "Current\n$defectStatus",
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 20),
            ),
            const SizedBox(height: 10),
            ValueListenableBuilder<Uint8List?>(
              valueListenable: _imageDataNotifier,
              builder: (context, imageData, child) {
                return imageData == null
                    ? const CircularProgressIndicator()
                    : Image.memory(
                        imageData,
                        gaplessPlayback: true,
                        fit: BoxFit.cover,
                      );
              },
            ),
            const SizedBox(height: 10),
            Text(
              "Total\n$totalDefectStatus",
              textAlign: TextAlign.center,
              style: const TextStyle(fontSize: 20),
            ),
          ],
        ),
      ),
    );
  }
}
