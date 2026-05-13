package com.nmaster23.rustdrop.android;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.provider.DocumentsContract;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int PICK_FILE = 2;
    public static final String ACTION_OPEN_DOCUMENT;
    static {
        System.loadLibrary("rustdrop");
    }

    private void openFile(Uri pickerInitialUri) {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        intent.addCategory(Intent.CATEGORY_OPENABLE);
        intent.setType("*/*"); // Changed to allow any file type
        intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, pickerInitialUri);

        startActivityForResult(intent, PICK_FILE);
    }

    @Override
    public void onActivityResult(int requestCode, int resultCode, Intent resultData) { // Removed extra closing brace here
        if (requestCode == PICK_FILE && resultCode == Activity.RESULT_OK) {
            Uri uri = null;
            if (resultData != null) {
                uri = resultData.getData();
                if (uri = null) {
                    return;
                }
                Cursor cursor = getContentResolver().query(uri, null, null, null, null);
                String filename = null;
                try {
                    if (cursor != null && cursor.moveToFirst()) {
                        int nameindex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);
                        if (nameindex != -1) {
                            filename = cursor.getString(nameindex);
                        }
                        int pathindex = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME);

                        cursor.close();
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
                try {
                    ParcelFileDescriptor filepfd = getContentResolver().openAssetFileDescriptor(uri, "r");
                    if (filepfd != null) {
                        int filepath = filepfd.detachFd();
                        this.onFilePicked(filename, filepath);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
}