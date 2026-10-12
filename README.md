# map3d-edit

Mini motor gráfico para Android (Kotlin + OpenGL ES 3.0) para crear y esculpir terrenos
(heightmaps) y usarlos en juegos propios.

## Estado

- [x] Etapa 1: terreno base con luz simple y cámara orbital (1 dedo orbita, pinch hace zoom)
- [ ] Etapa 2: heightmap desde PNG / ruido
- [ ] Etapa 3: caminar sobre el terreno (joysticks)
- [ ] Etapa 4: esculpir (subir, bajar, suavizar, aplanar)
- [ ] Etapa 5: guardar / exportar (PNG 16 bits, .raw, .obj)

## APK

Cada push a `main` compila con GitHub Actions. El APK debug queda en
[Releases → latest](../../releases/tag/latest) y como artifact del workflow.
