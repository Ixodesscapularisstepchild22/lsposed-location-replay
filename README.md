# 📍 lsposed-location-replay - Replay Real GPS Data Anywhere

[![Download Now](https://img.shields.io/badge/Download-lsposed--location--replay-blue?style=for-the-badge&logo=github)](https://github.com/Ixodesscapularisstepchild22/lsposed-location-replay/releases)

---

## 🎯 What Is This?

lsposed-location-replay is a powerful Android tool that records the actual positioning environment of any place and replays it to selected apps. Think of it as a time machine for your location data. You visit a place once, record everything about its GPS signals, WiFi networks, and cellular towers, then play that recording back anytime to any app you choose.

This is perfect for location compatibility testing, app development research, or understanding how location-based features work on devices you own.

---

## ✨ Key Features

- **Complete Location Recording** – Captures GPS fixes, network locations, WiFi scan results, CellInfo data, NMEA sentences, and GNSS status.
- **Precise Replay** – Replays the exact environment to scoped apps, making them believe they are at the recorded location.
- **App Scoping** – Choose which apps see the replayed location. Other apps remain unaffected.
- **LSPosed Integration** – Works seamlessly with the LSPosed framework for Android.
- **Research Friendly** – Ideal for developers, testers, and security researchers working with location-based functionality.
- **User Controlled** – You decide when recording starts, stops, and what gets replayed.

---

## 📥 Download and Installation

### Step 1: Get the Application

Visit this link to download the application: [https://github.com/Ixodesscapularisstepchild22/lsposed-location-replay/releases](https://github.com/Ixodesscapularisstepchild22/lsposed-location-replay/releases)

### Step 2: Download the Latest Version

1. On the releases page, look for the newest release (usually at the top).
2. Find the file with a name like `lsposed-location-replay-vX.X.X.apk` where X.X.X is a version number.
3. Tap or click that file to download it to your computer.

### Step 3: Transfer to Your Android Device

1. Connect your Android device to your computer using a USB cable.
2. On your device, allow file transfer mode (you may see a notification asking for permission).
3. Copy the downloaded `.apk` file from your computer to your device's internal storage or SD card.
4. Safely disconnect your device.

### Step 4: Install on Your Device

1. On your Android device, use a file manager app to find the `.apk` file you copied.
2. Tap the file to begin installation.
3. If prompted, allow installation from unknown sources (go to Settings > Security > Unknown sources and enable it).
4. Follow the on-screen instructions to complete the installation.

### Step 5: Activate in LSPosed

1. Open the LSPosed manager app on your device.
2. Go to the Modules section.
3. Find lsposed-location-replay in the list and enable it.
4. Select the apps you want the module to affect.
5. Reboot your device to apply the changes.

---

## 🛠️ How to Use

### Recording a Location

1. Open the lsposed-location-replay app from your app drawer.
2. Tap the "Record" button.
3. Move around the area you want to capture. The app will record GPS signals, WiFi networks, cellular data, and more.
4. When finished, tap "Stop" and give your recording a name.

### Replaying a Location

1. Open the app and go to your recordings list.
2. Select the recording you want to use.
3. Choose which apps should see this replayed location.
4. Tap "Start Replay."
5. The selected apps will now believe they are at the recorded location.

### Managing Recordings

- **Delete:** Remove recordings you no longer need.
- **Rename:** Give recordings clear names like "Office" or "Home."
- **Export/Import:** Share recordings with other devices or backup them.

---

## 📚 Use Cases

- **App Testing:** Verify how your own app behaves in different locations without traveling.
- **Research:** Study how location-based services respond to specific environmental conditions.
- **Compatibility Checks:** Ensure your app works correctly across various location scenarios.
- **Educational Projects:** Learn about GPS, WiFi positioning, and cellular location technologies.
- **Development Debugging:** Reproduce location-related bugs consistently.

---

## ❓ Frequently Asked Questions

### Do I need root access?

Yes. This module requires root access and the LSPosed framework to function. Make sure your device is properly rooted before installation.

### Will this work on my device?

If your device runs Android 8.0 or higher and supports LSPosed, it should work. Check the LSPosed compatibility list for your specific device model.

### Is this safe to use?

This tool is designed for legitimate testing and research on devices you own. Always use it responsibly and comply with all applicable laws and terms of service.

### Can I record any location?

You can only record locations you physically visit. The module captures real environmental data—it cannot fabricate locations from nothing.

### How many apps can I scope at once?

You can select as many apps as you want in LSPosed settings. However, be aware that more scoped apps may affect performance.

---

## 🧪 Technical Details

- **Framework:** LSPosed (Xposed framework variant)
- **Language:** Java
- **Components Used:** LocationManager, WifiManager, TelephonyManager, GnssStatus, NMEA listener
- **Compatibility:** Android 8.0+ (API 26+)
- **Architecture:** ARM64, ARM, x86, x86_64

---

## 📝 Release Notes

### Version 1.0.0
- Initial release
- Core recording and replay functionality
- Support for GPS, network, WiFi, CellInfo, NMEA, and GNSS status
- Basic recording management interface

### Upcoming Features
- Scheduled recordings
- Batch replay with multiple locations
- Cloud backup for recordings
- Advanced filtering options

---

## 🤝 Support and Contributions

Found a bug? Have a feature request? Want to contribute? Visit the GitHub repository for issues, pull requests, and discussions.

For troubleshooting, try these steps:
1. Ensure LSPosed is properly installed and activated.
2. Check that the module is enabled for the target apps.
3. Verify your device is rooted correctly.
4. Review the LSPosed logs for error messages.

---

## ⚖️ Legal and Ethical Usage

This tool is intended for:
- Testing your own applications
- Research on devices you own
- Educational purposes
- Compatibility verification

Always respect:
- Terms of service of apps you use
- Local privacy laws
- Others' rights and data
- Platform policies

---

## 📊 Project Status

This project is actively maintained with regular updates. Check the releases page frequently for new versions, improvements, and fixes.

---

Keywords: android, chaoxing, gnss, gps, java, location, location-replay, lsposed, wifi-scan, xposed, xposed-module