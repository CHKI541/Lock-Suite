# Auditoría general — versión 0.5.0

## Cambios verificados

- La purga de emergencia conserva la contraseña maestra de recuperación intencional y el límite de intentos. Al estar embebida en la APK, debe tratarse como un mecanismo break-glass: no ofrece protección contra alguien que pueda extraer y analizar la APK.
- Los comandos FCM de la versión 0.5.0 exigen una firma HMAC con una credencial aleatoria por dispositivo, guardada localmente en el almacenamiento cifrado. También se rechazan comandos repetidos y comandos sin identificador, firma o fecha válida.
- Las claves de firma de la aplicación y las contraseñas de firma de APK ya no están codificadas en el proyecto.
- El procesamiento DNS de la VPN usa una cola y un número de hilos acotados para reducir picos de CPU y memoria.

## Límites que requieren diseño y pruebas de campo

- La VPN actual solo filtra DNS convencional. No es una garantía de bloqueo frente a DNS cifrado (DoH/DoT), QUIC o conexiones directas por IP. Para afirmar un filtro no evadible hace falta sustituirla por un túnel completo que reenvíe TCP/UDP o integrar un proveedor de filtrado de red, más pruebas por versión de Android y OEM.
- Las reglas actuales de Realtime Database permiten escritura a cualquier usuario autenticado, incluida una sesión anónima. No deben desplegarse como modelo de seguridad final: hay que migrar el registro/sincronización a identidades de dispositivo con privilegios mínimos o a una API autenticada por el servidor.
- Las capacidades de Device Owner y las restricciones disponibles varían por Android, fabricante, ROM y método de aprovisionamiento. Deben validarse en una matriz de equipos físicos antes de prometer compatibilidad universal.

## Validación realizada

- `assembleRelease` compiló la APK firmada 0.5.0 (versionCode 50).
- `node --check admin-backend/functions/index.js` pasó sin errores de sintaxis.
