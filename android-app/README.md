# Smart Backpack Display — Android Companion App

A native Android application written in **Kotlin** that detects currently playing music from your phone (Spotify, YouTube Music, Apple Music, etc.) and beams the **song title, artist, album, live playback position, and resized album artwork** over **Bluetooth Low Energy (BLE)** to the ESP32 backpack display.

---

## 📱 Features

- **Automatic Media Detection**: Uses Android's `NotificationListenerService` and `MediaSessionManager` to capture playback from any standard Android music app.
- **Privacy-First**: Captures **only** music metadata. Never inspects personal messages, chats, or private notifications.
- **Background Execution**: Runs as an Android Foreground Service with a persistent status notification so music updates continue streaming when your screen is locked and your phone is in your pocket or backpack.
- **Fast Image Chunking**: Automatically scales high-resolution album artwork down to **80×80 pixels**, converts it to 16-bit **RGB565 binary format**, and transmits it across BLE chunks with checksum indices.
- **Auto-Reconnect**: Seamlessly reconnects to the ESP32 if you walk out of range and come back.

---

## 🛠️ How to Open and Build in Android Studio

### 1. Open Project
1. Open **Android Studio** (Hedgehog, Iguana, or Jellyfish).
2. Click **Open** and select:  
   `C:\Users\Abdul\.gemini\antigravity\scratch\smart-backpack\android-app`
3. Wait for Gradle to sync dependencies automatically.

### 2. Enable Developer Options on Your Phone
1. On your Android phone, go to **Settings ➔ About Phone**.
2. Tap **Build Number** 7 times until you see *"You are now a developer!"*.
3. Go to **Settings ➔ System / Developer Options** and enable **USB Debugging**.
4. Connect your phone to your laptop using a USB cable.

### 3. Run or Generate APK
- To run directly on your phone: Select your device from the top device dropdown in Android Studio and click the green **Run (▶)** button.
- To export a standalone APK: Click **Build ➔ Build Bundle(s) / APK(s) ➔ Build APK(s)**.

---

## ⚙️ First-Time Phone Setup

When you first open the app on your phone:
1. **Grant Bluetooth Permissions**: Tap **Allow** when prompted for Nearby Devices (Bluetooth) permissions.
2. **Grant Notification Access**: Tap **Open Settings** when prompted, find **Smart Backpack Display** in the list, and turn the toggle **ON**. This allows Android to share what song is currently playing on Spotify.
3. Turn on your ESP32 display.
4. Tap **Scan & Connect**. The status will turn green (**CONNECTED**)!

---

## 🎵 Supported Applications
- **Spotify** (Free & Premium)
- **YouTube Music**
- **Apple Music**
- **SoundCloud**
- **Amazon Music**
- **TIDAL**
- Any music player supporting Android's standard `MediaSessionCompat` API.
