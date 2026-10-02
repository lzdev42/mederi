package xyz.mederi.util

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * [classifyFilePath] 的穷举锁定测试（扩展名 → 四档分级的唯一真理源）。
 *
 * 三类易错点全部显式断言之：
 * 1. 无扩展名的常见文件（Makefile / Dockerfile / noext）**不是**内部文本 → REVEAL_IN_FOLDER；
 * 2. 隐藏文件 `.gitignore` / `.bashrc` 的 `substringAfterLast('.')` 会得到 `gitignore` / `bashrc`，
 *    二者都不在内部文本清单里 → REVEAL_IN_FOLDER；
 * 3. 大小写不敏感：`Foo.KT` 与 `foo.kt` 同档。
 */
class FileTypeClassifierTest {

    // ---------------- INTERNAL_TEXT ----------------

    @Test
    fun internalTextCoversSourceMarkdownAndConfigFiles() {
        assertEquals(FileOpenTarget.INTERNAL_TEXT, classifyFilePath("foo.kt"))
        assertEquals(FileOpenTarget.INTERNAL_TEXT, classifyFilePath("a/b/c.py"))       // 路径含分隔符
        assertEquals(FileOpenTarget.INTERNAL_TEXT, classifyFilePath("README.md"))
        assertEquals(FileOpenTarget.INTERNAL_TEXT, classifyFilePath("x.json"))
        assertEquals(FileOpenTarget.INTERNAL_TEXT, classifyFilePath("build.gradle"))
        assertEquals(FileOpenTarget.INTERNAL_TEXT, classifyFilePath("src/main.tsx"))
        assertEquals(FileOpenTarget.INTERNAL_TEXT, classifyFilePath("run.sh"))
        assertEquals(FileOpenTarget.INTERNAL_TEXT, classifyFilePath("app.config.yaml"))
    }

    /** Dockerfile 没有扩展名 → 不能当内部文本（断言它**不是** INTERNAL_TEXT）。 */
    @Test
    fun dockerfileHasNoExtensionSoItIsNotInternalText() {
        assertEquals(FileOpenTarget.REVEAL_IN_FOLDER, classifyFilePath("Dockerfile"))
        assertEquals(false, classifyFilePath("Dockerfile") == FileOpenTarget.INTERNAL_TEXT)
    }

    @Test
    fun internalTextIsCaseInsensitive() {
        assertEquals(FileOpenTarget.INTERNAL_TEXT, classifyFilePath("Foo.KT"))
        assertEquals(FileOpenTarget.INTERNAL_TEXT, classifyFilePath("X.MD"))
        assertEquals(
            classifyFilePath("foo.kt"),
            classifyFilePath("Foo.KT")
        )
    }

    // ---------------- INTERNAL_IMAGE ----------------

    @Test
    fun internalImageCoversLocalImageFormats() {
        assertEquals(FileOpenTarget.INTERNAL_IMAGE, classifyFilePath("a.png"))
        assertEquals(FileOpenTarget.INTERNAL_IMAGE, classifyFilePath("b.JPG"))
        assertEquals(FileOpenTarget.INTERNAL_IMAGE, classifyFilePath("c.webp"))
        assertEquals(FileOpenTarget.INTERNAL_IMAGE, classifyFilePath("d.svg"))
    }

    /** 同扩展名不同大小写结果必须完全一致。 */
    @Test
    fun imageClassificationIsCaseInsensitive() {
        // 注意裸串 "PNG" 没有点 → 无扩展名 → REVEAL；带点才谈扩展名大小写
        assertEquals(FileOpenTarget.INTERNAL_IMAGE, classifyFilePath("a.PNG"))
        assertEquals(FileOpenTarget.INTERNAL_IMAGE, classifyFilePath("a.png"))
        assertEquals(
            classifyFilePath("a.PNG"),
            classifyFilePath("a.png")
        )
        assertEquals(FileOpenTarget.REVEAL_IN_FOLDER, classifyFilePath("PNG"))
        assertEquals(
            classifyFilePath("a.png"),
            classifyFilePath("B.PNG")
        )
    }

    // ---------------- EXTERNAL ----------------

    @Test
    fun externalCoversOfficePdfWebArchiveBinaryAndMedia() {
        assertEquals(FileOpenTarget.EXTERNAL, classifyFilePath("a.docx"))   // office 新式
        assertEquals(FileOpenTarget.EXTERNAL, classifyFilePath("b.xls"))    // office 旧式
        assertEquals(FileOpenTarget.EXTERNAL, classifyFilePath("c.wps"))    // 金山
        assertEquals(FileOpenTarget.EXTERNAL, classifyFilePath("d.pages"))  // Apple iWork
        assertEquals(FileOpenTarget.EXTERNAL, classifyFilePath("e.pdf"))
        assertEquals(FileOpenTarget.EXTERNAL, classifyFilePath("f.html"))   // 明确口径：html 走浏览器
        assertEquals(FileOpenTarget.EXTERNAL, classifyFilePath("g.zip"))
        assertEquals(FileOpenTarget.EXTERNAL, classifyFilePath("h.exe"))
        assertEquals(FileOpenTarget.EXTERNAL, classifyFilePath("i.mp4"))
    }

    /** html/htm 归 EXTERNAL，不能因为"能当文本看"就落 INTERNAL_TEXT。 */
    @Test
    fun htmlIsExternalNotInternalText() {
        assertEquals(FileOpenTarget.EXTERNAL, classifyFilePath("page.html"))
        assertEquals(FileOpenTarget.EXTERNAL, classifyFilePath("page.htm"))
        assertEquals(false, classifyFilePath("page.html") == FileOpenTarget.INTERNAL_TEXT)
    }

    // ---------------- REVEAL_IN_FOLDER ----------------

    @Test
    fun revealForFilesWithoutExtension() {
        assertEquals(FileOpenTarget.REVEAL_IN_FOLDER, classifyFilePath("Makefile"))
        assertEquals(FileOpenTarget.REVEAL_IN_FOLDER, classifyFilePath("noext"))
        assertEquals(FileOpenTarget.REVEAL_IN_FOLDER, classifyFilePath(""))
    }

    @Test
    fun revealForUnknownExtension() {
        assertEquals(FileOpenTarget.REVEAL_IN_FOLDER, classifyFilePath("a.unknownext"))
        assertEquals(FileOpenTarget.REVEAL_IN_FOLDER, classifyFilePath("weird.zzz"))
    }

    /**
     * 隐藏文件陷阱：`substringAfterLast('.')` 对 `a.gitignore` 得到 `gitignore`、
     * 对 `.gitignore` 同样得到 `gitignore`——都不在内部文本清单里 → REVEAL_IN_FOLDER。
     */
    @Test
    fun revealForDotFilesWhoseDerivedExtensionLooksTextual() {
        assertEquals(FileOpenTarget.REVEAL_IN_FOLDER, classifyFilePath("a.gitignore"))
        assertEquals(FileOpenTarget.REVEAL_IN_FOLDER, classifyFilePath(".gitignore"))
        assertEquals(FileOpenTarget.REVEAL_IN_FOLDER, classifyFilePath("/repo/.gitattributes"))
        assertEquals(FileOpenTarget.REVEAL_IN_FOLDER, classifyFilePath(".bashrc"))
        assertEquals(
            false,
            classifyFilePath(".gitignore") == FileOpenTarget.INTERNAL_TEXT
        )
    }
}
