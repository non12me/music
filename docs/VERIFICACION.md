# Verificación de la entrega

Fecha: 13/09/2026.

## Ejecutado satisfactoriamente

- Descarga y revisión del código de la base; commit fijado en `upstream.lock.json`.
- Seis pruebas Python de integración del paquete:
  - Reaplicación sin duplicar modificaciones, conservando esquema y versión.
  - Detención ante cambios incompatibles antes de escribir los archivos de integración.
  - Rechazo de rutas ZIP que salgan de la carpeta de destino.
  - Rechazo de enlaces simbólicos en archivos de nuevas versiones.
  - XML válido y recursos Pulse sin nombres duplicados ni referencias faltantes.
  - Cobertura de las 18 tablas de la biblioteca en el formato de copia.
- Análisis sintáctico de los cinco archivos Kotlin nuevos y los dos archivos de pruebas Kotlin con tree-sitter-kotlin.

Estas comprobaciones no equivalen a compilar el código Android, ejecutar una app ni medir su rendimiento.

## Intentado y bloqueado

Se intentó `./gradlew :app:assembleFossDebug` con JDK 21. El wrapper falló al descargar Gradle 9.7.0: `java.net.SocketException: Network is unreachable`. También se intentó preparar el SDK desde las herramientas oficiales, pero la descarga de manifiestos desde Java no se completó.

No se sustituyeron las dependencias por versiones distintas para ocultar el fallo. No hay APK compilado en esta entrega. Las pruebas Kotlin/Room incluidas aún no se han ejecutado.

## Pruebas incluidas para GitHub/Android Studio

- Comparación numérica de versiones, rechazo de versiones preliminares y validación de nombres de repositorio.
- Restauración de título y favorito en una base Room en memoria.
- Rechazo de una copia con esquema distinto sin borrar datos locales.
- Reversión de la transacción si hay un registro incompleto después de empezar la restauración.

El flujo `Build Pulse` ejecuta las pruebas de Pulse antes de entregar un artefacto. Un resultado verde allí seguirá sin demostrar reproducción real con YouTube.

## Prueba necesaria en un dispositivo

1. Instalar el APK compilado, abrir biblioteca y buscar tres canciones reales, incluida una reciente.
2. Escuchar con otra app abierta y pantalla apagada; comprobar controles y auriculares.
3. Interrumpir y recuperar Internet sin perder cola, favoritos ni historial.
4. Probar la cuenta musical de Google, su sincronización y el modo sin cuenta.
5. Configurar un proyecto Firebase de prueba, crear cuenta Pulse y guardar una copia.
6. Comprobar que otro UID no puede leer la copia; probar también el acceso sin autenticar.
7. Restaurar primero en una instalación de prueba con la misma versión; verificar canciones, favoritos, listas e historial.
8. Confirmar que una restauración inválida conserva la biblioteca anterior.
9. Instalar una segunda compilación con la misma firma encima de la anterior y comprobar persistencia.
10. Verificar que la IA desactivada y Firebase sin configurar no bloquean búsqueda ni reproducción.

No se ha creado un proyecto Firebase, usado una cuenta Google, enviado correos de recuperación, publicado un repositorio ni ejecutado un workflow externo en nombre del usuario.
