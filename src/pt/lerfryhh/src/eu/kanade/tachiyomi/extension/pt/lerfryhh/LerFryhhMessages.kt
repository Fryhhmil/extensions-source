package eu.kanade.tachiyomi.extension.pt.lerfryhh

/**
 * Textos exibidos ao usuário. A fonte original era multi-idioma e carregava os textos
 * de `assets/i18n`; este fork é só pt-BR, então eles ficam aqui direto.
 */
object LerFryhhMessages {
    const val LOGIN_FAILED = "Falha ao entrar no servidor."
    const val LOGIN_TOKEN_EMPTY = "Erro: o token de autenticação está vazio.\nAbra a extensão primeiro."
    const val LOGIN_INVALID_URL = "URL inválida"
    const val LOGIN_TOKEN_PARSE = "Erro ao interpretar o token de autenticação."

    const val PREF_CUSTOM_SOURCE_TITLE = "Nome exibido da fonte"
    const val PREF_CUSTOM_SOURCE_SUMMARY =
        "Nome descritivo para identificar este servidor na lista de fontes."
    const val PREF_FILTERS_TITLE = "Filtros exibidos"
    const val PREF_FILTERS_SUMMARY = "Escolha quais filtros aparecem na busca."
    const val PREF_OPDS_SUMMARY =
        "URL OPDS copiada das configurações do usuário no Kavita. " +
            "Inclui o endereço do servidor e termina com a API key."
    const val PREF_OPDS_BAD_URL =
        "Endereço OPDS incorreto. Copie em Configurações do usuário → Apps de terceiros → URL OPDS."
    const val PREF_OPDS_DUPLICATED = "Esta URL já está configurada em outra fonte"
    const val PREF_OPDS_MISSING_ADDRESS = "Configure o endereço OPDS do servidor nas configurações da fonte."

    const val RESTART_APP = "Reinicie o app para aplicar a configuração."

    const val CHAPTERS_PARSE_ERROR =
        "Erro inesperado ao interpretar os capítulos. Confira os logs do app."
    const val SMART_FILTER_ERROR =
        "Não foi possível decodificar o filtro inteligente. Requer Kavita 0.7.11 ou mais recente."
    const val CHECK_VERSION =
        "Confira se o servidor Kavita está atualizado (0.7.8 ou mais recente)."

    fun httpError(code: Any?): String = when (code) {
        401 -> "Erro ao entrar. Tente novamente ou recarregue o app."
        else -> "Algo deu errado."
    }
}
