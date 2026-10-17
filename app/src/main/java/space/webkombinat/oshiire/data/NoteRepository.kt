package space.webkombinat.oshiire.data

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import space.webkombinat.oshiire.model.Note
import space.webkombinat.oshiire.model.NoteContent
import space.webkombinat.oshiire.model.NoteSummary
import java.io.File
import java.io.InputStream

interface NoteRepository {
    /** 更新日時の新しい順 */
    val notes: StateFlow<List<NoteSummary>>

    /** 画像ファイルが書き換えられたとき（注釈の保存など）にその相対パスが流れる */
    val imageChanges: SharedFlow<String>

    /** vault のルート（設定画面での表示用） */
    val vaultDir: File

    suspend fun refresh()

    /** 新しいメモの ID を払い出す。中身が書かれるまでファイルは作らない */
    suspend fun create(): String

    suspend fun load(id: String): Note?

    /** 中身が空なら（既にファイルがあれば）削除する */
    suspend fun save(id: String, content: NoteContent)

    /** ゴミ箱（vault/.trash）へ移す。[restore] で戻せる */
    suspend fun delete(id: String)

    suspend fun restore(id: String)

    /** 相対パス → 実ファイル */
    fun resolve(path: String): File

    /** 撮影用の新しい画像ファイル。戻り値は (相対パス, 実ファイル) */
    suspend fun newImageFile(): Pair<String, File>

    /** ギャラリーなどから画像を取り込み、相対パスを返す */
    suspend fun importImage(input: InputStream): String

    suspend fun notifyImageChanged(path: String)

    /** 画像ファイルの更新時刻（表示のキャッシュを分けるため）。ファイルを調べるので IO で呼ぶ */
    suspend fun imageVersion(path: String): Long
}
