package io.github.murphy955.rustreader.extract

import java.nio.charset.StandardCharsets

object SymbolMatcher {
    fun score(
        symbol: String,
        crateName: String,
        modulePath: List<String>,
        itemName: String,
        implSelfType: String? = null,
        isClosure: Boolean = false,
    ): Int {
        val itemHasToken = hasToken(symbol, itemName)
        if (!itemHasToken && !symbol.contains(itemName)) return 0

        var score = if (itemHasToken) 50 else 10

        if (hasToken(symbol, crateName)) score += 100

        var cursor = 0
        for (segment in modulePath) {
            val index = symbol.indexOf(token(segment), cursor)
            if (index >= 0) {
                score += 25
                cursor = index + token(segment).length
            }
        }

        if (implSelfType != null) {
            val typeName = simpleTypeName(implSelfType)
            if (typeName.isNotEmpty() && hasToken(symbol, typeName)) score += 80
        }

        if (!isClosure) {
            if (symbol.contains("NCN")) score -= 180
            if (symbol.startsWith("_RNCN")) score -= 60
            if (symbol.contains("NvN")) score -= 20
        }
        if (symbol.startsWith("_RNv")) score += 30
        if (symbol.startsWith("_ZN")) score += 10
        if (symbol.contains("17h")) score += 5

        return score
    }

    fun hasToken(symbol: String, identifier: String): Boolean {
        if (identifier.isEmpty()) return false
        return symbol.contains(token(identifier))
    }

    private fun token(identifier: String): String =
        identifier.toByteArray(StandardCharsets.UTF_8).size.toString() + identifier

    fun simpleTypeName(type: String): String {
        var text = type.trim()
        while (text.startsWith("&")) text = text.removePrefix("&").trim()
        text = text.removePrefix("mut ").trim()
        val generic = text.indexOf('<')
        if (generic >= 0) text = text.substring(0, generic)
        return text.substringAfterLast("::").substringAfterLast(' ').trim()
    }
}
