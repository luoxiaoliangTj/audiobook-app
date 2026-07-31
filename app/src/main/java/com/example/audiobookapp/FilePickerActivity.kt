package com.example.audiobookapp

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ListView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.util.ArrayList

class FilePickerActivity : AppCompatActivity() {

    private lateinit var lvFiles: ListView
    private lateinit var tvPath: TextView
    private var currentPath: File? = null
    private lateinit var fileAdapter: ArrayAdapter<String>
    private val PERMISSION_REQUEST_CODE = 100
    private val RESULT_CODE_FILE_SELECTED = 101

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_file_picker)

        lvFiles = findViewById(R.id.lvFiles)
        tvPath = findViewById(R.id.tvPath)

        // Request storage permissions
        if (ContextCompat.checkSelfPermission(
                this,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(android.Manifest.permission.READ_EXTERNAL_STORAGE),
                PERMISSION_REQUEST_CODE
            )
        } else {
            loadFiles()
        }

        lvFiles.setOnItemClickListener { parent, view, position, id ->
            val fileName = lvFiles.getItemAtPosition(position) as String
            val selectedFile = File(currentPath!!, fileName)
            if (selectedFile.isDirectory) {
                currentPath = selectedFile
                loadFiles()
            } else if (fileName.endsWith(".pdf", ignoreCase = true) ||
                fileName.endsWith(".epub", ignoreCase = true)) {
                // Return the selected file
                val resultIntent = Intent()
                resultIntent.data = Uri.fromFile(selectedFile)
                setResult(RESULT_OK, resultIntent)
                finish()
            } else {
                Toast.makeText(this, "Please select a PDF or EPUB file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadFiles() {
        tvPath.text = currentPath?.absolutePath ?: "No path"
        val files = ArrayList<String>()
        if (currentPath != null && currentPath!!.exists()) {
            val fileList = currentPath!!.listFiles()
            if (fileList != null) {
                for (file in fileList) {
                    files.add(file.name)
                }
            }
        }
        // Add parent directory option if not at root
        if (currentPath != null && currentPath!!.parentFile != null) {
            files.add(0, "..")
        }
        fileAdapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, files)
        lvFiles.adapter = fileAdapter
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                loadFiles()
            } else {
                Toast.makeText(this, "Permission denied", Toast.LENGTH_SHORT).show()
                finish()
            }
        }
    }
}