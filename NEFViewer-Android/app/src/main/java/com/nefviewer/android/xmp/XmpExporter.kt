package com.nefviewer.android.xmp

import android.content.Context
import androidx.documentfile.provider.DocumentFile
import android.net.Uri
import com.nefviewer.android.data.PhotoEntity
import com.nefviewer.android.data.ProjectEntity
import java.io.File

/** 评分导出为 XMP sidecar（xmp:Rating，与 Lightroom/Bridge 互认） */
object XmpExporter {

    fun xmpContent(rating: Int): String = """<?xml version="1.0" encoding="UTF-8"?>
<x:xmpmeta xmlns:x="adobe:ns:meta/">
 <rdf:RDF xmlns:rdf="http://www.w3.org/1999/02/22-rdf-syntax-ns#">
  <rdf:Description rdf:about=""
   xmlns:xmp="http://ns.adobe.com/xap/1.0/"
   xmp:Rating="$rating"/>
 </rdf:RDF>
</x:xmpmeta>
"""

    private fun sidecarName(fileName: String): String =
        fileName.substringBeforeLast('.') + ".xmp"

    /**
     * 导出某个项目全部已评分照片。
     * 应用私有图库：写在 NEF 旁边；SAF 图库/链接模式：写进对应 SAF 树（副本旁/树根）。
     * 返回写出数量。
     */
    fun export(context: Context, project: ProjectEntity, photos: List<PhotoEntity>): Int {
        val rated = photos.filter { it.rating > 0 }
        var count = 0
        when {
            project.libraryPath != null -> {
                val root = File(project.libraryPath)
                for (p in rated) {
                    runCatching {
                        val nef = File(root, p.relativePath ?: p.fileName)
                        val xmp = File(nef.parentFile, sidecarName(p.fileName))
                        xmp.writeText(xmpContent(p.rating))
                        count++
                    }
                }
            }
            project.libraryUri != null -> {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(project.libraryUri)) ?: return 0
                for (p in rated) {
                    runCatching {
                        val dir = com.nefviewer.android.importer.SafTree.findDir(
                            tree, com.nefviewer.android.importer.SafTree.parentOf(p.relativePath ?: "")
                        ) ?: tree
                        val name = sidecarName(p.fileName)
                        val doc = com.nefviewer.android.importer.SafTree.createOrReplace(
                            dir, name, "application/rdf+xml"
                        ) ?: return@runCatching
                        context.contentResolver.openOutputStream(doc.uri)?.use {
                            it.write(xmpContent(p.rating).toByteArray())
                            count++
                        }
                    }
                }
            }
            else -> {
                val tree = DocumentFile.fromTreeUri(context, Uri.parse(project.sourceUri)) ?: return 0
                for (p in rated) {
                    runCatching {
                        val name = sidecarName(p.fileName)
                        val doc = com.nefviewer.android.importer.SafTree.createOrReplace(
                            tree, name, "application/rdf+xml"
                        ) ?: return@runCatching
                        context.contentResolver.openOutputStream(doc.uri)?.use {
                            it.write(xmpContent(p.rating).toByteArray())
                            count++
                        }
                    }
                }
            }
        }
        return count
    }
}
