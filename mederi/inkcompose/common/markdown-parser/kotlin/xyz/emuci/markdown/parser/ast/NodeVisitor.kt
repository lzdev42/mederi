package xyz.emuci.markdown.parser.ast

/**
 * 用于遍历 AST 节点的访问者接口。
 */
interface NodeVisitor<R> {
    // 块级节点
    fun visitDocument(node: Document): R
    fun visitHeading(node: Heading): R
    fun visitSetextHeading(node: SetextHeading): R
    fun visitParagraph(node: Paragraph): R
    fun visitThematicBreak(node: ThematicBreak): R
    fun visitFencedCodeBlock(node: FencedCodeBlock): R
    fun visitIndentedCodeBlock(node: IndentedCodeBlock): R
    fun visitBlockQuote(node: BlockQuote): R
    fun visitListBlock(node: ListBlock): R
    fun visitListItem(node: ListItem): R
    fun visitHtmlBlock(node: HtmlBlock): R
    fun visitLinkReferenceDefinition(node: LinkReferenceDefinition): R
    fun visitTable(node: Table): R
    fun visitTableHead(node: TableHead): R
    fun visitTableBody(node: TableBody): R
    fun visitTableRow(node: TableRow): R
    fun visitTableCell(node: TableCell): R
    fun visitFootnoteDefinition(node: FootnoteDefinition): R
    fun visitMathBlock(node: MathBlock): R
    fun visitDefinitionList(node: DefinitionList): R
    fun visitDefinitionTerm(node: DefinitionTerm): R
    fun visitDefinitionDescription(node: DefinitionDescription): R
    fun visitAdmonition(node: Admonition): R
    fun visitFrontMatter(node: FrontMatter): R
    fun visitBlankLine(node: BlankLine): R
    fun visitTocPlaceholder(node: TocPlaceholder): R
    fun visitAbbreviationDefinition(node: AbbreviationDefinition): R

    fun visitCustomContainer(node: CustomContainer): R
    fun visitDiagramBlock(node: DiagramBlock): R
    fun visitVerticalTextBlock(node: VerticalTextBlock): R
    fun visitColumnsLayout(node: ColumnsLayout): R
    fun visitColumnItem(node: ColumnItem): R
    fun visitPageBreak(node: PageBreak): R

    // 行内节点
    fun visitText(node: Text): R
    fun visitSoftLineBreak(node: SoftLineBreak): R
    fun visitHardLineBreak(node: HardLineBreak): R
    fun visitEmphasis(node: Emphasis): R
    fun visitStrongEmphasis(node: StrongEmphasis): R
    fun visitStrikethrough(node: Strikethrough): R
    fun visitInlineCode(node: InlineCode): R
    fun visitLink(node: Link): R
    fun visitImage(node: Image): R
    fun visitAutolink(node: Autolink): R
    fun visitInlineHtml(node: InlineHtml): R
    fun visitHtmlEntity(node: HtmlEntity): R
    fun visitEscapedChar(node: EscapedChar): R
    fun visitFootnoteReference(node: FootnoteReference): R
    fun visitInlineMath(node: InlineMath): R
    fun visitHighlight(node: Highlight): R
    fun visitSuperscript(node: Superscript): R
    fun visitSubscript(node: Subscript): R
    fun visitInsertedText(node: InsertedText): R
    fun visitEmoji(node: Emoji): R
    fun visitStyledText(node: StyledText): R
    fun visitAbbreviation(node: Abbreviation): R
    fun visitKeyboardInput(node: KeyboardInput): R
    fun visitDirectiveBlock(node: DirectiveBlock): R
    fun visitDirectiveInline(node: DirectiveInline): R
    fun visitTabBlock(node: TabBlock): R
    fun visitTabItem(node: TabItem): R
    fun visitBibliographyDefinition(node: BibliographyDefinition): R
    fun visitCitationReference(node: CitationReference): R
    fun visitSpoiler(node: Spoiler): R
    fun visitWikiLink(node: WikiLink): R
    fun visitRubyText(node: RubyText): R
    fun visitFigure(node: Figure): R
}
