import 'package:fabric_defect_detector/pages/home.dart';
import 'package:fabric_defect_detector/pages/process_stream.dart';
import 'package:flutter/material.dart';

void main() {
  runApp(MyApp());
}

class MyApp extends StatelessWidget {
  @override
  Widget build(BuildContext context) {
    return MaterialApp(
      title: 'Fabric Defect Detector',
      initialRoute: "/home",
      routes: <String, WidgetBuilder>{
        '/home': (context) => const Home(),
        '/process_stream': (context) => const ProcessStream(),
      },
    );
  }
}
