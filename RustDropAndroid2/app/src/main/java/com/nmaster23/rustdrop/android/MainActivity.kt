package com.nmaster23.rustdrop.android

import android.R
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

private var selectedUri by mutableStateOf<Uri?>(null)

class MainActivity : ComponentActivity() {
    private val openDocumentLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri: Uri? ->
        selectedUri = uri
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            RustDropAndroidTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    Greeting(
                        modifier = Modifier.padding(innerPadding),
                        onOpenFile = { openFile() }
                    )
                }
            }
        }
    }
    fun openFile() {
        openDocumentLauncher.launch(arrayOf("application/pdf"))
    }
}

@Composable
fun Greeting(modifier: Modifier = Modifier, onOpenFile: () -> Unit) {
    Column(modifier = modifier) {
        Text(
            text = selectedUri?.toString() ?: "No file selected"
        )
        Button(onClick = onOpenFile) {
            Text("Select File")
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