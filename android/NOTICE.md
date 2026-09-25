# Third-party notices

Patch Pilot itself is licensed under the GPLv3 (see [`../LICENSE`](../LICENSE)).
It statically links the following libraries, each under the Apache License,
Version 2.0 (full text at <https://www.apache.org/licenses/LICENSE-2.0>):

- AndroidX (`core-ktx`, `lifecycle-runtime-ktx`, `lifecycle-viewmodel-compose`,
  `activity-compose`, `navigation-compose`, `datastore-preferences`) — The
  Android Open Source Project
- Jetpack Compose (`ui`, `ui-graphics`, `ui-tooling`, `ui-tooling-preview`,
  `material3`, `material-icons-core`) — The Android Open Source Project
- Kotlin standard library and `kotlinx.coroutines`, `kotlinx.serialization` —
  JetBrains s.r.o. and contributors

None of the above ship a `NOTICE` file with attribution content of their own
to reproduce; this file lists them per Apache License 2.0 §4 as a matter of
courtesy and to keep the list current as dependencies change (see
`android/app/build.gradle.kts` and `android/gradle/libs.versions.toml` for
exact versions).

It also bundles two fonts, each under the SIL Open Font License, Version 1.1
(full text alongside this file, at
[`licenses/OFL-Cinzel.txt`](licenses/OFL-Cinzel.txt) and
[`licenses/OFL-ShareTechMono.txt`](licenses/OFL-ShareTechMono.txt)) — a
free license the FSF lists as GPL-compatible. Neither font's copyright
holder endorses this project; the OFL specifically reserves that.

- Cinzel (`app/src/main/res/font/cinzel.ttf`) — Copyright 2020 The Cinzel
  Project Authors (https://github.com/NDISCOVER/Cinzel)
- Share Tech Mono (`app/src/main/res/font/share_tech_mono.ttf`) — Copyright
  (c) 2012, Carrois Type Design, Ralph du Carrois, with Reserved Font Name
  'Share'
