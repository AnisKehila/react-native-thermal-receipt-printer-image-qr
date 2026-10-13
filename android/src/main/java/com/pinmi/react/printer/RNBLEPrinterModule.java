package com.pinmi.react.printer;

import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Build;
import android.util.Base64;
import android.util.Log;

import com.facebook.react.bridge.Arguments;
import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;
import com.facebook.react.bridge.ReactContextBaseJavaModule;
import com.facebook.react.bridge.ReactMethod;
import com.facebook.react.bridge.WritableArray;
import com.facebook.react.modules.core.DeviceEventManagerModule;
import com.pinmi.react.printer.adapter.BLEPrinterAdapter;
import com.pinmi.react.printer.adapter.BLEPrinterDeviceId;
import com.pinmi.react.printer.adapter.PrinterAdapter;
import com.pinmi.react.printer.adapter.PrinterDevice;
//import com.pinmi.react.printer.adapter.PrinterOption;

import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Created by xiesubin on 2017/9/22.
 */

public class RNBLEPrinterModule extends ReactContextBaseJavaModule implements RNPrinterModule {

    public static final String EVENT_DISCONNECTED = "RNBLEPrinterDisconnected";

    // Context.RECEIVER_EXPORTED, inlined because this library compiles against SDK 32.
    private static final int RECEIVER_EXPORTED = 0x2;

    protected ReactApplicationContext reactContext;

    protected PrinterAdapter adapter;

    // Connecting and image printing block on the Bluetooth socket. Running them here
    // keeps them off the native modules thread shared by every other module, and
    // serializes them so a print never races a connect.
    private final ExecutorService ioExecutor = Executors.newSingleThreadExecutor();

    private final BroadcastReceiver aclReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (!BluetoothDevice.ACTION_ACL_DISCONNECTED.equals(intent.getAction())) return;
            BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
            if (device != null) {
                BLEPrinterAdapter.getInstance().handleDeviceDisconnected(device.getAddress());
            }
        }
    };

    public RNBLEPrinterModule(ReactApplicationContext reactContext) {
        super(reactContext);
        this.reactContext = reactContext;

        BLEPrinterAdapter.getInstance().setConnectionListener(new BLEPrinterAdapter.ConnectionListener() {
            @Override
            public void onDisconnected(String address) {
                emitDisconnected(address);
            }
        });

        IntentFilter filter = new IntentFilter(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        try {
            if (Build.VERSION.SDK_INT >= 33) {
                reactContext.registerReceiver(aclReceiver, filter, RECEIVER_EXPORTED);
            } else {
                reactContext.registerReceiver(aclReceiver, filter);
            }
        } catch (RuntimeException e) {
            Log.w("RNBLEPrinter", "Cannot listen for disconnects: " + e.getMessage());
        }
    }

    private void emitDisconnected(String address) {
        if (!reactContext.hasActiveReactInstance()) return;
        reactContext
                .getJSModule(DeviceEventManagerModule.RCTDeviceEventEmitter.class)
                .emit(EVENT_DISCONNECTED, address);
    }

    private BLEPrinterAdapter bleAdapter() {
        if (this.adapter == null) {
            this.adapter = BLEPrinterAdapter.getInstance();
        }
        return (BLEPrinterAdapter) this.adapter;
    }

    @Override
    public void invalidate() {
        try {
            reactContext.unregisterReceiver(aclReceiver);
        } catch (IllegalArgumentException e) {
            // Receiver was never registered.
        }
        BLEPrinterAdapter.getInstance().setConnectionListener(null);
        ioExecutor.shutdown();
        super.invalidate();
    }


    @ReactMethod
    @Override
    public void init(Callback successCallback, Callback errorCallback) {
        this.adapter = BLEPrinterAdapter.getInstance();
        this.adapter.init(reactContext, successCallback, errorCallback);
    }

    @ReactMethod
    @Override
    public void closeConn() {
        bleAdapter().closeConnectionIfExists();
    }

    @ReactMethod
    @Override
    public void getDeviceList(Callback successCallback, final Callback errorCallback) {
        // A React Native callback may only be invoked once: remember whether the
        // adapter already reported an error before falling back to NO_DEVICES.
        final boolean[] errored = { false };
        List<PrinterDevice> printerDevices = bleAdapter().getDeviceList(new Callback() {
            @Override
            public void invoke(Object... args) {
                errored[0] = true;
                errorCallback.invoke(args);
            }
        });
        if (errored[0]) return;
        WritableArray pairedDeviceList = Arguments.createArray();
        if (printerDevices.size() > 0) {
            for (PrinterDevice printerDevice : printerDevices) {
                pairedDeviceList.pushMap(printerDevice.toRNWritableMap());
            }
            successCallback.invoke(pairedDeviceList);
        } else {
            errorCallback.invoke("NO_DEVICES");
        }
    }


    @ReactMethod
    @Override
    public void printRawData(String base64Data, Callback errorCallback) {
        bleAdapter().printRawData(base64Data, errorCallback);
    }

    @ReactMethod
    @Override
    public void printImageData(String imageUrl, int imageWidth, int imageHeight, Callback errorCallback) {
        Log.v("imageUrl", imageUrl);
        bleAdapter().printImageData(imageUrl, imageWidth, imageHeight,errorCallback);
    }

    @ReactMethod
    @Override
    public void printImageBase64(String base64, int imageWidth, int imageHeight, Callback errorCallback) {
        byte[] decodedString = Base64.decode(base64, Base64.DEFAULT);
        Bitmap decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
        bleAdapter().printImageBase64(decodedByte, imageWidth, imageHeight,errorCallback);
    }

    @ReactMethod
    public void printImageBase64WithResult(final String base64, final int imageWidth, final int imageHeight,
                                           final Callback successCallback, final Callback errorCallback) {
        final BLEPrinterAdapter ble = bleAdapter();
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                byte[] decodedString = Base64.decode(base64, Base64.DEFAULT);
                Bitmap decodedByte = BitmapFactory.decodeByteArray(decodedString, 0, decodedString.length);
                ble.printImageBase64WithResult(decodedByte, imageWidth, imageHeight, successCallback, errorCallback);
            }
        });
    }

    @ReactMethod
    public void connectPrinter(final String innerAddress, final Callback successCallback, final Callback errorCallback) {
        final BLEPrinterAdapter ble = bleAdapter();
        ioExecutor.execute(new Runnable() {
            @Override
            public void run() {
                ble.selectDevice(BLEPrinterDeviceId.valueOf(innerAddress), successCallback, errorCallback);
            }
        });
    }

    @ReactMethod
    public void isConnected(Callback callback) {
        callback.invoke(bleAdapter().isConnected(), bleAdapter().getConnectedAddress());
    }

    // Required by NativeEventEmitter.
    @ReactMethod
    public void addListener(String eventName) {
    }

    @ReactMethod
    public void removeListeners(double count) {
    }

    @Override
    public String getName() {
        return "RNBLEPrinter";
    }
}
