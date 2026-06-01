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
import android.location.LocationManager
import java.util.UUID
import android.os.Build
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
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
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
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
    val scope = rememberCoroutineScope()
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val granted = permissions.entries.all { it.value }
        if (granted) {
            discoveredDevices.clear()
            Bluetooth(context)
        } else {
            Toast.makeText(context, "Permissions required", Toast.LENGTH_SHORT).show()
        }
    }
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
                val permissions = mutableListOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                )
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    permissions.add(Manifest.permission.BLUETOOTH_SCAN)
                    permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
                }
                
                permissionLauncher.launch(permissions.toTypedArray())
            },
            modifier = Modifier.size(width = 250.dp, height = 80.dp)
        ) {
            Text("Start Discovery")
        }
        Spacer(modifier = Modifier.height(25.dp))
        Text("Discovered: ", fontSize = 20.sp)
        LazyColumn(
            modifier = Modifier.weight(1f).fillMaxSize().padding(horizontal = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            items(discoveredDevices.values.toList()) { device ->
                val deviceName = try { device.name } catch (_: SecurityException) { null }
                Button(
                    onClick = {
                        val connectPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                            ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
                        } else true

                        if (connectPermission) {
                            scope.launch(Dispatchers.IO) {
                                try {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Connecting to ${device.address}...", Toast.LENGTH_SHORT).show()
                                    }
                                    
                                    val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
                                    val bluetoothAdapter: BluetoothAdapter? = bluetoothManager.adapter
                                    val remoteDevice = bluetoothAdapter?.getRemoteDevice(device.address)
                                    val socket = remoteDevice?.createRfcommSocketToServiceRecord(targetService)
                                    
                                    socket?.connect()
                                    
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Connected!", Toast.LENGTH_SHORT).show()
                                    }
                                    
                                } catch (e: Exception) {
                                    withContext(Dispatchers.Main) {
                                        Toast.makeText(context, "Connection failed: ${e.message}", Toast.LENGTH_LONG).show()
                                    }
                                }
                            }
                        } else {
                            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                                permissionLauncher.launch(arrayOf(Manifest.permission.BLUETOOTH_CONNECT))
                            }
                        }
                    }
                ) {
                    Text(text = deviceName ?: device.address)
                }
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

    if (bluetoothAdapter == null) {
        Toast.makeText(context, "Bluetooth not supported", Toast.LENGTH_SHORT).show()
        return
    }

    if (!bluetoothAdapter.isEnabled) {
        Toast.makeText(context, "Please enable Bluetooth", Toast.LENGTH_SHORT).show()
        return
    }
    val locationManager = context.getSystemService(Context.LOCATION_SERVICE) as LocationManager
    val isLocationEnabled = locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER) ||
            locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER)
            
    if (!isLocationEnabled) {
        Toast.makeText(context, "Please turn on System Location/GPS", Toast.LENGTH_LONG).show()
        return
    }

    val hasScanPermission = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        ActivityCompat.checkSelfPermission(context, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
    } else {
        ActivityCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    }

    if (hasScanPermission) {
        if (bluetoothAdapter.isDiscovering) {
            bluetoothAdapter.cancelDiscovery()
        }
        if (bluetoothAdapter.startDiscovery()) {
            Toast.makeText(context, "Discovery started...", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(context, "Failed to start discovery. Try toggling Bluetooth.", Toast.LENGTH_LONG).show()
        }
    }
}

private val receiver = object : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            BluetoothDevice.ACTION_FOUND -> {
                val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE, BluetoothDevice::class.java)
                } else {
                    @Suppress("DEPRECATION")
                    intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)
                }
                device?.let {
                    discoveredDevices[it.address] = it
                }
            }
        }
    }
}