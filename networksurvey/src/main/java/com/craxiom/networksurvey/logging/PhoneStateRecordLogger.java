package com.craxiom.networksurvey.logging;

import static com.craxiom.networksurvey.constants.DeviceStatusMessageConstants.ALTITUDE_COLUMN;
import static com.craxiom.networksurvey.constants.DeviceStatusMessageConstants.LATITUDE_COLUMN;
import static com.craxiom.networksurvey.constants.DeviceStatusMessageConstants.LONGITUDE_COLUMN;
import static com.craxiom.networksurvey.constants.DeviceStatusMessageConstants.NETWORK_REGISTRATION_COLUMN;
import static com.craxiom.networksurvey.constants.DeviceStatusMessageConstants.PHONE_STATE_TABLE_NAME;
import static com.craxiom.networksurvey.constants.DeviceStatusMessageConstants.SIM_OPERATOR_COLUMN;
import static com.craxiom.networksurvey.constants.DeviceStatusMessageConstants.SIM_STATE_COLUMN;
import static com.craxiom.networksurvey.constants.MessageConstants.ACCURACY;
import static com.craxiom.networksurvey.constants.MessageConstants.MISSION_ID_COLUMN;
import static com.craxiom.networksurvey.constants.MessageConstants.RECORD_NUMBER_COLUMN;
import static com.craxiom.networksurvey.constants.MessageConstants.TIME_COLUMN;

import android.os.Looper;

import com.craxiom.messaging.DeviceStatus;
import com.craxiom.messaging.PhoneState;
import com.craxiom.messaging.PhoneStateData;
import com.craxiom.messaging.phonestate.SimState;
import com.craxiom.networksurvey.constants.NetworkSurveyConstants;
import com.craxiom.networksurvey.listeners.IDeviceStatusListener;
import com.craxiom.networksurvey.services.NetworkSurveyService;
import com.craxiom.networksurvey.util.IOUtils;
import com.craxiom.networksurvey.util.MathUtils;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.base.Strings;
import com.google.gson.Gson;

import org.jetbrains.annotations.Nullable;

import java.sql.SQLException;

import mil.nga.geopackage.GeoPackage;
import mil.nga.geopackage.core.srs.SpatialReferenceSystem;
import mil.nga.geopackage.db.GeoPackageDataType;
import mil.nga.geopackage.features.user.FeatureColumn;
import mil.nga.geopackage.features.user.FeatureDao;
import mil.nga.geopackage.features.user.FeatureRow;
import mil.nga.geopackage.geom.GeoPackageGeometryData;
import mil.nga.sf.Point;
import timber.log.Timber;

/**
 * Logs phone state messages to a Geopackage file defined by {@link NetworkSurveyConstants#PHONESTATE_FILE_NAME_PREFIX}.
 *
 * @since 1.5.0
 */
public class PhoneStateRecordLogger extends SurveyRecordLogger implements IDeviceStatusListener
{
    public PhoneStateRecordLogger(NetworkSurveyService networkSurveyService, Looper serviceLooper)
    {
        super(networkSurveyService, serviceLooper,
                NetworkSurveyConstants.LOG_DIRECTORY_NAME,
                NetworkSurveyConstants.PHONESTATE_FILE_NAME_PREFIX);
    }

    @Override
    void createTables(GeoPackage geoPackage, SpatialReferenceSystem srs) throws SQLException
    {
        createTable(PHONE_STATE_TABLE_NAME, geoPackage, srs, false, (tableColumns, columnNumber) -> {
            tableColumns.add(FeatureColumn.createColumn(columnNumber++, LATITUDE_COLUMN, GeoPackageDataType.DOUBLE, false, null));
            tableColumns.add(FeatureColumn.createColumn(columnNumber++, LONGITUDE_COLUMN, GeoPackageDataType.DOUBLE, false, null));
            tableColumns.add(FeatureColumn.createColumn(columnNumber++, ALTITUDE_COLUMN, GeoPackageDataType.FLOAT, false, null));
            tableColumns.add(FeatureColumn.createColumn(columnNumber++, SIM_STATE_COLUMN, GeoPackageDataType.TEXT, false, null));
            tableColumns.add(FeatureColumn.createColumn(columnNumber++, SIM_OPERATOR_COLUMN, GeoPackageDataType.TEXT, false, null));
            //noinspection UnusedAssignment
            tableColumns.add(FeatureColumn.createColumn(columnNumber++, NETWORK_REGISTRATION_COLUMN, GeoPackageDataType.TEXT, false, null));
        });
    }

    @Override
    public void onDeviceStatus(DeviceStatus deviceStatus)
    {
        // Noop; currently only phone state is logged
    }

    @Override
    public void onPhoneState(PhoneState phoneState)
    {
        if (!loggingEnabled) return;

        handler.post(() -> {
            synchronized (geoPackageLock)
            {
                try
                {
                    if (geoPackage != null)
                    {
                        final PhoneStateData data = phoneState.getData();
                        FeatureDao featureDao = geoPackage.getFeatureDao(PHONE_STATE_TABLE_NAME);
                        FeatureRow row = featureDao.newRow();

                        row.setGeometry(buildGeometry(data));
                        row.setValue(LATITUDE_COLUMN, data.getLatitude());
                        row.setValue(LONGITUDE_COLUMN, data.getLongitude());
                        row.setValue(ALTITUDE_COLUMN, data.getAltitude());

                        row.setValue(TIME_COLUMN, IOUtils.getEpochFromRfc3339(data.getDeviceTime()));
                        row.setValue(MISSION_ID_COLUMN, data.getMissionId());
                        row.setValue(RECORD_NUMBER_COLUMN, data.getRecordNumber());
                        row.setValue(ACCURACY, MathUtils.roundAccuracy(data.getAccuracy()));

                        row.setValue(SIM_STATE_COLUMN, readSimState(data));
                        row.setValue(SIM_OPERATOR_COLUMN, data.getSimOperator());

                        String networkRegistrationJson = new Gson().toJson(data.getNetworkRegistrationInfoList());
                        row.setValue(NETWORK_REGISTRATION_COLUMN, networkRegistrationJson);

                        featureDao.insert(row);

                        checkIfRolloverNeeded();
                    }
                } catch (Exception e)
                {
                    Timber.e(e, "Something went wrong when trying to write a Phone State record");
                }
            }
        });
    }

    /**
     * @param data The {@link PhoneStateData} from which to read the sim state value.
     * @return A String representation of the sim state enum, removing the leading and trailing double-quotes.
     * @throws JsonProcessingException If the {@link SimState} enum cannot be parsed into a String.
     */
    @Nullable
    private String readSimState(PhoneStateData data) throws JsonProcessingException
    {
        String simState = new ObjectMapper().writeValueAsString(data.getSimState());

        if (!Strings.isNullOrEmpty(simState))
        {
            simState = simState.replace("\"", "");
        }

        return simState;
    }

    /**
     * @param data The {@link PhoneStateData} from which to pull the location (geometry).
     * @return A {@link GeoPackageGeometryData} object based on the latitude, longitude, and altitude
     * defined in the {@link PhoneStateData}.
     */
    private GeoPackageGeometryData buildGeometry(PhoneStateData data)
    {
        Point fix = new Point(data.getLongitude(), data.getLatitude(), (double) data.getAltitude());

        GeoPackageGeometryData geomData = new GeoPackageGeometryData(WGS84_SRS);
        geomData.setGeometry(fix);

        return geomData;
    }
}
