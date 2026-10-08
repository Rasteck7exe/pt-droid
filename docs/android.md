# Android (PT Droid)

Port del port nativo de P.T. a Android ARM64. Es el mismo juego en C++/Vulkan del repositorio, compilado como
`libmain.so` y envuelto con la actividad Java de SDL3 en un APK. Igual que en PC, **no trae ningún archivo del juego**: lee
los archivos de tu propia copia.

## Requisitos

- Teléfono Android 11 o más nuevo, ARM64, con **Vulkan 1.3** (por ejemplo Snapdragon 8 Gen 2/3/Elite).
- Tus tres archivos de P.T. (versión US, `CUSA01127`): `chunk1.psarc`, `texture.qar` y `pathid_list_ps4.bin`.
- Un control (Bluetooth o USB). Por ahora no hay controles táctiles.

## Conseguir el APK

Cada push a la rama `android-port` corre el workflow `.github/workflows/android.yml`, que compila el APK y lo publica:

- en **Releases** como pre-release `android-v0.1.N` (se descarga directo desde el teléfono), y
- como artefacto del workflow en la pestaña **Actions**.

Todas las compilaciones se firman con la misma llave de desarrollo (`android/app/pt-droid-dev.keystore`), así que cada
APK nuevo se instala encima del anterior sin perder tu partida.

## Los archivos del juego

Los tres archivos salen de tu dump de consola o de tu fake PKG. La forma más fácil desde la PC es el instalador de Linux o
Windows del proyecto original, que los extrae del PKG y los deja junto al port de PC. Luego cópialos al teléfono en:

    /storage/emulated/0/PT/CUSA01127/

Por USB con adb, desde la carpeta donde están los tres archivos:

    adb shell mkdir -p /storage/emulated/0/PT/CUSA01127
    adb push chunk1.psarc texture.qar pathid_list_ps4.bin /storage/emulated/0/PT/CUSA01127/

Para leer esa carpeta la app pide el permiso **Acceso a todos los archivos**: si no lo tiene, al abrirla aparece un aviso
con el botón *Dar permiso*, que abre la pantalla de ajustes; actívalo, regresa y toca *Reintentar*.

Si prefieres no dar ese permiso, la otra carpeta que funciona sin permisos es la de la propia app:

    /storage/emulated/0/Android/data/com.rasteck7.ptdroid/files/CUSA01127/

## Qué cambia respecto a PC

- **Texturas BC**: la mayoría de los drivers de Android no exponen los formatos BC que usa el juego. En ese caso se
  decodifican en la CPU al cargarse (`src/engine/render/bc_decode.cpp`, con bcdec), lo que usa más memoria de video que en
  PC. El log dice si el GPU las soporta (`vulkan: BC textures ...`).
- **Pantalla**: siempre horizontal; el swapchain usa el tamaño de la ventana y el compositor de Android se encarga de la
  rotación (`src/engine/render/vk_context.cpp`).
- **Voz**: whisper.cpp va enlazado dentro de `libmain.so` con un solo backend ARM64. La primera vez que el juego abre el
  micrófono, Android pide el permiso.
- **Botón atrás**: abre el menú de pausa.
- **Sin**: upscalers (FSR/DLSS/XeSS), texturas mejoradas (Real-ESRGAN), VR, ray tracing (los drivers móviles casi nunca
  tienen ray queries), buscador de actualizaciones ni capturas del museo en segundo plano.
- **Recursos**: los shaders, fuentes y modelos de voz viajan dentro del APK (`cmake/android_assets.cmake`) y se copian al
  almacenamiento interno la primera vez que abres cada versión nueva (`src/engine/platform/android_support.cpp`); esa
  primera apertura tarda unos segundos más.

## Logs

- `pt.log` queda en `/storage/emulated/0/Android/data/com.rasteck7.ptdroid/files/pt.log` (accesible por USB).
- En vivo: `adb logcat -s pt SDL`

## Compilar a mano

Con Android SDK, NDK 28.2.13676358, JDK 17, Gradle 8.12 y CMake 3.31.6 (con Ninja en su `bin/`):

    cd android
    printf 'sdk.dir=%s\ncmake.dir=%s\n' "$ANDROID_HOME" /ruta/a/cmake-3.31.6 > local.properties
    gradle assembleRelease

El APK queda en `android/app/build/outputs/apk/release/app-release.apk`.

## Pendiente

- Controles táctiles (stick virtual y botón de interacción).
- Recrear la superficie de Vulkan al volver de segundo plano: por ahora, minimizar la app puede cerrarla.
- Escala de resolución interna para teléfonos con pantalla QHD+.
- Transcodificar BC a ASTC/ETC2 en vez de RGBA para ahorrar memoria.
