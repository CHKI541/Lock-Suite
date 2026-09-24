# Pruebas de las reglas de Realtime Database

`test_rules.mjs` prueba `admin-backend/database.rules.json` contra el **emulador real**
de Realtime Database (no es una simulación a mano). Son 44 comprobaciones:

- lo que el celular legítimo TIENE que poder hacer (leer su `appPolicy`, su nombre,
  sus pedidos, su buzón `pendingCommands`, escribir el latido y los acks, etc.);
- el alta de un equipo nuevo, incluso si escribe `deviceSecrets` antes que `devices`;
- los ataques que TIENEN que fallar (secuestrar un equipo ajeno, pisar su
  `commandSecret`, leer la lista de equipos, inyectar comandos en el buzón…);
- la re-vinculación de un equipo reinstalado (`deviceClaims` → Aprobar en el panel);
- lo que hace el panel (admin).

## Cómo correrlas

Hace falta Node 18+ y Java 11+ (el emulador es un `.jar`).

```bash
cd tools/rules_tests
npm install
npm test
```

Tiene que terminar con `TODAS VERDES (44)` y código 0. Cualquier `✗ ROJO` es un
cambio de reglas que rompe algo: no se despliega hasta entender por qué.

`emulator_start.rules.json` solo sirve para que el emulador arranque (niega todo); la
prueba carga las reglas de verdad sola, desde `admin-backend/database.rules.json`.

Para probar OTRO archivo de reglas (por ejemplo, para comparar con las viejas):

```bash
RULES=/ruta/a/otras.rules.json npm test
```

Control negativo del 22/9/2026: con las reglas anteriores (commit `fd76ccc`) salen
**17 en rojo**, entre ellas que cualquier sesión anónima podía secuestrar un equipo
ajeno y que el celular no podía leer su propia `appPolicy`.
