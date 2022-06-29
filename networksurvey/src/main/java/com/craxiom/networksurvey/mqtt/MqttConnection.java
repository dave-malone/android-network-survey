package com.craxiom.networksurvey.mqtt;

import android.content.Context;

import com.amazonaws.services.iot.client.AWSIotException;
import com.amazonaws.services.iot.client.AWSIotMqttClient;
import com.craxiom.messaging.BluetoothRecord;
import com.craxiom.messaging.CdmaRecord;
import com.craxiom.messaging.DeviceStatus;
import com.craxiom.messaging.GnssRecord;
import com.craxiom.messaging.GsmRecord;
import com.craxiom.messaging.LteRecord;
import com.craxiom.messaging.NrRecord;
import com.craxiom.messaging.PhoneState;
import com.craxiom.messaging.UmtsRecord;
import com.craxiom.messaging.WifiBeaconRecord;
import com.craxiom.mqttlibrary.IConnectionStateListener;
import com.craxiom.mqttlibrary.connection.BrokerConnectionInfo;
import com.craxiom.mqttlibrary.connection.ConnectionState;
import com.craxiom.mqttlibrary.connection.DefaultMqttConnection;
import com.craxiom.networksurvey.listeners.IBluetoothSurveyRecordListener;
import com.craxiom.networksurvey.listeners.ICellularSurveyRecordListener;
import com.craxiom.networksurvey.listeners.IDeviceStatusListener;
import com.craxiom.networksurvey.listeners.IGnssSurveyRecordListener;
import com.craxiom.networksurvey.listeners.IWifiSurveyRecordListener;
import com.craxiom.networksurvey.model.WifiRecordWrapper;
import com.google.protobuf.MessageOrBuilder;
import com.google.protobuf.util.JsonFormat;

import java.util.List;

import timber.log.Timber;


/**
 * Class for creating a connection to an MQTT server.
 *
 * @since 0.1.1
 */
//public class MqttConnection extends DefaultMqttConnection
public class MqttConnection implements ICellularSurveyRecordListener, IWifiSurveyRecordListener,
        IBluetoothSurveyRecordListener, IGnssSurveyRecordListener, IDeviceStatusListener {
    private static final String MQTT_TOPIC_PREFIX = "networksurvey";
    private static final String MQTT_GSM_MESSAGE_TOPIC = MQTT_TOPIC_PREFIX + "/gsm";
    private static final String MQTT_CDMA_MESSAGE_TOPIC = MQTT_TOPIC_PREFIX + "/cdma";
    private static final String MQTT_UMTS_MESSAGE_TOPIC = MQTT_TOPIC_PREFIX + "/umts";
    private static final String MQTT_LTE_MESSAGE_TOPIC = MQTT_TOPIC_PREFIX + "/lte";
    private static final String MQTT_NR_MESSAGE_TOPIC = MQTT_TOPIC_PREFIX + "/nr";
    private static final String MQTT_WIFI_BEACON_MESSAGE_TOPIC = MQTT_TOPIC_PREFIX + "/80211_beacon";
    private static final String MQTT_BLUETOOTH_MESSAGE_TOPIC = MQTT_TOPIC_PREFIX + "/bluetooth";
    private static final String MQTT_GNSS_MESSAGE_TOPIC = MQTT_TOPIC_PREFIX + "/gnss";
    private static final String MQTT_DEVICE_STATUS_MESSAGE_TOPIC = MQTT_TOPIC_PREFIX + "/device_status";

    //TODO - provide your AWS access key pair here, but plan to NOT use these in a prod scenario
    public static final String AWS_ACCESS_KEY_ID = "AWS_ACCESS_KEY_ID";
    public static final String AWS_SECRET_ACCESS_KEY = "AWS_SECRET_ACCESS_KEY";

    private String mqttClientId = "mqttclientId";
    private AWSIotMqttClient client;

    private final JsonFormat.Printer jsonFormatter;

    public MqttConnection() {
        jsonFormatter = JsonFormat.printer().preservingProtoFieldNames().omittingInsignificantWhitespace();
    }

    /**
     * Send the provided Protobuf message to the MQTT Broker.
     * <p>
     * The Protobuf message is formatted as JSON and then published to the specified topic.
     *
     * @param mqttMessageTopic The MQTT Topic to publish the message to.
     * @param message          The Protobuf message to format as JSON and send to the MQTT Broker.
     */
    protected void publishMessage(String mqttMessageTopic, MessageOrBuilder message) {
        try {
            final String messageJson = jsonFormatter.print(message);
            Timber.d("Publishing to topic " + mqttMessageTopic + " message " + messageJson);

            if (client != null) {
                client.publish(mqttMessageTopic, messageJson);
            }
        } catch (Exception e) {
            Timber.e(e, "Caught an exception when trying to send an MQTT message");
        }
    }

    public void connect(BrokerConnectionInfo connectionInfo) {
        Timber.d("Connecting to MQTT server");

        Timber.d("Mqtt host: %s", connectionInfo.getMqttBrokerHost());
        Timber.d("Client ID: %s", connectionInfo.getMqttClientId());

        this.mqttClientId = connectionInfo.getMqttClientId();

        //TODO - let's not do this; use either certificates instead, or require authn via Cognito and use the Amplify APIs
        this.client = new AWSIotMqttClient(connectionInfo.getMqttBrokerHost(),
                connectionInfo.getMqttClientId(),
                AWS_ACCESS_KEY_ID,
                AWS_SECRET_ACCESS_KEY);

        try {
            this.client.connect();
        } catch (AWSIotException e) {
            Timber.e(e, "Failed to connect to AWS IoT Core");
        }
    }

    public void disconnect() {
        Timber.d("Disconnecting from MQTT server");
        try {
            this.client.disconnect();
        } catch (AWSIotException e) {
            Timber.e(e, "Failed to disconnect from AWS IoT Core");
        }
    }

    public ConnectionState getConnectionState() {
        //TODO - figure this one out
        return ConnectionState.DISCONNECTED;
    }

    public void registerMqttConnectionStateListener(IConnectionStateListener connectionStateListener) {
        //TODO - figure this one out
    }

    public void unregisterMqttConnectionStateListener(IConnectionStateListener connectionStateListener) {
        //TODO - figure this one out
    }


    @Override
    public void onGsmSurveyRecord(GsmRecord gsmRecord) {
        // Set the device name to the user entered value in the MQTT connection UI (or the value provided via MDM)
        if (mqttClientId != null) {
            final GsmRecord.Builder recordBuilder = gsmRecord.toBuilder();
            gsmRecord = recordBuilder.setData(recordBuilder.getDataBuilder().setDeviceName(mqttClientId)).build();
        }

        publishMessage(MQTT_GSM_MESSAGE_TOPIC, gsmRecord);
    }

    @Override
    public void onCdmaSurveyRecord(CdmaRecord cdmaRecord) {
        // Set the device name to the user entered value in the MQTT connection UI (or the value provided via MDM)
        if (mqttClientId != null) {
            final CdmaRecord.Builder recordBuilder = cdmaRecord.toBuilder();
            cdmaRecord = recordBuilder.setData(recordBuilder.getDataBuilder().setDeviceName(mqttClientId)).build();
        }

        publishMessage(MQTT_CDMA_MESSAGE_TOPIC, cdmaRecord);
    }

    @Override
    public void onUmtsSurveyRecord(UmtsRecord umtsRecord) {
        // Set the device name to the user entered value in the MQTT connection UI (or the value provided via MDM)
        if (mqttClientId != null) {
            final UmtsRecord.Builder recordBuilder = umtsRecord.toBuilder();
            umtsRecord = recordBuilder.setData(recordBuilder.getDataBuilder().setDeviceName(mqttClientId)).build();
        }

        publishMessage(MQTT_UMTS_MESSAGE_TOPIC, umtsRecord);
    }

    @Override
    public void onLteSurveyRecord(LteRecord lteRecord) {
        // Set the device name to the user entered value in the MQTT connection UI (or the value provided via MDM)
        if (mqttClientId != null) {
            final LteRecord.Builder recordBuilder = lteRecord.toBuilder();
            lteRecord = recordBuilder.setData(recordBuilder.getDataBuilder().setDeviceName(mqttClientId)).build();
        }

        publishMessage(MQTT_LTE_MESSAGE_TOPIC, lteRecord);
    }

    @Override
    public void onNrSurveyRecord(NrRecord nrRecord) {
        if (mqttClientId != null) {
            final NrRecord.Builder recordBuilder = nrRecord.toBuilder();
            nrRecord = recordBuilder.setData(recordBuilder.getDataBuilder().setDeviceName(mqttClientId)).build();
        }

        publishMessage(MQTT_NR_MESSAGE_TOPIC, nrRecord);
    }

    @Override
    public void onWifiBeaconSurveyRecords(List<WifiRecordWrapper> wifiBeaconRecords) {
        wifiBeaconRecords.forEach(wifiRecord -> {
            WifiBeaconRecord wifiBeaconRecord = wifiRecord.getWifiBeaconRecord();
            if (mqttClientId != null) {
                final WifiBeaconRecord.Builder recordBuilder = wifiBeaconRecord.toBuilder();
                wifiBeaconRecord = recordBuilder.setData(recordBuilder.getDataBuilder().setDeviceName(mqttClientId)).build();
            }
            publishMessage(MQTT_WIFI_BEACON_MESSAGE_TOPIC, wifiBeaconRecord);
        });
    }

    @Override
    public void onBluetoothSurveyRecord(BluetoothRecord bluetoothRecord) {
        // Set the device name to the user entered value in the MQTT connection UI (or the value provided via MDM)
        if (mqttClientId != null) {
            final BluetoothRecord.Builder recordBuilder = bluetoothRecord.toBuilder();
            bluetoothRecord = recordBuilder.setData(recordBuilder.getDataBuilder().setDeviceName(mqttClientId)).build();
        }

        publishMessage(MQTT_BLUETOOTH_MESSAGE_TOPIC, bluetoothRecord);
    }

    @Override
    public void onBluetoothSurveyRecords(List<BluetoothRecord> bluetoothRecords) {
        bluetoothRecords.forEach(bluetoothRecord -> {
            if (mqttClientId != null) {
                final BluetoothRecord.Builder recordBuilder = bluetoothRecord.toBuilder();
                bluetoothRecord = recordBuilder.setData(recordBuilder.getDataBuilder().setDeviceName(mqttClientId)).build();
            }
            publishMessage(MQTT_BLUETOOTH_MESSAGE_TOPIC, bluetoothRecord);
        });
    }

    @Override
    public void onGnssSurveyRecord(GnssRecord gnssRecord) {
        if (mqttClientId != null) {
            final GnssRecord.Builder gnssRecordBuilder = gnssRecord.toBuilder();
            gnssRecord = gnssRecordBuilder.setData(gnssRecordBuilder.getDataBuilder().setDeviceName(mqttClientId)).build();
        }

        publishMessage(MQTT_GNSS_MESSAGE_TOPIC, gnssRecord);
    }

    @Override
    public void onDeviceStatus(DeviceStatus deviceStatus) {
        if (mqttClientId != null) {
            final DeviceStatus.Builder deviceStatusBuilder = deviceStatus.toBuilder();
            deviceStatus = deviceStatusBuilder.setData(deviceStatusBuilder.getDataBuilder().setDeviceName(mqttClientId)).build();
        }

        publishMessage(MQTT_DEVICE_STATUS_MESSAGE_TOPIC, deviceStatus);
    }

    @Override
    public void onPhoneState(PhoneState phoneState) {
        if (mqttClientId != null) {
            final PhoneState.Builder messageBuilder = phoneState.toBuilder();
            phoneState = messageBuilder.setData(messageBuilder.getDataBuilder().setDeviceName(mqttClientId)).build();
        }

        publishMessage(MQTT_DEVICE_STATUS_MESSAGE_TOPIC, phoneState);
    }
}