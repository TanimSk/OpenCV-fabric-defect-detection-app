import 'dart:async';
import 'dart:typed_data';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';
import 'package:web_socket_channel/web_socket_channel.dart';

void main() {
  runApp(MyApp());
}

class MyApp extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'WebSocket Image Stream',
      theme: ThemeData(
        primarySwatch: Colors.blue,
      ),
      home: ImageStreamPage(),
    );
  }
}

class ImageStreamPage extends StatefulWidget {
  @override
  _ImageStreamPageState createState() => _ImageStreamPageState();
}

class _ImageStreamPageState extends State<ImageStreamPage> {
  late WebSocketChannel _channel;
  final ValueNotifier<Uint8List?> _imageDataNotifier =
      ValueNotifier<Uint8List?>(null);
  Timer? retryTimer;

  @override
  void initState() {
    super.initState();
    connectWebSocket();
  }

  void connectWebSocket() {
    try {
      _channel = WebSocketChannel.connect(
        Uri.parse('ws://192.168.1.103:5500'), // Replace with your server IP
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
          print('WebSocket connection closed');
        },
      );
    } catch (e) {
      print('WebSocket connection failed: $e');
      retryTimer = Timer(const Duration(seconds: 5), connectWebSocket);
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
