# Android (PT Droid)

Port del port nativo de P.T. a Android ARM64. Es el mismo juego en C++/Vulkan del repositorio, compilado como
`libmain.so` y envuelto con la actividad Java de SDL3 en un APK. Igual que en PC, **no trae ningún archivo del juego**: lee
los archivos de tu propia copia.

## Requisitos

- Teléfono Android 11 o más nuevo, ARM64, con **Vulkan 1.3** (por ejemplo Snapdragon 8 Gen 2/3/Elite).
- Tus tres archivos de P.T. (versión US, `CUSA01127`): `chunk1.psarc`, `texture.qar` y `pathid_list_ps4.bin`.
- Un control (Bluetooth o USB) o los controles táctiles en pantalla.

## Conseguir el APK

Cada push a la rama `android-port` corre el workflow `.github/workflows/android.yml`, que compila el APK y lo publica:

- en **Releases** como pre-release `android-v0.1.N` (se descarga directo desde el teléfono), y
- como artefacto del workflow en la pestaña **Actions**.

Todas las compilaciones se firman con la misma llave de desarrollo (`android/app/pt-droid-dev.keystore`), así que cada
APK nuevo se instala encima del anterior sin perder tu partida.

## Los archivos del juego

La primera vez que abres la app aparece la pantalla de configuración (`SetupActivity.java`), con el selector de archivos de
Android:

- **Elegir el PKG del juego**: si es un **fake PKG** (fPKG), la app lo abre y saca `chunk1.psarc`, `texture.qar` y
  `pathid_list_ps4.bin`, igual que el instalador de PC (`android/.../pkg/PkgExtractor.java`, basado en LibOrbisPkg). Si es
  el PKG **original de la tienda**, viene encriptado para la consola que tiene la licencia y no se puede abrir (el
  instalador de PC tampoco puede); la pantalla lo dice y pide los tres archivos ya extraídos.
- **Elegir la carpeta (con el PKG o los archivos)**: busca los tres archivos en la carpeta elegida y hasta tres niveles de
  subcarpetas; si no están pero hay un `.pkg`, lo extrae.
- **Elegir los 3 archivos**: el selector con selección múltiple (también acepta el `.pkg`).

Los archivos quedan, con barra de progreso, en la carpeta de la app
(`/storage/emulated/0/Android/data/com.rasteck7.ptdroid/files/CUSA01127/`), que no necesita permisos especiales; luego
arranca el juego, y las siguientes veces entra directo. Después puedes borrar el PKG o los originales. Si borras los datos
de la app, la pantalla vuelve a aparecer. El PKG tiene que estar en el teléfono (no en una carpeta de la nube).

También funcionan, sin pasar por la pantalla:

- copiar los archivos directo a esa carpeta de la app por USB o adb:

      adb shell mkdir -p /storage/emulated/0/Android/data/com.rasteck7.ptdroid/files/CUSA01127
      adb push chunk1.psarc texture.qar pathid_list_ps4.bin /storage/emulated/0/Android/data/com.rasteck7.ptdroid/files/CUSA01127/

- dejarlos en `/storage/emulated/0/PT/CUSA01127/` y dar a la app **Acceso a todos los archivos** (Ajustes → Apps → Acceso
  especial → Acceso a todos los archivos; la pantalla de configuración tiene un botón que abre esa opción).

## Controles táctiles

Mientras no hay un mando conectado, el juego dibuja controles encima de la imagen
(`src/engine/platform/touch_controls.cpp`), que funcionan como un mando de PS4 más:

- **Mitad izquierda**: stick de movimiento flotante (aparece donde pones el dedo; si lo arrastras más allá del borde, la
  base lo sigue). Arriba de él, el **D-pad** para los menús.
- **Mitad derecha**: stick para mirar, también flotante.
- **✕ ○ □ △**, **ZOOM** (R3), **L3**, **L1**, **R1**, **PAD** (botón del touchpad), **OPTIONS** (pausa) y **PC** (ajustes
  de PC).

Al conectar un mando se ocultan; al desconectarlo vuelven. Los avisos de botones del juego usan los símbolos de PlayStation.

## Qué cambia respecto a PC

- **Texturas BC**: la mayoría de los drivers de Android no exponen los formatos BC que usa el juego. En ese caso se
  decodifican en la CPU al cargarse (`src/engine/render/bc_decode.cpp`, con bcdec), lo que usa más memoria de video que en
  PC. El log dice si el GPU las soporta (`vulkan: BC textures ...`).
- **Exposición automática**: el brillo de la escena se mide con `shaders/luminance_serial.comp` (y el color del reflejo
  con `reflect_colour_serial.comp`), que no usan memoria compartida: en el Adreno 830 la versión de PC devolvía basura y
  la imagen se iba a negro. Una medición con valores imposibles se descarta (el log lo dice). `PT_REDUCE=shared` o
  `PT_REDUCE=serial` fuerza una u otra en cualquier plataforma.
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

## Driver de GPU propio

Solo en GPU **Adreno** (Qualcomm) puedes usar un driver Vulkan distinto al del teléfono, por ejemplo un build de Turnip
(Mesa), como hacen Winlator y los emuladores. En cualquier otra GPU la opción no aparece.

- En el juego: ajustes de PC → **Gráficos** → **Driver de GPU**. La fila del driver abre el selector de Android para
  elegir el .zip (el formato de siempre: `meta.json` y el `.so`, a veces con más `.so`); **Restaurar driver
  predeterminado** vuelve al del teléfono. Los dos cambios se aplican **al reiniciar el juego** (ciérralo del todo).
- Sin entrar al juego: mantén pulsado el icono de la app y elige **GPU driver** (o la pantalla que sale tras un crash).

El .zip se extrae en el almacenamiento privado de la app (`files/gpu_driver/`) y el juego lo carga al arrancar con
libadrenotools (`cmake/Adrenotools.cmake`, `src/engine/platform/android_support.cpp`). Cambiarlo o restaurarlo solo toca
esa carpeta.

- Si el driver no crea la instancia de Vulkan, el juego usa el del teléfono en esa misma sesión; si el juego se cierra
  antes de que arranque el renderizador, la siguiente vez descarta el driver solo (`.trial` en esa carpeta).
- El log dice cuál se usa: `android: custom GPU driver ...` y `vulkan: using ...`.
- Android 11 o más nuevo; el juego necesita Vulkan 1.3, así que usa un build reciente.

## Logs y crashes

- `pt.log` queda en `/storage/emulated/0/Android/data/com.rasteck7.ptdroid/files/pt.log` (accesible por USB).
- En vivo: `adb logcat -s pt SDL`
- Si el juego se cierra de golpe (crash, falta de memoria o se congela), al abrir la app otra vez la pantalla de inicio
  dice el motivo (el registro de Android, `ApplicationExitInfo`), muestra las últimas líneas del log y tiene
  **Compartir log**: un archivo con el dispositivo, el motivo, lo legible del tombstone y `pt.log`.
- Un crash nativo queda escrito en `pt.log` por el propio juego (`InstallCrashHandler` en
  `src/engine/platform/android_support.cpp`): la señal, la dirección, `pc`/`lr` y el backtrace con offsets dentro de
  `libmain.so`. Cada release trae `libmain-<versión>-symbols.so.xz`, la librería sin strip, para traducirlos:

      xz -d libmain-0.1.N-symbols.so.xz
      llvm-addr2line -Cfie libmain-0.1.N-symbols.so 0x<offset>

## Compilar a mano

Con Android SDK, NDK 28.2.13676358, JDK 17, Gradle 8.12 y CMake 3.31.6 (con Ninja en su `bin/`):

    cd android
    printf 'sdk.dir=%s\ncmake.dir=%s\n' "$ANDROID_HOME" /ruta/a/cmake-3.31.6 > local.properties
    gradle assembleRelease

El APK queda en `android/app/build/outputs/apk/release/app-release.apk`.

## Pendiente

- Opciones de los controles táctiles (tamaño, opacidad, mirar arrastrando el dedo).
- Recrear la superficie de Vulkan al volver de segundo plano: por ahora, minimizar la app puede cerrarla.
- Escala de resolución interna para teléfonos con pantalla QHD+.
- Transcodificar BC a ASTC/ETC2 en vez de RGBA para ahorrar memoria.
