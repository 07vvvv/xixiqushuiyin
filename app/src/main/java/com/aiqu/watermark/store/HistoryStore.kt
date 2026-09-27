package com.aiqu.watermark.store

import android.content.Context
import com.aiqu.watermark.data.model.HistoryEntry
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/**
 * 历史记录（本地 JSON 文件）
 *
 * 只存轻量元信息，不存完整解析结果 —— 需要时重新解析即可，避免文件膨胀。
 * 用文件而非 Room：少一层 KSP 注解处理，构建更省心；数据量在几百条级别完全够用。
 */
class HistoryStore(context: Context) {

    private val file = File(context.filesDir, "history.json")
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    /**
     * 用显式序列化器（而非 Json.encodeToString / decodeFromString 的 reified 扩展），
     * 因为那两个是 kotlinx.serialization 包下的扩展函数，必须额外 import；
     * 显式写法无导入歧义，任何版本都不会出现「Unresolved reference」。
     */
    private val listSerializer = ListSerializer(HistoryEntry.serializer())
    private val lock = Any()

    private val maxItems = 200

    fun list(): List<HistoryEntry> = synchronized(lock) {
        readAll().sortedByDescending { it.createdAt }
    }

    fun add(entry: HistoryEntry): HistoryEntry = synchronized(lock) {
        val all = readAll().toMutableList()
        val existing = all.indexOfFirst { it.sourceUrl.isNotBlank() && it.sourceUrl == entry.sourceUrl }
        val finalEntry = if (existing >= 0) {
            entry.copy(id = all[existing].id)
        } else {
            entry
        }
        if (existing >= 0) all.removeAt(existing)
        all.add(0, finalEntry)
        writeAll(all.take(maxItems))
        finalEntry
    }

    fun remove(id: String) = synchronized(lock) {
        writeAll(readAll().filterNot { it.id == id })
    }

    fun clear() = synchronized(lock) {
        writeAll(emptyList())
    }

    fun get(id: String): HistoryEntry? = synchronized(lock) {
        readAll().firstOrNull { it.id == id }
    }

    private fun readAll(): List<HistoryEntry> {
        if (!file.exists()) return emptyList()
        return try {
            json.decodeFromString(listSerializer, file.readText())
        } catch (e: Exception) {
            // 文件损坏时不要让应用崩掉，直接当作空历史
            emptyList()
        }
    }

    private fun writeAll(items: List<HistoryEntry>) {
        try {
            file.writeText(json.encodeToString(listSerializer, items))
        } catch (e: Exception) {
            // 写失败不阻断主流程
        }
    }
}
