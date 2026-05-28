package com.nmaster23.rustdrop.android

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.nmaster23.rustdrop.android.ui.theme.RustDropAndroidTheme
import androidx.compose.material3.Button
import androidx.compose.foundation.layout.Column
import java.util.UUID
import androidx.core.content.ContextCompat.startActivity
import android.Manifest
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.pm.PackageManager
import androidx.core.app.ActivityCompat
import android.content.IntentFilter
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.height
import androidx.compose.ui.Alignment
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import java.util.HashMap

private var selectedUri by mutableStateOf<Uri?>(null)
private val discoveredDevices = mutableStateMapOf<String, BluetoothDevice>()

val targetService: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0")
val targetChar: UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1")

class MainActivity : ComponentActivity() {
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        selectedUri = uri
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val filter = IntentFilter(BluetoothDevice.ACTION_FOUND)
        registerReceiver(receiver, filter)
        enableEdgeToEdge()
        setContent {
            RustDropAndroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        modifier = Modifier.padding(innerPadding),
                        onOpenFile = { openFile() },

                    )
                }
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            unregisterReceiver(receiver)
        } catch (_: Exception) { }
    }
    fun openFile() {
        openDocumentLauncher.launch(arrayOf("*/*"))
    }
}

@Composable
fun Greeting(modifier: Modifier = Modifier, onOpenFile: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = modifier.fillMaxSize(),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(
            text = selectedUri?.toString() ?: "No file selected",
            fontSize = 24.sp
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(
            onClick = onOpenFile,
            modifier = Modifier.size(width = 400.dp, height = 150.dp)
        ) {
            Text("Select File")
        }
        Spacer(modifier = Modifier.height(20.dp))
        Button(
            onClick = {
                discoveredDevices.clear()
                Bluetooth(context)
            },
            modifier = Modifier.size(width = 250.dp, height = 80.dp)
        ) {
            Text("Start Discovery")
        }
        Spacer(modifier = Modifier.height(25.dp))
        Text("Discovered: ", fontSize = 20.sp)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(discoveredDevices.values.toList()) { device ->
                Text(
                    text = "${device.address} (${device.address})",
                    modifier = Modifier.padding(8.dp)
                )
            }
        }
    }
}

@Preview(showBackground = true)
@Composable
fun GreetingPreview() {
    RustDropAndroidTheme {
        Greeting(onOpenFile = {})
    }
}

fun Bluetooth(context: Context) {
    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
    if (bluetoothAdapter?.state == BluetoothAdapter.STATE_OFF) {
        if (ActivityCompat.checkSelfPermission(
                context,
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            val intentEnable = Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)
            context.startActivity(intentEnable)
        }
    }
    if (ActivityCompat.checkSelfPermission(
            context,
            Manifest.permission.BLUETOOTH_SCAN
        ) == PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT < android.os.Build.VERSION_CODES.S
    ) {
        if (bluetoothAdapter?.isDiscovering == false) {
            bluetoothAdapter.startDiscovery()
        }
    }
}

private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothDevice.ACTION_FOUND -> {
                val device: BluetoothDevice? = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                device?.let {
                    discoveredDevices[it.address] = it
                }
            }
        }
    }
}