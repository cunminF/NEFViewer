package com.nefviewer.android.importer

import androidx.documentfile.provider.DocumentFile

/** SAF 树目录工具：按相对路径逐级查找/创建目录，避免 O(n²) 重复遍历 */
object SafTree {

    fun ensureDir(root: DocumentFile, relativePath: String): DocumentFile {
        var cur = root
        if (relativePath.isBlank()) return cur
        for (part in relativePath.split("/").filter { it.isNotBlank() }) {
            val next = cur.findFile(part)?.takeIf { it.isDirectory }
                ?: cur.createDirectory(part)
                ?: throw IllegalStateException("无法创建目录: $part")
            cur = next
        }
        return cur
    }

    /** 找到相对路径所在目录（不创建），不存在返回 null */
    fun findDir(root: DocumentFile, relativePath: String): DocumentFile? {
        var cur: DocumentFile = root
        if (relativePath.isBlank()) return cur
        for (part in relativePath.split("/").filter { it.isNotBlank() }) {
            cur = cur.findFile(part)?.takeIf { it.isDirectory } ?: return null
        }
        return cur
    }

    /** 在 dir 下创建文件；已存在则先删（DocumentFile 不支持覆盖写） */
    fun createOrReplace(dir: DocumentFile, name: String, mime: String): DocumentFile? {
        dir.findFile(name)?.delete()
        return dir.createFile(mime, name)
    }

    /** 相对路径的父目录部分（"a/b/c.NEF" → "a/b"；无父目录返回 ""） */
    fun parentOf(relativePath: String): String = relativePath.substringBeforeLast('/', "")
}
