import 'dart:convert';
import 'package:shared_preferences/shared_preferences.dart';

class SettingsPreferences {
  Map<String, dynamic> defaultSettings = {
    "device_ip": "0.0.0.0:0000", // "192.168.1.103:5500"
    "total_defection_count": 0,
  };

  Future<Map<String, dynamic>> getSettings() async {
    final SharedPreferences prefs = await SharedPreferences.getInstance();
    final String? settings = prefs.getString("settings");

    if (settings == null) {
      return defaultSettings;
    }
    return json.decode(settings);
  }

  Future<void> setSettings(Map<String, dynamic> settings) async {
    final SharedPreferences prefs = await SharedPreferences.getInstance();
    await prefs.setString("settings", jsonEncode(settings));
  }
}
