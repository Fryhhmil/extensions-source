# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this repo is

Keiyoushi's monorepo of **Mihon/Tachiyomi manga source extensions** — one Android
library module per source, each compiled into its own APK. Extensions are Kotlin
classes extending `HttpSource` (or `SourceFactory`) that scrape/consume a manga
site and map results to the app's `SManga` / `SChapter` / `Page` models. The app
provides the runtime; extensions only implement the source-specific request/parse
logic.

`CONTRIBUTING.md` is the authoritative, detailed guide (extension anatomy, the full
`HttpSource` call flow, `keiyoushi.utils` helpers, per-topic conventions). Read the
relevant section there before non-trivial work — this file is only the orientation
layer.

## Layout

- `src/<lang>/<source>/` — one extension. `<lang>` is ISO 639-1 (or `all` for
  multi-language). Code lives under `.../src/eu/kanade/tachiyomi/extension/<lang>/<source>/`,
  package `eu.kanade.tachiyomi.extension.<lang>.<source>`.
- `lib/<name>/` — shared helper modules (package `keiyoushi.lib.<name>`): crypto,
  cookie injection, JS deobfuscation, image processing, etc. Check here before
  writing something generic from scratch.
- `lib-multisrc/<theme>/` — "themes": base classes shared by many sites built on
  the same CMS. An extension opts in via `themePkg` in its `build.gradle.kts`.
- `core/` — the `keiyoushi.utils` extension functions available to every extension
  with no extra dependency (`parseAs`, `getPreferencesLazy`, `tryParse`,
  `firstInstance`, `rateLimit`, etc.). Prefer these over hand-rolled equivalents.
- `gradle/build-logic/` — the custom Gradle plugins. `kei.plugins.extension`
  provides the `keiyoushi { }` DSL used in each extension's `build.gradle.kts`;
  it also generates the `AndroidManifest`, `UrlActivity`, and source metadata.
- `gradle/kei.versions.toml` — the `kei` version catalog (plugins). App-exposed
  dependencies are in `gradle/libs.versions.toml`.

## Commands

Gradle project paths mirror the folder tree: `:src:<lang>:<source>`. This is a
Windows environment; use `./gradlew` via the Bash tool.

```bash
# Build one extension's debug APK (output: src/<lang>/<source>/build/outputs/apk/debug/)
./gradlew :src:pt:mangalivre:assembleDebug

# Format check / auto-format one module (Spotless + ktlint — CI enforces this)
./gradlew :src:pt:mangalivre:spotlessCheck
./gradlew :src:pt:mangalivre:spotlessApply

# List a module's tasks
./gradlew :src:pt:mangalivre:tasks --all
```

By default `settings.gradle.kts` loads **every** extension (`loadAllIndividualExtensions()`),
which is slow. To work on one source, edit `settings.gradle.kts`: comment out
`loadAllIndividualExtensions()` and uncomment/add
`loadIndividualExtension("<lang>", "<source>")`. `lib/` and `lib-multisrc/` always
load.

## Conventions that will bite you

- **Bump `versionCode`** in the extension's `build.gradle.kts` for any user-facing
  code change (skip only for no-op refactors). The version name is derived as
  `libVersion` + version code.
- **Spotless/ktlint is enforced.** After editing, expect the linter to reformat
  (e.g. wrapping long expressions). Run `spotlessApply` or let it; don't fight it.
- **DTOs:** `@Serializable` regular `class` (not `data class`); camelCase fields;
  `@SerialName` only when the JSON key differs; map only fields you use; no defaults
  on mandatory fields (let parsing fail loudly). Put `toSManga()`/`toSChapter()`
  mapping in the DTO file, not the main class. Parse with `response.parseAs<T>()`.
- **Networking:** every `GET()`/`POST()` passes `headers`; `Referer` to site root
  keeps its trailing slash; use `network.client.newBuilder()` and the
  `keiyoushi.network.rateLimit` builder (never `Thread.sleep`); don't make
  synchronous `client.newCall(...).execute()` calls inside `*Parse` methods —
  do the request in the corresponding `*Request` override, except inside interceptors.
- **Don't manually handle Cloudflare** in parsers; the app solves challenges before
  calling them. Store slugs/IDs (not absolute URLs) in `SManga.url`/`SChapter.url`.
- `versionId` is different from `versionCode`: bump it **only** when the source's
  URL structure fundamentally changes (it forces every user to re-migrate).

## Example: the MangaLivre / ToonLivre source (`src/pt/mangalivre/`)

A representative API-based source that also shows the harder patterns:
`MangaLivre.kt` reverse-engineers the site's anti-scraping scheme in interceptors —
a per-request signature header (name auto-discovered from the site's JS bundle,
value fetched from `/api/seed`) plus response bodies encrypted with CryptoJS Rabbit,
decrypted in `RabbitCipher.kt`. When such a scheme rotates, capture a HAR of the
site and diff it against the extension; the passphrase/header derivation is scraped
from the live JS bundle so it self-heals across releases.
