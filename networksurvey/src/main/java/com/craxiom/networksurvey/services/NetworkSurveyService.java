package com.craxiom.networksurvey.services;

import android.Manifest;
import android.annotation.SuppressLint;
import android.app.Notification;
import android.app.PendingIntent;
import android.app.Service;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanSettings;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.RestrictionsManager;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.location.GnssMeasurementsEvent;
import android.location.GnssStatus;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.net.wifi.ScanResult;
import android.net.wifi.WifiManager;
import android.os.BatteryManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.provider.Settings;
import android.telephony.CellInfo;
import android.telephony.PhoneStateListener;
import android.telephony.ServiceState;
import android.telephony.TelephonyManager;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.core.app.NotificationCompat;
import androidx.core.content.ContextCompat;
import androidx.preference.PreferenceManager;

import com.craxiom.messaging.DeviceStatus;
import com.craxiom.messaging.DeviceStatusData;
import com.craxiom.mqttlibrary.IConnectionStateListener;
import com.craxiom.mqttlibrary.IMqttService;
import com.craxiom.mqttlibrary.MqttConstants;
import com.craxiom.mqttlibrary.connection.BrokerConnectionInfo;
import com.craxiom.mqttlibrary.connection.ConnectionState;
import com.craxiom.mqttlibrary.connection.DefaultMqttConnection;
import com.craxiom.mqttlibrary.ui.AConnectionFragment;
import com.craxiom.networksurvey.Application;
import com.craxiom.networksurvey.BuildConfig;
import com.craxiom.networksurvey.CalculationUtils;
import com.craxiom.networksurvey.GpsListener;
import com.craxiom.networksurvey.NetworkSurveyActivity;
import com.craxiom.networksurvey.R;
import com.craxiom.networksurvey.constants.DeviceStatusMessageConstants;
import com.craxiom.networksurvey.constants.NetworkSurveyConstants;
import com.craxiom.networksurvey.listeners.IBluetoothSurveyRecordListener;
import com.craxiom.networksurvey.listeners.ICellularSurveyRecordListener;
import com.craxiom.networksurvey.listeners.IDeviceStatusListener;
import com.craxiom.networksurvey.listeners.IGnssFailureListener;
import com.craxiom.networksurvey.listeners.IGnssSurveyRecordListener;
import com.craxiom.networksurvey.listeners.IWifiSurveyRecordListener;
import com.craxiom.networksurvey.logging.BluetoothSurveyRecordLogger;
import com.craxiom.networksurvey.logging.CellularSurveyRecordLogger;
import com.craxiom.networksurvey.logging.GnssRecordLogger;
import com.craxiom.networksurvey.logging.PhoneStateRecordLogger;
import com.craxiom.networksurvey.logging.WifiSurveyRecordLogger;
import com.craxiom.networksurvey.mqtt.MqttConnection;
import com.craxiom.networksurvey.mqtt.MqttConnectionInfo;
import com.craxiom.networksurvey.util.IOUtils;
import com.craxiom.networksurvey.util.MathUtils;
import com.craxiom.networksurvey.util.PreferenceUtils;
import com.google.protobuf.Int32Value;

import java.time.ZonedDateTime;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import timber.log.Timber;

/**
 * This service is responsible for getting access to the Android {@link TelephonyManager} and periodically getting the
 * list of cellular towers the phone can see.  It then notifies any listeners of the cellular survey records.
 * <p>
 * It also handles starting the MQTT broker connection if connection information is present.
 *
 * @since 0.0.9
 */
public class NetworkSurveyService extends Service implements IConnectionStateListener, SharedPreferences.OnSharedPreferenceChangeListener, IMqttService
{
    /**
     * Time to wait between first location measurement received before considering this device does
     * not likely support raw GNSS collection.
     */
    private static final long TIME_TO_WAIT_FOR_GNSS_RAW_BEFORE_FAILURE = 1000L * 15L;
    private static final int PING_RATE_MS = 10_000;

    private final AtomicBoolean cellularScanningActive = new AtomicBoolean(false);
    private final AtomicBoolean wifiScanningActive = new AtomicBoolean(false);
    private final AtomicBoolean bluetoothScanningActive = new AtomicBoolean(false);
    private final AtomicBoolean deviceStatusActive = new AtomicBoolean(false);
    private final AtomicBoolean cellularLoggingEnabled = new AtomicBoolean(false);
    private final AtomicBoolean wifiLoggingEnabled = new AtomicBoolean(false);
    private final AtomicBoolean bluetoothLoggingEnabled = new AtomicBoolean(false);
    private final AtomicBoolean gnssLoggingEnabled = new AtomicBoolean(false);
    private final AtomicBoolean gnssStarted = new AtomicBoolean(false);

    private final AtomicInteger cellularScanningTaskId = new AtomicInteger();
    private final AtomicInteger wifiScanningTaskId = new AtomicInteger();
    private final AtomicInteger bluetoothScanningTaskId = new AtomicInteger();
    private final AtomicInteger deviceStatusGeneratorTaskId = new AtomicInteger();

    private final SurveyServiceBinder surveyServiceBinder;
    private final Handler uiThreadHandler;
    private final ExecutorService executorService;

    private volatile int cellularScanRateMs;
    private volatile int wifiScanRateMs;
    private volatile int bluetoothScanRateMs;
    private volatile int gnssScanRateMs;
    private volatile int deviceStatusScanRateMs;

    private String deviceId;
    private SurveyRecordProcessor surveyRecordProcessor;
    private GpsListener gpsListener;
    private IGnssFailureListener gnssFailureListener;
    private CellularSurveyRecordLogger cellularSurveyRecordLogger;
    private WifiSurveyRecordLogger wifiSurveyRecordLogger;
    private BluetoothSurveyRecordLogger bluetoothSurveyRecordLogger;
    private GnssRecordLogger gnssRecordLogger;
    private PhoneStateRecordLogger phoneStateRecordLogger;
    private Looper serviceLooper;
    private Handler serviceHandler;
    private LocationManager locationManager = null;
    private long firstGpsAcqTime = Long.MIN_VALUE;
    private boolean gnssRawSupportKnown = false;
    private boolean hasGnssRawFailureNagLaunched = false;
    private MqttConnection mqttConnection;
    private BroadcastReceiver managedConfigurationListener;

    private TelephonyManager.CellInfoCallback cellInfoCallback;
    private BroadcastReceiver wifiScanReceiver;
    private ScanCallback bluetoothScanCallback;
    private BroadcastReceiver bluetoothBroadcastReceiver;
    private GnssMeasurementsEvent.Callback measurementListener;
    private PhoneStateListener phoneStateListener;

    public NetworkSurveyService()
    {
        surveyServiceBinder = new SurveyServiceBinder();
        uiThreadHandler = new Handler(Looper.getMainLooper());

        executorService = Executors.newFixedThreadPool(8);
    }

    @Override
    public void onCreate()
    {
        super.onCreate();

        Timber.i("Creating the Network Survey Service");

        final Context context = getApplicationContext();

        final HandlerThread handlerThread = new HandlerThread("NetworkSurveyService");
        handlerThread.start();

        serviceLooper = handlerThread.getLooper();
        serviceHandler = new Handler(serviceLooper);

        deviceId = createDeviceId();
        cellularSurveyRecordLogger = new CellularSurveyRecordLogger(this, serviceLooper);
        wifiSurveyRecordLogger = new WifiSurveyRecordLogger(this, serviceLooper);
        bluetoothSurveyRecordLogger = new BluetoothSurveyRecordLogger(this, serviceLooper);
        gnssRecordLogger = new GnssRecordLogger(this, serviceLooper);
        phoneStateRecordLogger = new PhoneStateRecordLogger(this, serviceLooper);

        gpsListener = new GpsListener();

        surveyRecordProcessor = new SurveyRecordProcessor(gpsListener, deviceId, context, executorService);

        setScanRateValues();
        PreferenceManager.getDefaultSharedPreferences(context).registerOnSharedPreferenceChangeListener(this);

        // Must register for MDM updates AFTER initializing the MQTT connection because we try to make an MQTT connection if the MDM settings change
        initializeMqttConnection();
        registerManagedConfigurationListener();

        initializeCellularScanningResources();
        initializeWifiScanningResources();
        initializeBluetoothScanningResources();
        initializeGnssScanningResources();

        updateServiceNotification();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId)
    {
        // If we are started at boot, then that means the NetworkSurveyActivity was never run.  Therefore, to ensure we
        // read and respect the auto start logging user preferences, we need to read them and start logging here.
        final boolean startedAtBoot = intent.getBooleanExtra(NetworkSurveyConstants.EXTRA_STARTED_AT_BOOT, false);
        if (startedAtBoot)
        {
            Timber.i("Received the startedAtBoot flag in the NetworkSurveyService. Reading the auto start preferences");

            attemptMqttConnectionAtBoot();

            final Context applicationContext = getApplicationContext();

            final boolean autoStartCellularLogging = PreferenceUtils.getAutoStartPreference(NetworkSurveyConstants.PROPERTY_AUTO_START_CELLULAR_LOGGING, false, applicationContext);
            if (autoStartCellularLogging && !cellularLoggingEnabled.get())
            {
                toggleCellularLogging(true);
            }

            final boolean autoStartWifiLogging = PreferenceUtils.getAutoStartPreference(NetworkSurveyConstants.PROPERTY_AUTO_START_WIFI_LOGGING, false, applicationContext);
            if (autoStartWifiLogging && !wifiLoggingEnabled.get()) toggleWifiLogging(true);

            final boolean autoStartBluetoothLogging = PreferenceUtils.getAutoStartPreference(NetworkSurveyConstants.PROPERTY_AUTO_START_BLUETOOTH_LOGGING, false, applicationContext);
            if (autoStartBluetoothLogging && !bluetoothLoggingEnabled.get())
            {
                toggleBluetoothLogging(true);
            }

            final boolean autoStartGnssLogging = PreferenceUtils.getAutoStartPreference(NetworkSurveyConstants.PROPERTY_AUTO_START_GNSS_LOGGING, false, applicationContext);
            if (autoStartGnssLogging && !gnssLoggingEnabled.get()) toggleGnssLogging(true);
        }

        return START_REDELIVER_INTENT;
    }

    @Override
    public IBinder onBind(Intent intent)
    {
        //noinspection ReturnOfInnerClass
        return surveyServiceBinder;
    }

    @Override
    public void onDestroy()
    {
        Timber.i("onDestroy");

        unregisterManagedConfigurationListener();

        if (mqttConnection != null)
        {
            unregisterMqttConnectionStateListener(this);
            mqttConnection.disconnect();
        }

        PreferenceManager.getDefaultSharedPreferences(getApplicationContext()).unregisterOnSharedPreferenceChangeListener(this);

        stopCellularRecordScanning();
        stopWifiRecordScanning();
        removeLocationListener();
        stopGnssRecordScanning();
        stopDeviceStatusReport();
        stopAllLogging();

        serviceLooper.quitSafely();
        shutdownNotifications();
        executorService.shutdown();

        super.onDestroy();
    }

    @Override
    public void onSharedPreferenceChanged(SharedPreferences preferences, String key)
    {
        switch (key)
        {
            case NetworkSurveyConstants.PROPERTY_LOG_ROLLOVER_SIZE_MB:
                wifiSurveyRecordLogger.onSharedPreferenceChanged();
                bluetoothSurveyRecordLogger.onSharedPreferenceChanged();
                cellularSurveyRecordLogger.onSharedPreferenceChanged();
                gnssRecordLogger.onSharedPreferenceChanged();
                phoneStateRecordLogger.onSharedPreferenceChanged();
                break;
            case NetworkSurveyConstants.PROPERTY_CELLULAR_SCAN_INTERVAL_SECONDS:
            case NetworkSurveyConstants.PROPERTY_WIFI_SCAN_INTERVAL_SECONDS:
            case NetworkSurveyConstants.PROPERTY_BLUETOOTH_SCAN_INTERVAL_SECONDS:
            case NetworkSurveyConstants.PROPERTY_GNSS_SCAN_INTERVAL_SECONDS:
            case NetworkSurveyConstants.PROPERTY_DEVICE_STATUS_SCAN_INTERVAL_SECONDS:
                setScanRateValues();
                break;

            default:
        }
    }

    /**
     * Creates the {@link DefaultMqttConnection} instance.
     *
     * @since 0.1.1
     */
    public void initializeMqttConnection()
    {
        mqttConnection = new MqttConnection();
        mqttConnection.registerMqttConnectionStateListener(this);
    }

    /**
     * Connect to an MQTT broker.
     *
     * @param connectionInfo The information needed to connect to the MQTT broker.
     * @since 0.1.1
     */
    @Override
    public void connectToMqttBroker(BrokerConnectionInfo connectionInfo)
    {
        mqttConnection.connect(connectionInfo);
        MqttConnectionInfo networkSurveyConnection = (MqttConnectionInfo) connectionInfo;

        if (networkSurveyConnection.isCellularStreamEnabled())
        {
            registerCellularSurveyRecordListener(mqttConnection);
        }
        if (networkSurveyConnection.isWifiStreamEnabled())
        {
            registerWifiSurveyRecordListener(mqttConnection);
        }
        if (networkSurveyConnection.isBluetoothStreamEnabled())
        {
            registerBluetoothSurveyRecordListener(mqttConnection);
        }
        if (networkSurveyConnection.isGnssStreamEnabled())
        {
            registerGnssSurveyRecordListener(mqttConnection);
        }
        if (networkSurveyConnection.isDeviceStatusStreamEnabled())
        {
            registerDeviceStatusListener(mqttConnection);
        }
    }

    /**
     * Disconnect from the MQTT broker and also remove the MQTT survey record listener.
     *
     * @since 0.1.1
     */
    @Override
    public void disconnectFromMqttBroker()
    {
        Timber.i("Disconnecting from the MQTT Broker");

        mqttConnection.disconnect();

        unregisterCellularSurveyRecordListener(mqttConnection);
        unregisterWifiSurveyRecordListener(mqttConnection);
        unregisterBluetoothSurveyRecordListener(mqttConnection);
        unregisterGnssSurveyRecordListener(mqttConnection);
        unregisterDeviceStatusListener(mqttConnection);
    }

    /**
     * If connection information is specified for an MQTT Broker via the MDM Managed Configuration, then kick off an
     * MQTT connection.
     *
     * @param forceDisconnect Set to true so that the MQTT broker connection will be shutdown even if the MDM configured
     *                        connection info is not present.  This flag is needed to stop an MQTT connection if it was
     *                        previously configured via MDM, but the config has since been removed from the MDM.  In
     *                        that case, the connection info will be null but we still want to disconnect from the MQTT
     *                        broker.
     * @since 0.1.1
     */
    @Override
    public void attemptMqttConnectWithMdmConfig(boolean forceDisconnect)
    {
        if (isMqttMdmOverrideEnabled())
        {
            Timber.i("The MQTT MDM override is enabled, so no MDM configured MQTT connection will be attempted");
            return;
        }

        final BrokerConnectionInfo connectionInfo = getMdmBrokerConnectionInfo();

        if (connectionInfo != null)
        {
            // Make sure there is not another connection active first, if there is, disconnect. Don't use the
            // disconnectFromMqttBroker() method because it will cause the listener to get unregistered, which will
            // cause the NetworkSurveyService to get stopped if it is the last listener/user of the service.  Since we
            // are starting the connection right back up there is not a need to remove the listener.
            mqttConnection.disconnect();

            connectToMqttBroker(connectionInfo);
        } else
        {
            Timber.i("Skipping the MQTT connection because no MDN MQTT broker configuration has been set");

            if (forceDisconnect) disconnectFromMqttBroker();
        }
    }

    /**
     * @return The current connection state to the MQTT Broker.
     * @since 0.1.1
     */
    @Override
    public ConnectionState getMqttConnectionState()
    {
        if (mqttConnection != null) return mqttConnection.getConnectionState();

        return ConnectionState.DISCONNECTED;
    }

    /**
     * Adds an {@link IConnectionStateListener} so that it will be notified of all future MQTT connection state changes.
     *
     * @param connectionStateListener The listener to add.
     */
    @Override
    public void registerMqttConnectionStateListener(IConnectionStateListener connectionStateListener)
    {
        mqttConnection.registerMqttConnectionStateListener(connectionStateListener);
    }

    /**
     * Removes an {@link IConnectionStateListener} so that it will no longer be notified of MQTT connection state changes.
     *
     * @param connectionStateListener The listener to remove.
     */
    @Override
    public void unregisterMqttConnectionStateListener(IConnectionStateListener connectionStateListener)
    {
        mqttConnection.unregisterMqttConnectionStateListener(connectionStateListener);
    }

    public GpsListener getGpsListener()
    {
        return gpsListener;
    }

    public String getDeviceId()
    {
        return deviceId;
    }

    /**
     * Registers a new listener for changes to the location information.
     *
     * @since 1.6.0
     */
    public void registerLocationListener(LocationListener locationListener)
    {
        gpsListener.registerListener(locationListener);
    }

    /**
     * Unregisters a listener for changes to the location information.
     *
     * @since 1.6.0
     */
    public void unregisterLocationListener(LocationListener locationListener)
    {
        gpsListener.unregisterListener(locationListener);
    }

    /**
     * Registers a listener for notifications when new cellular survey records are available.
     *
     * @param surveyRecordListener The survey record listener to register.
     */
    public void registerCellularSurveyRecordListener(ICellularSurveyRecordListener surveyRecordListener)
    {
        if (surveyRecordProcessor != null)
        {
            surveyRecordProcessor.registerCellularSurveyRecordListener(surveyRecordListener);
        }

        startCellularRecordScanning(); // Only starts scanning if it is not already active.
    }

    /**
     * Unregisters a cellular survey record listener.
     * <p>
     * If the listener being removed is the last listener and nothing else is using this {@link NetworkSurveyService},
     * then this service is shutdown and will need to be restarted before it can be used again.
     *
     * @param surveyRecordListener The listener to unregister.
     */
    public void unregisterCellularSurveyRecordListener(ICellularSurveyRecordListener surveyRecordListener)
    {
        if (surveyRecordProcessor != null)
        {
            surveyRecordProcessor.unregisterCellularSurveyRecordListener(surveyRecordListener);
            if (!surveyRecordProcessor.isCellularBeingUsed()) stopCellularRecordScanning();
        }

        // Check to see if this service is still needed.  It is still needed if we are either logging, the UI is
        // visible, or a server connection is active.
        if (!isBeingUsed()) stopSelf();
    }

    /**
     * Registers a listener for notifications when new Wi-Fi survey records are available.
     *
     * @param surveyRecordListener The survey record listener to register.
     * @since 0.1.2
     */
    public void registerWifiSurveyRecordListener(IWifiSurveyRecordListener surveyRecordListener)
    {
        synchronized (wifiScanningActive)
        {
            if (surveyRecordProcessor != null)
            {
                surveyRecordProcessor.registerWifiSurveyRecordListener(surveyRecordListener);
            }

            startWifiRecordScanning(); // Only starts scanning if it is not already active.
        }
    }

    /**
     * Unregisters a Wi-Fi survey record listener.
     * <p>
     * If the listener being removed is the last listener and nothing else is using this {@link NetworkSurveyService},
     * then this service is shutdown and will need to be restarted before it can be used again.
     * <p>
     * If the service is still needed for other purposes (e.g. cellular survey records), but no longer for Wi-Fi
     * scanning, then just the Wi-Fi scanning portion of this service is stopped.
     *
     * @param surveyRecordListener The listener to unregister.
     * @since 0.1.2
     */
    public void unregisterWifiSurveyRecordListener(IWifiSurveyRecordListener surveyRecordListener)
    {
        synchronized (wifiScanningActive)
        {
            if (surveyRecordProcessor != null)
            {
                surveyRecordProcessor.unregisterWifiSurveyRecordListener(surveyRecordListener);
                if (!surveyRecordProcessor.isWifiBeingUsed()) stopWifiRecordScanning();
            }
        }

        // Check to see if this service is still needed.  It is still needed if we are either logging, the UI is
        // visible, or a server connection is active.
        if (!isBeingUsed()) stopSelf();
    }

    /**
     * Registers a listener for notifications when new Bluetooth survey records are available.
     *
     * @param surveyRecordListener The survey record listener to register.
     * @since 1.0.0
     */
    public void registerBluetoothSurveyRecordListener(IBluetoothSurveyRecordListener surveyRecordListener)
    {
        synchronized (bluetoothScanningActive)
        {
            if (surveyRecordProcessor != null)
            {
                surveyRecordProcessor.registerBluetoothSurveyRecordListener(surveyRecordListener);
            }

            startBluetoothRecordScanning(); // Only starts scanning if it is not already active.
        }
    }

    /**
     * Unregisters a Bluetooth survey record listener.
     * <p>
     * If the listener being removed is the last listener and nothing else is using this {@link NetworkSurveyService},
     * then this service is shutdown and will need to be restarted before it can be used again.
     * <p>
     * If the service is still needed for other purposes (e.g. cellular survey records), but no longer for Bluetooth
     * scanning, then just the Bluetooth scanning portion of this service is stopped.
     *
     * @param surveyRecordListener The listener to unregister.
     * @since 1.0.0
     */
    public void unregisterBluetoothSurveyRecordListener(IBluetoothSurveyRecordListener surveyRecordListener)
    {
        synchronized (bluetoothScanningActive)
        {
            if (surveyRecordProcessor != null)
            {
                surveyRecordProcessor.unregisterBluetoothSurveyRecordListener(surveyRecordListener);
                if (!surveyRecordProcessor.isBluetoothBeingUsed()) stopBluetoothRecordScanning();
            }
        }

        // Check to see if this service is still needed.  It is still needed if we are either logging, the UI is
        // visible, or a server connection is active.
        if (!isBeingUsed()) stopSelf();
    }

    /**
     * Registers a listener for notifications when new GNSS survey records are available.
     *
     * @param surveyRecordListener The survey record listener to register.
     * @since 0.3.0
     */
    public void registerGnssSurveyRecordListener(IGnssSurveyRecordListener surveyRecordListener)
    {
        synchronized (gnssStarted)
        {
            if (surveyRecordProcessor != null)
            {
                surveyRecordProcessor.registerGnssSurveyRecordListener(surveyRecordListener);
            }

            startGnssRecordScanning(); // Only starts scanning if it is not already active.
        }
    }

    /**
     * Unregisters a GNSS survey record listener.
     * <p>
     * If the listener being removed is the last listener and nothing else is using this {@link NetworkSurveyService},
     * then this service is shutdown and will need to be restarted before it can be used again.
     * <p>
     * If the service is still needed for other purposes (e.g. cellular survey records), but no longer for GNSS
     * scanning, then just the GNSS scanning portion of this service is stopped.
     *
     * @param surveyRecordListener The listener to unregister.
     * @since 0.3.0
     */
    public void unregisterGnssSurveyRecordListener(IGnssSurveyRecordListener surveyRecordListener)
    {
        synchronized (gnssStarted)
        {
            if (surveyRecordProcessor != null)
            {
                surveyRecordProcessor.unregisterGnssSurveyRecordListener(surveyRecordListener);
                if (!surveyRecordProcessor.isGnssBeingUsed()) stopGnssRecordScanning();
            }
        }

        // Check to see if this service is still needed.  It is still needed if we are either logging, the UI is
        // visible, or a server connection is active.
        if (!isBeingUsed()) stopSelf();
    }

    /**
     * Registers a listener for notifications when new device status messages are available.
     *
     * @param deviceStatusListener The survey record listener to register.
     * @since 1.1.0
     */
    public void registerDeviceStatusListener(IDeviceStatusListener deviceStatusListener)
    {
        synchronized (deviceStatusActive)
        {
            if (surveyRecordProcessor != null)
            {
                surveyRecordProcessor.registerDeviceStatusListener(deviceStatusListener);
            }

            startDeviceStatusReport(); // Only starts scanning if it is not already active.
        }
    }

    /**
     * Unregisters a device status message listener.
     * <p>
     * If the listener being removed is the last listener and nothing else is using this {@link NetworkSurveyService},
     * then this service is shutdown and will need to be restarted before it can be used again.
     *
     * @param deviceStatusListener The listener to unregister.
     * @since 1.1.0
     */
    public void unregisterDeviceStatusListener(IDeviceStatusListener deviceStatusListener)
    {
        synchronized (deviceStatusActive)
        {
            if (surveyRecordProcessor != null)
            {
                surveyRecordProcessor.unregisterDeviceStatusListener(deviceStatusListener);
                if (!surveyRecordProcessor.isDeviceStatusBeingUsed()) stopDeviceStatusReport();
            }
        }

        // Check to see if this service is still needed.  It is still needed if we are either logging, the UI is
        // visible, or a server connection is active.
        if (!isBeingUsed()) stopSelf();
    }

    /**
     * Used to check if this service is still needed.
     * <p>
     * This service is still needed if logging is enabled, if the UI is visible, or if a connection is active.  In other
     * words, if there is an active consumer of the survey records.
     *
     * @return True if there is an active consumer of the survey records produced by this service, false otherwise.
     * @since 0.1.1
     */
    public boolean isBeingUsed()
    {
        return cellularLoggingEnabled.get()
                || gnssLoggingEnabled.get()
                || wifiLoggingEnabled.get()
                || bluetoothLoggingEnabled.get()
                || getMqttConnectionState() != ConnectionState.DISCONNECTED
                || GrpcConnectionService.getConnectedState() != ConnectionState.DISCONNECTED
                || surveyRecordProcessor.isBeingUsed();
    }

    /**
     * Whenever the UI is visible, we need to pass information to it so it can be displayed to the user.
     *
     * @param networkSurveyActivity The activity that is now visible to the user.
     */
    public void onUiVisible(NetworkSurveyActivity networkSurveyActivity)
    {
        if (surveyRecordProcessor != null) surveyRecordProcessor.onUiVisible(networkSurveyActivity);

        startCellularRecordScanning();
    }

    /**
     * The UI is no longer visible, so don't send any updates to the UI.
     */
    public void onUiHidden()
    {
        if (surveyRecordProcessor != null)
        {
            surveyRecordProcessor.onUiHidden();
            if (!surveyRecordProcessor.isCellularBeingUsed()) stopCellularRecordScanning();
        }
    }

    /**
     * Toggles the cellular logging setting.
     * <p>
     * It is possible that an error occurs while trying to enable or disable logging.  In that event null will be
     * returned indicating that logging could not be toggled.
     *
     * @param enable True if logging should be enabled, false if it should be turned off.
     * @return The new state of logging.  True if it is enabled, or false if it is disabled.  Null is returned if the
     * toggling was unsuccessful.
     */
    public Boolean toggleCellularLogging(boolean enable)
    {
        synchronized (cellularLoggingEnabled)
        {
            final boolean originalLoggingState = cellularLoggingEnabled.get();
            if (originalLoggingState == enable) return originalLoggingState;

            Timber.i("Toggling cellular logging to %s", enable);

            final boolean successful = cellularSurveyRecordLogger.enableLogging(enable) &&
                    phoneStateRecordLogger.enableLogging(enable);
            if (successful)
            {
                toggleCellularConfig(enable);
            } else
            {
                // at least one of the loggers failed to toggle;
                // disable both and set local config to false
                cellularSurveyRecordLogger.enableLogging(false);
                phoneStateRecordLogger.enableLogging(false);
                toggleCellularConfig(false);
            }
            updateServiceNotification();

            final boolean newLoggingState = cellularLoggingEnabled.get();
            if (successful && newLoggingState) initializePing();

            return successful ? newLoggingState : null;
        }
    }

    /**
     * @param enable Value used to set {@code cellularLoggingEnabled}, and cellular and device
     *               status listeners. If {@code true} listeners are registered, otherwise they
     *               are unregistered.
     */
    private void toggleCellularConfig(boolean enable)
    {
        cellularLoggingEnabled.set(enable);
        if (enable)
        {
            registerCellularSurveyRecordListener(cellularSurveyRecordLogger);
            registerDeviceStatusListener(phoneStateRecordLogger);
        } else
        {
            unregisterCellularSurveyRecordListener(cellularSurveyRecordLogger);
            unregisterDeviceStatusListener(phoneStateRecordLogger);
        }
    }

    /**
     * Toggles the wifi logging setting.
     * <p>
     * It is possible that an error occurs while trying to enable or disable logging.  In that event null will be
     * returned indicating that logging could not be toggled.
     *
     * @param enable True if logging should be enabled, false if it should be turned off.
     * @return The new state of logging.  True if it is enabled, or false if it is disabled.  Null is returned if the
     * toggling was unsuccessful.
     * @since 0.1.2
     */
    public Boolean toggleWifiLogging(boolean enable)
    {
        synchronized (wifiLoggingEnabled)
        {
            final boolean originalLoggingState = wifiLoggingEnabled.get();
            if (originalLoggingState == enable) return originalLoggingState;

            Timber.i("Toggling wifi logging to %s", enable);

            if (enable)
            {
                // First check to see if Wi-Fi is enabled
                final boolean wifiEnabled = isWifiEnabled(true);
                if (!wifiEnabled) return null;
            }

            final boolean successful = wifiSurveyRecordLogger.enableLogging(enable);
            if (successful)
            {
                wifiLoggingEnabled.set(enable);
                if (enable)
                {
                    registerWifiSurveyRecordListener(wifiSurveyRecordLogger);
                } else
                {
                    unregisterWifiSurveyRecordListener(wifiSurveyRecordLogger);
                }
            }
            updateServiceNotification();

            final boolean newLoggingState = wifiLoggingEnabled.get();

            return successful ? newLoggingState : null;
        }
    }

    /**
     * Toggles the Bluetooth logging setting.
     * <p>
     * It is possible that an error occurs while trying to enable or disable logging.  In that event null will be
     * returned indicating that logging could not be toggled.
     *
     * @param enable True if logging should be enabled, false if it should be turned off.
     * @return The new state of logging.  True if it is enabled, or false if it is disabled.  Null is returned if the
     * toggling was unsuccessful.
     * @since 1.0.0
     */
    public Boolean toggleBluetoothLogging(boolean enable)
    {
        synchronized (bluetoothLoggingEnabled)
        {
            final boolean originalLoggingState = bluetoothLoggingEnabled.get();
            if (originalLoggingState == enable) return originalLoggingState;

            Timber.i("Toggling Bluetooth logging to %s", enable);

            if (enable)
            {
                // First check to see if Bluetooth is enabled
                final boolean bluetoothEnabled = isBluetoothEnabled(true);
                if (!bluetoothEnabled) return null;
            }

            final boolean successful = bluetoothSurveyRecordLogger.enableLogging(enable);
            if (successful)
            {
                bluetoothLoggingEnabled.set(enable);
                if (enable)
                {
                    registerBluetoothSurveyRecordListener(bluetoothSurveyRecordLogger);
                } else
                {
                    unregisterBluetoothSurveyRecordListener(bluetoothSurveyRecordLogger);
                }
            }
            updateServiceNotification();

            final boolean newLoggingState = bluetoothLoggingEnabled.get();

            return successful ? newLoggingState : null;
        }
    }

    public boolean isCellularLoggingEnabled()
    {
        return cellularLoggingEnabled.get();
    }

    public boolean isWifiLoggingEnabled()
    {
        return wifiLoggingEnabled.get();
    }

    public boolean isBluetoothLoggingEnabled()
    {
        return bluetoothLoggingEnabled.get();
    }

    public boolean isGnssLoggingEnabled()
    {
        return gnssLoggingEnabled.get();
    }

    /**
     * Toggles the GNSS logging setting.
     * <p>
     * It is possible that an error occurs while trying to enable or disable logging.  In that event null will be
     * returned indicating that logging could not be toggled.
     *
     * @param enable True if logging should be enabled, false if it should be turned off.
     * @return The new state of logging.  True if it is enabled, or false if it is disabled.  Null is returned if the
     * toggling was unsuccessful.
     */
    public Boolean toggleGnssLogging(boolean enable)
    {
        synchronized (gnssLoggingEnabled)
        {
            final boolean originalLoggingState = gnssLoggingEnabled.get();
            if (originalLoggingState == enable) return originalLoggingState;

            Timber.i("Toggling GNSS logging to %s", enable);

            final boolean successful = gnssRecordLogger.enableLogging(enable);
            if (successful)
            {
                gnssLoggingEnabled.set(enable);
                if (enable)
                {
                    registerGnssSurveyRecordListener(gnssRecordLogger);
                } else
                {
                    unregisterGnssSurveyRecordListener(gnssRecordLogger);
                }
            }

            updateServiceNotification();

            final boolean newLoggingState = gnssLoggingEnabled.get();

            return successful ? newLoggingState : null;
        }
    }

    /**
     * Sends out a ping to the Google DNS IP Address (8.8.8.8) every n seconds.  This allow the data connection to
     * stay alive, which will enable us to get Timing Advance information.
     */
    public void initializePing()
    {
        serviceHandler.postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (!cellularLoggingEnabled.get()) return;

                    sendPing();

                    serviceHandler.postDelayed(this, PING_RATE_MS);
                } catch (Exception e)
                {
                    Timber.e(e, "An exception occurred trying to send out a ping");
                }
            }
        }, PING_RATE_MS);
    }

    public int getWifiScanRateMs()
    {
        return wifiScanRateMs;
    }

    /**
     * Checks to see if the GNSS timeout has occurred. If we have waited longer than {@link #TIME_TO_WAIT_FOR_GNSS_RAW_BEFORE_FAILURE}
     * without any GNSS measurements coming in, we can assume that the device does not support raw GNSS measurements.
     * If that is the case then present that information to the user so they know their device won't support it.
     */
    public void checkForGnssTimeout()
    {
        if (!gnssRawSupportKnown && !hasGnssRawFailureNagLaunched)
        {
            if (firstGpsAcqTime < 0L)
            {
                firstGpsAcqTime = System.currentTimeMillis();
            } else if (System.currentTimeMillis() > firstGpsAcqTime + TIME_TO_WAIT_FOR_GNSS_RAW_BEFORE_FAILURE)
            {
                hasGnssRawFailureNagLaunched = true;

                // The user may choose to continue using the app even without GNSS since
                // they do get some satellite status on this display. If that is the case,
                // they can choose not to be nagged about this every time they launch the app.
                boolean ignoreRawGnssFailure = PreferenceUtils.getBoolean(Application.get().getString(R.string.pref_key_ignore_raw_gnss_failure), false);
                if (!ignoreRawGnssFailure && gnssFailureListener != null)
                {
                    gnssFailureListener.onGnssFailure();
                    gpsListener.clearGnssTimeoutCallback(); // No need for the callback anymore
                }
            }
        } else
        {
            gpsListener.clearGnssTimeoutCallback();
        }
    }

    /**
     * @return The Android ID associated with this device and app.
     */
    @SuppressLint("HardwareIds")
    private String createDeviceId()
    {
        return Settings.Secure.getString(getContentResolver(), Settings.Secure.ANDROID_ID);
    }

    /**
     * Triggers a read of the scan rate values and stores them in instance variables.
     * <p>
     * The approach for reading the scan rates is to first use the MDM provided values. If those are not
     * set then the user preference values are employed. Finally, the default values are used as a fallback.
     *
     * @since 0.3.0
     */
    private void setScanRateValues()
    {
        final Context applicationContext = getApplicationContext();

        cellularScanRateMs = PreferenceUtils.getScanRatePreferenceMs(NetworkSurveyConstants.PROPERTY_CELLULAR_SCAN_INTERVAL_SECONDS,
                NetworkSurveyConstants.DEFAULT_CELLULAR_SCAN_INTERVAL_SECONDS, applicationContext);

        wifiScanRateMs = PreferenceUtils.getScanRatePreferenceMs(NetworkSurveyConstants.PROPERTY_WIFI_SCAN_INTERVAL_SECONDS,
                NetworkSurveyConstants.DEFAULT_WIFI_SCAN_INTERVAL_SECONDS, applicationContext);

        bluetoothScanRateMs = PreferenceUtils.getScanRatePreferenceMs(NetworkSurveyConstants.PROPERTY_BLUETOOTH_SCAN_INTERVAL_SECONDS,
                NetworkSurveyConstants.DEFAULT_BLUETOOTH_SCAN_INTERVAL_SECONDS, applicationContext);

        gnssScanRateMs = PreferenceUtils.getScanRatePreferenceMs(NetworkSurveyConstants.PROPERTY_GNSS_SCAN_INTERVAL_SECONDS,
                NetworkSurveyConstants.DEFAULT_GNSS_SCAN_INTERVAL_SECONDS, applicationContext);

        deviceStatusScanRateMs = PreferenceUtils.getScanRatePreferenceMs(NetworkSurveyConstants.PROPERTY_DEVICE_STATUS_SCAN_INTERVAL_SECONDS,
                NetworkSurveyConstants.DEFAULT_DEVICE_STATUS_SCAN_INTERVAL_SECONDS, applicationContext);

        surveyRecordProcessor.setGnssScanRateMs(gnssScanRateMs);

        updateLocationListener();
    }

    /**
     * Creates a new {@link GpsListener} if necessary, and Registers with the Android {@link LocationManager} for
     * location updates.
     * <p>
     * It is necessary to call this method after any of the scanning services are updated. This is needed because the
     * location update rate depends on which scanners are active, and which of those has the shortest scan interval. So
     * if any of that changes we need to update the rate at which we request location updates.
     * <p>
     * If none of the scanning is active, then this method does nothing an returns immediately.
     */
    private void updateLocationListener()
    {
        if (!isBeingUsed()) return;

        Timber.d("Registering the location listener");

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        {
            Timber.w("ACCESS_FINE_LOCATION Permission not granted for the NetworkSurveyService");
            return;
        }

        final LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
        if (locationManager != null)
        {
            // Start with the highest value
            int smallestScanRate = Math.max(cellularScanRateMs, Math.max(wifiScanRateMs, Math.max(bluetoothScanRateMs, Math.max(gnssScanRateMs, deviceStatusScanRateMs))));

            // Find the smallest scan rate for all the scanning types that are active as a starting point
            if (cellularScanningActive.get() && cellularScanRateMs < smallestScanRate)
            {
                smallestScanRate = cellularScanRateMs;
            }

            if (wifiScanningActive.get() && wifiScanRateMs < smallestScanRate)
            {
                smallestScanRate = wifiScanRateMs;
            }

            if (bluetoothScanningActive.get() && bluetoothScanRateMs < smallestScanRate)
            {
                smallestScanRate = bluetoothScanRateMs;
            }

            if (gnssStarted.get() && gnssScanRateMs < smallestScanRate)
            {
                smallestScanRate = gnssScanRateMs;
            }

            if (deviceStatusActive.get() && deviceStatusScanRateMs < smallestScanRate)
            {
                smallestScanRate = deviceStatusScanRateMs;
            }

            // Use the smallest scan rate set by the user for the active scanning types
            if (smallestScanRate > 10_000) smallestScanRate = smallestScanRate / 2;

            Timber.d("Setting the location update rate to %d", smallestScanRate);

            try
            {
                final String provider;
                if (locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
                {
                    provider = LocationManager.GPS_PROVIDER;
                } else if (locationManager.isProviderEnabled(LocationManager.NETWORK_PROVIDER))
                {
                    provider = LocationManager.NETWORK_PROVIDER;
                } else
                {
                    provider = LocationManager.PASSIVE_PROVIDER;
                }
                locationManager.requestLocationUpdates(provider, smallestScanRate, 0f, gpsListener, serviceLooper);
            } catch (Throwable t)
            {
                // An IllegalArgumentException was occurring on phones that don't have a GPS provider, so some defensive coding here
                Timber.e(t, "Could not request location updates because of an exception.");
            }
        } else
        {
            Timber.e("The location manager was null when trying to request location updates for the NetworkSurveyService");
        }
    }

    /**
     * Removes the location listener from the Android {@link LocationManager}.
     */
    private void removeLocationListener()
    {
        if (gpsListener != null)
        {
            final LocationManager locationManager = (LocationManager) getSystemService(Context.LOCATION_SERVICE);
            if (locationManager != null) locationManager.removeUpdates(gpsListener);
        }
    }

    /**
     * Runs one cellular scan. This is used to prime the UI in the event that the scan interval is really long.
     *
     * @since 0.3.0
     */
    public void runSingleCellularScan()
    {
        final TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        if (telephonyManager == null || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
        {
            Timber.w("Unable to get access to the Telephony Manager.  No network information will be displayed");
            return;
        }

        // The service handler can be null if this service has been stopped but the activity still has a reference to this old service
        if (serviceHandler == null) return;

        serviceHandler.postDelayed(() -> {
            try
            {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                {
                    telephonyManager.requestCellInfoUpdate(executorService, cellInfoCallback);
                } else
                {
                    execute(() -> {
                        try
                        {
                            surveyRecordProcessor.onCellInfoUpdate(telephonyManager.getAllCellInfo(),
                                    CalculationUtils.getNetworkType(telephonyManager.getDataNetworkType()),
                                    CalculationUtils.getNetworkType(telephonyManager.getVoiceNetworkType()));
                        } catch (Throwable t)
                        {
                            Timber.e(t, "Something went wrong when trying to get the cell info for a single scan");
                        }
                    });
                }
            } catch (SecurityException e)
            {
                Timber.e(e, "Could not get the required permissions to get the network details");
            }
        }, 1_000);
    }

    /**
     * Gets the {@link TelephonyManager}, and then starts a regular poll of cellular records.
     * <p>
     * This method only starts scanning if the scan is not already active.
     */
    private void startCellularRecordScanning()
    {
        if (cellularScanningActive.getAndSet(true)) return;

        final TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        if (telephonyManager == null || !getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
        {
            Timber.w("Unable to get access to the Telephony Manager.  No network information will be displayed");
            return;
        }

        final int handlerTaskId = cellularScanningTaskId.incrementAndGet();

        serviceHandler.postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (!cellularScanningActive.get() || cellularScanningTaskId.get() != handlerTaskId)
                    {
                        Timber.i("Stopping the handler that pulls the latest cellular information; taskId=%d", handlerTaskId);
                        return;
                    }

                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                    {
                        telephonyManager.requestCellInfoUpdate(executorService, cellInfoCallback);
                    } else
                    {
                        execute(() -> {
                            try
                            {
                                surveyRecordProcessor.onCellInfoUpdate(telephonyManager.getAllCellInfo(),
                                        CalculationUtils.getNetworkType(telephonyManager.getDataNetworkType()),
                                        CalculationUtils.getNetworkType(telephonyManager.getVoiceNetworkType()));
                            } catch (Throwable t)
                            {
                                Timber.e(t, "Failed to pass the cellular info to the survey record processor");
                            }
                        });
                    }

                    serviceHandler.postDelayed(this, cellularScanRateMs);
                } catch (SecurityException e)
                {
                    Timber.e(e, "Could not get the required permissions to get the network details");
                }
            }
        }, 1_000);

        updateLocationListener();
    }

    /**
     * Wraps the execute command for the executor service in a try catch to prevent the app from crashing if something
     * goes wrong with submitting the runnable. The most common crash I am seeing seems to be from the executor service
     * shutting down but some scan results are coming in. Hopefully that is the only case because otherwise we are
     * losing some survey results.
     *
     * @param runnable The runnable to execute on the executor service.
     * @since 1.5.0
     */
    private void execute(Runnable runnable)
    {
        try
        {
            executorService.execute(runnable);
        } catch (Throwable t)
        {
            Timber.w(t, "Could not submit to the executor service");
        }
    }

    /**
     * Stop polling for cellular scan updates.
     *
     * @since 0.3.0
     */
    private void stopCellularRecordScanning()
    {
        Timber.d("Setting the cellular scanning active flag to false");
        cellularScanningActive.set(false);

        updateLocationListener();
    }

    /**
     * Sends out a ping to the Google DNS IP Address (8.8.8.8).
     */
    private void sendPing()
    {
        try
        {
            Runtime runtime = Runtime.getRuntime();
            Process ipAddressProcess = runtime.exec("/system/bin/ping -c 1 8.8.8.8");
            int exitValue = ipAddressProcess.waitFor();
            Timber.v("Ping Exit Value: %s", exitValue);
        } catch (Exception e)
        {
            Timber.e(e, "An exception occurred trying to send out a ping ");
        }
    }

    /**
     * Tries to establish an MQTT broker connection after the phone is first started up.
     * <p>
     * This method only applies to creating the MQTT connection at boot for two reasons. First, it checks the start
     * MQTT at boot preference before creating the connection, and secondly, it does not first disconnect any existing
     * connections because it assumes this method is being called from a fresh start of the Android phone.
     * <p>
     * First, it tries to create a connection using the MDM configured MQTT parameters as long as the user has not
     * toggled the MDM override option. In that case, this method will jump straight to using the user provided MQTT
     * connection information.
     * <p>
     * If the MDM override option is enabled, or if the MDM connection information could not be found then this method
     * attempts to use the user provided MQTT connection information.
     *
     * @since 0.1.3
     */
    private void attemptMqttConnectionAtBoot()
    {
        if (!PreferenceUtils.getMqttStartOnBootPreference(getApplicationContext()))
        {
            Timber.i("Skipping the mqtt auto-connect because the preference indicated as such");
            return;
        }

        // First try to use the MDM settings. The only exception to this is if the user has overridden the MDM settings
        boolean mdmConnection = false;
        if (!isMqttMdmOverrideEnabled())
        {
            final RestrictionsManager restrictionsManager = (RestrictionsManager) getSystemService(Context.RESTRICTIONS_SERVICE);
            if (restrictionsManager != null)
            {
                final BrokerConnectionInfo connectionInfo = getMdmBrokerConnectionInfo();
                if (connectionInfo != null)
                {
                    mdmConnection = true;
                    connectToMqttBroker(connectionInfo);
                }
            }
        }

        if (!mdmConnection)
        {
            final BrokerConnectionInfo userBrokerConnectionInfo = getUserBrokerConnectionInfo();
            if (userBrokerConnectionInfo != null)
            {
                connectToMqttBroker(userBrokerConnectionInfo);
            }
        }
    }

    /**
     * Create the Cellular Scan callback that will be notified of Cellular scan events once
     * {@link #startCellularRecordScanning()} is called.
     *
     * @since 0.3.0
     */
    private void initializeCellularScanningResources()
    {
        final TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);

        if (telephonyManager == null)
        {
            Timber.e("Unable to get access to the Telephony Manager.  No network information will be displayed");
            return;
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
        {
            cellInfoCallback = new TelephonyManager.CellInfoCallback()
            {
                @Override
                public void onCellInfo(@NonNull List<CellInfo> cellInfo)
                {
                    String dataNetworkType = "Unknown";
                    String voiceNetworkType = "Unknown";
                    if (ActivityCompat.checkSelfPermission(NetworkSurveyService.this, Manifest.permission.READ_PHONE_STATE) == PackageManager.PERMISSION_GRANTED)
                    {
                        dataNetworkType = CalculationUtils.getNetworkType(telephonyManager.getDataNetworkType());
                        voiceNetworkType = CalculationUtils.getNetworkType(telephonyManager.getVoiceNetworkType());
                    }

                    surveyRecordProcessor.onCellInfoUpdate(cellInfo, dataNetworkType, voiceNetworkType);
                }

                @Override
                public void onError(int errorCode, @Nullable Throwable detail)
                {
                    super.onError(errorCode, detail);
                    Timber.w(detail, "Received an error from the Telephony Manager when requesting a cell info update; errorCode=%s", errorCode);
                }
            };
        }
    }

    /**
     * Create the Wi-Fi Scan broadcast receiver that will be notified of Wi-Fi scan events once
     * {@link #startWifiRecordScanning()} is called.
     *
     * @since 0.1.2
     */
    private void initializeWifiScanningResources()
    {
        final WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);

        if (wifiManager == null)
        {
            Timber.e("The WifiManager is null. Wi-Fi survey won't work");
            return;
        }

        wifiScanReceiver = new BroadcastReceiver()
        {
            @Override
            public void onReceive(Context c, Intent intent)
            {
                boolean success = intent.getBooleanExtra(WifiManager.EXTRA_RESULTS_UPDATED, false);
                if (success)
                {
                    final List<ScanResult> results = wifiManager.getScanResults();
                    if (results == null)
                    {
                        Timber.d("Null wifi scan results");
                        return;
                    }

                    surveyRecordProcessor.onWifiScanUpdate(results);
                } else
                {
                    Timber.e("A Wi-Fi scan failed, ignoring the results.");
                }
            }
        };
    }

    /**
     * Create the Bluetooth Scan broadcast receiver that will be notified of Bluetooth scan events once
     * {@link #startBluetoothRecordScanning()} is called.
     *
     * @since 1.0.0
     */
    private void initializeBluetoothScanningResources()
    {
        final BluetoothManager bluetoothManager = (BluetoothManager) getSystemService(Context.BLUETOOTH_SERVICE);

        if (bluetoothManager == null)
        {
            Timber.e("The BluetoothManager is null. Bluetooth survey won't work");
            return;
        }

        final BluetoothAdapter bluetoothAdapter = bluetoothManager.getAdapter();
        if (bluetoothAdapter == null)
        {
            Timber.e("The BluetoothAdapter is null. Bluetooth survey won't work");
            return;
        }

        bluetoothBroadcastReceiver = new BroadcastReceiver()
        {
            @Override
            public void onReceive(Context context, Intent intent)
            {
                if (BluetoothDevice.ACTION_FOUND.equals(intent.getAction()))
                {
                    final BluetoothDevice device = intent.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE);
                    if (device == null)
                    {
                        Timber.e("Received a null BluetoothDevice in the broadcast action found call");
                        return;
                    }

                    int rssi = Short.MIN_VALUE;
                    if (intent.hasExtra(BluetoothDevice.EXTRA_RSSI))
                    {
                        rssi = intent.getShortExtra(BluetoothDevice.EXTRA_RSSI, Short.MIN_VALUE);
                    }

                    if (rssi == Short.MIN_VALUE) return;

                    surveyRecordProcessor.onBluetoothClassicScanUpdate(device, rssi);
                }
            }
        };

        bluetoothScanCallback = new ScanCallback()
        {
            @Override
            public void onScanResult(int callbackType, android.bluetooth.le.ScanResult result)
            {
                surveyRecordProcessor.onBluetoothScanUpdate(result);
            }

            @Override
            public void onBatchScanResults(List<android.bluetooth.le.ScanResult> results)
            {
                surveyRecordProcessor.onBluetoothScanUpdate(results);
            }

            @Override
            public void onScanFailed(int errorCode)
            {
                if (errorCode == SCAN_FAILED_ALREADY_STARTED)
                {
                    Timber.i("Bluetooth scan already started, so this scan failed");
                } else
                {
                    Timber.e("A Bluetooth scan failed, ignoring the results.");
                }
            }
        };
    }

    /**
     * Create the callbacks for the {@link GnssMeasurementsEvent} and the {@link GnssStatus} that will be notified of
     * events from the location manager once {@link #startGnssRecordScanning()} is called.
     *
     * @since 0.3.0
     */
    private void initializeGnssScanningResources()
    {
        measurementListener = new GnssMeasurementsEvent.Callback()
        {
            @Override
            public void onGnssMeasurementsReceived(GnssMeasurementsEvent event)
            {
                gnssRawSupportKnown = true;
                if (surveyRecordProcessor != null) surveyRecordProcessor.onGnssMeasurements(event);
            }
        };
    }

    /**
     * Register a listener for Wi-Fi scans, and then kick off a scheduled Wi-Fi scan.
     * <p>
     * This method only starts scanning if the scan is not already active.
     *
     * @since 0.1.2
     */
    private void startWifiRecordScanning()
    {
        if (wifiScanningActive.getAndSet(true)) return;

        final IntentFilter scanResultsIntentFilter = new IntentFilter();
        scanResultsIntentFilter.addAction(WifiManager.SCAN_RESULTS_AVAILABLE_ACTION);
        registerReceiver(wifiScanReceiver, scanResultsIntentFilter);

        final WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);

        if (wifiManager == null)
        {
            Timber.wtf("The Wi-Fi manager is null, can't start scanning for Wi-Fi networks.");
            wifiScanningActive.set(false);
            return;
        }

        final int handlerTaskId = wifiScanningTaskId.incrementAndGet();

        serviceHandler.postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (!wifiScanningActive.get() || wifiScanningTaskId.get() != handlerTaskId)
                    {
                        Timber.i("Stopping the handler that pulls the latest wifi information, taskId=%d", handlerTaskId);
                        return;
                    }

                    boolean success = wifiManager.startScan();

                    if (!success) Timber.e("Kicking off a Wi-Fi scan failed");

                    serviceHandler.postDelayed(this, wifiScanRateMs);
                } catch (Exception e)
                {
                    Timber.e(e, "Could not run a Wi-Fi scan");
                }
            }
        }, 2_000);

        updateLocationListener();
    }

    /**
     * Unregister the Wi-Fi scan broadcast receiver and stop the scanning service handler.
     *
     * @since 0.1.2
     */
    private void stopWifiRecordScanning()
    {
        wifiScanningActive.set(false);

        try
        {
            unregisterReceiver(wifiScanReceiver);
        } catch (Exception e)
        {
            // Because we are extra cautious and want to make sure that we unregister the receiver, when the service
            // is shutdown we call this method to make sure we stop any active scan and unregister the receiver even if
            // we don't have one registered.
            Timber.v(e, "Could not unregister the NetworkSurveyService Wi-Fi Scan Receiver");
        }

        updateLocationListener();
    }

    /**
     * Note that the {@link Manifest.permission#BLUETOOTH_SCAN} permission was added in Android 12, so this method
     * returns true for all older versions.
     *
     * @return True if the {@link Manifest.permission#BLUETOOTH_SCAN} permission has been granted. False otherwise.
     * @since 1.6.0
     */
    private boolean hasBtScanPermission()
    {
        // The BLUETOOTH_SCAN permission was added in Android 12
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.S) return true;

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED)
        {
            Timber.w("The BLUETOOTH_SCAN permission has not been granted");
            return false;
        }

        return true;
    }

    /**
     * Register a listener for Bluetooth scans, and then kick off a scheduled Bluetooth scan.
     * <p>
     * This method only starts scanning if the scan is not already active and we have the required permissions.
     *
     * @since 1.0.0
     */
    private void startBluetoothRecordScanning()
    {
        if (!hasBtScanPermission())
        {
            uiThreadHandler.post(() -> Toast.makeText(getApplicationContext(), getString(R.string.grant_bluetooth_scan_permission), Toast.LENGTH_LONG).show());
            return;
        }

        if (bluetoothScanningActive.getAndSet(true)) return;

        final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null)
        {
            Timber.e("The BluetoothAdapter is null. Bluetooth survey won't work");
            bluetoothScanningActive.set(false);
            return;
        }

        final BluetoothLeScanner bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
        if (bluetoothLeScanner == null)
        {
            Timber.e("The BluetoothLeScanner is null, unable to perform Bluetooth LE scans.");
            bluetoothScanningActive.set(false);
            return;
        }

        final IntentFilter intentFilter = new IntentFilter(BluetoothDevice.ACTION_FOUND);
        intentFilter.addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED);
        registerReceiver(bluetoothBroadcastReceiver, intentFilter);

        final ScanSettings.Builder scanSettingsBuilder = new ScanSettings.Builder();
        scanSettingsBuilder.setScanMode(ScanSettings.SCAN_MODE_LOW_POWER);
        scanSettingsBuilder.setReportDelay(bluetoothScanRateMs);
        bluetoothLeScanner.startScan(Collections.emptyList(), scanSettingsBuilder.build(), bluetoothScanCallback);

        final int handlerTaskId = bluetoothScanningTaskId.incrementAndGet();

        serviceHandler.postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (!bluetoothScanningActive.get() || bluetoothScanningTaskId.get() != handlerTaskId)
                    {
                        Timber.i("Stopping the handler that pulls the latest Bluetooth information; taskId=%d", handlerTaskId);
                        return;
                    }

                    // Calling start Discovery scans for BT Classic (BR/EDR) devices as well. However, it also seems
                    // it allows for getting some BLE devices as well, but we seem to get more with the BLE scanner above
                    if (!bluetoothAdapter.isDiscovering())
                    {
                        bluetoothAdapter.startDiscovery();
                    } else
                    {
                        Timber.d("Bluetooth discovery already in progress, not starting a new discovery.");
                    }

                    serviceHandler.postDelayed(this, bluetoothScanRateMs);
                } catch (Exception e)
                {
                    Timber.e(e, "Could not run a Bluetooth scan");
                }
            }
        }, 1_000);

        updateLocationListener();
    }

    /**
     * Unregister the Bluetooth scan callback and stop the scanning service handler.
     *
     * @since 1.0.0
     */
    private void stopBluetoothRecordScanning()
    {
        bluetoothScanningActive.set(false);

        try
        {
            final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
            if (bluetoothAdapter != null)
            {
                bluetoothAdapter.cancelDiscovery();

                final BluetoothLeScanner bluetoothLeScanner = bluetoothAdapter.getBluetoothLeScanner();
                if (bluetoothLeScanner != null) bluetoothLeScanner.stopScan(bluetoothScanCallback);
            }
            unregisterReceiver(bluetoothBroadcastReceiver);
        } catch (Exception e)
        {
            Timber.v(e, "Could not stop the Bluetooth Scan Callback");
        }

        updateLocationListener();
    }

    /**
     * Initialize and start the handler that generates a periodic Device Status Message.
     * <p>
     * This method only starts scanning if the scan is not already active.
     *
     * @since 1.1.0
     */
    private void startDeviceStatusReport()
    {
        if (deviceStatusActive.getAndSet(true)) return;

        final int handlerTaskId = deviceStatusGeneratorTaskId.incrementAndGet();

        serviceHandler.postDelayed(new Runnable()
        {
            @Override
            public void run()
            {
                try
                {
                    if (!deviceStatusActive.get() || deviceStatusGeneratorTaskId.get() != handlerTaskId)
                    {
                        Timber.i("Stopping the handler that generates the device status message; taskId=%d", handlerTaskId);
                        return;
                    }

                    surveyRecordProcessor.onDeviceStatus(generateDeviceStatus());

                    serviceHandler.postDelayed(this, deviceStatusScanRateMs);
                } catch (SecurityException e)
                {
                    Timber.e(e, "Could not get the required permissions to generate a device status message");
                }
            }
        }, 1000L);

        // Add a listener for the Service State information if we have access to the Telephony Manager
        final TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
        if (telephonyManager != null && getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
        {
            Timber.d("Adding the Telephony Manager Service State Listener");

            // Sadly we have to use the service handler for this because the PhoneStateListener constructor calls
            // Looper.myLooper(), which needs to be run from a thread where the looper is prepared. The better option
            // is to use the constructor that takes an executor service, but that is only supported in Android 10+.
            serviceHandler.post(() -> {
                phoneStateListener = new PhoneStateListener()
                {
                    @Override
                    public void onServiceStateChanged(ServiceState serviceState)
                    {
                        execute(() -> surveyRecordProcessor.onServiceStateChanged(serviceState, telephonyManager));
                    }

                    // We can't use this because you have to be a system app to get the READ_PRECISE_PHONE_STATE permission.
                    // So this is unused for now, but maybe at some point in the future we can make use of it.
                    /*@Override
                    public void onRegistrationFailed(@NonNull CellIdentity cellIdentity, @NonNull String chosenPlmn, int domain, int causeCode, int additionalCauseCode)
                    {
                        execute(() -> surveyRecordProcessor.onRegistrationFailed(cellIdentity, domain, causeCode, additionalCauseCode, telephonyManager));
                    }*/
                };

                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_SERVICE_STATE);
            });
        }
    }

    /**
     * Generate a device status message that can be sent to any remote servers.
     *
     * @return A Device Status message that can be sent to a remote server.
     * @since 1.1.0
     */
    private DeviceStatus generateDeviceStatus()
    {
        final DeviceStatusData.Builder dataBuilder = DeviceStatusData.newBuilder();
        dataBuilder.setDeviceSerialNumber(deviceId)
                .setDeviceTime(IOUtils.getRfc3339String(ZonedDateTime.now()));

        if (gpsListener != null)
        {
            final Location lastKnownLocation = gpsListener.getLatestLocation();
            if (lastKnownLocation != null)
            {
                dataBuilder.setLatitude(lastKnownLocation.getLatitude());
                dataBuilder.setLongitude(lastKnownLocation.getLongitude());
                dataBuilder.setAltitude((float) lastKnownLocation.getAltitude());
                dataBuilder.setAccuracy(MathUtils.roundAccuracy(lastKnownLocation.getAccuracy()));
            }
        }

        final IntentFilter intentFilter = new IntentFilter(Intent.ACTION_BATTERY_CHANGED);
        final Intent batteryStatus = registerReceiver(null, intentFilter);
        if (batteryStatus != null)
        {
            int level = batteryStatus.getIntExtra(BatteryManager.EXTRA_LEVEL, -1);
            int scale = batteryStatus.getIntExtra(BatteryManager.EXTRA_SCALE, -1);
            final float batteryPercent = (level / (float) scale) * 100;
            dataBuilder.setBatteryLevelPercent(Int32Value.of((int) batteryPercent));
        }

        dataBuilder.setDeviceModel(Build.MODEL);

        final DeviceStatus.Builder statusBuilder = DeviceStatus.newBuilder();
        statusBuilder.setMessageType(DeviceStatusMessageConstants.DEVICE_STATUS_MESSAGE_TYPE);
        statusBuilder.setVersion(BuildConfig.MESSAGING_API_VERSION);
        statusBuilder.setData(dataBuilder);

        return statusBuilder.build();
    }

    /**
     * Stop generating device status messages.
     *
     * @since 1.1.0
     */
    private void stopDeviceStatusReport()
    {
        Timber.d("Setting the device status active flag to false");

        if (phoneStateListener != null)
        {
            final TelephonyManager telephonyManager = (TelephonyManager) getSystemService(Context.TELEPHONY_SERVICE);
            if (telephonyManager != null && getPackageManager().hasSystemFeature(PackageManager.FEATURE_TELEPHONY))
            {
                Timber.d("Removing the Telephony Manager Service State Listener");

                telephonyManager.listen(phoneStateListener, PhoneStateListener.LISTEN_NONE);
            }
        }

        deviceStatusActive.set(false);

        updateLocationListener();
    }

    /**
     * A notification for this service that is started in the foreground so that we can continue to get GPS location
     * updates while the phone is locked or the app is not in the foreground.
     */
    private void updateServiceNotification()
    {
        startForeground(NetworkSurveyConstants.LOGGING_NOTIFICATION_ID, buildNotification());
    }

    /**
     * Creates a new {@link Notification} based on the current state of this service.  The returned notification can
     * then be passed on to the Android system.
     *
     * @return A {@link Notification} that represents the current state of this service (e.g. if logging is enabled).
     */
    private Notification buildNotification()
    {
        Application.createNotificationChannel(this);

        final boolean logging = cellularLoggingEnabled.get() || wifiLoggingEnabled.get() || bluetoothLoggingEnabled.get() || gnssLoggingEnabled.get();
        final com.craxiom.mqttlibrary.connection.ConnectionState connectionState = mqttConnection.getConnectionState();
        final boolean mqttConnectionActive = connectionState == ConnectionState.CONNECTED || connectionState == ConnectionState.CONNECTING;
        final CharSequence notificationTitle = getText(R.string.network_survey_notification_title);
        final String notificationText = getNotificationText(logging, mqttConnectionActive, connectionState);

        final Intent notificationIntent = new Intent(this, NetworkSurveyActivity.class);
        final PendingIntent pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE);

        final NotificationCompat.Builder builder = new NotificationCompat.Builder(this, NetworkSurveyConstants.NOTIFICATION_CHANNEL_ID)
                .setContentTitle(notificationTitle)
                .setOngoing(true)
                .setSmallIcon(mqttConnectionActive ? R.drawable.ic_cloud_connection : (logging ? R.drawable.logging_thick_icon : R.drawable.gps_map_icon))
                .setContentIntent(pendingIntent)
                .setTicker(notificationTitle)
                .setContentText(notificationText)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(notificationText));

        if (connectionState == ConnectionState.CONNECTING)
        {
            builder.setColor(getResources().getColor(R.color.connectionStatusConnecting, null));
            builder.setColorized(true);
        }

        return builder.build();
    }

    /**
     * Gets the text to use for the Network Survey Service Notification.
     *
     * @param logging              True if logging is active, false if disabled.
     * @param mqttConnectionActive True if the MQTT connection is either in a connected or reconnecting state.
     * @param connectionState      The actual connection state of the MQTT broker connection.
     * @return The text that can be added to the service notification.
     * @since 0.1.1
     */
    private String getNotificationText(boolean logging, boolean mqttConnectionActive, ConnectionState connectionState)
    {
        String notificationText = "";

        if (logging)
        {
            notificationText = String.valueOf(getText(R.string.logging_notification_text)) + (mqttConnectionActive ? getText(R.string.and) : "");
        }

        switch (connectionState)
        {
            case CONNECTED:
                notificationText += getText(R.string.mqtt_connection_notification_text);
                break;
            case CONNECTING:
                notificationText += getText(R.string.mqtt_reconnecting_notification_text);
                break;
            default:
        }

        return notificationText;
    }

    /**
     * Starts GNSS record scanning if it is not already started.
     * <p>
     * This method handles registering the GNSS listeners with Android so we get notified of updates.
     * <p>
     * This method is not thread safe, so make sure to call this method from a synchronized block.
     */
    private boolean startGnssRecordScanning()
    {
        if (gnssStarted.getAndSet(true)) return true;

        boolean success = false;

        boolean hasPermissions = ContextCompat.checkSelfPermission(this,
                Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED;
        hasPermissions = hasPermissions && ContextCompat.checkSelfPermission(this,
                Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;

        if (hasPermissions)
        {
            if (locationManager == null)
            {
                locationManager = getSystemService(LocationManager.class);
                if (locationManager != null)
                {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R)
                    {
                        locationManager.registerGnssMeasurementsCallback(executorService, measurementListener);
                    } else
                    {
                        locationManager.registerGnssMeasurementsCallback(measurementListener);
                    }
                    gpsListener.addGnssTimeoutCallback(this::checkForGnssTimeout);
                    Timber.i("Successfully registered the GNSS listeners");
                }
            } else
            {
                Timber.w("The location manager was null when registering the GNSS listeners");
            }

            success = true;
        }

        updateLocationListener();

        return success;
    }

    /**
     * Unregisters from GPS/GNSS updates from the Android OS.
     * <p>
     * This method is not thread safe, so make sure to call this method from a synchronized block.
     */
    private void stopGnssRecordScanning()
    {
        if (!gnssStarted.getAndSet(false)) return;

        if (locationManager != null)
        {
            locationManager.unregisterGnssMeasurementsCallback(measurementListener);
            gpsListener.clearGnssTimeoutCallback();
            locationManager = null;
        }

        updateLocationListener();
    }

    /**
     * If any of the loggers are still active, this stops them all just to be safe. If they are not active then nothing
     * changes.
     *
     * @since 0.3.0
     */
    private void stopAllLogging()
    {
        if (cellularSurveyRecordLogger != null) cellularSurveyRecordLogger.enableLogging(false);
        if (wifiSurveyRecordLogger != null) wifiSurveyRecordLogger.enableLogging(false);
        if (bluetoothSurveyRecordLogger != null) bluetoothSurveyRecordLogger.enableLogging(false);
        if (gnssRecordLogger != null) gnssRecordLogger.enableLogging(false);
        if (phoneStateRecordLogger != null) phoneStateRecordLogger.enableLogging(false);
    }

    /**
     * Close out the notification since we no longer need this service.
     */
    private void shutdownNotifications()
    {
        stopForeground(true);
    }

    /**
     * Register a listener so that if the Managed Config changes we will be notified of the new config and can restart
     * the MQTT broker connection with the new parameters.
     *
     * @since 0.1.1
     */
    private void registerManagedConfigurationListener()
    {
        final IntentFilter restrictionsFilter = new IntentFilter(Intent.ACTION_APPLICATION_RESTRICTIONS_CHANGED);

        managedConfigurationListener = new BroadcastReceiver()
        {
            @Override
            public void onReceive(Context context, Intent intent)
            {
                setScanRateValues();
                attemptMqttConnectWithMdmConfig(true);

                cellularSurveyRecordLogger.onMdmPreferenceChanged();
                wifiSurveyRecordLogger.onMdmPreferenceChanged();
                bluetoothSurveyRecordLogger.onMdmPreferenceChanged();
                gnssRecordLogger.onMdmPreferenceChanged();
                phoneStateRecordLogger.onMdmPreferenceChanged();
            }
        };

        registerReceiver(managedConfigurationListener, restrictionsFilter);
    }

    /**
     * Remove the managed configuration listener.
     *
     * @since 0.1.1
     */
    private void unregisterManagedConfigurationListener()
    {
        if (managedConfigurationListener != null)
        {
            try
            {
                unregisterReceiver(managedConfigurationListener);
            } catch (Exception e)
            {
                Timber.e(e, "Unable to unregister the Managed Configuration Listener when pausing the app");
            }
            managedConfigurationListener = null;
        }
    }

    /**
     * @return True if the MQTT MDM override has been enabled by the user.  False if the MDM config should still be employed.
     */
    private boolean isMqttMdmOverrideEnabled()
    {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());
        return preferences.getBoolean(MqttConstants.PROPERTY_MQTT_MDM_OVERRIDE, false);
    }

    /**
     * Get the MDM configured MQTT broker connection information to use to establish the connection.
     * <p>
     * If the user has specified to override the MDM connection config, then null is returned.
     *
     * @return The connection settings to use for the MQTT broker, or null if no connection information is present or
     * the user has overrode the MDM config.
     * @since 0.1.1
     */

    private BrokerConnectionInfo getMdmBrokerConnectionInfo()
    {
        final RestrictionsManager restrictionsManager = (RestrictionsManager) getSystemService(Context.RESTRICTIONS_SERVICE);
        if (restrictionsManager != null)
        {
            final Bundle mdmProperties = restrictionsManager.getApplicationRestrictions();

            final boolean hasBrokerHost = mdmProperties.containsKey(MqttConstants.PROPERTY_MQTT_CONNECTION_HOST);
            if (!hasBrokerHost) return null;

            final String mqttBrokerHost = mdmProperties.getString(MqttConstants.PROPERTY_MQTT_CONNECTION_HOST);
            final int portNumber = mdmProperties.getInt(MqttConstants.PROPERTY_MQTT_CONNECTION_PORT, MqttConstants.DEFAULT_MQTT_PORT);
            final boolean tlsEnabled = mdmProperties.getBoolean(MqttConstants.PROPERTY_MQTT_CONNECTION_TLS_ENABLED, MqttConstants.DEFAULT_MQTT_TLS_SETTING);
            final String clientId = mdmProperties.getString(MqttConstants.PROPERTY_MQTT_CLIENT_ID);
            final String username = mdmProperties.getString(MqttConstants.PROPERTY_MQTT_USERNAME);
            final String password = mdmProperties.getString(MqttConstants.PROPERTY_MQTT_PASSWORD);

            final boolean cellularStreamEnabled = mdmProperties.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_CELLULAR_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_CELLULAR_STREAM_SETTING);
            final boolean wifiStreamEnabled = mdmProperties.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_WIFI_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_WIFI_STREAM_SETTING);
            final boolean bluetoothStreamEnabled = mdmProperties.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_BLUETOOTH_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_BLUETOOTH_STREAM_SETTING);
            final boolean gnssStreamEnabled = mdmProperties.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_GNSS_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_GNSS_STREAM_SETTING);
            final boolean deviceStatusStreamEnabled = mdmProperties.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_DEVICE_STATUS_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_DEVICE_STATUS_STREAM_SETTING);

            if (mqttBrokerHost == null || clientId == null)
            {
                return null;
            }

            return new MqttConnectionInfo(mqttBrokerHost, portNumber, tlsEnabled, clientId, username, password,
                    cellularStreamEnabled, wifiStreamEnabled, bluetoothStreamEnabled, gnssStreamEnabled, deviceStatusStreamEnabled);
        }

        return null;
    }

    /**
     * Get the user configured MQTT broker connection information to use to establish the connection.
     * <p>
     * If no user defined MQTT broker connection information is present, then null is returned.
     *
     * @return The connection settings to use for the MQTT broker, or null if no connection information is present.
     * @since 0.1.3
     */
    private BrokerConnectionInfo getUserBrokerConnectionInfo()
    {
        final SharedPreferences preferences = PreferenceManager.getDefaultSharedPreferences(getApplicationContext());

        final String mqttBrokerHost = preferences.getString(MqttConstants.PROPERTY_MQTT_CONNECTION_HOST, "");
        if (mqttBrokerHost.isEmpty()) return null;

        final String clientId = preferences.getString(MqttConstants.PROPERTY_MQTT_CLIENT_ID, "");
        if (clientId.isEmpty()) return null;

        final int portNumber = preferences.getInt(MqttConstants.PROPERTY_MQTT_CONNECTION_PORT, MqttConstants.DEFAULT_MQTT_PORT);
        final boolean tlsEnabled = preferences.getBoolean(MqttConstants.PROPERTY_MQTT_CONNECTION_TLS_ENABLED, MqttConstants.DEFAULT_MQTT_TLS_SETTING);
        final String username = preferences.getString(MqttConstants.PROPERTY_MQTT_USERNAME, "");
        final String password = preferences.getString(MqttConstants.PROPERTY_MQTT_PASSWORD, "");

        final boolean cellularStreamEnabled = preferences.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_CELLULAR_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_CELLULAR_STREAM_SETTING);
        final boolean wifiStreamEnabled = preferences.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_WIFI_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_WIFI_STREAM_SETTING);
        final boolean bluetoothStreamEnabled = preferences.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_BLUETOOTH_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_BLUETOOTH_STREAM_SETTING);
        final boolean gnssStreamEnabled = preferences.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_GNSS_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_GNSS_STREAM_SETTING);
        final boolean deviceStatusStreamEnabled = preferences.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_DEVICE_STATUS_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_DEVICE_STATUS_STREAM_SETTING);

        return new MqttConnectionInfo(mqttBrokerHost, portNumber, tlsEnabled, clientId, username, password,
                cellularStreamEnabled, wifiStreamEnabled, bluetoothStreamEnabled, gnssStreamEnabled, deviceStatusStreamEnabled);
    }

    /**
     * Checks to see if the Wi-Fi manager is present, and if Wi-Fi is enabled.
     * <p>
     * After the check to see if Wi-Fi is enabled, if Wi-Fi is currently disabled and {@code promptEnable} is true, the
     * user is then prompted to turn on Wi-Fi.  Even if the user turns on Wi-Fi, this method will still return false
     * since the call to enable Wi-Fi is asynchronous.
     *
     * @param promptEnable If true, and Wi-Fi is currently disabled, the user will be presented with a UI to turn on Wi-Fi.
     * @return True if Wi-Fi is enabled, false if it is not.
     * @since 0.1.2
     */
    private boolean isWifiEnabled(boolean promptEnable)
    {
        boolean isEnabled = true;

        final WifiManager wifiManager = (WifiManager) getSystemService(Context.WIFI_SERVICE);

        if (wifiManager == null) isEnabled = false;

        if (wifiManager != null && !wifiManager.isWifiEnabled())
        {
            isEnabled = false;

            if (promptEnable)
            {
                Timber.i("Wi-Fi is disabled, prompting the user to enable it");

                if (Build.VERSION.SDK_INT >= 29)
                {
                    final Intent panelIntent = new Intent(Settings.Panel.ACTION_WIFI);
                    panelIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                    startActivity(panelIntent);
                } else
                {
                    // Open the Wi-Fi setting pages after a couple seconds
                    uiThreadHandler.post(() -> Toast.makeText(getApplicationContext(), getString(R.string.turn_on_wifi), Toast.LENGTH_SHORT).show());
                    serviceHandler.postDelayed(() -> {
                        try
                        {
                            final Intent wifiSettingIntent = new Intent(Settings.ACTION_WIFI_SETTINGS);
                            wifiSettingIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                            startActivity(wifiSettingIntent);
                        } catch (Exception e)
                        {
                            // An IllegalStateException can occur when the fragment is no longer attached to the activity
                            Timber.e(e, "Could not kick off the Wifi Settings Intent for the older pre Android 10 setup");
                        }
                    }, 2000);
                }
            }
        }

        return isEnabled;
    }

    /**
     * Checks to see if the Bluetooth adapter is present, and if Bluetooth is enabled.
     * <p>
     * After the check to see if Bluetooth is enabled, if Bluetooth is currently disabled and {@code promptEnable} is
     * true, the user is then prompted to turn on Bluetooth.  Even if the user turns on Bluetooth, this method will
     * still return false since the call to enable Wi-Fi is asynchronous.
     *
     * @param promptEnable If true, and Bluetooth is currently disabled, the user will be presented with a UI to turn on
     *                     Bluetooth.
     * @return True if Bluetooth is enabled, false if it is not.
     * @since 1.0.0
     */
    private boolean isBluetoothEnabled(boolean promptEnable)
    {
        final BluetoothAdapter bluetoothAdapter = BluetoothAdapter.getDefaultAdapter();
        if (bluetoothAdapter == null) return false;

        boolean isEnabled = true;

        if (!bluetoothAdapter.isEnabled())
        {
            isEnabled = false;

            if (promptEnable)
            {
                Timber.i("Bluetooth is disabled, prompting the user to enable it");

                uiThreadHandler.post(() -> Toast.makeText(getApplicationContext(), getString(R.string.turn_on_bluetooth), Toast.LENGTH_SHORT).show());
                serviceHandler.post(() -> {
                    try
                    {
                        final Intent enableBtIntent = new Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE);
                        enableBtIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
                        startActivity(enableBtIntent);
                    } catch (Exception e)
                    {
                        // An IllegalStateException can occur when the fragment is no longer attached to the activity
                        Timber.e(e, "Could not kick off the Bluetooth Enable Intent");
                    }
                });
            }
        }

        return isEnabled;
    }

    @Override
    public void onConnectionStateChange(ConnectionState connectionState)
    {
        updateServiceNotification();
    }

    /**
     * Class used for the client Binder.  Because we know this service always runs in the same process as its clients,
     * we don't need to deal with IPC.
     */
    public class SurveyServiceBinder extends AConnectionFragment.ServiceBinder
    {
        @Override
        public IMqttService getService()
        {
            return NetworkSurveyService.this;
        }
    }

    /**
     * Registers a listener any GNSS failures. This can include timing out before we received any
     * GNSS measurements.
     *
     * @param gnssFailureListener The listener.
     * @since 0.4.0
     */
    public void registerGnssFailureListener(IGnssFailureListener gnssFailureListener)
    {
        this.gnssFailureListener = gnssFailureListener;
    }

    /**
     * Clears the GNSS failure listener.
     *
     * @since 0.4.0
     */
    public void clearGnssFailureListener()
    {
        gnssFailureListener = null;
    }
}
