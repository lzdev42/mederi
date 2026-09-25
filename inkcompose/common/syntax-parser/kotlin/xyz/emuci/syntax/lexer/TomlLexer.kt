package xyz.emuci.syntax.lexer

import xyz.emuci.syntax.ast.CodeToken

internal object TomlLexer : Lexer {
    private val spec = ConfigurableLexerSpec(
        keywords = setOf("true", "false"),
        builtins = setOf("inf", "nan"),
        lineComments = listOf("#"),
        tripleStrings = setOf("\"\"\"", "'''"),
        stringQuotes = setOf('"', '\''),
        extraWordChars = setOf('-', '.'),
        operators = setOf("="),
        punctuation = setOf('{', '}', '(', ')', '[', ']', ';', ',', '.', ':')
    )

    override fun tokenize(code: String): List<CodeToken> = tokenizeWithSpec(code, spec)
}
