package com.smartisanrevival.onestep.data

import android.content.ContentUris
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import android.text.format.DateUtils
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/**
 * 三个内容面板的数据源（官方 RecentPhotoManager / RecentFileManager / RecentClipManager 语义重写）。
 *
 * - 照片：MediaStore Images 倒序，日期桶 {今天/昨天/7天/30天/更早}，隐藏清单存 JSON
 * - 文件：MediaStore Downloads/Files，扩展名→分组
 * - 剪贴板：自建历史（系统级复制历史是 Smartisan 私有 API 拿不到）——
 *   来源：分享收纳 + PROCESS_TEXT 收纳，JSON 持久化，上限 50 条
 *
 * v1 不引 Room（减少与并行开发的构建冲突），数据量小，JSON 文件够用。
 */
object ContentStores {

    private const val CLIP_FILE = "onestep_clip.json"
    private const val CLIP_MAX = 50

    // ---------- 通用日期桶 ----------

    data class Bucket(val label: String, val items: List<Item>)

    open class Item(val id: String, val label: String)

    fun bucketLabel(timeMs: Long, now: Long = System.currentTimeMillis()): String {
        return when {
            DateUtils.isToday(timeMs) -> "今天"
            DateUtils.isToday(timeMs + DateUtils.DAY_IN_MILLIS) -> "昨天"
            now - timeMs < 7 * DateUtils.DAY_IN_MILLIS -> "7 天内"
            now - timeMs < 30 * DateUtils.DAY_IN_MILLIS -> "30 天内"
            else -> "更早"
        }
    }

    fun <T : Item> groupByBucket(items: List<Pair<Long, T>>): List<Bucket> {
        val order = listOf("今天", "昨天", "7 天内", "30 天内", "更早")
        val grouped = items.groupBy { bucketLabel(it.first) }
        return order.mapNotNull { label ->
            grouped[label]?.let { Bucket(label, it.map { p -> p.second }) }
        }
    }

    // ---------- 照片 ----------

    class PhotoItem(id: String, val uri: Uri, label: String) : Item(id, label)

    fun recentPhotos(ctx: Context, limit: Int = 60): List<Bucket> {
        if (!hasImagePermission(ctx)) return emptyList()
        val out = mutableListOf<Pair<Long, PhotoItem>>()
        val proj = arrayOf(
            MediaStore.Images.Media._ID,
            MediaStore.Images.Media.DISPLAY_NAME,
            MediaStore.Images.Media.DATE_ADDED
        )
        try {
            ctx.contentResolver.query(
                MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
                proj, null, null,
                "${MediaStore.Images.Media.DATE_ADDED} DESC"
            )?.use { c ->
                var n = 0
                while (c.moveToNext() && n < limit) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: ""
                    val added = c.getLong(2) * 1000
                    val uri = ContentUris.withAppendedId(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, id)
                    out.add(added to PhotoItem(id.toString(), uri, name))
                    n++
                }
            }
        } catch (_: Exception) {
        }
        return groupByBucket(out)
    }

    fun hasImagePermission(ctx: Context): Boolean =
        ctx.checkSelfPermission(android.Manifest.permission.READ_MEDIA_IMAGES) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED ||
            ctx.checkSelfPermission(android.Manifest.permission.READ_EXTERNAL_STORAGE) ==
            android.content.pm.PackageManager.PERMISSION_GRANTED

    // ---------- 文件 ----------

    class FileItem(id: String, val path: String, label: String, val mime: String, val mediaUri: Uri? = null) : Item(id, label)

    private val DOC_EXTS = listOf("pdf", "doc", "docx", "xls", "xlsx", "ppt", "pptx", "txt",
        "epub", "mobi", "azw3", "csv", "md", "zip", "rar", "7z")

    fun recentFiles(ctx: Context, limit: Int = 40): List<Bucket> {
        val out = mutableListOf<Pair<Long, FileItem>>()
        val now = System.currentTimeMillis()
        // MediaStore Downloads：Android 10+ 一级下载集合，不需全文件权限也能看得到自己可及的条目
        try {
            val proj = arrayOf(
                MediaStore.Files.FileColumns._ID,
                MediaStore.Files.FileColumns.DISPLAY_NAME,
                MediaStore.Files.FileColumns.DATA,
                MediaStore.Files.FileColumns.MIME_TYPE,
                MediaStore.Files.FileColumns.DATE_MODIFIED
            )
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                proj, null, null,
                "${MediaStore.Files.FileColumns.DATE_MODIFIED} DESC"
            )?.use { c ->
                var n = 0
                while (c.moveToNext() && n < limit) {
                    val id = c.getLong(0)
                    val name = c.getString(1) ?: continue
                    val path = c.getString(2) ?: continue
                    val mime = c.getString(3) ?: ""
                    val modified = c.getLong(4) * 1000
                    val ext = name.substringAfterLast('.', "").lowercase()
                    if (mime.startsWith("image/") || mime.startsWith("video/") || mime.startsWith("audio/")) continue
                    if (ext !in DOC_EXTS && mime !in listOf("application/pdf", "application/zip")) continue
                    val uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id)
                    out.add(modified to FileItem(id.toString(), path, name, mime.ifBlank { ext }, uri))
                    n++
                }
            }
        } catch (_: Exception) {
        }
        // 补充扫描 Download 根目录（MediaStore 索引不到的边角文件）
        try {
            val dl = File(android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "")
            dl.listFiles()?.sortedByDescending { it.lastModified() }?.take(20)?.forEach { f ->
                val ext = f.extension.lowercase()
                if (ext in DOC_EXTS && out.none { it.second.path == f.absolutePath }) {
                    out.add(f.lastModified() to FileItem("f${f.lastModified()}", f.absolutePath, f.name, ext))
                }
            }
        } catch (_: Exception) {
        }
        return groupByBucket(out.sortedByDescending { it.first })
    }

    fun mimeIcon(mimeOrExt: String): String {
        val e = mimeOrExt.substringAfterLast('/', "").lowercase()
        return when (e) {
            "pdf" -> "PDF"
            "doc", "docx", "msword", "vnd.openxmlformats-officedocument.wordprocessingml.document" -> "DOC"
            "xls", "xlsx", "vnd.ms-excel", "vnd.openxmlformats-officedocument.spreadsheetml.sheet" -> "XLS"
            "ppt", "pptx", "vnd.ms-powerpoint" -> "PPT"
            "txt", "plain", "md" -> "TXT"
            "zip", "rar", "7z", "octet-stream" -> "ZIP"
            "epub", "mobi", "azw3" -> "书"
            else -> "文"
        }
    }

    // ---------- 剪贴板历史 ----------

    class ClipItem(id: String, val text: String, val timeMs: Long) : Item(id, text)

    fun clipFile(ctx: Context) = File(ctx.filesDir, CLIP_FILE)

    fun clipHistory(ctx: Context): List<ClipItem> {
        val f = clipFile(ctx)
        if (!f.exists()) return emptyList()
        return try {
            val arr = JSONArray(f.readText())
            (0 until arr.length()).mapNotNull { i ->
                val o = arr.optJSONObject(i) ?: return@mapNotNull null
                ClipItem(o.getString("t"), o.getString("s"), o.getLong("d"))
            }.sortedByDescending { it.timeMs }
        } catch (_: Exception) {
            emptyList()
        }
    }

    fun addClip(ctx: Context, text: String) {
        if (text.isBlank()) return
        val list = clipHistory(ctx).filter { it.text != text }.toMutableList()
        list.add(0, ClipItem(System.currentTimeMillis().toString(), text, System.currentTimeMillis()))
        while (list.size > CLIP_MAX) list.removeAt(list.size - 1)
        persistClips(ctx, list)
    }

    fun clearClips(ctx: Context) {
        clipFile(ctx).delete()
    }

    private fun persistClips(ctx: Context, list: List<ClipItem>) {
        try {
            val arr = JSONArray()
            list.forEach {
                arr.put(JSONObject().put("t", it.id).put("s", it.text).put("d", it.timeMs))
            }
            clipFile(ctx).writeText(arr.toString())
        } catch (_: Exception) {
        }
    }
}
