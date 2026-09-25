package xyz.emuci.latex.parser.component.handler

import xyz.emuci.latex.parser.model.LatexNode

/**
 * 大型运算符命令：\sum, \prod, \int, \lim 等
 */
internal fun CommandRegistry.installBigOperatorHandlers() {
    val displayLimitOperators = setOf(
        "sum", "prod", "bigcup", "bigcap", "bigvee", "bigwedge",
        "coprod", "bigoplus", "bigotimes", "bigsqcup", "bigodot", "biguplus",
        "lim", "max", "min", "sup", "inf", "limsup", "liminf", "det", "gcd"
    )
    val bigOpHandler = CommandHandler { cmdName, ctx, stream ->
        val (sub, sup, limitsMode) = ParseUtils.parseScriptsAndLimits(ctx, stream)
        LatexNode.BigOperator(
            operator = cmdName,
            subscript = sub,
            superscript = sup,
            limitsMode = limitsMode,
            limitsInDisplay = cmdName in displayLimitOperators
        )
    }

    register(
        "sum", "prod", "int", "oint", "iint", "iiint",
        "bigcup", "bigcap", "bigvee", "bigwedge",
        "coprod", "bigoplus", "bigotimes", "bigsqcup", "bigodot", "biguplus",
        "lim", "max", "min", "sup", "inf", "limsup", "liminf",
        "det", "gcd", "deg", "dim", "ker", "arg", "hom",
        handler = bigOpHandler
    )
}
