# PeerNetVPN (PeerNet) per Android

Condivisione cifrata della connessione Internet tra dispositivi Android.
Richiede Android 10 o successivo, senza root. L'interfaccia supporta otto lingue.

**0.5.0 è una build debug sperimentale.**

## Installazione e connessione

1. Scarica l'APK debug 0.5.0 da [GitHub Releases](https://github.com/MaximFedorovtoo/PeerNetVPN/releases) del repository.
2. Installalo su entrambi i dispositivi e autorizza la fonte di installazione scelta.
3. Attiva la condivisione su entrambi e attendi la misurazione della velocità di upload.
4. Apri «Mappa», scegli una regione e un nodo disponibile. Il catalogo di rilevamento
   è già configurato; puoi cambiarne l'indirizzo nelle impostazioni.
5. Sul dispositivo ricevente scegli le app e autorizza la connessione VPN Android.
   Solo le app selezionate usano il tunnel. Riconnettiti dopo aver cambiato la selezione.

Per usare la connessione di un altro partecipante, la propria condivisione deve
restare attiva e raggiungibile. Non sono necessari client connessi né un volume
minimo inviato. Ogni dispositivo serve al massimo tre dispositivi; il limite
complessivo è un terzo dell'upload misurato. Nessun credito, portafoglio o acquisto.

## Limiti e riservatezza

- Il rilevamento non garantisce la connessione. Alcune combinazioni NAT/CGNAT
  richiedono un partecipante raggiungibile come relay; l'abbinamento manuale
  ICE/WebRTC non dispone di un server TURN.
- Il catalogo scambia dati di rilevamento e abbinamento, non traffico VPN. Inviti e
  abbinamento manuali restano disponibili come alternativa. Mantieni segreti gli inviti.
- La cifratura protegge il trasporto tra dispositivi. Il nodo di uscita vede le
  destinazioni e il traffico applicativo non cifrato. PeerNet non promette anonimato.
- Ogni misurazione invia fino a 24 MiB di dati di prova a Selectel o, come riserva,
  a 59telecom. Avviene all'avvio della condivisione, al cambio di rete e ogni
  15 minuti. Interrompe le sessioni: occorre riconnettersi. Per condividere traffico
  Internet è necessaria una misurazione riuscita.
- Il paese viene cercato tramite api.country.is. Quello dichiarato dal nodo è
  approssimativo e non ne prova la posizione. La modalità pubblica espone indirizzo e chiave.
- Android permette una sola VPN di sistema per profilo. La condivisione mostra una
  notifica in primo piano e mantiene attiva la CPU. Supporta TCP/UDP; ICMP, multicast
  e accessi alla rete privata del proprietario dell'uscita non vengono instradati.
  Il DNS passa attraverso il nodo di uscita.

## Compilazione locale su Windows

Dalla radice del repository:

```powershell
powershell -ExecutionPolicy Bypass -File tools/build.ps1 :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Lo script prepara Java 17, Gradle 8.13 e Android SDK 35 in `.tools`, verifica
i download e accetta le licenze SDK.
APK: `app/build/outputs/apk/debug/app-debug.apk`.
Le verifiche su dispositivo richiedono un dispositivo collegato con debug USB attivo.
Vedi [README principale](../../README.md), [protocollo](../protocol.md) e
[avvisi sui componenti di terze parti](../../THIRD_PARTY_NOTICES.txt).

## Lingue

[English](README.en.md) · [Русский](README.ru.md) · [中文](README.zh.md) ·
[日本語](README.ja.md) · [Français](README.fr.md) · [Italiano](README.it.md) ·
[Español](README.es.md) · [Español (México)](README.es-MX.md)
