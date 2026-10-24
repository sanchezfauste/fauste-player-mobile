# Changelog

## [Unreleased]

### Documentation

* **mobile:** user guide book with emulator screenshots ([cf2db63](https://github.com/sanchezfauste/fauste-player-mobile/commit/cf2db63df3c05b5ea299112a5da210f3ec38abc3))
* **mobile:** technical documentation and project root files at desktop parity ([ac11e2f](https://github.com/sanchezfauste/fauste-player-mobile/commit/ac11e2f52d216de5ac0d52d005ebb81bed65e3e9))

### Miscellaneous Chores

* **mobile:** re-lock the desktop path dependencies at 0.8.0 ([a98f696](https://github.com/sanchezfauste/fauste-player-mobile/commit/a98f696e8a6c51799d6d290755c47bf47a60e983))

### Bug Fixes

* **backends-mobile:** wait for the boot save instead of racing it in the settled-core test ([781444e](https://github.com/sanchezfauste/fauste-player-mobile/commit/781444e726082e0d859e19441b78cfa3f2357937))
* **ci:** retry sdkmanager package installs against cdn download flakes ([889b168](https://github.com/sanchezfauste/fauste-player-mobile/commit/889b16820becf2b901df788dafffeaa056fa6553))
* **ci:** host build deps in the android lane kvm chmod and ci-sized test budgets ([1a95337](https://github.com/sanchezfauste/fauste-player-mobile/commit/1a953378cf17c6f5ecb9919ba84efc0ba9cfa1c3))
* **ci:** full-path sdkmanager pinned avd home and native build deps ([54d8e79](https://github.com/sanchezfauste/fauste-player-mobile/commit/54d8e791a1c66eb1725beac80a6f3d9046cec57d))
* **ci:** checkout the desktop repo inside the workspace and symlink the sibling path ([c39f4c6](https://github.com/sanchezfauste/fauste-player-mobile/commit/c39f4c61af2c41e20ab921be16b8263b1c7cadf6))

## [0.1.0](https://github.com/sanchezfauste/fauste-player-mobile/compare/3d0d7ee69cba6fa991ad066c2376af0388578ef5...v0.1.0) (2026-10-21)

The first release: a standalone, offline-first Android audio player —
the desktop project's Rust engine (model, store, decoding, mixer,
analysis, remote) reused unchanged through path dependencies, played
through a real AAudio backend, exposed to Kotlin over UniFFI, with a
Compose Multiplatform UI, a media3 foreground service, debounced
session persistence, crash reporting and Fluent i18n (en-US, es-ES).
Release verification — measurements, signing setup and the smoke run
against the R8-minified artifact — is recorded in
`docs/release/`.

The work was six phases, each merged to `main` as one reviewed pull
request; the entries below are grouped by phase. The phase plans in
`docs/superpowers/plans` hold the full engineering history (rulings and
deferrals included); test, ci and chore commits are not listed.

### Phase 0 — Foundation (2026-10-11 – 2026-10-12)

#### Features

* **backends-mobile:** aaudio stream config and error classification ([ebe38c6](https://github.com/sanchezfauste/fauste-player-mobile/commit/ebe38c6126b1e27c803eaed034479d4912d59ca4))
* **backends-mobile:** AAudio backend skeleton with availability checks ([a8c010f](https://github.com/sanchezfauste/fauste-player-mobile/commit/a8c010ff135264137960eb708d172d14fb1e0893))
* **mobile:** uniffi engine surface and generated kotlin bindings ([81db7cc](https://github.com/sanchezfauste/fauste-player-mobile/commit/81db7cc3932af3ccfdd7dd1223e832b803695240))

#### Bug Fixes

* **backends-mobile:** gate host-only constant to android target ([7a6fc47](https://github.com/sanchezfauste/fauste-player-mobile/commit/7a6fc47bea07936b60a55e45a1fea1ce8a0bd147))
* **mobile:** final-review fixes (desktop checkout in ci, jna-aar todo, xrun note, clippy uniffi lane) ([8fb8f86](https://github.com/sanchezfauste/fauste-player-mobile/commit/8fb8f8639d255be9032ca3de16a05fe190cb0c98))

#### Documentation

* **mobile:** design spec and phase 0 implementation plan ([3d0d7ee](https://github.com/sanchezfauste/fauste-player-mobile/commit/3d0d7ee69cba6fa991ad066c2376af0388578ef5))
* **mobile:** fix plan defects (unsafe-lint mechanism, uniffi proc-macro mode, relative paths) ([288b715](https://github.com/sanchezfauste/fauste-player-mobile/commit/288b715095f1e33c2adabf2619530ff9165c7a46))
* **mobile:** correct aaudio constant pins to NDK r27 values ([4b933a6](https://github.com/sanchezfauste/fauste-player-mobile/commit/4b933a6e8c3887b597a3ce9b8c1b2d5938b8a1fa))
* **mobile:** pin aaudio-sys 0.1 (0.2 does not exist on crates.io) ([dccea81](https://github.com/sanchezfauste/fauste-player-mobile/commit/dccea8101512919785ab675fcf5de7bd46ecb3fe))
* **mobile:** record phase 1 deferrals from the phase 0 final review ([e0970c4](https://github.com/sanchezfauste/fauste-player-mobile/commit/e0970c448eafedc0dc28ac40a6351b084061f5e8))

#### Build System

* **mobile:** workspace reusing the desktop crates by path ([8482ab3](https://github.com/sanchezfauste/fauste-player-mobile/commit/8482ab316dd97c1649c93b591938354bbac19748))
* **mobile:** kmp shared module and compose app skeleton with cargo ndk ([8f3af91](https://github.com/sanchezfauste/fauste-player-mobile/commit/8f3af91c0a04910360185d88b8fced5bcb1c9776))

### Phase 1 — AAudio audio backend (2026-10-12 – 2026-10-13)

#### Features

* **backends-mobile:** aaudio builder parameter mapping ([8466deb](https://github.com/sanchezfauste/fauste-player-mobile/commit/8466deb373c34a5518c0e0971c12afe885748ea8))
* **backends-mobile:** aaudio data callback core with one-slot renderer handoff ([7fc97b3](https://github.com/sanchezfauste/fauste-player-mobile/commit/7fc97b3150599c99bb97656f4f6a04ce66a6fc99))
* **backends-mobile:** real aaudio output stream with error and xrun reporting ([f7410be](https://github.com/sanchezfauste/fauste-player-mobile/commit/f7410be0e984578ec6936d676ea274f79bb52bad))

#### Bug Fixes

* **backends-mobile:** sound callback-box ownership and exact-buffer readback ([a4703e6](https://github.com/sanchezfauste/fauste-player-mobile/commit/a4703e68327e7732ecaba7144d55f37aad4efe55))
* **backends-mobile:** drop api-28 usage and content-type setters for the api-26 floor ([748ec4c](https://github.com/sanchezfauste/fauste-player-mobile/commit/748ec4cbb8670fef6d73275f621753acc572d0df))

#### Documentation

* **mobile:** phase 1 implementation plan (aaudio audio backend) ([e3090cc](https://github.com/sanchezfauste/fauste-player-mobile/commit/e3090ccb16d57a46a424adb56cd66fbe7682bbaf))
* **mobile:** correct phase 1 drop order in the plan (join monitor before close) ([fafcc0e](https://github.com/sanchezfauste/fauste-player-mobile/commit/fafcc0e809268578938e100be036f7f63b051cfd))
* **mobile:** phase 1 plan errata and phase 2 deferrals from the final review ([35d8d45](https://github.com/sanchezfauste/fauste-player-mobile/commit/35d8d45758078c349dd387a0505175c27266c98c))

#### Build System

* **mobile:** phase 0 final-review deferrals (jna aar, ktlint pin, buildRust caching) ([a9dc367](https://github.com/sanchezfauste/fauste-player-mobile/commit/a9dc3672fb8fe71c0c3adfcbf2857c7405a71f0e))

### Phase 2 — Rust core integration (2026-10-13)

#### Features

* **backends-mobile:** player core wiring store engine conductor analyzer ([3ea160c](https://github.com/sanchezfauste/fauste-player-mobile/commit/3ea160c043c0c9d4cca717917a7e4ec5d2809beb))
* **backends-mobile:** remote control and local server on the player core ([9b7718e](https://github.com/sanchezfauste/fauste-player-mobile/commit/9b7718e7dbdadb246badac03f8dd1e2914680510))
* **mobile:** real uniffi engine over the player core ([c77820a](https://github.com/sanchezfauste/fauste-player-mobile/commit/c77820a8f7f8d0e929cd9471c7e3d7b3a7f0167b))
* **mobile:** v2 bindings and decode smoke on android ([f3a8a48](https://github.com/sanchezfauste/fauste-player-mobile/commit/f3a8a48d3495ee3f9c440a510c9f58a62b42b8ef))

#### Bug Fixes

* **mobile:** set_volume reports a busy queue like its siblings ([4f38599](https://github.com/sanchezfauste/fauste-player-mobile/commit/4f3859977f7589d93d4397fe6ec20b71c40e2611))

#### Documentation

* **mobile:** phase 2 implementation plan (rust core integration) ([a5e0ee6](https://github.com/sanchezfauste/fauste-player-mobile/commit/a5e0ee6ba5c5aaa209b91e559beb5a83b5889b10))
* **mobile:** phase 2 plan deferrals from the final review ([5f9387f](https://github.com/sanchezfauste/fauste-player-mobile/commit/5f9387f60d10d5024f828c2a85c95e396a8009c1))

### Phase 3 — KMP shared and ViewModels (2026-10-14 – 2026-10-16)

#### Features

* **mobile:** ffi queue surface, real backend selection, remote status ([3c049c1](https://github.com/sanchezfauste/fauste-player-mobile/commit/3c049c1fea92aaea15fc6242689699cfcc412800))
* **mobile:** platform abstractions and koin skeleton in shared ([41ba357](https://github.com/sanchezfauste/fauste-player-mobile/commit/41ba357f63e28fa6c58a9707a5c36a04379a31a9))
* **mobile:** player controller queue manager and playback flows ([dc4c99a](https://github.com/sanchezfauste/fauste-player-mobile/commit/dc4c99a7131645d01e340aef595736a92bd1fcc6))
* **mobile:** repositories remote wrappers and complete koin graph ([9eaae75](https://github.com/sanchezfauste/fauste-player-mobile/commit/9eaae758df359789a9d4fdf68ef8f065a5cb4af5))

#### Bug Fixes

* **mobile:** propagate cancellation and clean partial imports in storage ([c7af6a0](https://github.com/sanchezfauste/fauste-player-mobile/commit/c7af6a0f65500234d852b1b7f72aca8c1907fa9f))
* **mobile:** guard the whole storage import for the null-on-failure contract ([e8dd788](https://github.com/sanchezfauste/fauste-player-mobile/commit/e8dd7882d06a1a12da8a1672e7d98716afdb7d66))

#### Documentation

* **mobile:** phase 3 implementation plan (kmp shared and viewmodels) ([c897acd](https://github.com/sanchezfauste/fauste-player-mobile/commit/c897acddc32a4c1de345e35db05acfd8e3adc1ed))
* **mobile:** correct the queue-jump kdoc and regenerate bindings ([3a15900](https://github.com/sanchezfauste/fauste-player-mobile/commit/3a15900b549ef069ce8d4577ed5ae22d9717360c))
* **mobile:** phase 3 plan deferrals from the final review ([9cb8976](https://github.com/sanchezfauste/fauste-player-mobile/commit/9cb89764737053a203c8e8554a285a8a32feea24))

#### Build System

* **mobile:** gradle builds the host cdylib before engine tests ([8c9002a](https://github.com/sanchezfauste/fauste-player-mobile/commit/8c9002a8a2369bca9547405f365726fe593d9cf1))

### Phase 4 — Compose UI (2026-10-16 – 2026-10-18)

#### Features

* **mobile:** nocturne theme and base player components ([1adf215](https://github.com/sanchezfauste/fauste-player-mobile/commit/1adf215c95e971d9eb22d298dea0ec4037b00d6f))
* **mobile:** set_next ffi surface and queue jump ([a616840](https://github.com/sanchezfauste/fauste-player-mobile/commit/a6168402ac98d27fb1173574eeb8eb92ab419fa7))
* **mobile:** app shell navigation player viewmodel and player screen ([0110b07](https://github.com/sanchezfauste/fauste-player-mobile/commit/0110b07512d67cdd843e49d81863a0e9d4a719ba))
* **mobile:** library screen with search and saf import ([9f9a4fe](https://github.com/sanchezfauste/fauste-player-mobile/commit/9f9a4feea8795728cbb764e58bdd12ffb6e75c23))
* **mobile:** settings screen and lifecycle-aware poll idling ([7e1893a](https://github.com/sanchezfauste/fauste-player-mobile/commit/7e1893ab27c228c0f28ce3373dfabe79ab0ab72e))
* **mobile:** media session foreground service and accessibility pass ([e07dba1](https://github.com/sanchezfauste/fauste-player-mobile/commit/e07dba1febb8b80be7d638a9dbf865611565d68d))

#### Bug Fixes

* **mobile:** publish media3 session state on the main thread ([a0229e1](https://github.com/sanchezfauste/fauste-player-mobile/commit/a0229e1911c26f89ad4bd0ec69ec628597580c28))
* **mobile:** safe stop dance service keep-awake gate and smoke fixes ([0b38cf2](https://github.com/sanchezfauste/fauste-player-mobile/commit/0b38cf282cc6b628b578b15e38b08cc7ac7e9860))
* **mobile:** guard the service start path and notification intent ([39ed060](https://github.com/sanchezfauste/fauste-player-mobile/commit/39ed060b02d6cbe149764d119e71f93940c50867))

#### Documentation

* **mobile:** phase 4 implementation plan (compose ui mvp) ([edb86e9](https://github.com/sanchezfauste/fauste-player-mobile/commit/edb86e99dfaaab892a566b8b91628e3b89cbe63e))
* **mobile:** phase 4 plan deferrals from the final review ([35f35dc](https://github.com/sanchezfauste/fauste-player-mobile/commit/35f35dc531429402cfb67714ccde87c8b671f050))

### Phase 5 — Polish and release (2026-10-18 – 2026-10-21)

#### Features

* **mobile:** entry id and armed reads across the ffi ([1eab99a](https://github.com/sanchezfauste/fauste-player-mobile/commit/1eab99aae0af03e2e2d090ea029b63315368fb39))
* **mobile:** audio focus handling in the playback service ([7ba7155](https://github.com/sanchezfauste/fauste-player-mobile/commit/7ba71552bb83282e911b5e4627e4e2b9b025ccd5))
* **mobile:** lifecycle-aware collection and honest restart semantics ([55d1566](https://github.com/sanchezfauste/fauste-player-mobile/commit/55d156608851ff1e4c54f5edb8245afca0d9314f))
* **mobile:** debounced session persistence for the player core ([c154991](https://github.com/sanchezfauste/fauste-player-mobile/commit/c154991af8cff5971bd4326ac0cd9a082487d178))
* **mobile:** crash reporting hooks rust and kotlin ([035df1c](https://github.com/sanchezfauste/fauste-player-mobile/commit/035df1c5711888eb1249c9e2448526d6183478fc))
* **mobile:** fluent i18n with en us and es es ([0104237](https://github.com/sanchezfauste/fauste-player-mobile/commit/01042378d2a079a9c1480ba8e059416f71cd5991))

#### Bug Fixes

* **mobile:** close the duck stale-read race and complete the focus matrix pins ([e011eeb](https://github.com/sanchezfauste/fauste-player-mobile/commit/e011eebbeb212ab335b0004abc9db40b7f56ee1b))

#### Documentation

* **mobile:** phase 5 implementation plan (polish and release) ([ad0e485](https://github.com/sanchezfauste/fauste-player-mobile/commit/ad0e485744cbe798764a666105a42193f06896b0))
* **mobile:** add task 3b session persistence to the phase 5 plan ([e577f19](https://github.com/sanchezfauste/fauste-player-mobile/commit/e577f19d7a3e0f2a16b4791d07d74c867660cb50))
* **mobile:** correct the armed-id test comment on the queue tail shape ([c27afa6](https://github.com/sanchezfauste/fauste-player-mobile/commit/c27afa6780252fc9ab0d4bb660c628212617c120))
* **mobile:** release measurements user guide readme and signing setup ([529916b](https://github.com/sanchezfauste/fauste-player-mobile/commit/529916bf512547dd4acc44f84ac0fa51fa9a4a11))
* **mobile:** post-release backlog from the phase 5 final review ([7981dfc](https://github.com/sanchezfauste/fauste-player-mobile/commit/7981dfcd1fe73b5e26b2456f5c3f5df765c30c1f))

#### Build System

* **mobile:** release build wiring and proguard rules ([3defe96](https://github.com/sanchezfauste/fauste-player-mobile/commit/3defe96fda508f03bce64a8d1ec8197293ce2169))
