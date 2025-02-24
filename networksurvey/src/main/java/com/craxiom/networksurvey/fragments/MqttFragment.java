package com.craxiom.networksurvey.fragments;

import static com.craxiom.mqttlibrary.MqttConstants.PROPERTY_MQTT_CLIENT_ID;
import static com.craxiom.mqttlibrary.MqttConstants.PROPERTY_MQTT_CONNECTION_HOST;
import static com.craxiom.mqttlibrary.MqttConstants.PROPERTY_MQTT_CONNECTION_PORT;
import static com.craxiom.mqttlibrary.MqttConstants.PROPERTY_MQTT_CONNECTION_TLS_ENABLED;
import static com.craxiom.mqttlibrary.MqttConstants.PROPERTY_MQTT_PASSWORD;
import static com.craxiom.mqttlibrary.MqttConstants.PROPERTY_MQTT_USERNAME;
import static com.craxiom.networksurvey.util.PreferenceUtils.populatePrefsFromMqttConnectionSettings;

import android.Manifest;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.view.ViewStub;
import android.widget.Button;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.widget.SwitchCompat;
import androidx.core.app.ActivityCompat;
import androidx.navigation.Navigation;

import com.craxiom.mqttlibrary.connection.BrokerConnectionInfo;
import com.craxiom.mqttlibrary.ui.AConnectionFragment;
import com.craxiom.networksurvey.R;
import com.craxiom.networksurvey.constants.NetworkSurveyConstants;
import com.craxiom.networksurvey.fragments.model.MqttConnectionSettings;
import com.craxiom.networksurvey.mqtt.MqttConnectionInfo;
import com.craxiom.networksurvey.services.NetworkSurveyService;

import timber.log.Timber;

/**
 * A fragment for allowing the user to connect to an MQTT broker. This fragment handles
 * the UI portion of the connection and delegates the actual connection logic to {@link NetworkSurveyService}.
 *
 * @since 0.1.1
 */
public class MqttFragment extends AConnectionFragment<NetworkSurveyService.SurveyServiceBinder>
{
    private SwitchCompat cellularStreamToggleSwitch;
    private SwitchCompat wifiStreamToggleSwitch;
    private SwitchCompat bluetoothStreamToggleSwitch;
    private SwitchCompat gnssStreamToggleSwitch;
    private SwitchCompat deviceStatusStreamToggleSwitch;

    private boolean cellularStreamEnabled = true;
    private boolean wifiStreamEnabled = true;
    private boolean bluetoothStreamEnabled = true;
    private boolean gnssStreamEnabled = true;
    private boolean deviceStatusStreamEnabled = true;

    private final ActivityResultLauncher<String> cameraPermissionRequestLauncher =
            registerForActivityResult(new ActivityResultContracts.RequestPermission(), isGranted -> {
                if (isGranted)
                {
                    Navigation.findNavController(requireActivity(), getId())
                           .navigate(MqttFragmentDirections.actionMqttConnectionFragmentToScannerFragment()
                                   .setMqttConnectionSettings(getCurrentMqttConnectionSettings()));
                } else
                {
                    Toast.makeText(getContext(), getString(R.string.grant_camera_permission), Toast.LENGTH_LONG).show();
                }
    });

    @Override
    public View onCreateView(LayoutInflater inflater, ViewGroup container, Bundle savedInstanceState)
    {
        MqttConnectionSettings mqttConnectionSettings =
                MqttFragmentArgs.fromBundle(getArguments()).getMqttConnectionSettings();

        if (mqttConnectionSettings != null)
        {
            populatePrefsFromMqttConnectionSettings(mqttConnectionSettings, getContext());
        }

        return super.onCreateView(inflater, container, savedInstanceState);
    }

    @Override
    protected void inflateAdditionalFieldsViewStub(LayoutInflater layoutInflater, ViewStub viewStub)
    {
        viewStub.setLayoutResource(R.layout.fragment_stream_options);
        View inflatedStub = viewStub.inflate();

        cellularStreamToggleSwitch = inflatedStub.findViewById(R.id.streamCellularToggleSwitch);
        wifiStreamToggleSwitch = inflatedStub.findViewById(R.id.streamWifiToggleSwitch);
        bluetoothStreamToggleSwitch = inflatedStub.findViewById(R.id.streamBluetoothToggleSwitch);
        gnssStreamToggleSwitch = inflatedStub.findViewById(R.id.streamGnssToggleSwitch);
        deviceStatusStreamToggleSwitch = inflatedStub.findViewById(R.id.streamDeviceStatusToggleSwitch);

        Button scanCodeButton = inflatedStub.findViewById(R.id.code_scan_button);
        scanCodeButton.setOnClickListener(v -> {
            if (hasCameraPermission())
            {
                Navigation.findNavController(requireActivity(), getId())
                        .navigate(MqttFragmentDirections.actionMqttConnectionFragmentToScannerFragment()
                                .setMqttConnectionSettings(getCurrentMqttConnectionSettings()));
            } else
            {
                cameraPermissionRequestLauncher.launch(Manifest.permission.CAMERA);
            }
        });
    }

    @Override
    protected Context getApplicationContext()
    {
        return requireActivity().getApplicationContext();
    }

    @Override
    protected Class<?> getServiceClass()
    {
        return NetworkSurveyService.class;
    }

    @Override
    protected void readMdmConfigAdditionalProperties(Bundle mdmProperties)
    {
        cellularStreamEnabled = mdmProperties.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_CELLULAR_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_CELLULAR_STREAM_SETTING);
        wifiStreamEnabled = mdmProperties.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_WIFI_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_WIFI_STREAM_SETTING);
        bluetoothStreamEnabled = mdmProperties.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_BLUETOOTH_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_BLUETOOTH_STREAM_SETTING);
        gnssStreamEnabled = mdmProperties.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_GNSS_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_GNSS_STREAM_SETTING);
        deviceStatusStreamEnabled = mdmProperties.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_DEVICE_STATUS_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_DEVICE_STATUS_STREAM_SETTING);
    }

    /**
     * Update the UI fields from the instance variables in this class.
     *
     * @since 0.1.5
     */
    @Override
    protected void updateUiFieldsFromStoredValues()
    {
        super.updateUiFieldsFromStoredValues();

        cellularStreamToggleSwitch.setChecked(cellularStreamEnabled);
        wifiStreamToggleSwitch.setChecked(wifiStreamEnabled);
        bluetoothStreamToggleSwitch.setChecked(bluetoothStreamEnabled);
        gnssStreamToggleSwitch.setChecked(gnssStreamEnabled);
        deviceStatusStreamToggleSwitch.setChecked(deviceStatusStreamEnabled);
    }

    @Override
    protected void readUIAdditionalFields()
    {
        cellularStreamEnabled = cellularStreamToggleSwitch.isChecked();
        wifiStreamEnabled = wifiStreamToggleSwitch.isChecked();
        bluetoothStreamEnabled = bluetoothStreamToggleSwitch.isChecked();
        gnssStreamEnabled = gnssStreamToggleSwitch.isChecked();
        deviceStatusStreamEnabled = deviceStatusStreamToggleSwitch.isChecked();
    }

    @Override
    protected void storeAdditionalParameters(SharedPreferences.Editor editor)
    {
        editor.putBoolean(NetworkSurveyConstants.PROPERTY_MQTT_CELLULAR_STREAM_ENABLED, cellularStreamEnabled);
        editor.putBoolean(NetworkSurveyConstants.PROPERTY_MQTT_WIFI_STREAM_ENABLED, wifiStreamEnabled);
        editor.putBoolean(NetworkSurveyConstants.PROPERTY_MQTT_BLUETOOTH_STREAM_ENABLED, bluetoothStreamEnabled);
        editor.putBoolean(NetworkSurveyConstants.PROPERTY_MQTT_GNSS_STREAM_ENABLED, gnssStreamEnabled);
        editor.putBoolean(NetworkSurveyConstants.PROPERTY_MQTT_DEVICE_STATUS_STREAM_ENABLED, deviceStatusStreamEnabled);
    }

    @Override
    protected void restoreAdditionalParameters(SharedPreferences sharedPreferences)
    {
        cellularStreamEnabled = sharedPreferences.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_CELLULAR_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_CELLULAR_STREAM_SETTING);
        wifiStreamEnabled = sharedPreferences.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_WIFI_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_WIFI_STREAM_SETTING);
        bluetoothStreamEnabled = sharedPreferences.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_BLUETOOTH_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_BLUETOOTH_STREAM_SETTING);
        gnssStreamEnabled = sharedPreferences.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_GNSS_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_GNSS_STREAM_SETTING);
        deviceStatusStreamEnabled = sharedPreferences.getBoolean(NetworkSurveyConstants.PROPERTY_MQTT_DEVICE_STATUS_STREAM_ENABLED, NetworkSurveyConstants.DEFAULT_MQTT_DEVICE_STATUS_STREAM_SETTING);
    }

    @Override
    protected void setConnectionInputFieldsEditable(boolean editable, boolean force)
    {
        super.setConnectionInputFieldsEditable(editable, force);

        cellularStreamToggleSwitch.setEnabled(editable);
        wifiStreamToggleSwitch.setEnabled(editable);
        bluetoothStreamToggleSwitch.setEnabled(editable);
        gnssStreamToggleSwitch.setEnabled(editable);
        deviceStatusStreamToggleSwitch.setEnabled(editable);
    }

    @Override
    protected BrokerConnectionInfo getBrokerConnectionInfo()
    {
        return new MqttConnectionInfo(host,
                portNumber,
                tlsEnabled,
                deviceName,
                mqttUsername,
                mqttPassword,
                cellularStreamEnabled,
                wifiStreamEnabled,
                bluetoothStreamEnabled,
                gnssStreamEnabled,
                deviceStatusStreamEnabled);
    }

    /**
     * Read current values from the MQTT Connection Fragment and return an instance of {@link MqttConnectionSettings}
     * object with those values.
     *
     * @since 1.7.0
     */
    private MqttConnectionSettings getCurrentMqttConnectionSettings()
    {
        return MqttConnectionSettings.builder()
                .host(mqttHostAddressEdit.getText().toString())
                .port(Integer.parseInt(mqttPortNumberEdit.getText().toString()))
                .tlsEnabled(tlsToggleSwitch.isChecked())
                .deviceName(deviceNameEdit.getText().toString())
                .mqttUsername(usernameEdit.getText().toString())
                .mqttPassword(passwordEdit.getText().toString())
                .build();
    }

    /**
     * @return True if the {@link Manifest.permission#CAMERA} permission has been granted. False otherwise.
     * @since 1.7.0
     */
    private boolean hasCameraPermission()
    {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED)
        {
            Timber.w("The CAMERA permission has not been granted");
            return false;
        }

        return true;
    }
}
