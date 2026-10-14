# map3d-edit

Mini motor gráfico para Android (Kotlin + OpenGL ES 3.0) para crear y esculpir terrenos
(heightmaps) y usarlos en juegos propios.

## Estado

- [x] Etapa 1: terreno base con luz simple y cámara orbital (1 dedo orbita, pinch hace zoom)
- [x] Etapa 2: heightmap desde PNG (selector de archivos) y ruido fBm con semilla
- [x] Etapa 3: caminar sobre el terreno (joystick izquierdo mueve, derecho mira)
- [x] Etapa 4: esculpir (subir, bajar, suavizar, aplanar, rugoso, terraza) con radio y fuerza ajustables,
      cursor del pincel, deshacer/rehacer (40 pasos), terreno 256x256 en 40x40 u
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

## Personaje-cámara y arma editora

La cámara de **Caminar** es un personaje de cajas (cuerpo, cabeza, visor) con una baliza magenta alta
y una flecha en la punta que indica hacia dónde mira. En la vista orbital siempre se ve dónde quedó.

- **Caminar:** joystick izquierdo mueve, derecho mira. Aparece una mira en el centro y el botón
  **ESCULPIR** (arma editora): mantenlo presionado para esculpir donde apunta la mira. Arrastrar el
  dedo sobre ese botón también gira la vista, para disparar y apuntar con el mismo pulgar.
- Las herramientas, radio, fuerza y deshacer funcionan igual que en la vista orbital
  (el botón **Panel** oculta o muestra ese panel).
- **Cámara aquí:** lleva el personaje al cursor del pincel y lo orienta como la vista actual.
