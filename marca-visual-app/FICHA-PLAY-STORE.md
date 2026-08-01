# Publicación en Google Play — Góndola

Estado al 1 de agosto de 2026.

- **App en Play Console:** creada · `ar.com.gondola.app` · ID interno `4973399769599238430`
- **Cuenta:** Fuerte Code (personal) — **sin acceso a producción todavía**
- **AAB firmado:** `C:\Users\Victoria\Desktop\Gondola-v1-release.aab` (30 MB, versionCode 1)
- **Clave de subida:** `my-upload-key.jks` en la raíz del repo · alias `upload`.
  El archivo está en `.gitignore` y no se commitea. **La contraseña NO va acá ni en
  ningún archivo del repo**: guardala en tu gestor de contraseñas. El build la toma
  de las variables de entorno `STORE_PASSWORD` y `KEY_PASSWORD`.

---

## Lo que YA quedó hecho

| | |
|---|---|
| ✅ | Key de Gemini fuera del APK — migrado a Firebase AI Logic, verificado con `grep` sobre el `.aab` |
| ✅ | App Check con Play Integrity (release) y proveedor de debug (local) |
| ✅ | `applicationId` → `ar.com.gondola.app`, registrado en Firebase con los SHA de la clave de subida |
| ✅ | AAB de release compilado y firmado (`jar verified`, `CN=Fuerte Code`) |
| ✅ | Política de privacidad publicada en https://compras-super-18da9.web.app/privacidad |
| ✅ | App creada en Play Console (Sin coste, Compras, es-419) |
| ✅ | Ficha: nombre, descripción breve y descripción completa guardadas como borrador |
| ✅ | Configuración de la tienda: categoría Compras, correo de contacto, sitio web |

## Lo que falta — y por qué lo tenés que hacer vos

Yo no puedo subir archivos al navegador desde esta sesión (el tooling solo deja
subir archivos que compartiste explícitamente con la sesión), y no firmo
declaraciones legales en tu nombre.

### 1. Subir los gráficos de la ficha
En **Aumentar usuarios → Fichas de Play Store → Ficha predeterminada**:

| Recurso | Archivo | Estado |
|---|---|---|
| Ícono 512×512 | `marca-visual-app/assets/gondola-icon-512.png` | listo |
| Gráfico de funciones 1024×500 | `marca-visual-app/assets/play-feature-graphic-1024x500.png` | listo |
| Capturas de teléfono (mín. 2) | — | **faltan, hay que sacarlas** |

Para las capturas: instalá el APK debug en el Samsung y sacá 4 pantallas
(catálogo con un comparador abierto, inicio con el historial, estadísticas, una
lista compartida). Si querés te dejo el APK debug compilado y las sacás vos, que
es más rápido que manejarte el teléfono.

### 2. Subir el AAB a prueba cerrada
**Probar y publicar → Pruebas → Prueba cerrada → Crear versión** y arrastrá
`C:\Users\Victoria\Desktop\Gondola-v1-release.aab`.

Cuando lo subas, Play va a ofrecerte activar **Play App Signing**: aceptalo (es
lo estándar y hace que la clave de subida sea recuperable si la perdés).

### 3. Registrar App Check con el certificado de Play ⚠️ IMPORTANTE
Firebase AI Logic ya tiene App Check **aplicado**, pero la app figura *sin
registrar*. **Si no hacés esto, los testers no van a poder escanear tickets.**

1. Play Console → **Configuración → Integridad de la aplicación** → copiá el
   **SHA-256 del certificado de firma de apps** (el de Google, no el de subida).
2. Firebase → App Check → Gondola → Play Integrity → pegá ese SHA-256 → Guardar.
3. Para que las builds locales sigan escaneando: corré la app en debug, buscá en
   Logcat el tag `DebugAppCheckProvider`, copiá el token y registralo en
   Firebase → App Check → Gondola → ⋮ → Administrar tokens de depuración.

### 4. Declaraciones (las firmás vos)
En **Probar y publicar → Contenido de la aplicación**:

- **Política de privacidad** → pegá `https://compras-super-18da9.web.app/privacidad`
- **Acceso a la app** → la app pide login, así que Google necesita credenciales de
  prueba. **Creá una cuenta demo** (ej. `demo@gondola...`) con algunos tickets
  cargados y pasásela a Google acá. Sin esto rebotan la revisión.
- **Anuncios** → No contiene anuncios
- **Clasificación de contenido** → cuestionario, todas las respuestas negativas →
  debería dar apta para todo público
- **Público objetivo** → 18 y más (evita el régimen de Familias)
- **Seguridad de los datos** → ver la tabla de abajo
- **Eliminación de datos** → la app ya tiene borrado de cuenta en Perfil →
  Eliminar cuenta; declaralo así

### 5. Los 12 testers
Google exige **12 testers en prueba cerrada durante 14 días seguidos** antes de
habilitar producción. Necesitás 12 cuentas de Google (correos de gente real que
mantenga la app instalada). Cargalas en la lista de la prueba cerrada.

**El reloj arranca recién cuando publicás la versión en prueba cerrada.**

---

## Seguridad de los datos — respuestas verificadas contra el código

**¿Recopila datos?** Sí · **¿Cifrados en tránsito?** Sí · **¿Se pueden borrar?** Sí (Perfil → Eliminar cuenta)

| Tipo de dato | Recopilado | Compartido | Obligatorio | Finalidad |
|---|---|---|---|---|
| Dirección de correo | Sí | No | Sí | Gestión de la cuenta |
| Nombre | Sí | No | No | Personalización |
| Ubicación aproximada (ciudad, la escribe el usuario) | Sí | No | No | Funcionalidad |
| Historial de compras | Sí | No | Sí | Funcionalidad y estadísticas |
| Fotos | Sí | No | No | Funcionalidad — se procesa y **no se almacena** |
| Interacción con la app / ID de dispositivo | No | — | — | — |

> Las fotos hay que declararlas aunque no se guarden: salen del dispositivo hacia
> Gemini para procesarse. Marcá "recopilado" y aclarará que el procesamiento es
> efímero. Verificado en `FirebaseRepository.saveTicket` — el `TicketModel` no
> tiene ningún campo de imagen.

---

## Textos de la ficha (ya cargados, por si querés editarlos)

**Descripción breve** (69/80):
```
Compará precios de súper en Tandil, escaneá tickets y armá tus listas.
```

**Descripción completa:** ya está cargada en Play Console. Incluye al final el
descargo de que Góndola no está afiliada a ninguna cadena de supermercados y que
los precios son de referencia — conviene no sacarlo, es lo que evita problemas de
marca con Vea/Carrefour/Día/Coop.
