package xyz.emuci.latex.renderer.font

import xyz.emuci.latex.renderer.model.LatexFontFamilies

/** Creates the single supported KaTeX TTF metrics/font provider. */
internal object MathFontProviderFactory {
    fun create(fontFamilies: LatexFontFamilies): MathFontProvider =
        TtfFontSetProvider(fontFamilies)
}
