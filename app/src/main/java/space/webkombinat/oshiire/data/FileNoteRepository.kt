package space.webkombinat.oshiire.data

import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import space.webkombinat.oshiire.markdown.MarkdownCodec
import space.webkombinat.oshiire.model.Note
import space.webkombinat.oshiire.model.NoteContent
import space.webkombinat.oshiire.model.NoteSummary
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * vault ディレクトリに .md と画像を置くだけの保存先。データベースは使わない。
 *
 * ```
 * vault/
 *   2026-10-17_124831.md
 *   attachments/IMG_20261017_124905.jpg
 *   .trash/            削除したメモ（元に戻す用）
 * ```
 */
class FileNoteRepository(
    override val vaultDir: File,
    private val io: CoroutineDispatcher,
    private val clock: () -> Long = System::currentTimeMillis,
) : NoteRepository {

    private val attachments = File(vaultDir, ATTACHMENTS)
    private val trash = File(vaultDir, ".trash")
    private val mutex = Mutex()

    /** 解析結果のキャッシュ（ID → 更新時刻と要約） */
    private val cache = HashMap<String, Pair<Long, NoteSummary>>()
    private val reserved = HashSet<String>()

    private val _notes = MutableStateFlow<List<NoteSummary>>(emptyList())
    override val notes: StateFlow<List<NoteSummary>> = _notes.asStateFlow()

    private val _imageChanges = MutableSharedFlow<String>(extraBufferCapacity = 8)
    override val imageChanges: SharedFlow<String> = _imageChanges.asSharedFlow()

    override suspend fun refresh() = withContext(io) {
        mutex.withLock { rescan() }
    }

    override suspend fun create(): String = withContext(io) {
        mutex.withLock {
            val base = ID_FORMAT.get()!!.format(Date(clock()))
            var id = base
            var n = 2
            while (noteFile(id).exists() || id in reserved) id = "$base-${n++}"
            reserved += id
            id
        }
    }

    override suspend fun load(id: String): Note? = withContext(io) {
        val f = noteFile(id)
        if (!f.exists()) return@withContext null
        Note(id, MarkdownCodec.decode(f.readText()), f.lastModified())
    }

    override suspend fun save(id: String, content: NoteContent) = withContext(io) {
        mutex.withLock {
            val f = noteFile(id)
            if (content.isBlank) {
                f.delete()
            } else {
                vaultDir.mkdirs()
                writeAtomic(f, MarkdownCodec.encode(content).toByteArray())
                f.setLastModified(clock())
            }
            rescan()
        }
    }

    override suspend fun delete(id: String) = withContext(io) {
        mutex.withLock {
            val f = noteFile(id)
            if (f.exists()) {
                trash.mkdirs()
                Files.move(f.toPath(), File(trash, f.name).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            rescan()
        }
    }

    override suspend fun restore(id: String) = withContext(io) {
        mutex.withLock {
            val t = File(trash, "$id.md")
            if (t.exists()) {
                // 移動しても更新時刻は変わらないので一覧の元の位置に戻る
                Files.move(t.toPath(), noteFile(id).toPath(), StandardCopyOption.REPLACE_EXISTING)
            }
            rescan()
        }
    }

    override fun resolve(path: String): File {
        val direct = File(vaultDir, path)
        // Obsidian の ![[name.jpg]] はファイル名だけのことがある
        if (!direct.exists() && !path.contains('/')) {
            val inAttachments = File(attachments, path)
            if (inAttachments.exists()) return inAttachments
        }
        return direct
    }

    override suspend fun newImageFile(): Pair<String, File> = withContext(io) {
        mutex.withLock {
            attachments.mkdirs()
            val base = "IMG_" + IMAGE_FORMAT.get()!!.format(Date(clock()))
            var name = "$base.jpg"
            var n = 2
            while (File(attachments, name).exists()) name = "${base}_${n++}.jpg"
            val file = File(attachments, name)
            file.createNewFile()
            "$ATTACHMENTS/$name" to file
        }
    }

    override suspend fun importImage(input: InputStream): String {
        val (path, file) = newImageFile()
        withContext(io) { input.use { src -> file.outputStream().use { src.copyTo(it) } } }
        return path
    }

    override suspend fun notifyImageChanged(path: String) {
        refresh()
        _imageChanges.emit(path)
    }

    private fun noteFile(id: String) = File(vaultDir, "$id.md")

    private fun rescan() {
        val files = vaultDir.listFiles { f -> f.isFile && f.name.endsWith(".md") }.orEmpty()
        val alive = HashSet<String>()
        val list = files.map { f ->
            val id = f.name.removeSuffix(".md")
            alive += id
            reserved -= id
            val modified = f.lastModified()
            val cached = cache[id]
            val summary = if (cached != null && cached.first == modified) {
                cached.second.withThumbnailVersion()
            } else {
                summarize(id, f, modified)
            }
            cache[id] = modified to summary
            summary
        }
        cache.keys.retainAll(alive)
        _notes.value = list.sortedWith(compareByDescending<NoteSummary> { it.updatedAt }.thenByDescending { it.id })
    }

    private fun summarize(id: String, f: File, modified: Long): NoteSummary {
        val content = MarkdownCodec.decode(runCatching { f.readText() }.getOrDefault(""))
        val thumb = MarkdownCodec.firstImage(content)
        return NoteSummary(
            id = id,
            title = MarkdownCodec.displayTitle(content),
            excerpt = MarkdownCodec.excerpt(content),
            thumbnail = thumb,
            thumbnailVersion = thumb?.let { resolve(it).lastModified() } ?: 0L,
            updatedAt = modified,
        )
    }

    private fun NoteSummary.withThumbnailVersion(): NoteSummary =
        thumbnail?.let { copy(thumbnailVersion = resolve(it).lastModified()) } ?: this

    companion object {
        const val ATTACHMENTS = "attachments"

        private val ID_FORMAT = ThreadLocal.withInitial { SimpleDateFormat("yyyy-MM-dd_HHmmss", Locale.US) }
        private val IMAGE_FORMAT = ThreadLocal.withInitial { SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US) }

        /** 一時ファイルに書いてから置き換える（書き込み途中で落ちても元のファイルが壊れない） */
        fun writeAtomic(target: File, bytes: ByteArray) {
            target.parentFile?.mkdirs()
            val tmp = File(target.parentFile, ".${target.name}.tmp")
            tmp.outputStream().use { out ->
                out.write(bytes)
                out.fd.sync()
            }
            Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
