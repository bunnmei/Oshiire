package space.webkombinat.oshiire.model

/** Markdown 本文の 1 かたまり。本文は「文章」と「画像（独立した 1 行）」の並びとして扱う。 */
sealed interface MdBlock {
    data class Text(val text: String) : MdBlock

    /** [path] は vault からの相対パス（例: attachments/IMG_20261017_124905.jpg） */
    data class Image(val path: String, val alt: String = "") : MdBlock
}

/** 1 つの .md ファイルの中身 */
data class NoteContent(
    val title: String,
    val blocks: List<MdBlock>,
) {
    val isBlank: Boolean
        get() = title.isBlank() && blocks.all { it is MdBlock.Text && it.text.isBlank() }
}

data class Note(
    val id: String,
    val content: NoteContent,
    val updatedAt: Long,
)

/** 一覧表示用の要約 */
data class NoteSummary(
    val id: String,
    val title: String,
    val excerpt: String,
    /** 最初の画像（vault からの相対パス）。なければ null */
    val thumbnail: String?,
    /** サムネイル画像の更新時刻。注釈で画像が変わったときに再読み込みさせるため */
    val thumbnailVersion: Long,
    val updatedAt: Long,
)
