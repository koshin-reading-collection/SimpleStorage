package com.anggrayudi.storage

import android.content.Context
import android.net.Uri
import android.os.Build
import android.os.StatFs
import android.system.ErrnoException
import android.system.Os
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.extension.getAppDirectory
import java.io.File
import java.io.IOException
import java.io.UnsupportedEncodingException
import java.net.URLDecoder

/**
 * Created on 16/08/20
 * @author Anggrayudi H
 */
object DocumentFileCompat {

    const val PRIMARY = "primary"

    const val MIME_TYPE_UNKNOWN = "*/*"

    const val MIME_TYPE_BINARY_FILE = "application/octet-stream"

    /**
     * @param storageId If in SD card, it should be integers like `6881-2249`. Otherwise, if in external storage it will be [PRIMARY]
     * @param filePath If in Downloads folder of SD card, it will be `Downloads/MyMovie.mp4`. If in internal storage it will be `Downloads/MyMovie.mp4` as well.
     */
    fun fromPath(context: Context, storageId: String = PRIMARY, filePath: String): DocumentFile? {
        return if (filePath.isEmpty()) {
            getRootDocumentFile(context, storageId)
        } else {
            exploreFile(context, storageId, filePath)
        }
    }

    fun getRootDocumentFile(context: Context, storageId: String): DocumentFile? = try {
        DocumentFile.fromTreeUri(context, createDocumentUri(storageId))
    } catch (e: SecurityException) {
        null
    }

    fun createDocumentUri(storageId: String, filePath: String = ""): Uri =
        Uri.parse("content://com.android.externalstorage.documents/tree/" + Uri.encode("$storageId:$filePath"))

    /**
     * Create folders. You should do this process in background.
     *
     * @return `null` if you have no storage permission. Will return to current directory if not success.
     */
    fun mkdirs(context: Context, storageId: String = PRIMARY, folderPath: String): DocumentFile? {
        if (storageId == PRIMARY) {
            // use java.io.File for faster performance
            val file = File(SimpleStorage.externalStoragePath + "/" + folderPath)
            file.mkdirs()
            if (file.isDirectory)
                return DocumentFile.fromFile(file)
        } else {
            var currentDirectory = getRootDocumentFile(context, storageId) ?: return null
            getDirectorySequence(folderPath).forEach {
                try {
                    val file = currentDirectory.findFile(it)
                    if (file == null || file.isFile)
                        currentDirectory = currentDirectory.createDirectory(it) ?: return null
                    else if (file.isDirectory)
                        currentDirectory = file
                } catch (e: Throwable) {
                    return null
                }
            }
            return currentDirectory
        }
        return null
    }

    /**
     * @return `null` if you don't have storage permission.
     */
    fun createFile(context: Context, storageId: String = PRIMARY, filePath: String, mimeType: String = MIME_TYPE_UNKNOWN): DocumentFile? {
        return if (storageId == PRIMARY) {
            val file = File(SimpleStorage.externalStoragePath + "/" + filePath)
            file.parentFile?.mkdirs()
            if (create(file)) DocumentFile.fromFile(file) else null
        } else try {
            val directory = mkdirsParentDirectory(context, storageId, filePath)
            val filename = getFileNameFromPath(filePath)
            if (filename.isEmpty()) null else directory?.createFile(mimeType, filename)
        } catch (e: Throwable) {
            null
        }
    }

    private fun getParentPath(filePath: String): String? = getDirectorySequence(filePath).let { it.getOrNull(it.size - 2) }

    private fun mkdirsParentDirectory(context: Context, storageId: String, filePath: String): DocumentFile? {
        val parentPath = getParentPath(filePath)
        return if (parentPath != null) {
            mkdirs(context, storageId, parentPath)
        } else {
            getRootDocumentFile(context, storageId)
        }
    }

    private fun getFileNameFromPath(filePath: String) = filePath.substringAfterLast('/')

    fun recreate(context: Context, storageId: String = PRIMARY, filePath: String, mimeType: String = MIME_TYPE_UNKNOWN): DocumentFile? {
        if (storageId == PRIMARY) {
            val file = File(filePath)
            file.delete()
            file.parentFile?.mkdirs()
            return if (create(file)) DocumentFile.fromFile(file) else null
        } else {
            val directory = mkdirsParentDirectory(context, storageId, filePath)
            val filename = getFileNameFromPath(filePath)
            if (filename.isEmpty()) {
                return null
            }
            val existingFile = directory?.findFile(filename)
            if (existingFile?.isFile == true) {
                existingFile.delete()
            }
            return directory?.createFile(mimeType, filename)
        }
    }

    private fun create(file: File): Boolean {
        return try {
            file.createNewFile() || file.isFile && file.length() == 0L
        } catch (e: IOException) {
            false
        }
    }

    /**
     * Get [DocumentFile] object from SD card.
     * @param directory SD card ID followed by directory name, for example `6881-2249:Download/Archive`,
     * where ID for SD card is `6881-2249`
     * @param fileName for example `intel_haxm.zip`
     * @return `null` if does not exist
     */
    private fun exploreFile(context: Context, storageId: String, filePath: String): DocumentFile? {
        var current = getRootDocumentFile(context, storageId) ?: return null
        getDirectorySequence(filePath).forEach {
            current = current.findFile(it) ?: return null
        }
        return current
    }

    /**
     * For example, `Downloads/Video/Sports/` will become array `["Downloads", "Video", "Sports"]`
     */
    private fun getDirectorySequence(file: String): Array<String> {
        val cleanedPath = cleanStoragePath(file)
        if (cleanedPath.isNotEmpty()) {
            return if (cleanedPath.contains("/")) {
                cleanedPath.split("/".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
            } else {
                arrayOf(cleanedPath)
            }
        }
        return emptyArray()
    }

    /**
     * It will remove double slash (`/`) when found
     */
    private fun cleanStoragePath(file: String): String {
        val resolvedPath = StringBuilder()
        file.split("/".toRegex())
            .dropLastWhile { it.isEmpty() }
            .map { it.trim { c -> c <= ' ' } }
            .filter { it.isNotEmpty() }
            .forEach { directory -> resolvedPath.append(directory).append("/") }
        return resolvedPath.toString().substringBeforeLast('/')
    }

    private fun recreateAppDirectory(context: Context) = context.getAppDirectory().apply { File(this).mkdirs() }

    /** Get available space in bytes. */
    @Suppress("DEPRECATION")
    fun getFreeSpace(context: Context, storageId: String): Long {
        try {
            if (storageId == PRIMARY) {
                val stat = StatFs(recreateAppDirectory(context))
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2)
                    stat.availableBytes
                else
                    (stat.blockSize * stat.availableBlocks).toLong()
            } else if (Build.VERSION.SDK_INT >= 21) {
                try {
                    val fileUri = getRootDocumentFile(context, storageId)?.uri ?: return 0
                    context.contentResolver.openFileDescriptor(fileUri, "r")?.use {
                        val stats = Os.fstatvfs(it.fileDescriptor)
                        return stats.f_bavail * stats.f_frsize
                    }
                } catch (e: ErrnoException) {
                    // ignore
                }
            }
        } catch (e: SecurityException) {
            // ignore
        } catch (e: IllegalArgumentException) {
            // ignore
        } catch (e: IOException) {
            // ignore
        }
        return 0
    }

    /** Get available space in bytes. */
    @Suppress("DEPRECATION")
    fun getUsedSpace(context: Context, storageId: String): Long {
        try {
            if (storageId == PRIMARY) {
                val stat = StatFs(recreateAppDirectory(context))
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2)
                    stat.totalBytes - stat.availableBytes
                else
                    (stat.blockSize * stat.blockCount - stat.blockSize * stat.availableBlocks).toLong()
            } else if (Build.VERSION.SDK_INT >= 21) {
                try {
                    val fileUri = getRootDocumentFile(context, storageId)?.uri ?: return 0
                    context.contentResolver.openFileDescriptor(fileUri, "r")?.use {
                        val stats = Os.fstatvfs(it.fileDescriptor)
                        return stats.f_blocks * stats.f_frsize - stats.f_bavail * stats.f_frsize
                    }
                } catch (e: ErrnoException) {
                    // ignore
                }
            }
        } catch (e: SecurityException) {
            // ignore
        } catch (e: IllegalArgumentException) {
            // ignore
        } catch (e: IOException) {
            // ignore
        }
        return 0
    }

    @Suppress("DEPRECATION")
    fun getStorageCapacity(context: Context, storageId: String): Long {
        try {
            if (storageId == PRIMARY) {
                val stat = StatFs(recreateAppDirectory(context))
                return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.JELLY_BEAN_MR2)
                    stat.totalBytes
                else
                    (stat.blockSize * stat.blockCount).toLong()
            } else if (Build.VERSION.SDK_INT >= 21) {
                try {
                    val fileUri = getRootDocumentFile(context, storageId)?.uri ?: return 0
                    context.contentResolver.openFileDescriptor(fileUri, "r")?.use {
                        val stats = Os.fstatvfs(it.fileDescriptor)
                        return stats.f_blocks * stats.f_frsize
                    }
                } catch (e: ErrnoException) {
                    // ignore
                }
            }
        } catch (e: SecurityException) {
            // ignore
        } catch (e: IllegalArgumentException) {
            // ignore
        } catch (e: IOException) {
            // ignore
        }
        return 0
    }

    fun getFileNameFromUrl(url: String): String {
        return try {
            URLDecoder.decode(url, "UTF-8").substringAfterLast('/')
        } catch (e: UnsupportedEncodingException) {
            url
        }
    }
}