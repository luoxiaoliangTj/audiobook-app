package com.example.audiobookapp

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Environment
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
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
import java.util.List

class MainActivity : AppCompatActivity() {

    private lateinit var btnBrowse: Button
    private lateinit var tvPath: TextView
    private lateinit var lvFiles: ListView
    private val PERMISSION_REQUEST_CODE = 100
    private var currentPath: File? = null
    private lateinit var fileAdapter: ArrayAdapter<String>

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        btnBrowse = findViewById(R.id.btnBrowse)
        tvPath = findViewById(R.id.tvPath)
        lvFiles = findViewById(R.id.lvFiles)

        // Request storage permissions
        if (ContextCompat.checkSelfPermission(
                this,
                Manifest.permission.READ_EXTERNAL_STORAGE
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE),
                PERMISSION_REQUEST_CODE
            )
        } else {
            loadFiles()
        }

        btnBrowse.setOnClickListener {
            // In a real app, you'd use a proper file picker
            // For simplicity, we'll just show documents directory
            currentPath = Environment.getExternalStoragePublicDirectory(
                Environment.DIRECTORY_DOCUMENTS
            )
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
                // Open reader activity
                val intent = Intent(this, ReaderActivity::class.java)
                intent.putExtra("file_path", selectedFile.absolutePath)
                startActivity(intent)
            } else {
                Toast.makeText(this, "Please select a PDF or EPUB file", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun loadFiles() {
        tvPath.text = currentPath?.absolutePath ?: "No path"
        val files = FileArrayList()
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
            }
        }
    }
}