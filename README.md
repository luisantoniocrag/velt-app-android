# velt-app-android

App Android (Kotlin + Jetpack Compose) para validación biométrica de palma con el sensor **Velt**.

La app se conecta al sensor por Bluetooth (wake BLE + SPP/RFCOMM), captura el template biométrico
y lo verifica contra el bioserver.

## Build

```bash
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Requiere Android SDK con `compileSdk 36` (`minSdk 29`).

## Documentación

Ver [`CLAUDE.md`](CLAUDE.md) para la arquitectura, el flujo de validación, el protocolo del sensor
y las notas de implementación.
