# Changelog

## [0.1.40](https://github.com/XOlegator/kalita/compare/v0.1.39...v0.1.40) (2026-11-04)


### Bug Fixes

* **packaging:** add libwayland-dev to .deb Build-Depends for noble ([#99](https://github.com/XOlegator/kalita/issues/99)) ([91c96dc](https://github.com/XOlegator/kalita/commit/91c96dc81770f580ab5034f9a794c527a0c5ba21))

## [0.1.39](https://github.com/XOlegator/kalita/compare/v0.1.38...v0.1.39) (2026-11-04)


### Features

* **packaging,agents:** AppCDS base archive + Claude Code plugin ([#97](https://github.com/XOlegator/kalita/issues/97)) ([d103764](https://github.com/XOlegator/kalita/commit/d1037648f16bc5592c84ea2edd97e8db6b03ddb5))

## [0.1.38](https://github.com/XOlegator/kalita/compare/v0.1.37...v0.1.38) (2026-11-04)


### Features

* **android,ci,packaging:** learning Android APK, user guide, agent artifacts + attestations ([#95](https://github.com/XOlegator/kalita/issues/95)) ([fa979ed](https://github.com/XOlegator/kalita/commit/fa979ed48992b84ec697d3812a12a28ac3ea1ef9))

## [0.1.37](https://github.com/XOlegator/kalita/compare/v0.1.36...v0.1.37) (2026-11-04)


### Bug Fixes

* **build-logic:** raise jackson-core/jackson-databind security floor to 2.22.4 ([#93](https://github.com/XOlegator/kalita/issues/93)) ([5be7cb8](https://github.com/XOlegator/kalita/commit/5be7cb8f27d98c5ada4e82aebefcc510b7efbf27))

## [0.1.36](https://github.com/XOlegator/kalita/compare/v0.1.35...v0.1.36) (2026-11-04)


### Features

* **ci,packaging:** build and attach Linux/Windows release artifacts ([#91](https://github.com/XOlegator/kalita/issues/91)) ([60d6a47](https://github.com/XOlegator/kalita/commit/60d6a4759d1a90381063b96f21bf34e0d750b2f3))

## [0.1.35](https://github.com/XOlegator/kalita/compare/v0.1.34...v0.1.35) (2026-11-04)


### Features

* **agent-api,cli:** elicitation-based confirmation for add_operations writes ([#89](https://github.com/XOlegator/kalita/issues/89)) ([377501b](https://github.com/XOlegator/kalita/commit/377501be2bbee24f0b9ef888c99cfc19bde3958f))

## [0.1.34](https://github.com/XOlegator/kalita/compare/v0.1.33...v0.1.34) (2026-11-02)


### Bug Fixes

* **desktop:** bundle Qt's Wayland client plugin and enable native JVM access ([#87](https://github.com/XOlegator/kalita/issues/87)) ([0024af8](https://github.com/XOlegator/kalita/commit/0024af84511df7fb181a4b58173f382682f98879))


### Build System

* **deps:** raise vulnerable guava transitive dependency to 33.7.2-jre ([#86](https://github.com/XOlegator/kalita/issues/86)) ([8e71640](https://github.com/XOlegator/kalita/commit/8e7164095dd691d8a8d59866821cb9cf22a62b81))

## [0.1.33](https://github.com/XOlegator/kalita/compare/v0.1.32...v0.1.33) (2026-11-02)


### Bug Fixes

* **ci:** use dh_shlibdeps --ignore-missing-info instead of ineffective -x ([#84](https://github.com/XOlegator/kalita/issues/84)) ([fa7f4b4](https://github.com/XOlegator/kalita/commit/fa7f4b4756cd5602d33b51a7805e5f58f6043efb))

## [0.1.32](https://github.com/XOlegator/kalita/compare/v0.1.31...v0.1.32) (2026-11-02)


### Bug Fixes

* **ci:** exclude libqt6gui6 from dh_shlibdeps for the private XcbQpa lib ([#82](https://github.com/XOlegator/kalita/issues/82)) ([7d6c649](https://github.com/XOlegator/kalita/commit/7d6c6496b27c68a931c0aeaf7d0a98b433feceb7))

## [0.1.31](https://github.com/XOlegator/kalita/compare/v0.1.30...v0.1.31) (2026-11-02)


### Bug Fixes

* **ci:** add missing shlibdeps Build-Depends and PPA upload race delay ([#79](https://github.com/XOlegator/kalita/issues/79)) ([53ce049](https://github.com/XOlegator/kalita/commit/53ce049c49608acfbc25e410df4739d492bef191))


### Tests

* **android:** surface the real error when scenario82's device-side copy fails ([#81](https://github.com/XOlegator/kalita/issues/81)) ([8401c53](https://github.com/XOlegator/kalita/commit/8401c531e5f8a050d9afdae151b6b0ffe2c8f38c))

## [0.1.30](https://github.com/XOlegator/kalita/compare/v0.1.29...v0.1.30) (2026-11-01)


### Bug Fixes

* **ci:** install libasound2-dev on the GH Actions runner for dpkg-buildpackage -S ([#77](https://github.com/XOlegator/kalita/issues/77)) ([f80efb5](https://github.com/XOlegator/kalita/commit/f80efb5f9316f8587ef8dd53e39a1f6c67e9a58b))

## [0.1.29](https://github.com/XOlegator/kalita/compare/v0.1.28...v0.1.29) (2026-11-01)


### Bug Fixes

* **ci:** add qt6-base-dev to Build-Depends so dh_shlibdeps can resolve ([#75](https://github.com/XOlegator/kalita/issues/75)) ([3380b72](https://github.com/XOlegator/kalita/commit/3380b72cf5e9ec2fd82e9b5f566aedb498d7df32))

## [0.1.28](https://github.com/XOlegator/kalita/compare/v0.1.27...v0.1.28) (2026-11-01)


### Bug Fixes

* **ci:** stop base64-decoding LAUNCHPAD_GPG_PRIVATE_KEY before import ([#73](https://github.com/XOlegator/kalita/issues/73)) ([01d36f2](https://github.com/XOlegator/kalita/commit/01d36f235769b6b4281aff2f5dd2c7ef769af4dc))

## [0.1.27](https://github.com/XOlegator/kalita/compare/v0.1.26...v0.1.27) (2026-11-01)


### Bug Fixes

* **ci:** install build-essential and libdistro-info-perl in ppa-build ([#71](https://github.com/XOlegator/kalita/issues/71)) ([936f723](https://github.com/XOlegator/kalita/commit/936f7237ac63c06b1619db3eeb9e9d7cf7580589))
* **ci:** override lintian's source-is-missing for bundled Qt/JRE binaries ([#72](https://github.com/XOlegator/kalita/issues/72)) ([87a02db](https://github.com/XOlegator/kalita/commit/87a02db6567123c813fb23f98386fdd75bd0444a))
* **ci:** use linux_gcc_64 arch id for aqt install-qt in ppa-build ([#69](https://github.com/XOlegator/kalita/issues/69)) ([aa4cd01](https://github.com/XOlegator/kalita/commit/aa4cd01edea43f78608c112b88219a33b7a24a2b))

## [0.1.26](https://github.com/XOlegator/kalita/compare/v0.1.25...v0.1.26) (2026-11-01)


### Features

* **desktop:** .deb packaging for the xolegator/packages PPA ([#67](https://github.com/XOlegator/kalita/issues/67)) ([c131481](https://github.com/XOlegator/kalita/commit/c1314819d21f212b6482394249ab77dc81c4f296))

## [0.1.25](https://github.com/XOlegator/kalita/compare/v0.1.24...v0.1.25) (2026-11-01)


### Features

* **agent-api,cli:** MCP Resources and Prompts for kalita mcp ([#64](https://github.com/XOlegator/kalita/issues/64)) ([a66cca2](https://github.com/XOlegator/kalita/commit/a66cca2cb840696e3a190b7c9a740f97f48198d9))

## [0.1.24](https://github.com/XOlegator/kalita/compare/v0.1.23...v0.1.24) (2026-11-01)


### Bug Fixes

* **deps:** update okhttp, kotlinx-coroutines, AGP and MCP Kotlin SDK ([#63](https://github.com/XOlegator/kalita/issues/63)) ([462bcfa](https://github.com/XOlegator/kalita/commit/462bcfa474c0f5ed9e64a3fbcaf70cf53ac85aea))

## [0.1.23](https://github.com/XOlegator/kalita/compare/v0.1.22...v0.1.23) (2026-10-26)


### Features

* **agent-api,cli:** add undo_changes, the last §12.13 agent tool ([#59](https://github.com/XOlegator/kalita/issues/59)) ([aa3dc23](https://github.com/XOlegator/kalita/commit/aa3dc23c266f2d44fdf07b6f47db10833a634a5c))

## [0.1.22](https://github.com/XOlegator/kalita/compare/v0.1.21...v0.1.22) (2026-10-25)


### Features

* **agent-api,desktop,cli:** agent connection settings (mode, limits) ([#57](https://github.com/XOlegator/kalita/issues/57)) ([8372340](https://github.com/XOlegator/kalita/commit/83723402bfdb46c23eefb58a608f91e912c4b030))

## [0.1.21](https://github.com/XOlegator/kalita/compare/v0.1.20...v0.1.21) (2026-10-25)


### Features

* **storage-local,agent-api,desktop:** local socket channel to a running desktop instance ([#55](https://github.com/XOlegator/kalita/issues/55)) ([bcf0938](https://github.com/XOlegator/kalita/commit/bcf0938a27d77f132629ffae461ebccacab311c9))

## [0.1.20](https://github.com/XOlegator/kalita/compare/v0.1.19...v0.1.20) (2026-10-25)


### Features

* **agent-api:** write-mode-only agent tools — update/delete/restore operation, add dictionary item ([#53](https://github.com/XOlegator/kalita/issues/53)) ([e6d1b3a](https://github.com/XOlegator/kalita/commit/e6d1b3a6eaa929445e3e80b15743c399f845f246))

## [0.1.19](https://github.com/XOlegator/kalita/compare/v0.1.18...v0.1.19) (2026-10-24)


### Features

* **app-cli:** add kalita mcp — stdio MCP server over agent-api tools ([#51](https://github.com/XOlegator/kalita/issues/51)) ([7baebf1](https://github.com/XOlegator/kalita/commit/7baebf14b8cb199e59085c0c17e5c87f8c6bdadf))

## [0.1.18](https://github.com/XOlegator/kalita/compare/v0.1.17...v0.1.18) (2026-10-24)


### Features

* **desktop:** panels and layout — information panel, column pinning, named views (stage 7) ([#49](https://github.com/XOlegator/kalita/issues/49)) ([a2883b8](https://github.com/XOlegator/kalita/commit/a2883b84356169dd239d8e6f86645a6a94636dfd))

## [0.1.17](https://github.com/XOlegator/kalita/compare/v0.1.16...v0.1.17) (2026-10-24)


### Features

* **desktop:** multi-sort, filter panel, quick filter, search, and saved filters (stage 6) ([#47](https://github.com/XOlegator/kalita/issues/47)) ([3a83db2](https://github.com/XOlegator/kalita/commit/3a83db2fa916388933aff5c4cebca69c4b8ff959))

## [0.1.16](https://github.com/XOlegator/kalita/compare/v0.1.15...v0.1.16) (2026-10-23)


### Features

* **desktop:** income/expense journal grid, operation form, and dock panels (stage 5) ([#45](https://github.com/XOlegator/kalita/issues/45)) ([d6bc4f8](https://github.com/XOlegator/kalita/commit/d6bc4f8faf07f46b9de9a1fad2e0604262b156c3))

## [0.1.15](https://github.com/XOlegator/kalita/compare/v0.1.14...v0.1.15) (2026-10-23)


### Features

* **desktop:** dictionary browsers, rate panel, and CRUD operations (stage 4) ([#43](https://github.com/XOlegator/kalita/issues/43)) ([7820b8a](https://github.com/XOlegator/kalita/commit/7820b8abb264fd25aba7bc88c1bf9e36682638b7))

## [0.1.14](https://github.com/XOlegator/kalita/compare/v0.1.13...v0.1.14) (2026-10-23)


### Features

* **desktop:** application shell — AppSettings, AppController, MainWindow (stage 3) ([#41](https://github.com/XOlegator/kalita/issues/41)) ([c31987c](https://github.com/XOlegator/kalita/commit/c31987c7f105b224ffa03a9253b2eb88002ec4d7))

## [0.1.13](https://github.com/XOlegator/kalita/compare/v0.1.12...v0.1.13) (2026-10-22)


### Features

* **core:** AuditStamp, ChangeSetBuilder, form rules, and presentation (stage 2) ([#39](https://github.com/XOlegator/kalita/issues/39)) ([f723330](https://github.com/XOlegator/kalita/commit/f723330c186f32c2fd57cfb8b54dee9be21ca5b4))

## [0.1.12](https://github.com/XOlegator/kalita/compare/v0.1.11...v0.1.12) (2026-10-22)


### Features

* **core:** budget tree mutations — move up/down, reparent, root protection (stage 2) ([#37](https://github.com/XOlegator/kalita/issues/37)) ([b7fa50f](https://github.com/XOlegator/kalita/commit/b7fa50fe459f24d5a189111e178d216b054c1faf))

## [0.1.11](https://github.com/XOlegator/kalita/compare/v0.1.10...v0.1.11) (2026-10-22)


### Features

* **core:** journal query with serializable filter and multi-sort (stage 2) ([#35](https://github.com/XOlegator/kalita/issues/35)) ([92ffef4](https://github.com/XOlegator/kalita/commit/92ffef45798ccbce6351ded46e873a48f4106a3d))

## [0.1.10](https://github.com/XOlegator/kalita/compare/v0.1.9...v0.1.10) (2026-10-20)


### Bug Fixes

* **deps:** update Jackcess, kotlinx.serialization, kotest, Gradle, and build-tool floors ([#33](https://github.com/XOlegator/kalita/issues/33)) ([636681e](https://github.com/XOlegator/kalita/commit/636681ee89c72397c42062395c6e185105b2ca6e))

## [0.1.9](https://github.com/XOlegator/kalita/compare/v0.1.8...v0.1.9) (2026-10-20)


### Features

* **core:** domain entities, exchange rates, journal columns and balances (stage 2) ([#31](https://github.com/XOlegator/kalita/issues/31)) ([4e60007](https://github.com/XOlegator/kalita/commit/4e60007413039146b7f99911383a96c468cdcae3))

## [0.1.8](https://github.com/XOlegator/kalita/compare/v0.1.7...v0.1.8) (2026-10-18)


### Features

* **storage:** build-time switch to the upstream Jackcess line (stage 1) ([#27](https://github.com/XOlegator/kalita/issues/27)) ([0b48cb5](https://github.com/XOlegator/kalita/commit/0b48cb585b71328eb56d66668c91d07a8c9ade76))

## [0.1.7](https://github.com/XOlegator/kalita/compare/v0.1.6...v0.1.7) (2026-10-13)


### Features

* **storage:** storage session, dev CLI and property-based tests (stage 1) ([#24](https://github.com/XOlegator/kalita/issues/24)) ([9d6e502](https://github.com/XOlegator/kalita/commit/9d6e5028c1941ef4bd6ab86a26cfad990aded918))


### Build System

* raise vulnerable build-tool dependencies of AGP and Android Lint ([#23](https://github.com/XOlegator/kalita/issues/23)) ([e4f7fbf](https://github.com/XOlegator/kalita/commit/e4f7fbfce1dd1b928992c03f77d608a08036218a))

## [0.1.6](https://github.com/XOlegator/kalita/compare/v0.1.5...v0.1.6) (2026-10-12)


### Features

* **storage:** change sets, atomic commit, local lock/backup/history (stage 1) ([#21](https://github.com/XOlegator/kalita/issues/21)) ([5d7852f](https://github.com/XOlegator/kalita/commit/5d7852f255ed32f17ffe6ae9a05ee1a642cd41af))

## [0.1.5](https://github.com/XOlegator/kalita/compare/v0.1.4...v0.1.5) (2026-10-11)


### Features

* Jackcess-on-Android write scenario + Jet oracle cross-check (stage 0.4) ([#19](https://github.com/XOlegator/kalita/issues/19)) ([97f0a89](https://github.com/XOlegator/kalita/commit/97f0a89222757092197a5204c1117faed6523fb7))

## [0.1.4](https://github.com/XOlegator/kalita/compare/v0.1.3...v0.1.4) (2026-10-11)


### Features

* jpackage MSI and AppImage installer PoC (stage 0.3) ([#17](https://github.com/XOlegator/kalita/issues/17)) ([562636f](https://github.com/XOlegator/kalita/commit/562636f1456de43e54ae7eabbfdac3144b9adc3e))


### Continuous Integration

* gate release-please on actual feat/fix/breaking commits ([#15](https://github.com/XOlegator/kalita/issues/15)) ([b73ef6c](https://github.com/XOlegator/kalita/commit/b73ef6c326835f8bd36330ca3dff9031f068de97))


### Chores

* add repo governance templates (stage 0.5) ([#16](https://github.com/XOlegator/kalita/issues/16)) ([9d058f6](https://github.com/XOlegator/kalita/commit/9d058f630acfe14536faf6751f43a442dbf56629))

## [0.1.3](https://github.com/XOlegator/kalita/compare/v0.1.2...v0.1.3) (2026-10-11)


### Documentation

* add ADR-0001..0008 ([#13](https://github.com/XOlegator/kalita/issues/13)) ([f8bee48](https://github.com/XOlegator/kalita/commit/f8bee48131186b13b5636239f9b635bc2b40f70e))


### Chores

* add REUSE/SPDX compliance (stage 0.5) ([#12](https://github.com/XOlegator/kalita/issues/12)) ([3ef7464](https://github.com/XOlegator/kalita/commit/3ef746421ea7585964b9c642a140afd084db4449))
* remove X-Cash screenshots from research tools ([#11](https://github.com/XOlegator/kalita/issues/11)) ([99be525](https://github.com/XOlegator/kalita/commit/99be5259bc45f63bffc899e00f0d7e34794f1cf5))

## [0.1.2](https://github.com/XOlegator/kalita/compare/v0.1.1...v0.1.2) (2026-10-11)


### Features

* add QtJambi desktop window reading storage-mdb (stage 0.2) ([#9](https://github.com/XOlegator/kalita/issues/9)) ([b61e24b](https://github.com/XOlegator/kalita/commit/b61e24bebea974bb068b15b43611be6af5ebfd72))

## [0.1.1](https://github.com/XOlegator/kalita/compare/v0.1.0...v0.1.1) (2026-10-11)


### Features

* scaffold Gradle multi-module project (stage 0.1) ([#7](https://github.com/XOlegator/kalita/issues/7)) ([96fdaad](https://github.com/XOlegator/kalita/commit/96fdaad8add9cfc86e7d983c9f677ca14ca534aa))

## 0.1.0 (2026-10-11)


### Continuous Integration

* use RELEASE_PLEASE_TOKEN PAT for release-please PR creation ([#3](https://github.com/XOlegator/kalita/issues/3)) ([3df3804](https://github.com/XOlegator/kalita/commit/3df38046821648acb95fec6f5c1ed78d076d2c8a))


### Chores

* **release:** keep releases on 0.x versioning pre-1.0 ([#5](https://github.com/XOlegator/kalita/issues/5)) ([a6e7307](https://github.com/XOlegator/kalita/commit/a6e73079ca02c072f315e0f15a03aceb0239686b))
* **release:** pin first release to 0.1.0 ([#6](https://github.com/XOlegator/kalita/issues/6)) ([669c8a5](https://github.com/XOlegator/kalita/commit/669c8a56acaf91065f4bf1c58e4bba59af3bcd0a))
* **research:** drop old yCash name from QtJambi prototype window title ([#2](https://github.com/XOlegator/kalita/issues/2)) ([4ac121d](https://github.com/XOlegator/kalita/commit/4ac121d9c09c0737b6b637a44e72cba5e5cf59d7))
* set up PR-only workflow, release automation, and changelog rules ([#1](https://github.com/XOlegator/kalita/issues/1)) ([816c07d](https://github.com/XOlegator/kalita/commit/816c07dcc01ad8dc00f4500847e06bf4ceee1cfe))

## Changelog

All notable changes to this project are tracked here by release automation
(release-please). See [docs/releasing.md](docs/releasing.md) for how versions,
releases, and this file are generated.
