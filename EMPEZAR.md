# Pulse Music — edición personal

Proyecto Android para estudiar, personalizar y compilar tu propia aplicación musical. Se basa en Metrolist 13.7.0, de la familia InnerTune, y conserva su licencia GPL-3.0 y sus créditos.

**Estado de esta entrega:** código fuente y proceso de compilación preparados. No incluye un APK compilado ni una prueba de reproducción en un teléfono. El intento de compilación en este entorno se bloqueó al descargar Gradle por un error de red. No se ha comprobado el inicio de sesión, Firestore ni la reproducción actual contra servicios reales. Consulta `docs/VERIFICACION.md`.

## Lo que contiene

- Base musical nativa: búsqueda de canciones, artistas, álbumes y listas; novedades que entrega YouTube Music; biblioteca, favoritos e historial.
- Reproductor en segundo plano, controles multimedia, cola, letras, temas, audio local y funciones de caché de la base comunitaria.
- Identidad Pulse Music: icono propio, acento menta, modo oscuro y preferencias iniciales de contenido para Perú/español. Puedes cambiarlas.
- Ajustes → **Mi Pulse: cuenta, copias y actualizaciones**.
- Cuenta de Google de la base: sus funciones existentes de sincronización con YouTube Music.
- Cuenta Pulse opcional con correo/contraseña: creación, acceso, recuperación de contraseña, cierre de sesión y copias privadas por dispositivo en tu proyecto Firebase.
- Copia manual y programación diaria opcional con red no medida y batería suficiente. Android determina la hora efectiva.
- Copias de Cuenta Pulse consistentes mediante transacción, comprimidas y verificadas con SHA-256. No incluyen archivos de audio, preferencias de acceso, contraseñas, sesiones de Google ni claves de IA.
- Flujo para incorporar una nueva versión estable comunitaria, volver a aplicar Pulse, compilar, probar y firmar con la misma clave.
- Sin Jamendo. No se han añadido SDK de anuncios ni facturación. No necesitas Apps Script para reproducir música.

**Sincronización y copias tienen funciones distintas.** La cuenta de Google utiliza las integraciones de la base, que pueden fallar o cambiar. La cuenta Pulse guarda una copia completa por dispositivo; no mezcla en tiempo real los cambios de dos teléfonos. Restaurar una copia reemplaza la biblioteca local. No se implementó una sincronización universal entre proveedores.

## Ruta desde el navegador, sin instalar programas en tu PC

Necesitas una cuenta de GitHub y un teléfono con Android 8 o posterior. GitHub Codespaces/Actions y Firebase ofrecen uso gratuito con cuotas: conserva sus planes gratuitos y no actives facturación. Si se agota una cuota, habrá que esperar; no es uso ilimitado.

### 1. Crear tu repositorio privado

1. En GitHub, crea un repositorio llamado `pulse-music`, con visibilidad **Private** y un README inicial.
2. Abre **Code → Codespaces → Create codespace on main**.
3. Arrastra `Pulse-Music-source.zip` al explorador de archivos de Codespaces, en la raíz del repositorio.
4. En la terminal de Codespaces ejecuta:

```bash
python -m zipfile -e Pulse-Music-source.zip .
python tools/apply_pulse.py
python -m unittest discover -s tests -v
```

5. Guarda el código en tu repositorio. No incluyas una carpeta `.private` ni claves de firma:

```bash
git add .github .gitignore EMPEZAR.md LICENSE docs firebase firebase.json tools overlay tests android upstream.lock.json
git commit -m "feat: add Pulse Music personal edition"
git push
```

No se publica nada en Play Store. Tus archivos y los APK de Actions permanecen sujetos a la privacidad y los permisos de tu repositorio.

### 2. Crear la firma permanente, una sola vez

Esta firma permite instalar las futuras versiones encima de la primera, conservando los datos. No crees otra para cada actualización.

1. En GitHub abre **Actions → Setup signing (una sola vez) → Run workflow**.
2. Cuando termine, abre la ejecución y descarga el artefacto `Pulse-firma-privada`. Expande los ZIP hasta ver estos archivos:
   - `pulse.jks`
   - `signing.json`
   - `PULSE_KEYSTORE_BASE64.txt`
   - `PULSE_KEY_PASSWORD.txt`
3. Guarda esos archivos en un lugar privado. El artefacto de GitHub está configurado para caducar al día.
4. En **Settings → Secrets and variables → Actions → New repository secret**, crea:

| Nombre | Valor |
|---|---|
| `PULSE_KEYSTORE` | Contenido completo de `PULSE_KEYSTORE_BASE64.txt` |
| `PULSE_KEY_PASSWORD` | Contenido de `PULSE_KEY_PASSWORD.txt` |

No pegues esos valores en el código, en un comentario ni en un issue. No vuelvas a ejecutar Setup signing después de haber instalado la aplicación.

### 3. Compilar tu APK

1. Abre **Actions → Build Pulse → Run workflow**.
2. Elige `packaged` para la primera compilación.
3. Espera a que todas las etapas terminen correctamente. Se instalan las herramientas, se compila, se ejecutan las pruebas de Pulse y se firma el APK.
4. Descarga el artefacto `Pulse-Music`. Contiene:
   - `Pulse-Music.apk`
   - `build-info.json`, con la versión base y el SHA-256 del APK.
   - `Pulse-Music-source.zip`, con el código exacto usado en esa compilación.
5. Abre el APK en tu teléfono. Si Android lo solicita, autoriza a ese navegador o gestor de archivos a instalar aplicaciones.

**Si Actions falla, no habrá un APK validado.** Abre la etapa roja y conserva el error. No instales un APK distinto de un sitio aleatorio como sustituto de tu edición personal.

### 4. Primera prueba en el teléfono

1. Abre Pulse Music y busca una canción.
2. Reprodúcela, cambia de aplicación y apaga la pantalla.
3. Comprueba pausa, siguiente y anterior desde los controles multimedia. Concede el permiso de notificaciones si Android lo solicita.
4. Crea una lista y marca un favorito. Cierra y vuelve a abrir la aplicación para comprobar que se conservan.
5. Prueba una canción reciente y una canción antigua. Añade también un enlace compartido desde YouTube o YouTube Music.
6. Prueba Bluetooth y un corte breve de Internet. Conservar datos locales no significa poder reproducir una canción remota sin conexión.

Si la fuente no reproduce, comprueba primero la actualización comunitaria y el error concreto. Una compilación correcta no garantiza que YouTube acepte sus solicitudes.

## Cuenta Google y biblioteca musical

En **Ajustes → Mi Pulse → Conectar Google**, usa el flujo de acceso de la base. También puedes utilizar la aplicación sin esa cuenta.

La cuenta permite las funciones de sincronización que implementa Metrolist. Las listas creadas solo como locales y las estadísticas locales no deben tratarse como si fueran una copia completa en Google. Para conservar todo el contenido de la base de datos, utiliza una copia local o Cuenta Pulse.

## Cuenta Pulse por correo: configuración gratuita inicial

Esta parte es opcional y se realiza una vez. No interviene en la reproducción.

1. En [Firebase Console](https://console.firebase.google.com/), crea un proyecto en el plan **Spark**. No actives Analytics si no lo necesitas, ni Cloud Storage, Cloud Functions o un plan con facturación.
2. En **Authentication → Sign-in method**, activa **Email/Password**.
3. En **Firestore Database**, crea la base de datos `(default)` en modo de producción. Elige la región que prefieras entre las disponibles.
4. En la pestaña **Rules**, reemplaza las reglas por el contenido de `firebase/firestore.rules` y publica esas reglas.
5. En **Indexes → Exemptions**, añade una exención para el grupo de colecciones `chunks`, campo `data`, desactivando sus índices. El archivo `firebase/firestore.indexes.json` contiene la misma configuración para quien use Firebase CLI.
6. En **Project settings → General**, registra una aplicación Web llamada Pulse Personal, sin activar Hosting. Copia los valores `projectId` y `apiKey` de su configuración. Son identificadores de cliente; la protección de la biblioteca depende de las reglas y del usuario autenticado.
7. En Pulse abre **Mi Pulse → Configuración inicial de Firebase**. Pega el ID del proyecto y la clave API web, y guarda.
8. Escribe tu correo y una contraseña y pulsa **Crear cuenta**. Para los siguientes accesos usa **Entrar**.
9. Pulsa **Guardar copia ahora**. Después pulsa **Ver copias de mis dispositivos** y comprueba que aparece tu teléfono.
10. Si quieres, activa **Copia diaria con Wi-Fi**. Es una programación aproximada del sistema, no una promesa de copia a una hora exacta.

Las reglas aíslan los documentos por el UID de Firebase. Una cuenta diferente no tiene permiso para leer tus copias. No se ha desplegado ni probado este backend con una cuenta real durante la preparación del proyecto.

### Restaurar

1. Guarda una copia local antes de hacer pruebas de restauración.
2. Usa la misma versión de Pulse que creó la copia si ha cambiado el esquema de la biblioteca.
3. Desactiva temporalmente la sincronización de Google para evitar que vuelva a aplicar cambios remotos mientras restauras.
4. Entra en Cuenta Pulse con el mismo correo y el mismo proyecto de Firebase.
5. Pulsa **Ver copias de mis dispositivos**, elige una y confirma **Restaurar**.
6. Se pausará la música y se guardará una copia local de recuperación antes de sustituir los registros.
7. Cierra y abre la app. Comprueba la biblioteca antes de volver a activar la sincronización con Google.

Si necesitas volver atrás, utiliza **Mi Pulse → Deshacer última restauración**. Esta opción corresponde a las restauraciones de Cuenta Pulse, no al importador original.

La exportación local heredada de Metrolist también puede incluir ajustes y datos de sesión: conserva esos ZIP de forma privada. Las copias de Cuenta Pulse usan un formato separado que excluye los archivos de acceso.

Las copias no contienen audio. Los archivos locales de otro teléfono necesitan volver a estar disponibles. Una copia de una versión de esquema diferente se rechaza antes de borrar registros; no se ejecuta una conversión improvisada.

## Actualizar sin editar código normalmente

1. Exporta una copia local desde **Ajustes → Importar o exportar biblioteca**.
2. En **Mi Pulse → Actualizaciones**, guarda tu repositorio en formato `usuario/pulse-music`.
3. Pulsa **Comprobar nueva base**.
4. Si hay una nueva versión, pulsa **Preparar actualización**. Se abrirá GitHub.
5. Ejecuta **Build Pulse**, eligiendo `latest-stable`.
6. El flujo descarga una publicación estable, fija su commit, comprueba que las integraciones siguen siendo compatibles, aplica Pulse y compila con tu firma original.
7. Si termina correctamente, instala su APK **encima** de la versión anterior. No desinstales primero.
8. Conserva también el ZIP del código exacto que produjo ese APK.

El proceso no instala una versión automáticamente ni sustituye Pulse por el APK oficial de Metrolist. Si cambia una integración o las tablas de la base, el flujo se detiene para que se revise. Esto reduce las modificaciones manuales, pero no elimina todo mantenimiento ni demuestra que una nueva fuente reproduzca correctamente.

## IA opcional

La base incluye ajustes de traducción de letras con varios proveedores, entre ellos Gemini. Puedes dejar la IA desactivada. En esta entrega no se ha implementado un DJ conversacional, un recomendador propio ni una IA que repare integraciones automáticamente. Verifica el nivel gratuito del modelo que elijas antes de usar una clave; no actives facturación si quieres mantener coste cero.

## Alternativa con Android Studio

Instala JDK 21 y el SDK que solicita `android/app/build.gradle.kts` (37 en la base incluida). Configura `JAVA_HOME` y `ANDROID_HOME`. Desde la raíz del paquete:

```bash
python tools/setup_signing.py
python tools/build.py
```

Para una compilación de depuración, usa `python tools/build.py --variant debug`. Mantén siempre `.private/` y el identificador `com.brandon.pulse.music`. La primera descarga de dependencias necesita Internet.

## Archivos importantes

| Archivo o carpeta | Función |
|---|---|
| `android/` | Código completo de la base con Pulse aplicado; conserva las instrucciones y licencias originales. |
| `overlay/` | Funciones y recursos propios de Pulse. |
| `tools/apply_pulse.py` | Integración repetible y comprobada sobre la base. |
| `tools/prepare_upstream.py` | Preparación de una nueva versión estable. |
| `tools/build.py` | Compilación, pruebas, firma y datos del APK. |
| `upstream.lock.json` | Repositorio y commit exactos de la base. |
| `firebase/` | Reglas y configuración de índices para copias privadas. |
| `docs/VERIFICACION.md` | Qué se comprobó y qué falta probar. |
| `docs/ALCANCE.md` | Decisiones técnicas y límites de esta edición. |

Consulta las fuentes originales: [Metrolist](https://github.com/MetrolistGroup/Metrolist), [Android Media3](https://developer.android.com/media/media3/session/background-playback), [Firebase Auth REST](https://firebase.google.com/docs/reference/rest/auth), [Firestore REST](https://firebase.google.com/docs/firestore/reference/rest), [Firebase Spark](https://firebase.google.com/pricing).
