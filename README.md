# beneWave

beneWave (formerly greenWave Maps) is an Android app for cyclists, built on [CoMaps](https://codeberg.org/comaps/comaps) (offline maps from OpenStreetMap). During navigation it suggests a speed so you reach the next traffic light while it is green. It is a fork with no connection to the CoMaps project. beneWave is one of the projects of bene, a non-profit association being founded in Cologne, next to [beneMap](https://benemap.org).

The app is in beta and the interface is only in German so far. Inside the app it is still called greenWave Maps until the rename is done there. I test it on my own rides. It learns traffic light timings from rides, so a suggestion gets better the more often a light has been passed.

## What it adds to CoMaps

- Two speed suggestions for the next light, a slower and a faster one, on the navigation screen and in the notification. For a light it hasn't learned yet it shows nothing.
- It detects stops and green passes per direction, matches the GPS track to the road (Newson & Krumm HMM) and estimates each light's cycle from that.
- In Hamburg it uses the city's open live traffic light data (SensorThings API).
- Bike routing modes for long straight sections, few traffic lights, little car traffic or as few stops as possible. Arrival times include the expected waiting at lights.
- A ride history with GPX tracks. It compares each ride with the route the navigation suggested and shows which of two routes between the same places is faster on average.
- Tracking without navigation, with automatic bike detection.
- A nerd mode where you calibrate a light by hand by tapping each time it switches.
- Optional sharing of anonymous light events with a server you choose. It is off by default and no server is preset. Events go out once a week over Wi-Fi, without an account or GPS track, and lights near the start and end of each ride stay on the phone.

The learning logic is a Kotlin Multiplatform module in `android/greenwave` (JVM and iOS targets) with its own tests. The Android code is in `android/app/src/main/java/dev/greenwave/maps`.

## Building

Build it like CoMaps, see [INSTALL.md](INSTALL.md) and [README.comaps.md](README.comaps.md). The app has its own application ID, so it installs next to CoMaps.

To run the tests of the learning logic:

```bash
cd android && ./gradlew :greenwave:jvmTest
```

## Data and credits

- Map data © OpenStreetMap contributors (ODbL).
- Live traffic light data Hamburg: Freie und Hansestadt Hamburg, Datenlizenz Deutschland Namensnennung 2.0 (tld.iot.hamburg.de).
- The map matching is ported to Kotlin from [hmm-lib](https://github.com/bmwcarit/hmm-lib) by BMW Car IT GmbH (Apache 2.0).
- The rest of the app is CoMaps, which is based on Organic Maps.

Most of the beneWave code was written with AI assistance. It is not meant to be contributed back to CoMaps.

## License

Apache License 2.0 like CoMaps, see [LICENSE](LICENSE) and [NOTICE](NOTICE).
