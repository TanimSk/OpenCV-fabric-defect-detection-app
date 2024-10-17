import 'package:fabric_defect_detector/utils/settings_preferences.dart';
import 'package:flutter/material.dart';

class Home extends StatefulWidget {
  const Home({super.key});

  @override
  _HomeState createState() => _HomeState();
}

class _HomeState extends State<Home> {
  late Map<String, dynamic> _settings;
  late TextEditingController _textController;
  late SettingsPreferences _settingsPreferences;

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
        child: Column(
          mainAxisAlignment: MainAxisAlignment.center,
          children: <Widget>[
            const Text('Enter the IP address of the device:'),
            TextField(
                controller: _textController,
                decoration: const InputDecoration(
                  hintText: 'Device IP',
                )),
            ElevatedButton(
              onPressed: () {
                _settings["device_ip"] = _textController.text;
                _settingsPreferences.setSettings(_settings);
                Navigator.pushReplacementNamed(context, '/process_stream');
              },
              child: const Text("Connect to device"),
            ),
          ],
        ),
      ),
    );
  }
}
