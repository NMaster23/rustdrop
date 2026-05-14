package com.nmaster23.rustdrop.android;

import android.app.Activity;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.ParcelFileDescriptor;
import android.provider.DocumentsContract;
import android.provider.OpenableColumns;

import androidx.appcompat.app.AppCompatActivity;

public class MainActivity extends AppCompatActivity {
    private static final int PICK_FILE = 2;
    public native void initJniBridge();
    public native void onBlueChunkReceived(byte[] chunk);
    public native void FilePicker(String filename, int filepath);
    static {
        System.loadLibrary("rustdrop");
    }

    @Override
    protected void onCreate(bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        instance = this;
        initJniBridge();
        startBleServer();
    }

    public static void FilePickerTrigger() {
        if (instance != null) {
            instance.openFile(null);
        }
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
                if (uri == null) {
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
                    }
                    cursor.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
                try (ParcelFileDescriptor filepfd = getContentResolver().openFileDescriptor(uri, "r")) {
                    if (filepfd != null) {
                        int filepath = filepfd.detachFd();
                        this.FilePicker(filename, filepath);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        }
    }
    public void startBleServer() {
        BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);
        BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        BluetoothGattServer gattServer = bluetoothManager.openGattServer(this, new BluetoothGattServerCallback() {
            @Override
            public void onCharacteristicWriteRequest(BluetoothDevice device, int requestId, BluetoothGattCharacteristic characteristic, boolean preparedWrite, boolean responseNeeded, int offset, byte[] value) {
                super.onCharacteristicWriteRequest(device, requestId, characteristic, preparedWrite, responseNeeded, offset, value);
                onBlueChunkReceived(value);

                if (responseNeeded) {
                    
                }
            }
        });
        UUID SERVICE_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef0");
        UUID CHAR_UUID = UUID.fromString("12345678-1234-5678-1234-56789abcdef1");

        BluetoothGattService service = new BluetoothGattService(SERVICE_UUID, BluetoothGattService.SERVICE_TYPE_PRIMARY);
        BluetoothGattCharacteristic characteristic = new BluetoothGattCharacteristic(CHAR_UUID,
                BluetoothGattCharacteristic.PROPERTY_WRITE, BluetoothGattCharacteristic.PERMISSION_WRITE);
        service.addCharacteristic(characteristic);
        gattServer.addService(service);
        BluetoothLeAdvertiser advertiser = bluetoothAdapter.getBluetoothLeAdvertiser();
        AdvertiseSettings settings = new AdvertiseSettings.Builder()
                .setAdvertiseMode(AdvertiseSettings.ADVERTISE_MODE_LOW_LATENCY)
                .setConnectable(true).build();
                
        AdvertiseData data = new AdvertiseData.Builder()
                .setIncludeDeviceName(true)
                .addServiceUuid(new ParcelUuid(SERVICE_UUID)).build();

        advertiser.startAdvertising(settings, data, new AdvertiseCallback() {
            @Override
            public void onStartSuccess(AdvertiseSettings settingsInEffect) {
                System.out.println("Android BLE Server Started!");
            }
        });
    }
}