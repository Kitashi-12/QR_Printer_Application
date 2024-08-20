package com.example.qrprinter

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Bundle
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class MainActivity : Activity() {
    private var bluetoothAdapter: BluetoothAdapter? = null
    private var selectedDevice: BluetoothDevice? = null
    private var tvSelectedFile: TextView? = null
    private var ivSelectedImage: ImageView? = null
    private var fileUri: Uri? = null
    private var selectedBitmap: Bitmap? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val btnSearchDevice = findViewById<Button>(R.id.btnSearchDevice)
        val btnSelectFile = findViewById<Button>(R.id.btnSelectFile)
        val btnPrint = findViewById<Button>(R.id.btnPrint)
        tvSelectedFile = findViewById(R.id.tvSelectedFile)
        ivSelectedImage = findViewById(R.id.ivSelectedImage)

        bluetoothAdapter = BluetoothAdapter.getDefaultAdapter()
        if (bluetoothAdapter == null) {
            Toast.makeText(this, "Bluetooth desteklenmiyor", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        btnSearchDevice.setOnClickListener {
            if (ContextCompat.checkSelfPermission(
                    this@MainActivity,
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this@MainActivity,
                    arrayOf(Manifest.permission.ACCESS_FINE_LOCATION),
                    REQUEST_FINE_LOCATION
                )
            } else {
                searchDevices()
            }
        }

        btnSelectFile.setOnClickListener { selectFile() }
        btnPrint.setOnClickListener { printImage() }
    }

    private fun searchDevices() {
        if (!bluetoothAdapter!!.isEnabled) {
            val enableBtIntent = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            startActivityForResult(enableBtIntent, REQUEST_ENABLE_BT)
            return
        }

        val pairedDevices = bluetoothAdapter!!.bondedDevices
        val deviceNames = ArrayList<String>()
        val devices = ArrayList<BluetoothDevice>()

        if (pairedDevices.isNotEmpty()) {
            for (device in pairedDevices) {
                deviceNames.add(device.name)
                devices.add(device)
            }

            val adapter = ArrayAdapter(this, android.R.layout.simple_list_item_1, deviceNames)

            val builder = AlertDialog.Builder(this)
            builder.setTitle("Cihaz Seçin")
            builder.setAdapter(
                adapter
            ) { _, which ->
                selectedDevice = devices[which]
                Toast.makeText(
                    this@MainActivity,
                    "Seçilen Cihaz: " + selectedDevice!!.name,
                    Toast.LENGTH_SHORT
                ).show()
            }
            builder.setNegativeButton("İptal", null)
            builder.show()
        } else {
            Toast.makeText(this, "Eşleşmiş cihaz bulunamadı", Toast.LENGTH_SHORT).show()
        }
    }

    private fun selectFile() {
        val intent = Intent(Intent.ACTION_GET_CONTENT)
        intent.type = "image/*" // Sadece görüntü dosyalarını göster
        startActivityForResult(intent, REQUEST_SELECT_FILE)
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_ENABLE_BT && resultCode == RESULT_OK) {
            searchDevices()
        } else if (requestCode == REQUEST_SELECT_FILE && resultCode == RESULT_OK) {
            if (data != null) {
                fileUri = data.data
                tvSelectedFile!!.text = fileUri!!.path

                // Resmi bitmap formatına dönüştür ve görüntüle
                selectedBitmap = convertImageToBitmap(fileUri!!)
                if (selectedBitmap != null) {
                    ivSelectedImage!!.setImageBitmap(selectedBitmap)
                    ivSelectedImage!!.visibility = ImageView.VISIBLE
                } else {
                    Toast.makeText(this, "Resim yüklenemedi", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun convertImageToBitmap(uri: Uri): Bitmap? {
        return try {
            val inputStream: InputStream? = contentResolver.openInputStream(uri)
            BitmapFactory.decodeStream(inputStream)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    private fun printImage() {
        if (selectedBitmap != null) {
            sendBitmapToPrinter(selectedBitmap!!)
        } else {
            Toast.makeText(this, "Bir resim seçilmedi", Toast.LENGTH_SHORT).show()
        }
    }

    private fun sendBitmapToPrinter(bitmap: Bitmap) {
        if (selectedDevice == null) {
            Toast.makeText(this, "Bir cihaz seçilmedi", Toast.LENGTH_SHORT).show()
            return
        }

        // UUID değiştirmeniz gerekebilir. Bu, SPP (Serial Port Profile) UUID'sidir.
        val uuid = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
        var socket: BluetoothSocket? = null

        try {
            socket = selectedDevice!!.createRfcommSocketToServiceRecord(uuid)
            socket.connect()

            val outputStream: OutputStream = socket.outputStream
            val bytes = bitmapToEscPosCommands(bitmap)
            outputStream.write(bytes)
            outputStream.flush()
            outputStream.close()
            socket.close()

            Toast.makeText(this, "Resim yazıcıya gönderildi", Toast.LENGTH_SHORT).show()
        } catch (e: IOException) {
            e.printStackTrace()
            Toast.makeText(this, "Resim gönderilemedi", Toast.LENGTH_SHORT).show()
            try {
                socket?.close()
            } catch (closeException: IOException) {
                closeException.printStackTrace()
            }
        }
    }

    private fun bitmapToEscPosCommands(bitmap: Bitmap): ByteArray {
        val width = bitmap.width
        val height = bitmap.height
        val bytes = ByteArray((width / 8 + 1) * height + 8) // *8 bit for width

        var index = 0

        // Başlangıç komutları
        bytes[index++] = 0x1B.toByte() // ESC
        bytes[index++] = 0x2A.toByte() // *
        bytes[index++] = 0x21.toByte() // (mode)
        bytes[index++] = (width / 8).toByte() // Width in bytes
        bytes[index++] = (height % 256).toByte() // Height low byte
        bytes[index++] = (height / 256).toByte() // Height high byte

        for (y in 0 until height) {
            var byte = 0
            for (x in 0 until width) {
                if (bitmap.getPixel(x, y) == 0xFF000000.toInt()) { // Siyah piksel
                    byte = byte or (1 shl (7 - (x % 8)))
                }
                if (x % 8 == 7 || x == width - 1) {
                    bytes[index++] = byte.toByte()
                    byte = 0
                }
            }
        }

        // Satır sonu
        bytes[index++] = 0x0A.toByte()
        return bytes
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<String>,
        grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_FINE_LOCATION && grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
            searchDevices()
        } else {
            Toast.makeText(this, "İzin reddedildi", Toast.LENGTH_SHORT).show()
        }
    }

    companion object {
        private const val REQUEST_ENABLE_BT = 1
        private const val REQUEST_SELECT_FILE = 2
        private const val REQUEST_FINE_LOCATION = 3
    }
}
