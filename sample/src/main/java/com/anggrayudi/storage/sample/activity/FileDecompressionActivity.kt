package com.anggrayudi.storage.sample.activity

import android.os.Bundle
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import com.anggrayudi.storage.callback.ZipDecompressionCallback
import com.anggrayudi.storage.file.MimeType
import com.anggrayudi.storage.file.decompressZip
import com.anggrayudi.storage.file.fullName
import com.anggrayudi.storage.file.getAbsolutePath
import com.anggrayudi.storage.sample.R
import kotlinx.android.synthetic.main.activity_file_decompression.*
import kotlinx.android.synthetic.main.view_file_picked.view.*
import kotlinx.coroutines.launch

/**
 * Created on 04/01/22
 * @author Anggrayudi H
 */
class FileDecompressionActivity : BaseActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_decompression)
        setupSimpleStorage()
        btnStartDecompress.setOnClickListener { startDecompress() }
    }

    private fun setupSimpleStorage() {
        storageHelper.onFileSelected = { _, files ->
            val file = files.first()
            layoutDecompressFile_srcZip.run {
                tag = file
                tvFilePath.text = file.fullName
            }
        }
        layoutDecompressFile_srcZip.btnBrowse.setOnClickListener {
            storageHelper.openFilePicker(filterMimeTypes = arrayOf(MimeType.ZIP))
        }

        storageHelper.onFolderSelected = { _, folder ->
            layoutDecompressFile_destFolder.run {
                tag = folder
                tvFilePath.text = folder.getAbsolutePath(context)
            }
        }
        layoutDecompressFile_destFolder.btnBrowse.setOnClickListener {
            storageHelper.openFolderPicker()
        }
    }

    private fun startDecompress() {
        val zipFile = layoutDecompressFile_srcZip.tag as? DocumentFile
        if (zipFile == null) {
            Toast.makeText(this, "Please select source ZIP file", Toast.LENGTH_SHORT).show()
            return
        }
        val targetFolder = layoutDecompressFile_destFolder.tag as? DocumentFile
        if (targetFolder == null) {
            Toast.makeText(this, "Please select destination folder", Toast.LENGTH_SHORT).show()
            return
        }
        ioScope.launch {
            zipFile.decompressZip(applicationContext, targetFolder, object : ZipDecompressionCallback<DocumentFile>(uiScope) {
                override fun onCompleted(
                    zipFile: DocumentFile,
                    targetFolder: DocumentFile,
                    bytesDecompressed: Long,
                    totalFilesDecompressed: Int,
                    decompressionRate: Float
                ) {
                    Toast.makeText(applicationContext, "Decompressed $totalFilesDecompressed files from ${zipFile.name}", Toast.LENGTH_SHORT).show()
                }

                override fun onFailed(errorCode: ErrorCode) {
                    Toast.makeText(applicationContext, "$errorCode", Toast.LENGTH_SHORT).show()
                }
            })
        }
    }
}