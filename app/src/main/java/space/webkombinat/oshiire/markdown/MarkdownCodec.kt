package space.webkombinat.oshiire.markdown

import space.webkombinat.oshiire.model.MdBlock
import space.webkombinat.oshiire.model.NoteContent

/**
 * メモ ⇔ Markdown の変換。Obsidian でそのまま開ける素直な形で書き出す。
 *
 * ```
 * # タイトル
 *
 * 本文…
 *
 * ![](attachments/IMG_20261017_124905.jpg)
 *
 * 本文…
 * ```
 * 読み込み時は、1 行まるごとの画像記法（`![alt](path)` と Obsidian の `![[path]]`）を画像ブロック、
 * それ以外を文章ブロックとして扱う。
 */
object MarkdownCodec {

    private val MD_IMAGE = Regex("""^!\[([^\]]*)]\(\s*<?([^)>]+?)>?\s*\)$""")
    private val WIKI_IMAGE = Regex("""^!\[\[([^\]|]+)(?:\|([^\]]*))?]]$""")
    private val HEADING = Regex("""^#\s+(.*)$""")

    fun encode(content: NoteContent): String {
        val parts = buildList {
            if (content.title.isNotBlank()) add("# ${content.title.trim()}")
            for (b in content.blocks) {
                when (b) {
                    is MdBlock.Text -> b.text.trim('\n').takeIf { it.isNotBlank() }?.let(::add)
                    is MdBlock.Image -> add("![${b.alt}](${b.path.replace(" ", "%20")})")
                }
            }
        }
        return if (parts.isEmpty()) "" else parts.joinToString("\n\n", postfix = "\n")
    }

    fun decode(markdown: String): NoteContent {
        val lines = markdown.replace("\r\n", "\n").split('\n')
        var start = 0
        while (start < lines.size && lines[start].isBlank()) start++
        var title = ""
        if (start < lines.size) {
            HEADING.matchEntire(lines[start].trim())?.let {
                title = it.groupValues[1].trim()
                start++
            }
        }

        val blocks = mutableListOf<MdBlock>()
        val text = StringBuilder()
        fun flushText() {
            val t = text.toString().trim('\n')
            if (t.isNotBlank()) blocks += MdBlock.Text(t)
            text.clear()
        }
        for (i in start until lines.size) {
            val image = parseImageLine(lines[i])
            if (image != null) {
                flushText()
                blocks += image
            } else {
                text.append(lines[i]).append('\n')
            }
        }
        flushText()
        return NoteContent(title, blocks)
    }

    fun parseImageLine(line: String): MdBlock.Image? {
        val t = line.trim()
        MD_IMAGE.matchEntire(t)?.let {
            return MdBlock.Image(path = it.groupValues[2].replace("%20", " "), alt = it.groupValues[1])
        }
        WIKI_IMAGE.matchEntire(t)?.let {
            return MdBlock.Image(path = it.groupValues[1].trim(), alt = it.groupValues[2])
        }
        return null
    }

    /** 一覧に出すタイトル。見出しがなければ本文の 1 行目 */
    fun displayTitle(content: NoteContent): String =
        content.title.ifBlank {
            content.blocks.asSequence()
                .filterIsInstance<MdBlock.Text>()
                .flatMap { it.text.lineSequence() }
                .map(::plain)
                .firstOrNull { it.isNotBlank() }
                .orEmpty()
        }

    /** 一覧に出す冒頭文。記号を外して 1 行につなげる（タイトル代わりに使った行は除く） */
    fun excerpt(content: NoteContent, maxLength: Int = 120): String {
        val lines = content.blocks.asSequence()
            .filterIsInstance<MdBlock.Text>()
            .flatMap { it.text.lineSequence() }
            .map(::plain)
            .filter { it.isNotBlank() }
            .toMutableList()
        if (content.title.isBlank() && lines.isNotEmpty()) lines.removeAt(0)
        val joined = lines.joinToString(" ").replace(Regex("\\s+"), " ").trim()
        return if (joined.length <= maxLength) joined else joined.take(maxLength).trimEnd() + "…"
    }

    fun firstImage(content: NoteContent): String? =
        content.blocks.firstNotNullOfOrNull { (it as? MdBlock.Image)?.path }

    /** Markdown の装飾記号を外した素の文字列 */
    fun plain(line: String): String = line.trim()
        .replace(Regex("""^(#{1,6}\s+|>\s*|[-*+]\s+\[[ xX]]\s+|[-*+]\s+|\d+\.\s+)"""), "")
        .replace(Regex("""!?\[([^\]]*)]\([^)]*\)"""), "$1")
        .replace(Regex("""[*_~`]+"""), "")
        .trim()
}
