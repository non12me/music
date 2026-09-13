# Alcance de Pulse Music

## Decisión de base

La edición usa Metrolist 13.7.0, commit `7c5ba3ad22cb562d337897f82163a2b7fce665f3`, publicado en el repositorio comunitario consultado. Es una continuación de la familia InnerTune con integraciones y funciones más recientes que el repositorio original examinado. El proyecto declara modo de mantenimiento: correcciones y mejoras menores, sin garantía de soporte futuro.

La personalización mantiene sus modelos, esquema Room, proveedores y código de reproducción. Los archivos de Pulse se agrupan bajo `com.metrolist.music.pulse`. El identificador instalable es distinto: `com.brandon.pulse.music`. La versión de la base no se altera.

## Qué se implementó específicamente

1. Tema inicial oscuro con acento menta, identidad visual vectorial y contenido español/Perú configurable.
2. Panel Mi Pulse con accesos a audio, aspecto, cuenta musical, copias e IA opcional.
3. Autenticación independiente con correo usando Firebase Auth REST. El token de renovación se cifra con una clave de Android Keystore. No se guarda la contraseña.
4. Copia de las 18 tablas de la biblioteca dentro de una transacción. Se serializan los datos y se comprimen; no se copian los archivos de preferencias de acceso.
5. Copias separadas por UID y dispositivo en Firestore. Fragmentos inmutables de hasta 400.000 bytes; manifiesto publicado al final y verificación SHA-256 al recuperar.
6. Copia diaria opcional mediante JobScheduler con red no medida y batería suficiente.
7. Restauración explícita con confirmación, comprobación de esquema, validación de columnas, transacción y comprobación de relaciones. Conserva una recuperación previa en `files/pulse_before_restore.json.gz`.
8. Actualización asistida desde una publicación estable de la base y una clave de firma permanente. No descarga ni instala en segundo plano un APK de otra aplicación.

## Funciones heredadas

El proyecto conserva búsqueda, novedades de la fuente, biblioteca, reproducción en segundo plano, controles Android, caché, audio local, listas, letras, temas y las integraciones de la base. Su presencia se comprobó en el código y documentación; no se midió su rendimiento ni disponibilidad contra YouTube en un teléfono.

La reproducción remota usa la integración no oficial de la base y sus dependencias/servicios auxiliares. No se desarrolló ni probó un extractor nuevo en esta entrega. No se implementó un pool propio de instancias públicas ni un cambio automático entre catálogos musicales independientes. Mantener varias rutas hacia YouTube no evita un fallo compartido de ese origen.

## Novedades y catálogo

La app consulta el catálogo y las novedades que entrega la fuente. Un lanzamiento puede aparecer cuando esté publicado e indexado allí. No se promete incluir toda la música mundial, detectar cada lanzamiento en 24 horas ni reproducir un contenido retirado, privado o inaccesible. Pegar un enlace añade una referencia; no crea disponibilidad del audio.

No se incluyó el radar programado de Apps Script del README de referencia. La base ya consulta novedades y evita exigir un segundo backend para la primera instalación. No hay Jamendo.

## Sincronización frente a respaldo

- La integración de la cuenta Google de Metrolist controla la sincronización musical que soporta esa base. No convierte todas las estadísticas locales en datos sincronizados universalmente.
- La cuenta Pulse copia la biblioteca completa por dispositivo. No es un protocolo de fusión automática: dos teléfonos guardan dos copias distintas.
- Restaurar reemplaza la biblioteca local. Requiere la misma versión de esquema. Las migraciones de una actualización instalada corresponden al código comunitario de Room; no se prometen restauraciones arbitrarias entre esquemas.
- Una actualización con el mismo identificador y firma conserva normalmente el almacenamiento de la app. Desinstalar, borrar datos o perder la firma son situaciones distintas.

## Coste y dependencia

El código no incorpora suscripción, anuncios propios ni servicios de pago obligatorios. Se usan servicios gratuitos con cuotas. El coste cero depende de mantener esos planes sin facturación y aceptar que una operación puede quedar pendiente al agotar una cuota.

Las copias admiten hasta 32 MiB comprimidos y 128 MiB de JSON sin comprimir. No almacenan audio ni intentan servir música desde Firestore. Se conserva una copia publicada por dispositivo; fragmentos de una subida interrumpida podrían quedar sin referencia y requerir limpieza si se acumulan. No se ha implementado eliminación completa de la cuenta Firebase desde la app; se administra desde la consola del proyecto.

La clave API web de Firebase identifica el proyecto; la autorización depende de Firebase Auth y las reglas. Estas reglas son para uso personal autenticado, no un servicio público protegido frente a altas masivas. No hay infraestructura propia que mantener, pero sí dependencias externas.

## Mantenimiento esperado

El objetivo es que una actualización habitual requiera ejecutar un flujo y reinstalar su APK, sin editar código. El flujo solo comprueba compatibilidad de integración y compilación/pruebas automatizadas. Si el código comunitario cambia de forma incompatible o el origen bloquea la reproducción, una persona tendrá que revisar el problema. No hay una IA autoreparadora ni un plazo garantizado para el siguiente fallo.

## Licencia y uso personal

Se conservan GPL-3.0, los avisos de autoría y la documentación original dentro de `android/`. La licencia del código no concede derechos sobre catálogos musicales. El funcionamiento técnico de un cliente no oficial y las condiciones de los proveedores son asuntos distintos. Fuente: [condiciones de YouTube](https://www.youtube.com/static?template=terms).

## Revisión auxiliar bloqueada

Las instrucciones del repositorio pedían descargar una skill auxiliar externa llamada `ponytail`. La revisión automática rechazó esa descarga por considerar que introducía instrucciones no confiables sin autorización suficiente. No se reintentó por otra vía y no se ejecutó esa skill. Se continuó con el trabajo independiente del proyecto y las comprobaciones descritas en VERIFICACION.md.
