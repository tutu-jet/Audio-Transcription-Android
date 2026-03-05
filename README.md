# Audio-Transcription-Android

Android client for **WhisperLive** (real-time speech-to-text over WebSocket).

It captures microphone audio, resamples it to **16 kHz mono**, encodes it as **float32 PCM bytes**, streams it to the WhisperLive Python WebSocket server, and shows transcription segments as they arrive.

> This client is designed to work with the WhisperLive server in this repo.

## Features

- Microphone capture with `AudioRecord`
- Resample to 16 kHz mono (linear interpolation)
- Stream `float32` PCM frames to WhisperLive via WebSocket
- Live transcription UI (Jetpack Compose)
- Start / Pause / Resume / Stop
- Sends `END_OF_AUDIO` when stopping

## Requirements

- Android Studio (Giraffe+ recommended)
- Android 8.0+ (minSdk 26)
- A reachable WhisperLive server (same Wi‑Fi is easiest)

## Running

1. Start the server (from repo root):

```bash
python run_server.py --host 0.0.0.0 --port 9090
```

2. In Android app, set the server URL in the UI:

- `ws://<your-server-ip>:9090`

3. Run on a physical device (recommended). Make sure microphone permission is granted.

## Protocol notes (important)

- On connect, client must send a **JSON string** config first, e.g.:

```json
{"uid":"<uuid>","language":"en","task":"transcribe","model":"small","use_vad":true,"max_clients":4,"max_connection_time":600}
```

- Audio frames are sent as **binary** payloads containing `np.float32` samples.
- When stopping, send the UTF-8 string **`END_OF_AUDIO`**.
- Server responses are JSON strings. Transcription is in `segments`.

## Project structure

```
Audio-Transcription-Android/
  settings.gradle.kts
  build.gradle.kts
  app/
    build.gradle.kts
    src/main/
      AndroidManifest.xml
      java/.../ (Kotlin sources)
```

