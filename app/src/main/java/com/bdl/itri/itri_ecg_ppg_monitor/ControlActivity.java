package com.bdl.itri.itri_ecg_ppg_monitor;

import android.Manifest;
import android.bluetooth.BluetoothGattCharacteristic;
import android.bluetooth.BluetoothGattService;
import android.content.BroadcastReceiver;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.os.IBinder;
import android.support.annotation.Nullable;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.View;
import android.widget.ExpandableListView;
import android.widget.SimpleExpandableListAdapter;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileOutputStream;
import java.io.IOException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.HashMap;
import java.util.List;


public class ControlActivity extends AppCompatActivity {
    private final static String TAG = ControlActivity.class.getSimpleName();

    public static final String EXTRAS_DEVICE_NAME = "DEVICE_NAME";
    public static final String EXTRAS_DEVICE_ADDRESS = "DEVICE_ADDRESS";

    // Key names received from the BluetoothRfcommClient Handler
    public static final String DEVICE_NAME = "device_name";
    public static final String TOAST = "toast";

    // Intent request codes
    private static final int REQUEST_CONNECT_DEVICE = 1;
    private static final int REQUEST_ENABLE_BT = 2;

    // bt-uart constants
    private static final int MAX_SAMPLES = 2048;
    private static final int  MAX_LEVEL	= 512;
    private static final int  DATA_START = (MAX_LEVEL + 1);
    private static final int  DATA_END = (MAX_LEVEL + 2);

    private static final byte  REQ_DATA = 0x00;
    private static final byte  ADJ_HORIZONTAL = 0x01;
    private static final byte  ADJ_VERTICAL = 0x02;
    private static final byte  ADJ_POSITION = 0x03;

    private static final byte  CHANNEL1 = 0x01;
    private static final byte  CHANNEL2 = 0x02;

    // Run/Pause status
    private boolean bReady = false;
    // receive data
    private int[] dataECG = new int[MAX_SAMPLES/2];
    private int[] dataPPG = new int[MAX_SAMPLES/2];

    // Recording data
    private boolean savingFileState = false;
    private String stringECGBuffer = "ECG:";
    private String stringPPGBuffer = "PPG:";

    private int dataIndex=0, dataIndex1=0, dataIndex2=0;
    private boolean bDataAvailable=false;

    private String mDeviceName;
    private String mDeviceAddress;


    private boolean mConnected = false;
    private BluetoothGattCharacteristic mNotifyCharacteristic;
    private BluetoothLeService mBluetoothLeService;

    TextView textViewState;
    TextView hexECGValue;
    TextView hexPPGValue;
    TextView decECGValue;
    TextView decPPGValue;



    public WaveformView WaveformArea = null;

    private ExpandableListView mGattServicesList;

    private final String LIST_NAME = "NAME";
    private final String LIST_UUID = "UUID";

    private ArrayList<ArrayList<BluetoothGattCharacteristic>> mGattCharacteristics =
            new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_control);

        final Intent intent = getIntent();
        mDeviceName = intent.getStringExtra(EXTRAS_DEVICE_NAME);
        mDeviceAddress = intent.getStringExtra(EXTRAS_DEVICE_ADDRESS);

        TextView textViewDeviceName = (TextView)findViewById(R.id.textDeviceName);
        TextView textViewDeviceAddr = (TextView)findViewById(R.id.textDeviceAddress);
        textViewState = (TextView)findViewById(R.id.textState);
        hexPPGValue = (TextView)findViewById(R.id.PPGvalueHex);
        hexECGValue = (TextView)findViewById(R.id.ECGvalueHex);
        decPPGValue = (TextView)findViewById(R.id.PPGvalueDec);
        decECGValue = (TextView)findViewById(R.id.ECGvalueDec);



        initialDataArray(dataECG, dataPPG);

        textViewDeviceName.setText(mDeviceName);
        textViewDeviceAddr.setText(mDeviceAddress);

        // waveform / plot area
        WaveformArea = (WaveformView)findViewById(R.id.WaveformArea);

        mGattServicesList = (ExpandableListView) findViewById(R.id.gatt_services_list);
        mGattServicesList.setOnChildClickListener(servicesListClickListner);

        Intent gattServiceIntent = new Intent(this, BluetoothLeService.class);
        bindService(gattServiceIntent, mServiceConnection, BIND_AUTO_CREATE);

        requestStoragePermission();
    }

    // Request external storage permission
    private void requestStoragePermission() {
        if(Build.VERSION.SDK_INT >= 23) {
            int hasWritePermission = checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE);
            if (hasWritePermission != PackageManager.PERMISSION_GRANTED){
                requestPermissions(new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 1);
            }
        }
    }


    // Code to manage Service lifecycle.
    private final ServiceConnection mServiceConnection = new ServiceConnection() {

        @Override
        public void onServiceConnected(ComponentName componentName, IBinder service) {
            mBluetoothLeService = ((BluetoothLeService.LocalBinder) service).getService();
            if (!mBluetoothLeService.initialize()) {
                Log.e(TAG, "Unable to initialize Bluetooth");
                finish();
            }
            // Automatically connects to the device upon successful start-up initialization.
            mBluetoothLeService.connect(mDeviceAddress);
        }

        @Override
        public void onServiceDisconnected(ComponentName componentName) {
            mBluetoothLeService = null;
        }
    };

    // Handles various events fired by the Service.
    // ACTION_GATT_CONNECTED: connected to a GATT server.
    // ACTION_GATT_DISCONNECTED: disconnected from a GATT server.
    // ACTION_GATT_SERVICES_DISCOVERED: discovered GATT services.
    // ACTION_DATA_AVAILABLE: received data from the device.  This can be a result of read
    //                        or notification operations.
    private final BroadcastReceiver mGattUpdateReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            final String action = intent.getAction();
            if (BluetoothLeService.ACTION_GATT_CONNECTED.equals(action)) {
                mConnected = true;
                updateConnectionState("GATT_CONNECTED");
            } else if (BluetoothLeService.ACTION_GATT_DISCONNECTED.equals(action)) {
                mConnected = false;
                updateConnectionState("GATT_DISCONNECTED");
                clearUI();
            } else if (BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED.equals(action)) {
                // Show all the supported services and characteristics on the user interface.
                displayGattServices(mBluetoothLeService.getSupportedGattServices());
            } else if (BluetoothLeService.ACTION_DATA_AVAILABLE.equals(action)) {
                displayData(intent.getStringExtra(BluetoothLeService.EXTRA_DATA));
                displayECGData(intent.getStringExtra(BluetoothLeService.ECG_DATA));
                displayPPGData(intent.getStringExtra(BluetoothLeService.PPG_DATA));
                Log.d("myTAG", "From ControlActivity call WaveformArea.set_data()");
                WaveformArea.set_data( dataECG, dataPPG);
            }
        }
    };

    private void clearUI() {
        mGattServicesList.setAdapter((SimpleExpandableListAdapter) null);
    }

    private void initialDataArray(int[] data1, int[] data2) {
        Log.d("myTAG", "ControlActivity.initialDataArray()");
        for( int i = 0; i < data1.length; i++){
            data1[i] = 384;
            data2[i] = 128;
        }
    }

    private void updateConnectionState(final String st) {
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                textViewState.setText(st);
            }
        });
    }

    private void displayECGData(String data) {
        if (data != null) {
            hexECGValue.setText( data );
            decECGValue.setText( String.valueOf( Integer.valueOf( data, 16).intValue() ) );
            pushData( dataECG, Integer.valueOf( data, 16)+255 );
        }
        if (savingFileState){
            stringECGBuffer += "," + Integer.valueOf( data, 16).toString();
        }
    }

    private void displayPPGData(String data) {
        if (data != null) {
            hexPPGValue.setText( data );
            decPPGValue.setText( String.valueOf( Integer.valueOf( data, 16).intValue() ) );
            pushData( dataPPG, Integer.valueOf( data, 16) );
        }
        if (savingFileState){
            stringPPGBuffer += "," + Integer.valueOf( data, 16).toString();
        }
    }

    private void displayData(String data) {
        if (data != null) {
            textViewState.setText( data );
        }
    }

    private void pushData ( int[] dataArray, int newValue) {
        Log.d("myTAG", "ControlActivity.pushData()");
        for ( int i = 0; i < dataArray.length-4; i++ ) {
            dataArray[i] = dataArray[i+4];
        }
        // Linear Interpolation
        dataArray[ dataArray.length - 1 ] = newValue;
        dataArray[ dataArray.length - 3 ] = (dataArray[ dataArray.length - 5 ] + newValue) / 2;
        dataArray[ dataArray.length - 2 ] = (dataArray[ dataArray.length - 3 ] + newValue) / 2;
        dataArray[ dataArray.length - 4 ] = (dataArray[ dataArray.length - 5 ] + dataArray[ dataArray.length - 3 ]) / 2;
    }

    //Saving the ECG/PPG data to the text file at /sdcard path

    public void startRecording(View view) {
        if (savingFileState){
            Toast.makeText(getApplicationContext(), "Already Start Recording!!", Toast.LENGTH_SHORT).show();
        }
        else {
            savingFileState = true;
            Toast.makeText(getApplicationContext(), "Start Recording...", Toast.LENGTH_SHORT).show();
        }
    }

    public void stopRecording(View view) {
        if (savingFileState){
            savingFileState = false;

            Calendar calendar = Calendar.getInstance();
            SimpleDateFormat mdformat = new SimpleDateFormat("yyyy_MM_dd_hh_mm_ss");
            String strECGFileName = "ECGdata_" + mdformat.format(calendar.getTime()) + ".txt";
            String strPPGFileName = "PPGdata_" + mdformat.format(calendar.getTime()) + ".txt";

            //Write file to external storage (microSD card)
            try {
                File fileECG = new File(Environment.getExternalStorageDirectory(), strECGFileName);
                File filePPG = new File(Environment.getExternalStorageDirectory(), strPPGFileName);
                FileOutputStream outputStreamECG = new FileOutputStream(fileECG);
                FileOutputStream outputStreamPPG = new FileOutputStream(filePPG);
                outputStreamECG.write(stringECGBuffer.getBytes());
                outputStreamPPG.write(stringPPGBuffer.getBytes());
                outputStreamECG.close();
                outputStreamPPG.close();
                String  pathSDCard = Environment.getDownloadCacheDirectory().getPath();
                Toast.makeText(getApplicationContext(), "Save File Succeed (/sdcard/)!\n" + " Path: " + pathSDCard, Toast.LENGTH_LONG).show();
            } catch (FileNotFoundException e) {
                e.printStackTrace();
            } catch (IOException e) {
                e.printStackTrace();
            }

            stringECGBuffer = "ECG:";
            stringPPGBuffer = "PPG:";
        }
        else {
            Toast.makeText(getApplicationContext(), "Please press Start Recording at first!", Toast.LENGTH_SHORT).show();
        }
    }


    // Demonstrates how to iterate through the supported GATT Services/Characteristics.
    // In this sample, we populate the data structure that is bound to the ExpandableListView
    // on the UI.
    private void displayGattServices(List<BluetoothGattService> gattServices) {

        if (gattServices == null) return;
        String uuid = null;
        String unknownServiceString = "Unknown Service";
        String unknownCharaString = "Unknown Characteristic";
        ArrayList<HashMap<String, String>> gattServiceData =
                new ArrayList<HashMap<String, String>>();
        ArrayList<ArrayList<HashMap<String, String>>> gattCharacteristicData
                = new ArrayList<ArrayList<HashMap<String, String>>>();
        mGattCharacteristics = new ArrayList<ArrayList<BluetoothGattCharacteristic>>();

        // Loops through available GATT Services.
        for (BluetoothGattService gattService : gattServices) {
            HashMap<String, String> currentServiceData = new HashMap<String, String>();
            uuid = gattService.getUuid().toString();
            currentServiceData.put(
                    LIST_NAME, lookup(uuid, unknownServiceString));
            currentServiceData.put(LIST_UUID, uuid);
            gattServiceData.add(currentServiceData);

            ArrayList<HashMap<String, String>> gattCharacteristicGroupData =
                    new ArrayList<HashMap<String, String>>();
            List<BluetoothGattCharacteristic> gattCharacteristics =
                    gattService.getCharacteristics();
            ArrayList<BluetoothGattCharacteristic> charas =
                    new ArrayList<BluetoothGattCharacteristic>();

            // Loops through available Characteristics.
            for (BluetoothGattCharacteristic gattCharacteristic : gattCharacteristics) {
                charas.add(gattCharacteristic);
                HashMap<String, String> currentCharaData = new HashMap<String, String>();
                uuid = gattCharacteristic.getUuid().toString();
                currentCharaData.put(
                        LIST_NAME, lookup(uuid, unknownCharaString));
                currentCharaData.put(LIST_UUID, uuid);
                gattCharacteristicGroupData.add(currentCharaData);

            }
            mGattCharacteristics.add(charas);
            gattCharacteristicData.add(gattCharacteristicGroupData);
        }

        SimpleExpandableListAdapter gattServiceAdapter = new SimpleExpandableListAdapter(
                this,
                gattServiceData,
                android.R.layout.simple_expandable_list_item_2,
                new String[] {LIST_NAME, LIST_UUID},
                new int[] { android.R.id.text1, android.R.id.text2 },
                gattCharacteristicData,
                android.R.layout.simple_expandable_list_item_2,
                new String[] {LIST_NAME, LIST_UUID},
                new int[] { android.R.id.text1, android.R.id.text2 }
        );
        mGattServicesList.setAdapter(gattServiceAdapter);
    }

    // If a given GATT characteristic is selected, check for supported features.  This sample
    // demonstrates 'Read' and 'Notify' features.  See
    // http://d.android.com/reference/android/bluetooth/BluetoothGatt.html for the complete
    // list of supported characteristic features.
    private final ExpandableListView.OnChildClickListener servicesListClickListner =
            new ExpandableListView.OnChildClickListener() {
                @Override
                public boolean onChildClick(ExpandableListView parent, View v, int groupPosition,
                                            int childPosition, long id) {
                    if (mGattCharacteristics != null) {
                        final BluetoothGattCharacteristic characteristic =
                                mGattCharacteristics.get(groupPosition).get(childPosition);
                        final int charaProp = characteristic.getProperties();
                        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_READ) > 0) {
                            // If there is an active notification on a characteristic, clear
                            // it first so it doesn't update the data field on the user interface.
                            if (mNotifyCharacteristic != null) {
                                mBluetoothLeService.setCharacteristicNotification(
                                        mNotifyCharacteristic, false);
                                mNotifyCharacteristic = null;
                            }
                            mBluetoothLeService.readCharacteristic(characteristic);
                        }
                        if ((charaProp | BluetoothGattCharacteristic.PROPERTY_NOTIFY) > 0) {
                            mNotifyCharacteristic = characteristic;
                            mBluetoothLeService.setCharacteristicNotification(
                                    characteristic, true);
                        }
                        return true;
                    }
                    return false;
                }
            };

    @Override
    protected void onResume() {
        super.onResume();
        registerReceiver(mGattUpdateReceiver, makeGattUpdateIntentFilter());
        if (mBluetoothLeService != null) {
            final boolean result = mBluetoothLeService.connect(mDeviceAddress);
            Log.d(TAG, "Connect request result=" + result);
        }
    }

    @Override
    protected void onPause() {
        super.onPause();
        unregisterReceiver(mGattUpdateReceiver);
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        unbindService(mServiceConnection);
        mBluetoothLeService = null;
    }

    private static IntentFilter makeGattUpdateIntentFilter() {
        final IntentFilter intentFilter = new IntentFilter();
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_CONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_DISCONNECTED);
        intentFilter.addAction(BluetoothLeService.ACTION_GATT_SERVICES_DISCOVERED);
        intentFilter.addAction(BluetoothLeService.ACTION_DATA_AVAILABLE);
        return intentFilter;
    }

    private static HashMap<String, String> attributes = new HashMap();

    public static String lookup(String uuid, String defaultName) {
        String name = attributes.get(uuid);
        return name == null ? defaultName : name;
    }

}
