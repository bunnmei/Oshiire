package space.webkombinat.oshiire.ui.editor

import org.junit.Assert.assertEquals
import org.junit.Test
import space.webkombinat.oshiire.model.MdBlock

class BlockOpsTest {

    private val ops = BlockOps()

    /** 中身だけを比べやすい形にする（T:文章 / I:画像） */
    private fun List<EditorBlock>.shape() = map {
        when (it) {
            is EditorBlock.Text -> "T:${it.text}"
            is EditorBlock.Image -> "I:${it.path}"
        }
    }

    @Test
    fun fromMarkdown_alwaysStartsAndEndsWithText() {
        val blocks = ops.fromMarkdown(listOf(MdBlock.Image("a"), MdBlock.Image("b")))
        assertEquals(listOf("T:", "I:a", "T:", "I:b", "T:"), blocks.shape())
        assertEquals(listOf("T:"), ops.fromMarkdown(emptyList()).shape())
    }

    @Test
    fun insertImage_splitsTextAtCursor() {
        val blocks = ops.fromMarkdown(listOf(MdBlock.Text("前半\n後半")))
        val target = blocks[0].id
        val r = ops.insertImage(blocks, target, cursor = 3, path = "p.jpg")
        assertEquals(listOf("T:前半", "I:p.jpg", "T:後半"), r.blocks.shape())
        assertEquals(r.blocks[1].id, r.imageId)
        assertEquals(r.blocks[2].id, r.nextTextId)
        // 元の文章ブロックの ID は前半側に残る
        assertEquals(target, r.blocks[0].id)
    }

    @Test
    fun insertImage_atEndCreatesEmptyTextAfter() {
        val blocks = ops.fromMarkdown(listOf(MdBlock.Text("本文")))
        val r = ops.insertImage(blocks, blocks[0].id, cursor = 2, path = "p.jpg")
        assertEquals(listOf("T:本文", "I:p.jpg", "T:"), r.blocks.shape())
    }

    @Test
    fun insertImage_consecutiveShotsKeepTextBetween() {
        var blocks = ops.fromMarkdown(listOf(MdBlock.Text("a")))
        var r = ops.insertImage(blocks, blocks[0].id, 1, "1.jpg")
        r = ops.insertImage(r.blocks, r.nextTextId, 0, "2.jpg")
        assertEquals(listOf("T:a", "I:1.jpg", "T:", "I:2.jpg", "T:"), r.blocks.shape())
    }

    @Test
    fun insertImage_unknownTargetAppendsAtEnd() {
        val blocks = ops.fromMarkdown(listOf(MdBlock.Text("a")))
        val r = ops.insertImage(blocks, targetId = 999, cursor = 0, path = "p.jpg")
        assertEquals(listOf("T:a", "I:p.jpg", "T:"), r.blocks.shape())
    }

    @Test
    fun insertImage_clampsCursor() {
        val blocks = ops.fromMarkdown(listOf(MdBlock.Text("ab")))
        val r = ops.insertImage(blocks, blocks[0].id, cursor = 99, path = "p.jpg")
        assertEquals(listOf("T:ab", "I:p.jpg", "T:"), r.blocks.shape())
    }

    @Test
    fun removeImage_mergesSurroundingText() {
        val blocks = ops.fromMarkdown(listOf(MdBlock.Text("前"), MdBlock.Image("p"), MdBlock.Text("後")))
        val result = ops.removeImage(blocks, blocks[1].id)
        assertEquals(listOf("T:前\n後"), result.shape())
    }

    @Test
    fun moveImage_swapsWithNeighbourSkippingEmptyText() {
        val blocks = ops.fromMarkdown(listOf(MdBlock.Text("a"), MdBlock.Image("1"), MdBlock.Image("2"), MdBlock.Text("b")))
        // [T:a, I:1, T:, I:2, T:b]
        val i2 = blocks.first { it is EditorBlock.Image && it.path == "2" }.id
        val up = ops.moveImage(blocks, i2, up = true)
        assertEquals(listOf("T:a", "I:2", "T:", "I:1", "T:b"), up.shape())

        val i1 = blocks.first { it is EditorBlock.Image && it.path == "1" }.id
        val above = ops.moveImage(blocks, i1, up = true)
        assertEquals(listOf("T:", "I:1", "T:a", "I:2", "T:b"), above.shape())
    }

    @Test
    fun moveImage_atEdgeDoesNothing() {
        val blocks = ops.fromMarkdown(listOf(MdBlock.Image("1")))
        assertEquals(blocks, ops.moveImage(blocks, blocks[1].id, up = true))
        assertEquals(blocks, ops.moveImage(blocks, blocks[1].id, up = false))
    }

    @Test
    fun toMarkdown_keepsOrder() {
        val md = listOf(MdBlock.Text("a"), MdBlock.Image("p", "alt"), MdBlock.Text("b"))
        assertEquals(md, ops.toMarkdown(ops.fromMarkdown(md)))
    }
}
