package xyz.emuci.syntax.lexer

import xyz.emuci.syntax.ast.CodeToken

internal object DockerfileLexer : Lexer {
    private val spec = ConfigurableLexerSpec(
        keywords = setOf(
            "FROM", "RUN", "CMD", "LABEL", "EXPOSE", "ENV", "ADD", "COPY", "ENTRYPOINT",
            "VOLUME", "USER", "WORKDIR", "ARG", "ONBUILD", "STOPSIGNAL", "HEALTHCHECK",
            "SHELL", "MAINTAINER"
        ),
        builtins = setOf("AS"),
        lineComments = listOf("#"),
        stringQuotes = setOf('"', '\''),
        variablePrefixes = listOf("$"),
        caseInsensitiveWords = true,
        extraWordChars = setOf('-', '.'),
        operators = setOf("&&", "||", "<<", "=", "\\"),
        punctuation = setOf('{', '}', '(', ')', '[', ']', ';', ',', '.', ':')
    )

    override fun tokenize(code: String): List<CodeToken> = tokenizeWithSpec(code, spec)
}
