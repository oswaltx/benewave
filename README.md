# greenWave Maps

**Offline bike navigation that tells you how fast to ride to catch the next traffic light on green.**

greenWave Maps is a fork of [CoMaps](https://codeberg.org/comaps/comaps), the
community-driven, privacy-first offline maps app. It keeps everything CoMaps
does (offline OpenStreetMap maps, navigation, no tracking) and adds a
"green wave" assistant for cyclists. It is an independent project and not
affiliated with or endorsed by CoMaps.

> Status: beta, Android only, UI currently in German. Tested on real rides,
> but traffic-light timing is learned, so recommendations improve over time.

## What greenWave adds

- **Speed advice for the next light**: two speed options (slower / faster) in
  the navigation screen and notification, so you arrive while it is green.
  No advice for lights it does not know yet, instead of guessing.
- **Learning traffic lights from your rides**: stops and green pass-throughs
  are detected per direction, map-matched to the road (Newson & Krumm HMM) and
  turned into cycle estimates with a built-in self-check.
- **Live signal data where available**: the open SensorThings traffic light
  feed of the City of Hamburg is used directly.
- **Bike route modes**: long straights, few traffic lights, little car traffic,
  or smooth riding; arrival times include expected waiting at lights.
- **My rides**: ride list, GPX track on the map, comparison with the route the
  navigation suggested, and which of two ways between the same places is
  faster on average.
- **Tracking without navigation**, bike detection and battery saving.
- **Nerd mode**: calibrate a traffic light by hand by tapping on each switch
  to green or red.
- **Optional, opt-in sharing** of anonymised light events with a board server
  you choose (weekly, Wi-Fi only, no account, no track, start and end of each
  ride stay on the device). Off by default; no server is preset.

The shared learning logic lives in a Kotlin Multiplatform module
(`android/greenwave`, targets JVM and iOS) with its own tests. The Android
integration is in `android/app/src/main/java/dev/greenwave/maps`.

## Building

Build like CoMaps; see [INSTALL.md](INSTALL.md) and
[README.comaps.md](README.comaps.md). The app uses its own application ID and
name, so it installs next to CoMaps.

Run the greenWave logic tests:

```bash
cd android && ./gradlew :greenwave:jvmTest
```

## Data sources and credits

- Map data © OpenStreetMap contributors (ODbL).
- Live traffic light data Hamburg: Freie und Hansestadt Hamburg, Datenlizenz
  Deutschland – Namensnennung 2.0 (tld.iot.hamburg.de).
- Map matching based on [hmm-lib](https://github.com/bmwcarit/hmm-lib) by
  BMW Car IT GmbH (Apache 2.0), ported to Kotlin.
- Everything else: the CoMaps and Organic Maps contributors.

greenWave code was written with AI assistance and reviewed by me. It is not
intended for upstream contribution to CoMaps.

## License

Apache License 2.0, like CoMaps. See [LICENSE](LICENSE) and [NOTICE](NOTICE).
