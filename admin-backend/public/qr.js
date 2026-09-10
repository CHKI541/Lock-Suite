/**
 * qr.js — generador de códigos QR, escrito para LockSuite (10/9/2026, B.61).
 * ═══════════════════════════════════════════════════════════════════════════
 *
 * POR QUÉ NO SE USA UNA BIBLIOTECA DE UN CDN, QUE SERÍA LO OBVIO:
 *
 * El panel se abre de dos formas. Una es un navegador común. La otra es
 * `:admin-app`, la cáscara nativa que existe para poder administrar desde un
 * celular kosher que no tiene navegador — y esa cáscara **filtra por lista blanca
 * de host de dónde puede bajar cualquier subrecurso** (`RESOURCE_ALLOWED_HOSTS` en
 * `admin-app/…/MainActivity.kt`). Hoy esa lista tiene Firebase, gstatic, la Cloud
 * Function y los íconos de Wikimedia. Un `<script src="https://cdn…">` nuevo
 * **no cargaría ahí**, y el síntoma sería "el QR anda en la compu y en el celular
 * no", sin ningún error visible. Agregar el CDN a la lista tampoco es gratis: cada
 * host nuevo en esa lista es superficie que hay que justificar.
 *
 * Este archivo se sirve del MISMO origen que el panel, así que pasa el filtro sin
 * tocar ninguna lista.
 *
 * ALCANCE, DICHO DE FRENTE: implementa lo que este proyecto necesita y nada más —
 * **modo byte, nivel de corrección L, versiones 1 a 40**. No hace modo numérico,
 * ni alfanumérico, ni kanji, ni ECI. El payload de aprovisionamiento es un JSON
 * ASCII de ~400-600 bytes, que en byte/L entra cómodo (queda en versión 17).
 *
 * ─────────────────────────────────────────────────────────────────────────────
 * VERIFICACIÓN: SE COMPARÓ MÓDULO POR MÓDULO CONTRA UNA IMPLEMENTACIÓN DE
 * REFERENCIA, Y LA PRIMERA VUELTA NO PASÓ
 * ─────────────────────────────────────────────────────────────────────────────
 *
 * La matriz que produce esto se comparó, módulo por módulo, contra la biblioteca
 * `qrcode` de Python sobre once cadenas —incluido el payload real de
 * aprovisionamiento— forzando modo byte en la referencia (si no, `python-qrcode`
 * elige modo alfanumérico solo y las versiones no coinciden por un motivo que no
 * tiene nada que ver con el código de acá).
 *
 * **La primera versión de este archivo NO pasó, y tenía DOS errores distintos.**
 * Los dos estaban en la parte estructural, ninguno en el álgebra, y los dos se
 * veían como un desastre en la parte de datos. Quedan escritos porque son el modo
 * de falla típico de esto:
 *
 *   1. **Las dos copias de la información de formato estaban traspuestas.** Los
 *      bits 0..5 iban a la fila 8 cuando van a la columna 8, y al revés. Como las
 *      dos copias ocupan una fila 8 y una columna 8 que se cruzan, el error es
 *      perfectamente simétrico y "parece bien" al mirarlo. Síntoma: 8 a 16 módulos
 *      distintos en las versiones bajas, **todos** en la fila 8 y la columna 8.
 *   2. **El temporizador se dibujaba ANTES que los patrones de alineación.** Un
 *      patrón de alineación se saltea cuando su centro ya está ocupado —así se
 *      omiten los tres que pisarían un finder—, pero con el temporizador ya puesto
 *      ese mismo "ya está ocupado" saltea también los que caen sobre la línea de
 *      tiempo, como (6, 22) en la versión 7. Síntoma: **las versiones 1 a 6 salían
 *      perfectas y de la 7 en adelante no coincidía un solo módulo**, porque
 *      faltaba un patrón entero y el mapa de reservados quedaba corrido.
 *
 * La moraleja, por si esto vuelve a no coincidir alguna vez: **mirá el orden en que
 * se dibujan los patrones fijos y el mapa de reservados antes que el álgebra.** Un
 * módulo de más o de menos en la lista de reservados corre el zigzag entero.
 *
 * Estado final: **84 casos idénticos módulo por módulo, cubriendo las 40 versiones**
 * (cada una llena y en su borde inferior), más hebreo, acentos y el payload real de
 * aprovisionamiento. Y **10 controles negativos, los 10 detectados** — entre ellos
 * los dos bugs de arriba reintroducidos a propósito.
 */
(function (global) {
    "use strict";

    // ── GF(256) con el polinomio primitivo 0x11d, el del estándar QR ──
    var EXP = new Uint8Array(512);
    var LOG = new Uint8Array(256);
    (function () {
        var x = 1;
        for (var i = 0; i < 255; i++) {
            EXP[i] = x;
            LOG[x] = i;
            x <<= 1;
            if (x & 0x100) x ^= 0x11d;
        }
        for (var j = 255; j < 512; j++) EXP[j] = EXP[j - 255];
    })();

    function gfMul(a, b) {
        if (a === 0 || b === 0) return 0;
        return EXP[LOG[a] + LOG[b]];
    }

    /** Polinomio generador de Reed-Solomon de grado `grado`. */
    function rsGenerator(grado) {
        var poly = [1];
        for (var i = 0; i < grado; i++) {
            var siguiente = new Array(poly.length + 1).fill(0);
            for (var j = 0; j < poly.length; j++) {
                siguiente[j] ^= poly[j];
                siguiente[j + 1] ^= gfMul(poly[j], EXP[i]);
            }
            poly = siguiente;
        }
        return poly;
    }

    /** Códigos de corrección de un bloque de datos. */
    function rsEncode(datos, ecLen) {
        var gen = rsGenerator(ecLen);
        var resto = new Array(ecLen).fill(0);
        for (var i = 0; i < datos.length; i++) {
            var factor = datos[i] ^ resto[0];
            resto.shift();
            resto.push(0);
            if (factor !== 0) {
                for (var j = 0; j < ecLen; j++) {
                    resto[j] ^= gfMul(gen[j + 1], factor);
                }
            }
        }
        return resto;
    }

    // ── Tablas del estándar, nivel L únicamente ──
    //
    // Por versión (1..40): [códigos EC por bloque, bloques del grupo 1,
    // datos por bloque del grupo 1, bloques del grupo 2, datos por bloque del grupo 2].
    var EC_L = [
        null,
        [7, 1, 19, 0, 0], [10, 1, 34, 0, 0], [15, 1, 55, 0, 0], [20, 1, 80, 0, 0],
        [26, 1, 108, 0, 0], [18, 2, 68, 0, 0], [20, 2, 78, 0, 0], [24, 2, 97, 0, 0],
        [30, 2, 116, 0, 0], [18, 2, 68, 2, 69], [20, 4, 81, 0, 0], [24, 2, 92, 2, 93],
        [26, 4, 107, 0, 0], [30, 3, 115, 1, 116], [22, 5, 87, 1, 88], [24, 5, 98, 1, 99],
        [28, 1, 107, 5, 108], [30, 5, 120, 1, 121], [28, 3, 113, 4, 114], [28, 3, 107, 5, 108],
        [28, 4, 116, 4, 117], [28, 2, 111, 7, 112], [30, 4, 121, 5, 122], [30, 6, 117, 4, 118],
        [26, 8, 106, 4, 107], [28, 10, 114, 2, 115], [30, 8, 122, 4, 123], [30, 3, 117, 10, 118],
        [30, 7, 116, 7, 117], [30, 5, 115, 10, 116], [30, 13, 115, 3, 116], [30, 17, 115, 0, 0],
        [30, 17, 115, 1, 116], [30, 13, 115, 6, 116], [30, 12, 121, 7, 122], [30, 6, 121, 14, 122],
        [30, 17, 122, 4, 123], [30, 4, 122, 18, 123], [30, 20, 117, 4, 118], [30, 19, 118, 6, 119]
    ];

    /** Coordenadas de los patrones de alineación por versión. */
    var ALINEACION = [
        null, [], [6, 18], [6, 22], [6, 26], [6, 30], [6, 34], [6, 22, 38], [6, 24, 42],
        [6, 26, 46], [6, 28, 50], [6, 30, 54], [6, 32, 58], [6, 34, 62], [6, 26, 46, 66],
        [6, 26, 48, 70], [6, 26, 50, 74], [6, 30, 54, 78], [6, 30, 56, 82], [6, 30, 58, 86],
        [6, 34, 62, 90], [6, 28, 50, 72, 94], [6, 26, 50, 74, 98], [6, 30, 54, 78, 102],
        [6, 28, 54, 80, 106], [6, 32, 58, 84, 110], [6, 30, 58, 86, 114], [6, 34, 62, 90, 118],
        [6, 26, 50, 74, 98, 122], [6, 30, 54, 78, 102, 126], [6, 26, 52, 78, 104, 130],
        [6, 30, 56, 82, 108, 134], [6, 34, 60, 86, 112, 138], [6, 30, 58, 86, 114, 142],
        [6, 34, 62, 90, 118, 146], [6, 30, 54, 78, 102, 126, 150], [6, 24, 50, 76, 102, 128, 154],
        [6, 28, 54, 80, 106, 132, 158], [6, 32, 58, 84, 110, 136, 162],
        [6, 26, 54, 82, 110, 138, 166], [6, 30, 58, 86, 114, 142, 170]
    ];

    function capacidadDatos(version) {
        var t = EC_L[version];
        return t[1] * t[2] + t[3] * t[4];
    }

    /** BCH(15,5) del formato, con la máscara 0x5412 del estándar. */
    function bitsDeFormato(mascara) {
        // Nivel L = 01 en los dos bits altos.
        var datos = (0x01 << 3) | mascara;
        var resto = datos << 10;
        for (var i = 14; i >= 10; i--) {
            if (resto & (1 << i)) resto ^= 0x537 << (i - 10);
        }
        return ((datos << 10) | resto) ^ 0x5412;
    }

    /** BCH(18,6) de la versión, para v7 en adelante. */
    function bitsDeVersion(version) {
        var resto = version << 12;
        for (var i = 17; i >= 12; i--) {
            if (resto & (1 << i)) resto ^= 0x1f25 << (i - 12);
        }
        return (version << 12) | resto;
    }

    function bytesDeTexto(texto) {
        // TextEncoder da UTF-8 y está en todo navegador que abra este panel.
        return Array.from(new TextEncoder().encode(texto));
    }

    function elegirVersion(cantidadBytes) {
        for (var v = 1; v <= 40; v++) {
            var contador = v <= 9 ? 8 : 16;
            var bits = 4 + contador + cantidadBytes * 8;
            if (bits <= capacidadDatos(v) * 8) return v;
        }
        return -1;
    }

    function armarCodewords(bytes, version) {
        var contador = version <= 9 ? 8 : 16;
        var bits = [];
        function poner(valor, largo) {
            for (var i = largo - 1; i >= 0; i--) bits.push((valor >>> i) & 1);
        }
        poner(0x4, 4);                 // modo byte
        poner(bytes.length, contador);
        for (var i = 0; i < bytes.length; i++) poner(bytes[i], 8);

        var totalBits = capacidadDatos(version) * 8;
        // Terminador: hasta cuatro ceros, o menos si no entran.
        var terminador = Math.min(4, totalBits - bits.length);
        for (var t = 0; t < terminador; t++) bits.push(0);
        while (bits.length % 8 !== 0) bits.push(0);

        var datos = [];
        for (var b = 0; b < bits.length; b += 8) {
            var byte = 0;
            for (var k = 0; k < 8; k++) byte = (byte << 1) | bits[b + k];
            datos.push(byte);
        }
        // Relleno alternando 0xEC / 0x11, como manda el estándar.
        var relleno = [0xec, 0x11];
        var r = 0;
        while (datos.length < capacidadDatos(version)) datos.push(relleno[r++ % 2]);
        return datos;
    }

    /** Reparte en bloques, calcula EC y entrelaza. */
    function entrelazar(datos, version) {
        var t = EC_L[version];
        var ecLen = t[0];
        var bloques = [];
        var pos = 0;
        var g;
        for (g = 0; g < t[1]; g++) { bloques.push(datos.slice(pos, pos + t[2])); pos += t[2]; }
        for (g = 0; g < t[3]; g++) { bloques.push(datos.slice(pos, pos + t[4])); pos += t[4]; }

        var ec = bloques.map(function (bl) { return rsEncode(bl, ecLen); });

        var salida = [];
        var maxDatos = Math.max.apply(null, bloques.map(function (b) { return b.length; }));
        for (var i = 0; i < maxDatos; i++) {
            for (var j = 0; j < bloques.length; j++) {
                if (i < bloques[j].length) salida.push(bloques[j][i]);
            }
        }
        for (var e = 0; e < ecLen; e++) {
            for (var k = 0; k < ec.length; k++) salida.push(ec[k][e]);
        }
        return salida;
    }

    function nuevaMatriz(n) {
        var m = [];
        for (var i = 0; i < n; i++) m.push(new Array(n).fill(null));
        return m;
    }

    function ponerPatronesFijos(m, version) {
        var n = m.length;
        function finder(fila, col) {
            for (var r = -1; r <= 7; r++) {
                for (var c = -1; c <= 7; c++) {
                    var fr = fila + r, fc = col + c;
                    if (fr < 0 || fr >= n || fc < 0 || fc >= n) continue;
                    var dentro = (r >= 0 && r <= 6 && (c === 0 || c === 6)) ||
                                 (c >= 0 && c <= 6 && (r === 0 || r === 6)) ||
                                 (r >= 2 && r <= 4 && c >= 2 && c <= 4);
                    m[fr][fc] = dentro ? 1 : 0;
                }
            }
        }
        finder(0, 0); finder(0, n - 7); finder(n - 7, 0);

        // ⚠️ ALINEACIÓN ANTES QUE EL TEMPORIZADOR. EL ORDEN NO ES COSMÉTICO.
        //
        // El patrón de alineación se saltea cuando su centro ya está ocupado, que es
        // como se omiten los tres que pisarían un finder. Pero si el temporizador se
        // dibuja primero, ese mismo "ya está ocupado" saltea también **los que caen
        // sobre la línea de tiempo** —por ejemplo (6, 22) en la versión 7—, que sí
        // van. El síntoma es que las versiones 1 a 6 salen perfectas y de la 7 en
        // adelante no coincide un solo módulo, porque falta un patrón entero y el
        // mapa de reservados queda corrido.
        var coords = ALINEACION[version];
        for (var a = 0; a < coords.length; a++) {
            for (var b = 0; b < coords.length; b++) {
                var fr = coords[a], fc = coords[b];
                if (m[fr][fc] !== null) continue;   // acá solo caen los tres de los finders
                for (var dr = -2; dr <= 2; dr++) {
                    for (var dc = -2; dc <= 2; dc++) {
                        var esOscuro = Math.max(Math.abs(dr), Math.abs(dc)) !== 1;
                        m[fr + dr][fc + dc] = esOscuro ? 1 : 0;
                    }
                }
            }
        }

        // Temporizadores: solo rellenan lo que quedó vacío.
        for (var i = 8; i < n - 8; i++) {
            var v = i % 2 === 0 ? 1 : 0;
            if (m[6][i] === null) m[6][i] = v;
            if (m[i][6] === null) m[i][6] = v;
        }

        // Módulo oscuro fijo.
        m[n - 8][8] = 1;

        // Info de versión (v7+).
        if (version >= 7) {
            var vb = bitsDeVersion(version);
            for (var i2 = 0; i2 < 18; i2++) {
                var bit = (vb >> i2) & 1;
                var fila = Math.floor(i2 / 3);
                var col = i2 % 3;
                m[fila][n - 11 + col] = bit;
                m[n - 11 + col][fila] = bit;
            }
        }
    }

    /**
     * Las 30 posiciones de la información de formato, EN ORDEN DE BIT: las primeras
     * 15 son la copia VERTICAL (bits 0..14) y las otras 15 la HORIZONTAL.
     *
     * ⚠️ ACÁ ESTUVO EL ÚNICO ERROR REAL DE ESTE ARCHIVO, Y COSTÓ DOS VUELTAS DE
     * VERIFICACIÓN. Las dos copias estaban **traspuestas**: los bits 0..5 se
     * escribían en la FILA 8 cuando van en la COLUMNA 8, y al revés. Como las dos
     * copias ocupan una fila 8 y una columna 8 que se cruzan, el error es
     * perfectamente simétrico y "parece bien" al mirarlo.
     *
     * La forma correcta, leída de una implementación de referencia y no deducida:
     *
     *   Copia VERTICAL (columna 8, arriba y abajo):
     *     · bits 0..5   → (i, 8)              filas 0..5
     *     · bit  6      → (7, 8)              se saltea la fila 6 (temporizador)
     *     · bit  7      → (8, 8)
     *     · bits 8..14  → (n-15+i, 8)         filas n-7 … n-1
     *
     *   Copia HORIZONTAL (fila 8, derecha e izquierda):
     *     · bits 0..7   → (8, n-1-i)          columnas n-1 … n-8
     *     · bit  8      → (8, 7)              se saltea la columna 6 (temporizador)
     *     · bits 9..14  → (8, 14-i)           columnas 5 … 0
     *
     * Son 30 módulos distintos. El módulo oscuro fijo `(n-8, 8)` **no** es uno de
     * ellos y no va en esta lista: se pone aparte en `ponerPatronesFijos()`.
     *
     * Y el detalle que hace que un error acá se vea como un desastre en otro lado:
     * esta misma lista arma el mapa de RESERVADOS. Una posición de más o de menos
     * corre el zigzag de datos y a partir de ahí no coincide un solo módulo — que
     * es exactamente el síntoma que tuvo la primera vuelta de v7 en adelante. Si
     * alguna vez esto vuelve a no coincidir, mirá acá antes que el álgebra.
     */
    function posicionesDeFormato(n) {
        var pos = [];
        var i;
        // ── Copia vertical: columna 8 ──
        for (i = 0; i < 15; i++) {
            if (i < 6) pos.push([i, 8]);
            else if (i < 8) pos.push([i + 1, 8]);
            else pos.push([n - 15 + i, 8]);
        }
        // ── Copia horizontal: fila 8 ──
        for (i = 0; i < 15; i++) {
            if (i < 8) pos.push([8, n - 1 - i]);
            else if (i === 8) pos.push([8, 7]);
            else pos.push([8, 14 - i]);
        }
        return pos;
    }

    function colocarFormato(m, mascara) {
        var n = m.length;
        var bits = bitsDeFormato(mascara);
        var pos = posicionesDeFormato(n);
        for (var i = 0; i < 15; i++) {
            var bit = (bits >> i) & 1;
            m[pos[i][0]][pos[i][1]] = bit;
            m[pos[15 + i][0]][pos[15 + i][1]] = bit;
        }
    }

    function colocarDatos(m, codewords, reservado) {
        var n = m.length;
        var bitIndex = 0;
        var total = codewords.length * 8;
        var arriba = true;
        for (var col = n - 1; col > 0; col -= 2) {
            if (col === 6) col--;   // la columna del temporizador no lleva datos
            for (var paso = 0; paso < n; paso++) {
                var fila = arriba ? n - 1 - paso : paso;
                for (var d = 0; d < 2; d++) {
                    var c = col - d;
                    if (reservado[fila][c]) continue;
                    var bit = 0;
                    if (bitIndex < total) {
                        bit = (codewords[bitIndex >> 3] >> (7 - (bitIndex & 7))) & 1;
                    }
                    bitIndex++;
                    m[fila][c] = bit;
                }
            }
            arriba = !arriba;
        }
    }

    function condicionMascara(mascara, fila, col) {
        switch (mascara) {
            case 0: return (fila + col) % 2 === 0;
            case 1: return fila % 2 === 0;
            case 2: return col % 3 === 0;
            case 3: return (fila + col) % 3 === 0;
            case 4: return (Math.floor(fila / 2) + Math.floor(col / 3)) % 2 === 0;
            case 5: return ((fila * col) % 2) + ((fila * col) % 3) === 0;
            case 6: return (((fila * col) % 2) + ((fila * col) % 3)) % 2 === 0;
            case 7: return (((fila + col) % 2) + ((fila * col) % 3)) % 2 === 0;
        }
        return false;
    }

    /** Las cuatro reglas de penalización del estándar. */
    function penalizacion(m) {
        var n = m.length, total = 0, i, j, run, anterior;

        // Regla 1: cinco o más del mismo color seguidos, en filas y columnas.
        for (i = 0; i < n; i++) {
            run = 1; anterior = m[i][0];
            for (j = 1; j < n; j++) {
                if (m[i][j] === anterior) { run++; }
                else { if (run >= 5) total += 3 + (run - 5); run = 1; anterior = m[i][j]; }
            }
            if (run >= 5) total += 3 + (run - 5);
            run = 1; anterior = m[0][i];
            for (j = 1; j < n; j++) {
                if (m[j][i] === anterior) { run++; }
                else { if (run >= 5) total += 3 + (run - 5); run = 1; anterior = m[j][i]; }
            }
            if (run >= 5) total += 3 + (run - 5);
        }

        // Regla 2: bloques de 2x2 del mismo color.
        for (i = 0; i < n - 1; i++) {
            for (j = 0; j < n - 1; j++) {
                var v = m[i][j];
                if (v === m[i][j + 1] && v === m[i + 1][j] && v === m[i + 1][j + 1]) total += 3;
            }
        }

        // Regla 3: el patrón 1:1:3:1:1 con cuatro claros de un lado.
        var patronA = [1, 0, 1, 1, 1, 0, 1, 0, 0, 0, 0];
        var patronB = [0, 0, 0, 0, 1, 0, 1, 1, 1, 0, 1];
        function coincide(get, largo, inicio, patron) {
            for (var k = 0; k < patron.length; k++) {
                if (inicio + k >= largo || get(inicio + k) !== patron[k]) return false;
            }
            return true;
        }
        for (i = 0; i < n; i++) {
            for (j = 0; j < n; j++) {
                var fila = (function (r) { return function (x) { return m[r][x]; }; })(i);
                var columna = (function (c) { return function (x) { return m[x][c]; }; })(i);
                if (coincide(fila, n, j, patronA) || coincide(fila, n, j, patronB)) total += 40;
                if (coincide(columna, n, j, patronA) || coincide(columna, n, j, patronB)) total += 40;
            }
        }

        // Regla 4: desbalance entre oscuros y claros.
        var oscuros = 0;
        for (i = 0; i < n; i++) for (j = 0; j < n; j++) if (m[i][j]) oscuros++;
        var porcentaje = (oscuros * 100) / (n * n);
        total += Math.floor(Math.abs(porcentaje - 50) / 5) * 10;
        return total;
    }

    /**
     * Genera la matriz del QR. Devuelve `{matriz, version, mascara}`; `matriz` es un
     * arreglo de arreglos de 0/1, sin margen (el margen lo pone quien dibuje).
     */
    function generar(texto) {
        var bytes = bytesDeTexto(texto);
        var version = elegirVersion(bytes.length);
        if (version < 0) {
            throw new Error("El texto no entra en un QR nivel L ni en la versión 40 (" +
                bytes.length + " bytes).");
        }
        var codewords = entrelazar(armarCodewords(bytes, version), version);
        var n = version * 4 + 17;

        // Se arma una vez la plantilla con los patrones fijos y el mapa de reservados.
        var base = nuevaMatriz(n);
        ponerPatronesFijos(base, version);
        var reservado = [];
        for (var i = 0; i < n; i++) {
            reservado.push(base[i].map(function (v) { return v !== null; }));
        }
        // Los módulos del formato también están reservados, aunque todavía no tengan
        // valor. ⚠️ Solo estos: ver el comentario de posicionesDeFormato() sobre por
        // qué `(8, n-8)` NO va acá.
        posicionesDeFormato(n).forEach(function (p) { reservado[p[0]][p[1]] = true; });

        var mejor = null;
        for (var mascara = 0; mascara < 8; mascara++) {
            var m = base.map(function (fila) { return fila.slice(); });
            colocarDatos(m, codewords, reservado);
            for (var f = 0; f < n; f++) {
                for (var c = 0; c < n; c++) {
                    if (!reservado[f][c] && condicionMascara(mascara, f, c)) m[f][c] ^= 1;
                }
            }
            colocarFormato(m, mascara);
            var p = penalizacion(m);
            if (mejor === null || p < mejor.penalizacion) {
                mejor = { matriz: m, version: version, mascara: mascara, penalizacion: p };
            }
        }
        return mejor;
    }

    /** Dibuja el QR como SVG. `margen` va en módulos (el estándar pide 4). */
    function svg(texto, opciones) {
        opciones = opciones || {};
        var margen = opciones.margen === undefined ? 4 : opciones.margen;
        var lado = opciones.lado || 320;
        var r = generar(texto);
        var n = r.matriz.length;
        var total = n + margen * 2;
        var d = "";
        for (var i = 0; i < n; i++) {
            for (var j = 0; j < n; j++) {
                if (r.matriz[i][j]) d += "M" + (j + margen) + " " + (i + margen) + "h1v1h-1z";
            }
        }
        return '<svg xmlns="http://www.w3.org/2000/svg" width="' + lado + '" height="' + lado +
            '" viewBox="0 0 ' + total + " " + total + '" shape-rendering="crispEdges" ' +
            'role="img" aria-label="Código QR de aprovisionamiento">' +
            '<rect width="' + total + '" height="' + total + '" fill="#fff"/>' +
            '<path d="' + d + '" fill="#000"/></svg>';
    }

    global.LockSuiteQR = { generar: generar, svg: svg };
})(typeof window !== "undefined" ? window : globalThis);

if (typeof module !== "undefined" && module.exports) {
    module.exports = (typeof window !== "undefined" ? window : globalThis).LockSuiteQR;
}
