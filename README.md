# velt

Monorepo del proyecto Velt: validación biométrica de palma con el sensor **Velt**.

```
.
├── android-app/   # app Android (Kotlin + Jetpack Compose)
└── backend/       # backend (en construcción)
```

La app Android se conecta al sensor por Bluetooth (wake BLE + SPP/RFCOMM), captura el template
biométrico y lo verifica contra el bioserver.

## Build (android-app)

```bash
cd android-app
./gradlew :app:assembleDebug
./gradlew :app:installDebug
```

Requiere Android SDK con `compileSdk 36` (`minSdk 29`).

## Documentación

Ver [`CLAUDE.md`](CLAUDE.md) para la arquitectura, el flujo de validación, el protocolo del sensor
y las notas de implementación.
