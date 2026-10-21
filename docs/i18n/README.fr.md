# PeerNetVPN (PeerNet) pour Android

Partage chiffré d'une connexion Internet entre appareils Android.
Android 10 ou ultérieur, sans root. L'interface propose huit langues.

**0.5.0 est une version debug expérimentale.**

## Installation et connexion

1. Téléchargez l'APK debug 0.5.0 dans [GitHub Releases](https://github.com/MaximFedorovtoo/PeerNetVPN/releases) du dépôt.
2. Installez-le sur les deux appareils et autorisez la source d'installation choisie.
3. Activez le partage sur les deux appareils et attendez la mesure du débit montant.
4. Ouvrez « Carte », choisissez une région et un nœud disponible. Le répertoire de
   découverte est préconfiguré ; son adresse peut être modifiée dans les paramètres.
5. Sur l'appareil récepteur, choisissez les applications et autorisez le VPN Android.
   Seules les applications choisies utilisent le tunnel. Reconnectez-vous après
   toute modification de la sélection.

Le partage doit rester actif et joignable pour utiliser la connexion d'un autre
participant. Aucun client connecté ni volume minimal envoyé n'est exigé.
Chaque appareil dessert au plus trois appareils ; leur débit cumulé est limité
au tiers du débit montant mesuré. Aucun crédit, portefeuille ou achat.

## Limites et confidentialité

- La découverte ne garantit pas la connexion. Certains NAT/CGNAT nécessitent un
  participant relais joignable ; l'appairage manuel ICE/WebRTC n'utilise pas de TURN.
- Le répertoire échange les données de découverte et d'appairage, pas le trafic VPN.
  Les invitations et l'appairage manuels restent disponibles en secours.
  Gardez les invitations confidentielles.
- Le chiffrement protège le transport entre appareils. Le nœud de sortie voit
  les destinations et le trafic applicatif non chiffré. PeerNet ne promet pas l'anonymat.
- Chaque mesure envoie jusqu'à 24 Mio de données de test à Selectel ou, en secours,
  à 59telecom. Elle a lieu au démarrage du partage, lors d'un changement de réseau
  et toutes les 15 minutes. Elle coupe les sessions : reconnectez-vous ensuite.
  Une mesure réussie est nécessaire pour partager du trafic Internet.
- La recherche du pays contacte api.country.is. Le pays déclaré par un nœud est
  approximatif et ne prouve pas sa position. Le mode public expose adresse et clé.
- Android autorise un seul VPN système par profil. Le partage affiche une notification
  de premier plan et maintient le processeur éveillé. TCP/UDP sont pris en charge ;
  ICMP, multicast et accès au réseau privé du propriétaire de la sortie ne sont pas
  acheminés. Le DNS passe par la sortie.

## Compilation locale sous Windows

Depuis la racine du dépôt :

```powershell
powershell -ExecutionPolicy Bypass -File tools/build.ps1 :core:test :app:assembleDebug :app:assembleDebugAndroidTest :app:lintDebug
```

Le script prépare Java 17, Gradle 8.13 et Android SDK 35 dans `.tools`, vérifie les
téléchargements et accepte les licences SDK.
APK : `app/build/outputs/apk/debug/app-debug.apk`.
Les contrôles sur appareil nécessitent un appareil connecté avec débogage USB activé.
Voir le [README principal](../../README.md), le [protocole](../protocol.md) et les
[mentions des composants tiers](../../THIRD_PARTY_NOTICES.txt).

## Langues

[English](README.en.md) · [Русский](README.ru.md) · [中文](README.zh.md) ·
[日本語](README.ja.md) · [Français](README.fr.md) · [Italiano](README.it.md) ·
[Español](README.es.md) · [Español (México)](README.es-MX.md)
