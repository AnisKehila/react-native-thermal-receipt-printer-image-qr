package com.pinmi.react.printer.adapter;

import android.bluetooth.BluetoothDevice;
import android.os.ParcelUuid;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.bridge.WritableMap;

/**
 * Created by xiesubin on 2017/9/21.
 */

public class BLEPrinterDevice implements PrinterDevice {
    private BluetoothDevice mBluetoothDevice;
    private BLEPrinterDeviceId mPrinterDeviceId;

    public BLEPrinterDevice(BluetoothDevice bluetoothDevice) {
        this.mBluetoothDevice = bluetoothDevice;
        this.mPrinterDeviceId = BLEPrinterDeviceId.valueOf(bluetoothDevice.getAddress());
    }

    @Override
    public PrinterDeviceId getPrinterDeviceId() {
        return this.mPrinterDeviceId;
    }

    @Override
    public WritableMap toRNWritableMap() {
        WritableMap deviceMap = Arguments.createMap();
        deviceMap.putString("inner_mac_address", this.mPrinterDeviceId.getInnerMacAddress());
        deviceMap.putString("device_name", this.mBluetoothDevice.getName());
        
        // Add device UUIDs
        WritableArray uuidArray = Arguments.createArray();
        ParcelUuid[] uuids = this.mBluetoothDevice.getUuids();
        if (uuids != null) {
            for (ParcelUuid uuid : uuids) {
                uuidArray.pushString(uuid.getUuid().toString());
            }
        }
        deviceMap.putArray("uuids", uuidArray);
        
        return deviceMap;
    }
}
