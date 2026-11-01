# Manga Livre — toggle "interface de download em lotes"

Data: 2026-07-07 · Arquivo alvo: `src/pt/mangalivre/.../MangaLivre.kt`

## Objetivo
Permitir que "Abrir na WebView" da tela de detalhes da obra abra uma interface web
de download em lotes (`/download-ui/<slug>`) num host configurável, em vez da
página normal do toonlivre.

## Preferências novas (setupPreferenceScreen)
1. `SwitchPreferenceCompat` — key `downloadUiEnabledPref`, título "Abrir interface
   de download em lotes", default `false`.
2. `EditTextPreference` — key `downloadUiUrlPref`, título "URL da interface de
   download", default `""`. Summary mostra o valor atual (SimpleSummaryProvider).
   Usuário digita só o host base, ex.: `http://192.168.0.10:8080`.

## Getters
- `downloadUiEnabled: Boolean` = `preferences.getBoolean(DOWNLOAD_UI_ENABLED_PREF, false)`
- `downloadUiBaseUrl: String` = `preferences.getString(DOWNLOAD_UI_URL_PREF, "").orEmpty().trim()`

## Lógica (único ponto de comportamento tocado)
```kotlin
override fun getMangaUrl(manga: SManga): String {
    val base = downloadUiBaseUrl
    return if (downloadUiEnabled && base.isNotBlank()) {
        "${base.trimEnd('/')}/download-ui/${manga.url}"
    } else {
        "$baseUrl/${manga.url}"
    }
}
```
`manga.url` já é o slug/id (ex.: `obra-c1f65a0b`).

## Regras
- Toggle OFF **ou** URL vazia → comportamento atual (`$baseUrl/${manga.url}`). Nunca
  fica sem abrir nada.
- Sem validação de formato da URL (só `trim` + `trimEnd('/')`).

## Fora de escopo
- `getChapterUrl` (WebView de capítulo) inalterado.
- Nenhuma mudança no scraper de token nem nos requests de API.

## Verificação
- `./gradlew :src:pt:mangalivre:compileDebugKotlin -q` → EXIT 0.
- Conferência lógica de `getMangaUrl` nos 3 casos (off / on+vazio / on+preenchido).
