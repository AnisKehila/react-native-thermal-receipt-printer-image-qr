package com.pinmi.react.printer.adapter;
import static com.pinmi.react.printer.adapter.UtilsImage.getPixelsSlow;
import static com.pinmi.react.printer.adapter.UtilsImage.recollectSlice;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.graphics.Bitmap;
import android.util.Base64;
import android.util.Log;

import com.facebook.react.bridge.Callback;
import com.facebook.react.bridge.ReactApplicationContext;

import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.util.ArrayList;
import java.net.URL;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import android.graphics.BitmapFactory;
/**
 * Created by xiesubin on 2017/9/21.
 */

public class BLEPrinterAdapter implements PrinterAdapter{

    // Error codes passed to JS error callbacks. The app maps them to translated messages.
    public static final String ERROR_NO_ADAPTER = "NO_ADAPTER";
    public static final String ERROR_BT_DISABLED = "BT_DISABLED";
    public static final String ERROR_PERMISSION_DENIED = "PERMISSION_DENIED";
    public static final String ERROR_NOT_PAIRED = "NOT_PAIRED";
    public static final String ERROR_CONNECT_FAILED = "CONNECT_FAILED";
    public static final String ERROR_NOT_CONNECTED = "NOT_CONNECTED";
    public static final String ERROR_WRITE_FAILED = "WRITE_FAILED";
    public static final String ERROR_IMAGE_NOT_FOUND = "IMAGE_NOT_FOUND";

    // BluetoothSocket.connect() has no timeout of its own and can block for a long
    // time when the printer is off or out of range; closing the socket aborts it.
    private static final long CONNECT_TIMEOUT_MS = 8000;

    public interface ConnectionListener {
        void onDisconnected(String address);
    }

    private static BLEPrinterAdapter mInstance;


    private final String LOG_TAG = "RNBLEPrinter";

    private BluetoothDevice mBluetoothDevice;
    private BluetoothSocket mBluetoothSocket;
    private ConnectionListener mConnectionListener;

    private final ScheduledExecutorService mTimeoutScheduler = Executors.newSingleThreadScheduledExecutor();


    private ReactApplicationContext mContext;

    private final static char ESC_CHAR = 0x1B;
    private static final byte[] SELECT_BIT_IMAGE_MODE = { 0x1B, 0x2A, 33 };
    private final static byte[] SET_LINE_SPACE_24 = new byte[] { ESC_CHAR, 0x33, 24 };
    private final static byte[] SET_LINE_SPACE_32 = new byte[] { ESC_CHAR, 0x33, 32 };
    private final static byte[] LINE_FEED = new byte[] { 0x0A };
    private static final byte[] CENTER_ALIGN = { 0x1B, 0X61, 0X31 };



    private BLEPrinterAdapter(){}

    public static synchronized BLEPrinterAdapter getInstance() {
        if(mInstance == null) {
            mInstance = new BLEPrinterAdapter();
        }
        return mInstance;
    }

    public synchronized void setConnectionListener(ConnectionListener listener) {
        this.mConnectionListener = listener;
    }

    public synchronized boolean isConnected() {
        return this.mBluetoothSocket != null && this.mBluetoothSocket.isConnected();
    }

    public synchronized String getConnectedAddress() {
        return this.mBluetoothDevice != null ? this.mBluetoothDevice.getAddress() : null;
    }

    @Override
    public void init(ReactApplicationContext reactContext, Callback successCallback, Callback errorCallback) {
        this.mContext = reactContext;
        BluetoothAdapter bluetoothAdapter = getBTAdapter();
        if(bluetoothAdapter == null) {
            errorCallback.invoke(ERROR_NO_ADAPTER);
            return;
        }
        if(!bluetoothAdapter.isEnabled()) {
            errorCallback.invoke(ERROR_BT_DISABLED);
            return;
        }else{
            successCallback.invoke();
        }

    }

    private static BluetoothAdapter getBTAdapter() {
        return BluetoothAdapter.getDefaultAdapter();
    }

    @Override
    public List<PrinterDevice> getDeviceList(Callback errorCallback) {
        BluetoothAdapter bluetoothAdapter = getBTAdapter();
        List<PrinterDevice> printerDevices = new ArrayList<>();
        if(bluetoothAdapter == null) {
            errorCallback.invoke(ERROR_NO_ADAPTER);
            return printerDevices;
        }
        if (!bluetoothAdapter.isEnabled()) {
            errorCallback.invoke(ERROR_BT_DISABLED);
            return printerDevices;
        }
        try {
            Set<BluetoothDevice> pairedDevices = bluetoothAdapter.getBondedDevices();
            for (BluetoothDevice device : pairedDevices) {
                printerDevices.add(new BLEPrinterDevice(device));
            }
        } catch (SecurityException e) {
            errorCallback.invoke(ERROR_PERMISSION_DENIED);
        }
        return printerDevices;
    }

    @Override
    public void selectDevice(PrinterDeviceId printerDeviceId, Callback successCallback, Callback errorCallback) {
        BluetoothAdapter bluetoothAdapter = getBTAdapter();
        if(bluetoothAdapter == null) {
            errorCallback.invoke(ERROR_NO_ADAPTER);
            return;
        }
        if (!bluetoothAdapter.isEnabled()) {
            errorCallback.invoke(ERROR_BT_DISABLED);
            return;
        }
        BLEPrinterDeviceId blePrinterDeviceId = (BLEPrinterDeviceId)printerDeviceId;
        synchronized (this) {
            if(this.mBluetoothDevice != null){
                if(this.mBluetoothDevice.getAddress().equals(blePrinterDeviceId.getInnerMacAddress()) && isConnected()){
                    Log.v(LOG_TAG, "do not need to reconnect");
                    successCallback.invoke(new BLEPrinterDevice(this.mBluetoothDevice).toRNWritableMap());
                    return;
                }else{
                    closeConnectionIfExists();
                }
            }
        }

        Set<BluetoothDevice> pairedDevices;
        try {
            pairedDevices = bluetoothAdapter.getBondedDevices();
        } catch (SecurityException e) {
            errorCallback.invoke(ERROR_PERMISSION_DENIED);
            return;
        }

        for (BluetoothDevice device : pairedDevices) {
            if(device.getAddress().equals(blePrinterDeviceId.getInnerMacAddress())){

                try{
                    connectBluetoothDevice(device, false);
                    successCallback.invoke(new BLEPrinterDevice(device).toRNWritableMap());
                    return;
                } catch (IOException e) {
                    try {
                        connectBluetoothDevice(device, true);
                        successCallback.invoke(new BLEPrinterDevice(device).toRNWritableMap());
                        return;
                    } catch (IOException er) {
                        Log.e(LOG_TAG, "Failed to connect: " + er.getMessage());
                        errorCallback.invoke(ERROR_CONNECT_FAILED);
                        return;
                    }
                } catch (SecurityException e) {
                    errorCallback.invoke(ERROR_PERMISSION_DENIED);
                    return;
                }
            }
        }
        errorCallback.invoke(ERROR_NOT_PAIRED);
    }

    private void connectBluetoothDevice(BluetoothDevice device, Boolean retry) throws IOException {
        UUID uuid = UUID.fromString("00001101-0000-1000-8000-00805f9b34fb");

        // Android recommends cancelling discovery before connecting: an ongoing scan
        // (from any app or the system settings) slows down or breaks RFCOMM connects.
        BluetoothAdapter bluetoothAdapter = getBTAdapter();
        try {
            if (bluetoothAdapter != null && bluetoothAdapter.isDiscovering()) {
                bluetoothAdapter.cancelDiscovery();
            }
        } catch (SecurityException e) {
            Log.w(LOG_TAG, "Cannot cancel discovery: " + e.getMessage());
        }

        final BluetoothSocket socket;
        if (retry) {
            try {
                socket = (BluetoothSocket) device.getClass()
                        .getMethod("createRfcommSocket", new Class[] { int.class }).invoke(device, 1);
            } catch (Exception e) {
                throw new IOException("Failed to create socket using reflection method", e);
            }
        } else {
            socket = device.createInsecureRfcommSocketToServiceRecord(uuid);
        }

        ScheduledFuture<?> timeout = mTimeoutScheduler.schedule(new Runnable() {
            @Override
            public void run() {
                closeQuietly(socket);
            }
        }, CONNECT_TIMEOUT_MS, TimeUnit.MILLISECONDS);

        try {
            socket.connect();
        } catch (IOException e) {
            closeQuietly(socket);
            throw e;
        } finally {
            timeout.cancel(false);
        }

        if (!socket.isConnected()) {
            closeQuietly(socket);
            throw new IOException("Connection timed out");
        }

        synchronized (this) {
            this.mBluetoothSocket = socket;
            this.mBluetoothDevice = device;
        }
    }

    private void closeQuietly(BluetoothSocket socket) {
        if (socket == null) return;
        try {
            socket.close();
        } catch (IOException e) {
            Log.e(LOG_TAG, "Error closing socket: " + e.getMessage());
        }
    }

    @Override
    public synchronized void closeConnectionIfExists() {
        closeQuietly(this.mBluetoothSocket);
        this.mBluetoothSocket = null;
        this.mBluetoothDevice = null;
    }

    /**
     * Drops the connection if {@code socket} is still the active one and notifies the
     * listener, so JS stops treating the printer as connected.
     */
    public void handleConnectionLost(BluetoothSocket socket) {
        String address;
        ConnectionListener listener;
        synchronized (this) {
            if (socket == null || socket != this.mBluetoothSocket) return;
            address = this.mBluetoothDevice != null ? this.mBluetoothDevice.getAddress() : null;
            closeConnectionIfExists();
            listener = this.mConnectionListener;
        }
        if (listener != null) {
            listener.onDisconnected(address);
        }
    }

    /** Called when Android reports the link to {@code address} was lost. */
    public void handleDeviceDisconnected(String address) {
        BluetoothSocket socket;
        synchronized (this) {
            if (address == null || this.mBluetoothDevice == null
                    || !address.equals(this.mBluetoothDevice.getAddress())) {
                return;
            }
            socket = this.mBluetoothSocket;
        }
        handleConnectionLost(socket);
    }

    private synchronized BluetoothSocket getConnectedSocket() {
        return isConnected() ? this.mBluetoothSocket : null;
    }

    @Override
    public void printRawData(String rawBase64Data, Callback errorCallback) {
        final BluetoothSocket socket = getConnectedSocket();
        if (socket == null) {
            errorCallback.invoke(ERROR_NOT_CONNECTED);
            return;
        }

        final String rawData = rawBase64Data;
        Log.v(LOG_TAG, "start to print raw data " + rawBase64Data);

        new Thread(new Runnable() {
            @Override
            public void run() {
                byte[] bytes = Base64.decode(rawData, Base64.DEFAULT);
                try {
                    OutputStream printerOutputStream = socket.getOutputStream();
                    printerOutputStream.write(bytes, 0, bytes.length);
                    printerOutputStream.flush();
                    Log.v(LOG_TAG, "Successfully printed raw data");
                } catch (IOException e) {
                    Log.e(LOG_TAG, "Failed to print data: " + e.getMessage());
                    handleConnectionLost(socket);
                }
            }
        }).start();
    }

    public static Bitmap getBitmapFromURL(String src) {
        try {
            URL url = new URL(src);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setDoInput(true);
            connection.connect();
            InputStream input = connection.getInputStream();
            Bitmap myBitmap = BitmapFactory.decodeStream(input);

            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            myBitmap.compress(Bitmap.CompressFormat.PNG, 100, baos);

            return myBitmap;
        } catch (IOException e) {
            // Log exception
            return null;
        }
    }

    @Override
    public void printImageData(String imageUrl, int  imageWidth, int imageHeight, Callback errorCallback) {
        final Bitmap bitmapImage = getBitmapFromURL(imageUrl);

        if(bitmapImage == null) {
            errorCallback.invoke(ERROR_IMAGE_NOT_FOUND);
            return;
        }

        printImageBase64(bitmapImage, imageWidth, imageHeight, errorCallback);
    }

    @Override
    public void printImageBase64(final Bitmap bitmapImage, int imageWidth, int imageHeight,Callback errorCallback) {
        String error = writeImage(bitmapImage, imageWidth, imageHeight);
        if (error != null) {
            errorCallback.invoke(error);
        }
    }

    /** Like {@link #printImageBase64} but reports success too, so JS can await the result. */
    public void printImageBase64WithResult(final Bitmap bitmapImage, int imageWidth, int imageHeight,
                                           Callback successCallback, Callback errorCallback) {
        String error = writeImage(bitmapImage, imageWidth, imageHeight);
        if (error != null) {
            errorCallback.invoke(error);
        } else {
            successCallback.invoke();
        }
    }

    /** Writes the image to the printer. Returns an error code, or null on success. */
    private String writeImage(Bitmap bitmapImage, int imageWidth, int imageHeight) {
        if(bitmapImage == null) {
            return ERROR_IMAGE_NOT_FOUND;
        }

        final BluetoothSocket socket = getConnectedSocket();
        if (socket == null) {
            return ERROR_NOT_CONNECTED;
        }

        try {
            int[][] pixels = getPixelsSlow(bitmapImage, imageWidth, imageHeight);

            // Buffer the thousands of 3-byte column writes into larger socket writes.
            OutputStream printerOutputStream = new BufferedOutputStream(socket.getOutputStream(), 8192);

            printerOutputStream.write(SET_LINE_SPACE_24);
            printerOutputStream.write(CENTER_ALIGN);

            for (int y = 0; y < pixels.length; y += 24) {
                // Like I said before, when done sending data,
                // the printer will resume to normal text printing
                printerOutputStream.write(SELECT_BIT_IMAGE_MODE);
                // Set nL and nH based on the width of the image
                printerOutputStream.write(new byte[]{(byte)(0x00ff & pixels[y].length)
                        , (byte)((0xff00 & pixels[y].length) >> 8)});
                for (int x = 0; x < pixels[y].length; x++) {
                    // for each stripe, recollect 3 bytes (3 bytes = 24 bits)
                    printerOutputStream.write(recollectSlice(y, x, pixels));
                }

                // Do a line feed, if not the printing will resume on the same line
                printerOutputStream.write(LINE_FEED);
            }
            printerOutputStream.write(SET_LINE_SPACE_32);
            printerOutputStream.write(LINE_FEED);

            printerOutputStream.flush();
            return null;
        } catch (IOException e) {
            Log.e(LOG_TAG, "failed to print image: " + e.getMessage());
            handleConnectionLost(socket);
            return ERROR_WRITE_FAILED;
        }
    }
}
