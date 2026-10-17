package space.webkombinat.oshiire.ui.editor

import space.webkombinat.oshiire.model.MdBlock

/** 編集画面で扱うブロック。[id] は LazyColumn のキーとフォーカス指定に使う */
sealed interface EditorBlock {
    val id: Long

    data class Text(override val id: Long, val text: String) : EditorBlock

    /** [version] は画像ファイルが書き換わったときに増やして再読み込みさせる */
    data class Image(override val id: Long, val path: String, val alt: String = "", val version: Long = 0) : EditorBlock
}

/**
 * ブロック列の操作。どの操作の後も「先頭と末尾は文章」「文章どうしは隣り合わない」
 * 「画像と画像の間には（空でも）文章がある」状態に整える。こうしておくと、写真の前後どこにでも
 * すぐ書き足せる。
 */
class BlockOps(private var nextId: Long = 1L) {

    fun newId(): Long = nextId++

    fun fromMarkdown(blocks: List<MdBlock>): List<EditorBlock> = normalize(
        blocks.map {
            when (it) {
                is MdBlock.Text -> EditorBlock.Text(newId(), it.text)
                is MdBlock.Image -> EditorBlock.Image(newId(), it.path, it.alt)
            }
        }
    )

    fun toMarkdown(blocks: List<EditorBlock>): List<MdBlock> = blocks.map {
        when (it) {
            is EditorBlock.Text -> MdBlock.Text(it.text)
            is EditorBlock.Image -> MdBlock.Image(it.path, it.alt)
        }
    }

    data class Inserted(val blocks: List<EditorBlock>, val imageId: Long, val nextTextId: Long)

    /**
     * 画像を挿入する。[targetId] の文章ブロックの [cursor] 位置で文章を前後に分け、間に画像を入れる。
     * [targetId] が見つからなければ末尾に追加する。続きを書くための文章ブロックの ID も返す。
     */
    fun insertImage(blocks: List<EditorBlock>, targetId: Long?, cursor: Int, path: String): Inserted {
        val image = EditorBlock.Image(newId(), path)
        val index = blocks.indexOfFirst { it.id == targetId && it is EditorBlock.Text }
        val result = if (index < 0) {
            normalize(blocks + image + EditorBlock.Text(newId(), ""))
        } else {
            val t = blocks[index] as EditorBlock.Text
            val at = cursor.coerceIn(0, t.text.length)
            val before = t.text.substring(0, at).trimEnd('\n')
            val after = t.text.substring(at).trimStart('\n')
            val replaced = listOf(t.copy(text = before), image, EditorBlock.Text(newId(), after))
            normalize(blocks.subList(0, index) + replaced + blocks.subList(index + 1, blocks.size))
        }
        val imageIndex = result.indexOfFirst { it.id == image.id }
        return Inserted(result, image.id, result[imageIndex + 1].id)
    }

    fun removeImage(blocks: List<EditorBlock>, imageId: Long): List<EditorBlock> =
        normalize(blocks.filterNot { it.id == imageId })

    /** 画像を 1 つ上（[up]）または下のブロックと入れ替える（空の文章はとばす） */
    fun moveImage(blocks: List<EditorBlock>, imageId: Long, up: Boolean): List<EditorBlock> {
        val list = blocks.filterNot { it is EditorBlock.Text && it.text.isBlank() }.toMutableList()
        val i = list.indexOfFirst { it.id == imageId }
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in list.indices) return blocks
        list[i] = list[j].also { list[j] = list[i] }
        return normalize(list)
    }

    fun updateText(blocks: List<EditorBlock>, id: Long, text: String): List<EditorBlock> =
        blocks.map { if (it.id == id && it is EditorBlock.Text) it.copy(text = text) else it }

    fun normalize(blocks: List<EditorBlock>): List<EditorBlock> {
        val out = mutableListOf<EditorBlock>()
        for (b in blocks) {
            val last = out.lastOrNull()
            when {
                b is EditorBlock.Text && last is EditorBlock.Text -> {
                    out[out.lastIndex] = last.copy(text = joinText(last.text, b.text))
                }
                b is EditorBlock.Image && (last == null || last is EditorBlock.Image) -> {
                    out += EditorBlock.Text(newId(), "")
                    out += b
                }
                else -> out += b
            }
        }
        if (out.lastOrNull() !is EditorBlock.Text) out += EditorBlock.Text(newId(), "")
        return out
    }

    private fun joinText(a: String, b: String): String = when {
        a.isEmpty() -> b
        b.isEmpty() -> a
        else -> "$a\n$b"
    }
}
