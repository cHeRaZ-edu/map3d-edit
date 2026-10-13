# map3d-edit

Mini motor gráfico para Android (Kotlin + OpenGL ES 3.0) para crear y esculpir terrenos
(heightmaps) y usarlos en juegos propios.

## Estado

- [x] Etapa 1: terreno base con luz simple y cámara orbital (1 dedo orbita, pinch hace zoom)
- [x] Etapa 2: heightmap desde PNG (selector de archivos) y ruido fBm con semilla
- [x] Etapa 3: caminar sobre el terreno (joystick izquierdo mueve, derecho mira)
- [x] Etapa 4: esculpir (subir, bajar, suavizar, aplanar) con radio y fuerza ajustables
- [x] Etapa 5: guardar y exportar (autoguardado, proyecto .m3d, PNG 16 bits, .raw, .obj)

## APK

Cada push a `main` compila con GitHub Actions. El APK debug queda en
[Releases → latest](../../releases/tag/latest) y como artifact del workflow.

## Archivos

El botón **Archivo** guarda/abre el proyecto (`.m3d`, alturas exactas) y exporta:

- **PNG 16 bits** y **RAW 16 bits** (uint16 little-endian, sin cabecera): el heightmap se estira
  a 0..65535 con su mínimo y máximo reales. La app avisa qué altura en unidades de mundo
  corresponde a 0 y a 65535 para poder reconstruir la escala en otro motor.
- **OBJ** con vértices, normales y caras (Y arriba, unidades de mundo).

Además hay autoguardado: al salir de la app se guarda el terreno y se recupera al abrirla.
