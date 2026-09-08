# Phone as Cam (PaC) — Ultra-Low-Latency Camera Live Stream

<p align="center">
  <b>Verwandle jedes Android-Smartphone in eine latenzfreie Überwachungs- & Streaming-Kamera mit Weitwinkel-, Megafon- und GPS-Unterstützung.</b>
</p>

<p align="center">
  <img src="https://img.shields.io/badge/Platform-Android-green.svg" alt="Android" />
  <img src="https://img.shields.io/badge/Language-Kotlin-purple.svg" alt="Kotlin" />
  <img src="https://img.shields.io/badge/Protocol-WebRTC%20H.264-blue.svg" alt="WebRTC" />
  <img src="https://img.shields.io/badge/Latency-%3C%2080ms-brightgreen.svg" alt="Latency" />
  <img src="https://img.shields.io/badge/UI-Jetpack%20Compose-navy.svg" alt="Jetpack Compose" />
</p>

---

## [1] Features & Leistungsmerkmale

- **Zero-Latency WebRTC Stream:** Hardware-beschleunigtes **H.264 Baseline**-Encoding für Echtzeit-Übertragungen (< 80 ms Latenz) direkt im Browser ohne Zusatz-Software.
- **Display-Off & Background Streaming:** Streamt dank `PARTIAL_WAKE_LOCK` und `WIFI_MODE_FULL_HIGH_PERF` unterbrechungsfrei weiter, selbst wenn der Bildschirm gesperrt oder ausgeschaltet ist.
- **Zero-Client Idle Standby:** Ist kein Zuschauer im Web-Interface aktiv, pausiert die Frame-Kompression automatisch (minimaler Stromverbrauch, kein Überhitzen).
- **Universeller Weitwinkel-Support:** Automatische Erkennung physischer Ultraweitwinkel-Linsen (< 3.2 mm Brennweite) sowie optischer Weitwinkelbereiche (< 1.0x) über `CameraManager`.
- **Weitwinkel-Taschenlampe:** Intelligenter Hardware-Fallback für das Blitzlicht auch bei aktiver Weitwinkellinse.
- **Selfie-Kamera & Bild-in-Bild (PiP):** Gleichzeitiges Selfie-PiP auf unterstützten Multi-Cam-Geräten sowie universeller Switch zwischen Haupt- und Frontkamera.
- **Bidirektionales Audio:**
  - **Tonausgabe:** Echtzeit-Audio vom Smartphone-Mikrofon zum Browser.
  - **Megafon-Modus:** Sprachausgabe vom PC/Browser direkt über den Smartphone-Lautsprecher (mit automatischer Lautstärken-Maximierung auf 100%).
- **Live GPS-Tracking:** Integrierte Standortkarte via OpenStreetMap (Leaflet) im Web-Interface.

---

## [2] Streaming-Presets

Schnellauswahl für typische Einsatzszenarien im Web-Interface:

| Preset | Auflösung | Bildrate | Bitrate | Anwendungsfall |
| :--- | :--- | :--- | :--- | :--- |
| **Eco** | 480p (640x480) | 15 FPS | 1.2 Mbit/s | Maximaler Hitzeschutz, ideal für Dauerbetrieb |
| **Standard** | 720p (1280x720) | 20 FPS | 2.5 Mbit/s | Ausgewogenes Verhältnis aus Bildschärfe & Akku |
| **Smooth** | 720p (1280x720) | 30 FPS | 4.5 Mbit/s | Sehr flüssige Bewegungsdarstellung |
| **Ultra 60** | 1080p (1920x1080) | 60 FPS | 8.0 Mbit/s | Höchste Detailstufe & 60 FPS Bildwiederholrate |

---

## [3] Architektur & Technologie

- **Kamera-Pipeline:** [CameraX](https://developer.android.com/training/camerax) (ImageAnalysis mit NV21 YUV-Puffern) + Camera2 Interop.
- **Streaming-Engine:** WebRTC (Android SDK) mit direktem NV21-Puffer-Push und dynamischer Bitraten-/FPS-Regelung über `RtpSender.parameters`.
- **Webserver:** Integrierter [NanoHTTPD](https://github.com/NanoHttpd/nanohttpd) Server zur Bereitstellung des Web-UI und REST-Steuerkanals.
- **App-UI:** [Jetpack Compose](https://developer.android.com/jetpack/compose) mit AMOLED-Sparmodus und IP-/Port-Konfiguration.

---

## [4] Installation & Schnellstart

### Voraussetzungen
- Android 7.0 (API Level 24) oder höher (empfohlen: Android 10+)
- JDK 17 oder höher
- Android Studio / Android SDK Platform-Tools

### Kompilieren & Ausführen
```bash
# Repository klonen
git clone https://github.com/theStxve/phone-as-cam-app.git
cd phone-as-cam-app

# Debug APK kompilieren
./gradlew assembleDebug

# Auf verbundenem Gerät installieren
./gradlew installDebug
```

---

## [5] Web-Interface Bedienung

1. Starte die App auf dem Smartphone und tippe auf **"Start Stream"**.
2. Notiere die angezeigte IP-Adresse und den Port (z. B. `http://192.168.178.50:8080`).
3. Öffne die URL im Webbrowser auf dem Zielgerät.
4. **Hinweis zum Megafon-Modus:** Da Browser Mikrofon-Zugriffe auf HTTP-Seiten einschränken, aktiviere in Google Chrome unter `chrome://flags/#unsafely-treat-insecure-origin-as-secure` die IP deiner Smartphone-Kamera.

---

## [6] Lizenz
Dieses Projekt ist für private und Bildungszwecke lizenziert. Details siehe Repository.
