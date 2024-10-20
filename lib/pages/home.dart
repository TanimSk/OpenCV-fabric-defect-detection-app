import 'package:fabric_defect_detector/utils/settings_preferences.dart';
import 'package:flutter/material.dart';
import 'package:flutter/services.dart';

class Home extends StatefulWidget {
  const Home({super.key});

  @override
  _HomeState createState() => _HomeState();
}

class _HomeState extends State<Home> {
  late Map<String, dynamic> _settings;
  late TextEditingController _textController;
  late SettingsPreferences _settingsPreferences;
  int _defectionCount = 0;
  // Platform channel to communicate with native code
  static const platform = MethodChannel('opencv_processing');

  @override
  void initState() {
    super.initState();
    _settingsPreferences = SettingsPreferences();
    _textController = TextEditingController();
    _settings = _settingsPreferences.defaultSettings;
    _loadSettings();
  }

  Future<void> _loadSettings() async {
    final settings = await _settingsPreferences.getSettings();
    setState(() {
      _defectionCount = settings["total_defection_count"];
      _settings = settings;
      _textController.text = _settings["device_ip"] ?? "";
      print(_settings);
    });
  }

  @override
  Widget build(BuildContext context) {
    return Scaffold(
      appBar: AppBar(
        title: const Text('Setup device IP'),
      ),
      body: Center(
        child: Padding(
          padding: const EdgeInsets.only(left: 30, right: 30),
          child: Column(
            mainAxisAlignment: MainAxisAlignment.center,
            children: <Widget>[
              Text(
                'Defection count: $_defectionCount',
                style: const TextStyle(fontSize: 18),
              ),
              const SizedBox(height: 30),
              const Text('Enter the IP address of the device:'),
              const SizedBox(height: 20),
              TextField(
                  controller: _textController,
                  decoration: const InputDecoration(
                    hintText: 'Device IP',
                  )),
              const SizedBox(height: 60),
              ElevatedButton(
                onPressed: () async {
                  await platform.invokeMethod(
                    'startDetection',
                    false,
                  );
                  _settings["device_ip"] = _textController.text;
                  await _settingsPreferences.setSettings(_settings);
                  Navigator.pushReplacementNamed(context, '/process_stream');
                },
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.black,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(4.0),
                  ),
                ),
                child: const Text(
                  "Connect to device",
                  style: TextStyle(fontSize: 12, color: Colors.white),
                ),
              ),
              const SizedBox(height: 30),
              ElevatedButton(
                onPressed: () async {
                  _settings["total_defection_count"] = 0;
                  await _settingsPreferences.setSettings(_settings);
                  setState(() {
                    _defectionCount = 0;
                  });
                },
                style: ElevatedButton.styleFrom(
                  backgroundColor: Colors.black,
                  shape: RoundedRectangleBorder(
                    borderRadius: BorderRadius.circular(4.0),
                  ),
                ),
                child: const Text(
                  "    Reset Count    ",
                  style: TextStyle(fontSize: 12, color: Colors.white),
                ),
              ),
            ],
          ),
        ),
      ),
    );
  }
}
