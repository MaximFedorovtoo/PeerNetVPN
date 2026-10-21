# PeerNetVPN (PeerNet) para Android

Comparte la conexión a Internet entre dispositivos Android mediante transporte
cifrado. Requiere Android 10 o posterior, sin root. La interfaz admite ocho idiomas.

**0.5.0 es una compilación debug experimental.**

## Instalación y conexión

1. Descarga el APK debug 0.5.0 de [GitHub Releases](https://github.com/MaximFedorovtoo/PeerNetVPN/releases) del repositorio.
2. Instálalo en ambos dispositivos y autoriza la fuente de instalación elegida.
3. Activa el uso compartido en ambos y espera a que termine la medición de subida.
4. Abre «Mapa» y elige una región y un nodo disponible. El directorio de
   descubrimiento ya está configurado; puedes cambiar su dirección en los ajustes.
5. En el dispositivo receptor elige las aplicaciones y autoriza la VPN de Android.
   Solo las aplicaciones seleccionadas usan el túnel. Vuelve a conectarte
   después de cambiar la selección.

Para usar la conexión de otro participante, tu conexión compartida debe permanecer
activa y accesible. No se exigen clientes conectados ni un volumen mínimo enviado.
Cada dispositivo atiende hasta tres dispositivos; su límite conjunto es un tercio
de la velocidad de subida medida. No hay créditos, monederos ni compras.

## Límites y privacidad

- El descubrimiento no garantiza la conexión. Algunas combinaciones de NAT/CGNAT
  necesitan un participante accesible como retransmisor. El emparejamiento manual
  ICE/WebRTC no dispone de servidor TURN.
- El directorio intercambia datos de descubrimiento y emparejamiento, no tráfico VPN.
  Las invitaciones y el emparejamiento manuales siguen disponibles como alternativa.
  Mantén las invitaciones en secreto.
- El cifrado protege el transporte entre dispositivos. El nodo de salida ve los
  destinos y el tráfico de las aplicaciones sin cifrar. PeerNet no promete anonimato.
- Cada medición envía hasta 24 MiB de datos de prueba a Selectel o al proveedor
  alternativo 59telecom. Se ejecuta al activar el uso compartido, al cambiar de red
  y cada 15 minutos. Desconecta las sesiones: vuelve a conectarte después.
  Para compartir tráfico de Internet, la medición debe completarse correctamente.
- La consulta del país usa api.country.is. El país declarado por el nodo es
  aproximado y no demuestra su ubicación. El modo público expone dirección y clave.
- Android permite una VPN de sistema por perfil. El uso compartido muestra una
  notificación en primer plano y mantiene la CPU activa. Admite TCP/UDP; no enruta
  ICMP, multidifusión ni acceso a la red privada del propietario del nodo de salida.
  El DNS pasa por la salida.

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
