package com.poli.actipuls;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanResult;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.database.Cursor;
import android.database.sqlite.SQLiteDatabase;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.AsyncTask;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;
import android.widget.ProgressBar;

import com.poli.actipuls.accelerometer.AccelerometerHelper;
import com.poli.actipuls.bluetooth.BluetoothController;
import com.poli.actipuls.bluetooth.BluetoothHelper;
import com.poli.actipuls.data.DatabaseHelper;
import com.poli.actipuls.model.HealthData;

import java.util.HashMap;
import java.util.Map;

public class SensorActivity extends AppCompatActivity implements SensorEventListener {

    // scan period in milliseconds
    private static final long SCAN_PERIOD = 10000;
    // Tag for Logging
    private final static String TAG = SensorActivity.class.getSimpleName();
    private final String ARDUINO_ADDRESS = "E4:F5:28:2B:B4:AE";
    // initializes variables
    private BluetoothAdapter blueToothAdapter;
    private BluetoothHelper btHelper = new BluetoothHelper(this);
    private BluetoothDevice myDevice;
    private BluetoothLeScanner scanner;
    private DatabaseHelper dbHelper;
    private SQLiteDatabase mDb;
    Cursor cursor;
    private ProgressBar progressBar;
    private BluetoothController btControl;
    private SensorManager sensorManager;
    private Button buttonStart, buttonStop;
    private TextView connectionState;
    // Broadcast Receiver to update according to state
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothHelper.ACTION_GATT_CONNECTED.equals(action)) {
                updateConnectionState(R.string.connected);
            } else if (BluetoothHelper.ACTION_GATT_DISCONNECTED.equals(action)) {
                updateConnectionState(R.string.disconnected);
            }
        }
    };
    private AccelerometerHelper accHelper = new AccelerometerHelper();
    private Handler handler;
    private boolean isScanning = false;
    private Map<String, BluetoothDevice> scanResults;
    // Scan Callback
    private ScanCallback callback = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            super.onScanResult(callbackType, result);
            BluetoothDevice device = result.getDevice();
            String deviceAddress = device.getAddress();
            // put scan results in map
            scanResults.put(deviceAddress, device);
        }

    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_sensor);

        // initialize SensorManager
        sensorManager = (SensorManager) getSystemService(SENSOR_SERVICE);
        // initialize bluetooth adapter
        blueToothAdapter = btHelper.getBluetoothAdapter(this);
        // initialize a bluetooth controller
        btControl = new BluetoothController(this);
        // get the permissions
        btHelper.getPermissions(blueToothAdapter);
        // find views from the UI
        buttonStart = findViewById(R.id.btnStart);
        buttonStop = findViewById(R.id.btnStop);
        progressBar = findViewById(R.id.progressBar_cyclic);
        connectionState = findViewById(R.id.connectionState);
     // buttonStart.setVisibility(View.INVISIBLE);
      //  buttonStart.setEnabled(false);
        buttonStop.setVisibility(View.INVISIBLE);
        progressBar.setVisibility(View.VISIBLE);


        // Start Scanning
        startScan();

        // click listener for button start
        buttonStart.setOnClickListener((View v) -> {

            Toast.makeText(getApplicationContext(), "Connecting...", Toast.LENGTH_LONG).show();
            //on click
            updateConnectionState(R.string.connecting);
            connectGattService();


            btControl.startScheduler();
            // for logging
            Cursor cursor = btControl.getAllDataInTable();
            Log.i(TAG,"Data =========" +cursor.getCount() );

            // TODO method addItem adds mock data to the azure database, must fix to add real data
           // addItem();
            buttonStop.setVisibility(View.VISIBLE);
            buttonStart.setVisibility(View.INVISIBLE);
        });
        // click listener for button stop
        buttonStop.setOnClickListener((View v) -> {
            Toast.makeText(getApplicationContext(), "Disconnecting...", Toast.LENGTH_LONG).show();
            //on click
            btHelper.disconnect(blueToothAdapter);
            buttonStop.setVisibility(View.INVISIBLE);
            buttonStart.setVisibility(View.VISIBLE);
            btControl.shutDownExecutor();
        });

        dbHelper = new DatabaseHelper();
    }

    /**
     * connect the get service is the device search was succesfull
     */
    public void connectGattService() {
        if (myDevice != null) {
            btHelper.connectToGattServer(myDevice, this);
            buttonStop.setVisibility(View.VISIBLE);
            buttonStart.setVisibility(View.INVISIBLE);
        } else {
            Toast.makeText(getApplicationContext(), "Device unavailable", Toast.LENGTH_LONG).show();
            try {
                Thread.sleep(1000);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            updateConnectionState(R.string.notfound);
        }

    }

    /**
     * Start scanning for devices
     */
    private void startScan() {
        if (blueToothAdapter == null || isScanning) {
            return;
        }
        updateConnectionState(R.string.scan);
        scanResults = new HashMap<>();
        scanner = blueToothAdapter.getBluetoothLeScanner();
        scanner.startScan(callback);
        isScanning = true;
        handler = new Handler();
        handler.postDelayed(this::stopScan, SCAN_PERIOD);
    }

    /**
     * stop scanning
     */
    private void stopScan() {
        if (isScanning && blueToothAdapter != null && blueToothAdapter.isEnabled() && scanner != null) {
            scanner.stopScan(callback);
            findDevice();
        }
        callback = null;
        isScanning = false;
        handler = null;
    }

    /**
     * find specific device from map
     */
    private void findDevice() {
        if (scanResults.isEmpty()) {
            Toast.makeText(getApplicationContext(), "Device unavailable", Toast.LENGTH_LONG).show();
            updateConnectionState(R.string.notfound);
            progressBar.setVisibility(View.GONE);
            return;
        }
        for (String deviceAddress : scanResults.keySet()) {
            if (deviceAddress.equals(ARDUINO_ADDRESS)) {
                myDevice = scanResults.get(deviceAddress);
                Log.d("SCAN", "Found device: " + deviceAddress);
                buttonStart.setVisibility(View.VISIBLE);
                progressBar.setVisibility(View.GONE);
            } else {
                updateConnectionState(R.string.notfound);
                progressBar.setVisibility(View.GONE);
                Toast.makeText(getApplicationContext(), "Device unavailable", Toast.LENGTH_LONG).show();
            }
        }
    }

    /**
     * Update the Ui with the connection state
     *
     * @param resourceId
     */
    private void updateConnectionState(final int resourceId) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                connectionState.setText(resourceId);
            }
        });
    }

    /**
     * On Resume
     */
    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mGattUpdateReceiver, updateIntentFilter());
        // register this class as a listener for the orientation and
        // accelerometer sensors
        sensorManager.registerListener(this,
                sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER),
                SensorManager.SENSOR_DELAY_NORMAL);
    }

    /**
     * On Pause
     */
    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
        sensorManager.unregisterListener(this);
    }

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            accHelper.getAccelerometer(event);
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) {
        // not implemented
    }

    /**
     * Verifies if there is movement according to the accelerometer
     *
     * @return
     */
    public boolean isExercising() {
        if (accHelper.getAccelerationSquareRoot() > 20) {
            Log.i(TAG, "Acc is " + accHelper.getAccelerometerData());
            return true;
        }
        return false;
    }

    /**
     * Add a new item
     */
    public void addItem() {
        if (dbHelper.getClient() == null) {
            return;
        }

        // Create a new item
        final HealthData item = new HealthData();

        item.setAccelerometru(accHelper.getAccelerometerData());
        item.setPuls(String.valueOf(MockPulseGenerator.generatePulse()));

        // Insert the new item
        AsyncTask<Void, Void, Void> task = new AsyncTask<Void, Void, Void>() {
            @Override
            protected Void doInBackground(Void... params) {
                try {
                    dbHelper.addItemInTable(item);

                } catch (final Exception e) {
                    Log.e(TAG, "insert Error" + e);
                }
                return null;
            }
        };

        runAsyncTask(task);
    }

    /**
     * Run an ASync task on the corresponding executor
     *
     * @param task
     * @return
     */
    private AsyncTask<Void, Void, Void> runAsyncTask(AsyncTask<Void, Void, Void> task) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.HONEYCOMB) {
            return task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);
        } else {
            return task.execute();
        }
    }

    /**
     * Update intent filter
     *
     * @return
     */
    private static IntentFilter updateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothHelper.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothHelper.ACTION_GATT_DISCONNECTED);
        return intentFilter;
    }

}
