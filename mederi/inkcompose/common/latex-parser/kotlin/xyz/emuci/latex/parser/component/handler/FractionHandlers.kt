package xyz.emuci.latex.parser.component.handler

import xyz.emuci.latex.parser.model.LatexNode

/**
 * 分数与二项式相关命令：\frac, \dfrac, \tfrac, \cfrac, \binom, \tbinom, \dbinom
 */
internal fun CommandRegistry.installFractionHandlers() {
    // 分数
    register("frac") { _, ctx, _ ->
        val numerator = ctx.parseArgument() ?: LatexNode.Text("")
        val denominator = ctx.parseArgument() ?: LatexNode.Text("")
        LatexNode.Fraction(
            numerator = numerator,
            denominator = denominator,
            style = LatexNode.Fraction.FractionStyle.NORMAL
        )
    }

    register("dfrac") { _, ctx, _ ->
        val numerator = ctx.parseArgument() ?: LatexNode.Text("")
        val denominator = ctx.parseArgument() ?: LatexNode.Text("")
        LatexNode.Fraction(
            numerator = numerator,
            denominator = denominator,
            style = LatexNode.Fraction.FractionStyle.DISPLAY
        )
    }

    register("tfrac") { _, ctx, _ ->
        val numerator = ctx.parseArgument() ?: LatexNode.Text("")
        val denominator = ctx.parseArgument() ?: LatexNode.Text("")
        LatexNode.Fraction(
            numerator = numerator,
            denominator = denominator,
            style = LatexNode.Fraction.FractionStyle.TEXT
        )
    }

    register("cfrac") { _, ctx, _ ->
        val numerator = ctx.parseArgument() ?: LatexNode.Text("")
        val denominator = ctx.parseArgument() ?: LatexNode.Text("")
        LatexNode.Fraction(
            numerator = numerator,
            denominator = denominator,
            style = LatexNode.Fraction.FractionStyle.CONTINUED
        )
    }

    // 二项式系数
    register("binom") { _, ctx, _ ->
        val top = ctx.parseArgument() ?: LatexNode.Text("")
        val bottom = ctx.parseArgument() ?: LatexNode.Text("")
        LatexNode.Binomial(top, bottom, LatexNode.Binomial.BinomialStyle.NORMAL)
    }

    register("tbinom") { _, ctx, _ ->
        val top = ctx.parseArgument() ?: LatexNode.Text("")
        val bottom = ctx.parseArgument() ?: LatexNode.Text("")
        LatexNode.Binomial(top, bottom, LatexNode.Binomial.BinomialStyle.TEXT)
    }

    register("dbinom") { _, ctx, _ ->
        val top = ctx.parseArgument() ?: LatexNode.Text("")
        val bottom = ctx.parseArgument() ?: LatexNode.Text("")
        LatexNode.Binomial(top, bottom, LatexNode.Binomial.BinomialStyle.DISPLAY)
    }

    // mathtools split fractions. The two pieces form a ruleless two-line fraction.
    register("splitfrac", "splitdfrac") { command, ctx, _ ->
        val top = ctx.parseArgument() ?: LatexNode.Text("")
        val bottom = ctx.parseArgument() ?: LatexNode.Text("")
        val fraction = LatexNode.Fraction(
            numerator = top,
            denominator = bottom,
            style = LatexNode.Fraction.FractionStyle.RULELESS
        )
        if (command == "splitdfrac") {
            LatexNode.MathStyle(listOf(fraction), LatexNode.MathStyle.MathStyleType.DISPLAY)
        } else fraction
    }

    // Generalized fraction: \genfrac{left}{right}{rule}{style}{num}{den}
    // Custom non-zero rule thickness is accepted and rendered with the engine's
    // standard rule thickness; a zero thickness produces a ruleless fraction.
    register("genfrac") { _, ctx, _ ->
        val left = ParseUtils.extractDelimiter(ctx.parseArgument() ?: LatexNode.Text(""))
        val right = ParseUtils.extractDelimiter(ctx.parseArgument() ?: LatexNode.Text(""))
        val rule = ParseUtils.extractText(listOf(ctx.parseArgument() ?: LatexNode.Text(""))).trim()
        val styleNumber = ParseUtils.extractText(
            listOf(ctx.parseArgument() ?: LatexNode.Text(""))
        ).trim().toIntOrNull()
        val numerator = ctx.parseArgument() ?: LatexNode.Text("")
        val denominator = ctx.parseArgument() ?: LatexNode.Text("")
        val ruleless = rule.matches(Regex("""[+-]?0+(?:\.0+)?(?:[A-Za-z]+)?"""))
        val fractionStyle = when {
            ruleless -> LatexNode.Fraction.FractionStyle.RULELESS
            styleNumber == 0 -> LatexNode.Fraction.FractionStyle.DISPLAY
            styleNumber == 1 -> LatexNode.Fraction.FractionStyle.TEXT
            else -> LatexNode.Fraction.FractionStyle.NORMAL
        }
        val fraction = LatexNode.Fraction(numerator, denominator, fractionStyle)
        val styled = when (styleNumber) {
            2 -> LatexNode.MathStyle(
                listOf(fraction), LatexNode.MathStyle.MathStyleType.SCRIPT
            )
            3 -> LatexNode.MathStyle(
                listOf(fraction), LatexNode.MathStyle.MathStyleType.SCRIPT_SCRIPT
            )
            else -> fraction
        }
        if (left.isNotEmpty() || right.isNotEmpty()) {
            LatexNode.Delimited(left, right, listOf(styled))
        } else {
            styled
        }
    }
}
