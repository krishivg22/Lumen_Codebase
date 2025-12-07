# Lumen: Offline Assistive-Vision App

Lumen is an offline AI-powered Android application designed to support visually challenged users through real-time object detection, live OCR, document question answering, and scene captioning. All processing runs fully on-device, ensuring privacy, low latency, and reliable performance on Arm-based Android hardware.

---

## Features
- **Object Recognition:** Real-time detection using EfficientDet-Lite0 with spoken feedback.  
- **Live Text Reader:** On-device OCR with noise filtering and instant TTS narration.  
- **Document Assistant:** Document capture, text extraction, and offline QA using MobileBERT.  
- **Scene Description:** InceptionV3 + LSTM captioner generating natural-language descriptions.

---

## Technologies
Kotlin, Android SDK, CameraX, TensorFlow Lite, ML Kit OCR, MobileBERT QA, InceptionV3–LSTM captioner, TextToSpeech, SpeechRecognizer.

---

## Installation
1. Download the APK from the **Releases** section or build using Android Studio (Build → Generate APK).  
2. Install on an Android device (either directly or via `adb install -r app-debug.apk`).  
3. Open Lumen and grant the required camera/audio permissions.

---

## Demo
Watch the full demonstration video here:  
**https://youtu.be/d3427ZxMt5A?si=D7gMdWqdbGfofvIx**

---

## Documentation
For the complete project write-up, including architecture, models, and implementation details, please refer to the accompanying documentation in this repository or submission package.

