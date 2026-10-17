package space.webkombinat.oshiire.markdown

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import space.webkombinat.oshiire.model.MdBlock
import space.webkombinat.oshiire.model.NoteContent

class MarkdownCodecTest {

    private val sample = NoteContent(
        title = "朝のカフェ",
        blocks = listOf(
            MdBlock.Text("今日は駅前のカフェへ。\n\n窓際の席が空いていた。"),
            MdBlock.Image("attachments/IMG_20261017_124905.jpg"),
            MdBlock.Text("ラテアートがきれい。"),
        ),
    )

    @Test
    fun encode_writesHeadingTextAndImageLines() {
        val md = MarkdownCodec.encode(sample)
        assertEquals(
            """
            # 朝のカフェ

            今日は駅前のカフェへ。

            窓際の席が空いていた。

            ![](attachments/IMG_20261017_124905.jpg)

            ラテアートがきれい。

            """.trimIndent(),
            md,
        )
    }

    @Test
    fun decode_roundTrips() {
        assertEquals(sample, MarkdownCodec.decode(MarkdownCodec.encode(sample)))
    }

    @Test
    fun encode_skipsEmptyTextAndBlankTitle() {
        val content = NoteContent("", listOf(MdBlock.Text(""), MdBlock.Image("a.jpg"), MdBlock.Text("\n")))
        assertEquals("![](a.jpg)\n", MarkdownCodec.encode(content))
        assertEquals("", MarkdownCodec.encode(NoteContent("", listOf(MdBlock.Text("")))))
    }

    @Test
    fun decode_withoutHeading_keepsFirstLineAsText() {
        val c = MarkdownCodec.decode("買い物リスト\n- 牛乳\n")
        assertEquals("", c.title)
        assertEquals(listOf(MdBlock.Text("買い物リスト\n- 牛乳")), c.blocks)
    }

    @Test
    fun decode_onlyFirstHeadingIsTitle() {
        val c = MarkdownCodec.decode("\n# タイトル\n\n## 見出し\n本文")
        assertEquals("タイトル", c.title)
        assertEquals(listOf(MdBlock.Text("## 見出し\n本文")), c.blocks)
    }

    @Test
    fun decode_recognizesObsidianEmbedsAndAltText() {
        val c = MarkdownCodec.decode("![[IMG_1.jpg]]\n![[sub/IMG_2.png|300]]\n![説明](attachments/My%20Photo.jpg)\n")
        assertEquals(
            listOf(
                MdBlock.Image("IMG_1.jpg"),
                MdBlock.Image("sub/IMG_2.png", "300"),
                MdBlock.Image("attachments/My Photo.jpg", "説明"),
            ),
            c.blocks,
        )
    }

    @Test
    fun decode_inlineImageInsideSentenceStaysText() {
        val c = MarkdownCodec.decode("ここに ![](a.jpg) がある")
        assertEquals(listOf(MdBlock.Text("ここに ![](a.jpg) がある")), c.blocks)
    }

    @Test
    fun encode_escapesSpacesInPath() {
        val md = MarkdownCodec.encode(NoteContent("", listOf(MdBlock.Image("attachments/My Photo.jpg"))))
        assertEquals("![](attachments/My%20Photo.jpg)\n", md)
    }

    @Test
    fun summary_titleExcerptAndFirstImage() {
        assertEquals("朝のカフェ", MarkdownCodec.displayTitle(sample))
        assertEquals("今日は駅前のカフェへ。 窓際の席が空いていた。 ラテアートがきれい。", MarkdownCodec.excerpt(sample))
        assertEquals("attachments/IMG_20261017_124905.jpg", MarkdownCodec.firstImage(sample))
    }

    @Test
    fun summary_withoutTitle_usesFirstLineAndExcludesItFromExcerpt() {
        val c = MarkdownCodec.decode("**メモ** の1行目\n- [ ] 2行目\n> 3行目")
        assertEquals("メモ の1行目", MarkdownCodec.displayTitle(c))
        assertEquals("2行目 3行目", MarkdownCodec.excerpt(c))
        assertNull(MarkdownCodec.firstImage(c))
    }

    @Test
    fun excerpt_isTruncated() {
        val c = NoteContent("t", listOf(MdBlock.Text("あ".repeat(200))))
        val e = MarkdownCodec.excerpt(c, maxLength = 10)
        assertEquals("あ".repeat(10) + "…", e)
    }

    @Test
    fun decode_handlesCrLf() {
        val c = MarkdownCodec.decode("# T\r\n\r\nline1\r\nline2\r\n")
        assertEquals(NoteContent("T", listOf(MdBlock.Text("line1\nline2"))), c)
    }
}
