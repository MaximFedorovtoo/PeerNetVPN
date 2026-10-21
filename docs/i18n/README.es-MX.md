# PeerNetVPN (PeerNet) para Android

Comparte una conexión a Internet entre dispositivos Android con transporte cifrado.
Requiere Android 10 o posterior, sin root. La interfaz está disponible en ocho idiomas.

**0.5.0 es una compilación debug experimental.**

## Instalación y conexión

1. Descarga el APK debug 0.5.0 de [GitHub Releases](https://github.com/MaximFedorovtoo/PeerNetVPN/releases) del repositorio.
2. Instálalo en ambos celulares y permite instalar desde la fuente que elegiste.
3. Activa la conexión compartida en ambos y espera a que termine la medición de subida.
4. Abre «Mapa», elige una región y un nodo disponible. El directorio de
   descubrimiento ya viene configurado; puedes cambiar su dirección en Configuración.
5. En el celular receptor, selecciona las apps y permite la conexión VPN de Android.
   Solo las apps seleccionadas usan el túnel. Vuelve a conectarte después de
   cambiar la selección.

Para usar la conexión de otra persona, la tuya debe permanecer compartida y accesible.
No necesitas tener clientes conectados ni enviar una cantidad mínima de datos.
Cada dispositivo atiende hasta tres dispositivos; entre todos tienen un límite de
un tercio de la velocidad de subida medida. No hay créditos, billeteras ni compras.

## Límites y privacidad

- Encontrar un nodo no garantiza la conexión. Algunas combinaciones de NAT/CGNAT
  necesitan un participante accesible que retransmita los datos. La vinculación
  manual ICE/WebRTC no cuenta con un servidor TURN.
- El directorio intercambia datos de descubrimiento y vinculación, no tráfico VPN.
  Las invitaciones y la vinculación manuales siguen disponibles como respaldo.
  Mantén las invitaciones en privado.
- El cifrado protege el transporte entre dispositivos. El nodo de salida puede ver
  los destinos y el tráfico de apps sin cifrar. PeerNet no promete anonimato.
- Cada medición envía hasta 24 MiB de datos de prueba a Selectel o, como respaldo,
  a 59telecom. Se realiza al activar la conexión compartida, al cambiar de red y cada
  15 minutos. Desconecta las sesiones: vuelve a conectarte al terminar.
  Para compartir tráfico de Internet, la medición debe completarse con éxito.
- La consulta del país usa api.country.is. El país que informa un nodo es aproximado
  y no demuestra su ubicación. El modo público expone la dirección y la clave.
- Android permite una sola VPN del sistema por perfil. La conexión compartida muestra
  una notificación en primer plano y mantiene activa la CPU. Admite TCP/UDP; no enruta
  ICMP, multidifusión ni acceso a la red privada de quien ofrece la salida.
  El DNS pasa por el nodo de salida.

## Compilación local en Windows

Desde la raíz del repositorio:

```powershell
powershell -ExecutionPolicy Bypass -File tools/build.ps1 :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

El script prepara Java 17, Gradle 8.13 y Android SDK 35 en `.tools`, verifica las
descargas y acepta las licencias del SDK.
APK: `app/build/outputs/apk/debug/app-debug.apk`.
Las pruebas físicas requieren un dispositivo conectado con depuración USB activada.
Consulta el [README principal](../../README.md), el [protocolo](../protocol.md) y los
[avisos de terceros](../../THIRD_PARTY_NOTICES.txt).

## Idiomas

[English](README.en.md) · [Русский](README.ru.md) · [中文](README.zh.md) ·
[日本語](README.ja.md) · [Français](README.fr.md) · [Italiano](README.it.md) ·
[Español](README.es.md) · [Español (México)](README.es-MX.md)
