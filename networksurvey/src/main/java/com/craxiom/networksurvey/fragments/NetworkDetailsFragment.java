package com.craxiom.networksurvey.fragments;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.location.Location;
import android.location.LocationListener;
import android.location.LocationManager;
import android.location.LocationProvider;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TableLayout;
import android.widget.TableRow;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.core.app.ActivityCompat;
import androidx.lifecycle.LifecycleOwner;
import androidx.lifecycle.ViewModelProvider;
import androidx.lifecycle.ViewModelStoreOwner;
import androidx.navigation.fragment.NavHostFragment;

import com.craxiom.messaging.CdmaRecord;
import com.craxiom.messaging.GsmRecord;
import com.craxiom.messaging.GsmRecordData;
import com.craxiom.messaging.LteRecord;
import com.craxiom.messaging.LteRecordData;
import com.craxiom.messaging.NrRecord;
import com.craxiom.messaging.NrRecordData;
import com.craxiom.messaging.UmtsRecord;
import com.craxiom.messaging.UmtsRecordData;
import com.craxiom.networksurvey.CalculationUtils;
import com.craxiom.networksurvey.R;
import com.craxiom.networksurvey.constants.LteMessageConstants;
import com.craxiom.networksurvey.constants.NetworkSurveyConstants;
import com.craxiom.networksurvey.databinding.FragmentNetworkDetailsBinding;
import com.craxiom.networksurvey.fragments.model.CellularViewModel;
import com.craxiom.networksurvey.fragments.model.GsmNeighbor;
import com.craxiom.networksurvey.fragments.model.LteNeighbor;
import com.craxiom.networksurvey.fragments.model.NrNeighbor;
import com.craxiom.networksurvey.fragments.model.UmtsNeighbor;
import com.craxiom.networksurvey.listeners.ICellularSurveyRecordListener;
import com.craxiom.networksurvey.model.CellularProtocol;
import com.craxiom.networksurvey.model.CellularRecordWrapper;
import com.craxiom.networksurvey.services.NetworkSurveyService;
import com.craxiom.networksurvey.util.ColorUtils;
import com.craxiom.networksurvey.util.MathUtils;
import com.craxiom.networksurvey.util.ParserUtils;

import java.text.DecimalFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.SortedSet;
import java.util.TreeSet;
import java.util.stream.Collectors;

import app.futured.donut.DonutProgressView;
import app.futured.donut.DonutSection;
import timber.log.Timber;

/**
 * A fragment for displaying the latest cellular network details to the user.
 *
 * @since 1.6.0 (It really came earlier, but was minimal until the 1.6.0 rewrite.
 */
public class NetworkDetailsFragment extends AServiceDataFragment implements ICellularSurveyRecordListener, LocationListener
{
    static final String TITLE = "Details";

    // The next two values have been added because certain devices don't follow the Interger#MAX_VALUE approach defined
    // in the Android API. The phone is supposed to report Interger#MAX_VALUE to indicate "Unknown/Unset" values, but
    // Pixel devices seem to report -120 all the time for UMTS RSCP, and Samsung devics seem to report -24 for UMTS RSCP.
    // These values are technically valid and filtering them out is an incorrect thing to do, but it is all I can think
    // of right now to prevent invalid values from being reported.
    private static final int RSCP_UNSET_VALUE_120 = -120;
    private static final int RSCP_UNSET_VALUE_24 = -24;

    private final DecimalFormat locationFormat = new DecimalFormat("###.#####");

    private FragmentNetworkDetailsBinding binding;
    private CellularViewModel viewModel;

    @Nullable
    @Override
    public View onCreateView(@NonNull LayoutInflater inflater, @Nullable ViewGroup container, @Nullable Bundle savedInstanceState)
    {
        binding = FragmentNetworkDetailsBinding.inflate(inflater);

        final ViewModelStoreOwner viewModelStoreOwner = NavHostFragment.findNavController(this).getViewModelStoreOwner(R.id.nav_graph);
        final ViewModelProvider viewModelProvider = new ViewModelProvider(viewModelStoreOwner);
        viewModel = viewModelProvider.get(getClass().getName(), CellularViewModel.class);

        initializeLocationTextView();

        initializeObservers();

        return binding.getRoot();
    }

    @Override
    public void onResume()
    {
        super.onResume();

        // In the edge case event where the user has just granted the location permission but has not restarted the app,
        // we need to update the UI to show the new location in this onResume method. There might be better approaches
        // instead of recalling the initialize view method each time the fragment is resumed.
        initializeLocationTextView();

        startAndBindToService();
    }

    @Override
    public void onDestroyView()
    {
        removeObservers();

        super.onDestroyView();
    }

    @Override
    protected void registerDataListeners(NetworkSurveyService service)
    {
        service.registerCellularSurveyRecordListener(this);
        service.registerLocationListener(this);
        initializeLocationTextView(); // Refresh the location views because we might have missed something between the
        // initial call and when we registered as a listener.

        service.runSingleCellularScan();
    }

    @Override
    protected void unregisterDataListeners(NetworkSurveyService service)
    {
        service.unregisterLocationListener(this);
        service.unregisterCellularSurveyRecordListener(this);
    }

    @Override
    public void onGsmSurveyRecord(GsmRecord gsmRecord)
    {
    }

    @Override
    public void onCdmaSurveyRecord(CdmaRecord cdmaRecord)
    {
    }

    @Override
    public void onUmtsSurveyRecord(UmtsRecord umtsRecord)
    {
    }

    @Override
    public void onLteSurveyRecord(LteRecord lteRecord)
    {
    }

    @Override
    public void onNrSurveyRecord(NrRecord nrRecord)
    {
    }

    @Override
    public void onCellularBatch(List<CellularRecordWrapper> cellularGroup)
    {
        processCellularGroup(cellularGroup);
    }

    @Override
    public void onNetworkType(String dataNetworkType, String voiceNetworkType)
    {
        viewModel.setDataNetworkType(dataNetworkType);
        viewModel.setVoiceNetworkType(voiceNetworkType);
    }

    @Override
    public void onProviderEnabled(@NonNull String provider)
    {
        if (LocationManager.GPS_PROVIDER.equals(provider)) viewModel.setProviderEnabled(true);
    }

    @Override
    public void onProviderDisabled(@NonNull String provider)
    {
        if (LocationManager.GPS_PROVIDER.equals(provider)) viewModel.setProviderEnabled(false);
    }

    @Override
    public void onLocationChanged(@NonNull Location location)
    {
        viewModel.setLocation(location);
    }

    /**
     * Initialize the model view observers. These observers look for changes to the model view
     * values, and then update the UI based on any changes.
     */
    private void initializeObservers()
    {
        final LifecycleOwner viewLifecycleOwner = getViewLifecycleOwner();

        viewModel.getDataNetworkType().observe(viewLifecycleOwner, networkType -> binding.currentDataNetwork.setText(networkType));
        viewModel.getCarrier().observe(viewLifecycleOwner, carrier -> binding.currentCarrier.setText(carrier));
        viewModel.getVoiceNetworkType().observe(viewLifecycleOwner, networkType -> binding.currentVoiceNetwork.setText(networkType));

        viewModel.getProviderEnabled().observe(viewLifecycleOwner, this::updateLocationProviderStatus);
        viewModel.getLocation().observe(viewLifecycleOwner, this::updateLocationTextView);

        viewModel.getServingCellProtocol().observe(viewLifecycleOwner, this::updateServingCellProtocol);

        viewModel.getMcc().observe(viewLifecycleOwner, s -> binding.mcc.setText(s));
        viewModel.getMnc().observe(viewLifecycleOwner, s -> binding.mnc.setText(s));
        viewModel.getAreaCode().observe(viewLifecycleOwner, s -> binding.tac.setText(s));
        viewModel.getCellId().observe(viewLifecycleOwner, this::updateCellIdentity);
        viewModel.getChannelNumber().observe(viewLifecycleOwner, s -> binding.earfcn.setText(s));

        viewModel.getPci().observe(viewLifecycleOwner, s -> binding.pci.setText(s));
        viewModel.getBandwidth().observe(viewLifecycleOwner, s -> binding.bandwidth.setText(s));
        viewModel.getTa().observe(viewLifecycleOwner, s -> binding.ta.setText(s));

        viewModel.getSignalOne().observe(viewLifecycleOwner, this::updateSignalStrengthOne);
        viewModel.getSignalTwo().observe(viewLifecycleOwner, this::updateSignalStrengthTwo);

        viewModel.getNrNeighbors().observe(viewLifecycleOwner, this::updateNrNeighborsView);
        viewModel.getLteNeighbors().observe(viewLifecycleOwner, this::updateLteNeighborsView);
        viewModel.getUmtsNeighbors().observe(viewLifecycleOwner, this::updateUmtsNeighborsView);
        viewModel.getGsmNeighbors().observe(viewLifecycleOwner, this::updateGsmNeighborsView);
    }

    /**
     * Cleans up by removing all the view model observers.
     */
    private void removeObservers()
    {
        final LifecycleOwner viewLifecycleOwner = getViewLifecycleOwner();

        viewModel.getDataNetworkType().removeObservers(viewLifecycleOwner);
        viewModel.getCarrier().removeObservers(viewLifecycleOwner);
        viewModel.getVoiceNetworkType().removeObservers(viewLifecycleOwner);

        viewModel.getProviderEnabled().removeObservers(viewLifecycleOwner);
        viewModel.getLocation().removeObservers(viewLifecycleOwner);

        viewModel.getServingCellProtocol().removeObservers(viewLifecycleOwner);

        viewModel.getMcc().removeObservers(viewLifecycleOwner);
        viewModel.getMnc().removeObservers(viewLifecycleOwner);
        viewModel.getAreaCode().removeObservers(viewLifecycleOwner);
        viewModel.getCellId().removeObservers(viewLifecycleOwner);
        viewModel.getChannelNumber().removeObservers(viewLifecycleOwner);

        viewModel.getPci().removeObservers(viewLifecycleOwner);
        viewModel.getBandwidth().removeObservers(viewLifecycleOwner);
        viewModel.getTa().removeObservers(viewLifecycleOwner);

        viewModel.getSignalOne().removeObservers(viewLifecycleOwner);
        viewModel.getSignalTwo().removeObservers(viewLifecycleOwner);

        viewModel.getNrNeighbors().removeObservers(viewLifecycleOwner);
        viewModel.getLteNeighbors().removeObservers(viewLifecycleOwner);
        viewModel.getUmtsNeighbors().removeObservers(viewLifecycleOwner);
        viewModel.getGsmNeighbors().removeObservers(viewLifecycleOwner);
    }

    /**
     * Clears out the UI, which is needed if the phone stops seeing towers or something else happens (e.g. airplane mode).
     */
    private void clearCellularUi()
    {
        // TODO Will this happen via the other listener?
        /*viewModel.setDataNetworkType("");
        viewModel.setCarrier("");
        viewModel.setVoiceNetworkType("");*/

        viewModel.setServingCellProtocol(CellularProtocol.NONE);

        viewModel.setMcc("");
        viewModel.setMnc("");
        viewModel.setAreaCode("");
        viewModel.setCellId(null);
        viewModel.setChannelNumber("");

        viewModel.setPci("");
        viewModel.setBandwidth("");
        viewModel.setTa("");

        viewModel.setSignalOne(null);
        viewModel.setSignalTwo(null);

        viewModel.setNrNeighbors(Collections.emptySortedSet());
        viewModel.setLteNeighbors(Collections.emptySortedSet());
        viewModel.setUmtsNeighbors(Collections.emptySortedSet());
        viewModel.setGsmNeighbors(Collections.emptySortedSet());
    }

    /**
     * Initialize the location text view based on the phone's state.
     */
    private void initializeLocationTextView()
    {
        final TextView tvLocation = binding.location;

        final String displayText;
        final int textColor;

        if (!hasLocationPermission())
        {
            tvLocation.setText(getString(R.string.missing_location_permission));
            tvLocation.setTextColor(getResources().getColor(R.color.connectionStatusDisconnected, null));
            return;
        }

        final Location location = viewModel.getLocation().getValue();
        if (location != null)
        {
            updateLocationTextView(location);
            return;
        }

        final LocationManager locationManager = (LocationManager) requireContext().getSystemService(Context.LOCATION_SERVICE);
        if (locationManager == null)
        {
            Timber.wtf("Could not get the location manager.");
            displayText = getString(R.string.no_gps_device);
            textColor = R.color.connectionStatusDisconnected;
        } else
        {
            final LocationProvider locationProvider = locationManager.getProvider(LocationManager.GPS_PROVIDER);
            if (locationProvider == null)
            {
                displayText = getString(R.string.no_gps_device);
            } else if (!locationManager.isProviderEnabled(LocationManager.GPS_PROVIDER))
            {
                // gps exists, but isn't on
                displayText = getString(R.string.turn_on_gps);
            } else
            {
                displayText = getString(R.string.searching_for_location);
            }

            textColor = R.color.connectionStatusConnecting;
        }

        tvLocation.setText(displayText);
        tvLocation.setTextColor(getResources().getColor(textColor, null));
    }

    /**
     * Updates the location text view with the latest latitude and longitude, or if the latest location is below the
     * accuracy threshold then the text view is updated to notify the user of such.
     *
     * @param latestLocation The latest location if available, or null if the accuracy is not good enough.
     */
    private void updateLocationTextView(Location latestLocation)
    {
        final TextView locationTextView = binding.location;
        final TextView accuracyTextView = binding.accuracy;
        if (latestLocation != null)
        {
            final String latLonString = locationFormat.format(latestLocation.getLatitude()) + ", " +
                    locationFormat.format(latestLocation.getLongitude());
            locationTextView.setText(latLonString);
            locationTextView.setTextColor(getResources().getColor(R.color.normalText, null));

            accuracyTextView.setText(getString(R.string.accuracy_value, Integer.toString(MathUtils.roundAccuracy(latestLocation.getAccuracy()))));
        } else
        {
            locationTextView.setText(R.string.low_gps_confidence);
            locationTextView.setTextColor(Color.YELLOW);

            accuracyTextView.setText(getString(R.string.accuracy_initial));
        }
    }

    /**
     * Updates the location UI based on the provided location provider status. If this method is called, it always
     * results in the clearing of the lat/lon from the UI. Therefore, it should only be called when the location
     * provider is enabled or disabled.
     *
     * @param enabled The new status of the location provider; true for enabled, false for disabled.
     */
    private void updateLocationProviderStatus(boolean enabled)
    {
        final TextView locationTextView = binding.location;

        locationTextView.setTextColor(getResources().getColor(R.color.connectionStatusConnecting, null));
        locationTextView.setText(enabled ? R.string.searching_for_location : R.string.turn_on_gps);
    }

    /**
     * Updates the serving cell title for the serving cell card to reflect the technology being
     * displayed in rest of the card.
     * <p>
     * This method also handles initializing the cellular details UI to handle this protocol.
     *
     * @param protocol The new protocol for the serving cell.
     */
    private void updateServingCellProtocol(CellularProtocol protocol)
    {
        final TextView titleTextView = binding.cellularDetailsTitle;
        titleTextView.setText(getString(R.string.card_title_cellular_details, protocol));

        // TODO We need to clear the values in the view model when switching between protocols

        switch (protocol)
        {
            case NONE:
                titleTextView.setText(R.string.card_title_cellular_details_initial);
                break;

            case GSM:
                binding.tacLabel.setText(R.string.lac_label);
                binding.enbIdGroup.setVisibility(View.GONE);
                binding.sectorIdGroup.setVisibility(View.GONE);
                binding.earfcnLabel.setText(R.string.arfcn_label);
                binding.pciLabel.setText(R.string.bsic_label);
                binding.bandwidthGroup.setVisibility(View.GONE);
                binding.taGroup.setVisibility(View.GONE);
                binding.signalOneLabel.setText(R.string.rssi_label);
                binding.signalTwoGroup.setVisibility(View.GONE);
                break;

            case CDMA:
                binding.enbIdGroup.setVisibility(View.GONE);
                binding.sectorIdGroup.setVisibility(View.GONE);
                binding.signalTwoGroup.setVisibility(View.GONE);
                break;

            case UMTS:
                binding.tacLabel.setText(R.string.lac_label);
                binding.enbIdGroup.setVisibility(View.GONE);
                binding.sectorIdGroup.setVisibility(View.GONE);
                binding.earfcnLabel.setText(R.string.uarfcn_label);
                binding.pciLabel.setText(R.string.psc_label);
                binding.bandwidthGroup.setVisibility(View.GONE);
                binding.taGroup.setVisibility(View.GONE);
                binding.signalOneLabel.setText(R.string.rssi_label);
                binding.signalTwoLabel.setText(R.string.rscp_label);
                binding.signalTwoGroup.setVisibility(View.VISIBLE);
                break;

            case LTE:
                binding.tacLabel.setText(R.string.tac_label);
                binding.enbIdGroup.setVisibility(View.VISIBLE);
                binding.sectorIdGroup.setVisibility(View.VISIBLE);
                binding.earfcnLabel.setText(R.string.earfcn_label);
                binding.pciLabel.setText(R.string.pci_label);
                binding.bandwidthGroup.setVisibility(View.VISIBLE);
                binding.taGroup.setVisibility(View.VISIBLE);
                binding.signalOneLabel.setText(R.string.rsrp_label);
                binding.signalTwoLabel.setText(R.string.rsrq_label);
                binding.signalTwoGroup.setVisibility(View.VISIBLE);
                break;

            case NR:
                binding.tacLabel.setText(R.string.tac_label);
                binding.enbIdGroup.setVisibility(View.GONE);
                binding.sectorIdGroup.setVisibility(View.GONE);
                binding.earfcnLabel.setText(R.string.narfcn_label);
                binding.pciLabel.setText(R.string.pci_label);
                binding.bandwidthGroup.setVisibility(View.GONE);
                binding.taGroup.setVisibility(View.GONE);
                binding.signalOneLabel.setText(R.string.ss_rsrp_label);
                binding.signalTwoLabel.setText(R.string.ss_rsrq_label);
                binding.signalTwoGroup.setVisibility(View.VISIBLE);
                break;
        }
    }

    /**
     * @return True if the {@link Manifest.permission#ACCESS_FINE_LOCATION} permission has been granted.  False otherwise.
     */
    private boolean hasLocationPermission()
    {
        if (ActivityCompat.checkSelfPermission(requireContext(), Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED)
        {
            Timber.w("The ACCESS_FINE_LOCATION permission has not been granted");
            return false;
        }

        return true;
    }

    /**
     * The method responsible for handling a new batch of cellular records.
     *
     * @param cellularGroup The new batch of cellular records.
     */
    private void processCellularGroup(List<CellularRecordWrapper> cellularGroup)
    {
        if (cellularGroup.isEmpty()) clearCellularUi();

        final List<GsmRecordData> gsmNeighbors = new ArrayList<>();
        final List<UmtsRecordData> umtsNeighbors = new ArrayList<>();
        final List<LteRecordData> lteNeighbors = new ArrayList<>();
        final List<NrRecordData> nrNeighbors = new ArrayList<>();
        for (CellularRecordWrapper cellularRecord : cellularGroup)
        {
            switch (cellularRecord.cellularProtocol)
            {
                case NONE:
                    return;

                case GSM:
                    final GsmRecordData gsmData = ((GsmRecord) cellularRecord.cellularRecord).getData();
                    if (gsmData.hasServingCell() && gsmData.getServingCell().getValue())
                    {
                        viewModel.setServingCellProtocol(cellularRecord.cellularProtocol);
                        processGsmServingCell(gsmData);
                    } else
                    {
                        gsmNeighbors.add(gsmData);
                    }
                    break;

                case CDMA:
                    // TODO What do do about CDMA?
                    break;

                case UMTS:
                    final UmtsRecordData umtsData = ((UmtsRecord) cellularRecord.cellularRecord).getData();
                    if (umtsData.hasServingCell() && umtsData.getServingCell().getValue())
                    {
                        viewModel.setServingCellProtocol(cellularRecord.cellularProtocol);
                        processUmtsServingCell(umtsData);
                    } else
                    {
                        umtsNeighbors.add(umtsData);
                    }
                    break;

                case LTE:
                    final LteRecordData lteData = ((LteRecord) cellularRecord.cellularRecord).getData();
                    if (lteData.hasServingCell() && lteData.getServingCell().getValue())
                    {
                        viewModel.setServingCellProtocol(cellularRecord.cellularProtocol);
                        processLteServingCell(lteData);
                    } else
                    {
                        lteNeighbors.add(lteData);
                    }
                    break;

                case NR:
                    final NrRecordData nrData = ((NrRecord) cellularRecord.cellularRecord).getData();
                    if (nrData.hasServingCell() && nrData.getServingCell().getValue())
                    {
                        viewModel.setServingCellProtocol(cellularRecord.cellularProtocol);
                        processNrServingCell(nrData);
                    } else
                    {
                        nrNeighbors.add(nrData);
                    }
                    break;
            }
        }

        processGsmNeighbors(gsmNeighbors);
        processUmtsNeighbors(umtsNeighbors);
        processLteNeighbors(lteNeighbors);
        processNrNeighbors(nrNeighbors);
    }

    /**
     * Takes in the GSM serving cell details and sets it in the view model so that it can be
     * displayed in the UI.
     *
     * @param data The details for the GSM serving cell record.
     */
    private void processGsmServingCell(GsmRecordData data)
    {
        viewModel.setCarrier(data.getProvider());
        viewModel.setMcc(data.hasMcc() ? String.valueOf(data.getMcc().getValue()) : "");
        viewModel.setMnc(data.hasMnc() ? String.valueOf(data.getMnc().getValue()) : "");
        viewModel.setAreaCode(data.hasLac() ? String.valueOf(data.getLac().getValue()) : "");
        viewModel.setCellId(data.hasCi() ? (long) data.getCi().getValue() : null);
        viewModel.setChannelNumber(data.hasArfcn() ? String.valueOf(data.getArfcn().getValue()) : "");
        viewModel.setPci(data.hasBsic() ? ParserUtils.bsicToString(data.getBsic().getValue()) : "");

        viewModel.setSignalOne(data.hasSignalStrength() ? (int) data.getSignalStrength().getValue() : null);
    }

    /**
     * Takes in the UMTS serving cell details and sets it in the view model so that it can be
     * displayed in the UI.
     *
     * @param data The details for the UMTS serving cell record.
     */
    private void processUmtsServingCell(UmtsRecordData data)
    {
        viewModel.setCarrier(data.getProvider());
        viewModel.setMcc(data.hasMcc() ? String.valueOf(data.getMcc().getValue()) : "");
        viewModel.setMnc(data.hasMnc() ? String.valueOf(data.getMnc().getValue()) : "");
        viewModel.setAreaCode(data.hasLac() ? String.valueOf(data.getLac().getValue()) : "");
        viewModel.setCellId(data.hasCid() ? (long) data.getCid().getValue() : null);
        viewModel.setChannelNumber(data.hasUarfcn() ? String.valueOf(data.getUarfcn().getValue()) : "");
        viewModel.setPci(data.hasPsc() ? String.valueOf(data.getPsc().getValue()) : "");

        viewModel.setSignalOne(data.hasSignalStrength() ? (int) data.getSignalStrength().getValue() : null);
        viewModel.setSignalTwo(data.hasRscp() ? (int) data.getRscp().getValue() : null);
    }

    /**
     * Takes in the LTE serving cell details and sets it in the view model so that it can be
     * displayed in the UI.
     *
     * @param data The details for the LTE serving cell record.
     */
    private void processLteServingCell(LteRecordData data)
    {
        viewModel.setCarrier(data.getProvider());
        viewModel.setMcc(data.hasMcc() ? String.valueOf(data.getMcc().getValue()) : "");
        viewModel.setMnc(data.hasMnc() ? String.valueOf(data.getMnc().getValue()) : "");
        viewModel.setAreaCode(data.hasTac() ? String.valueOf(data.getTac().getValue()) : "");
        viewModel.setCellId(data.hasEci() ? (long) data.getEci().getValue() : null);
        viewModel.setChannelNumber(data.hasEarfcn() ? String.valueOf(data.getEarfcn().getValue()) : "");

        if (data.hasPci())
        {
            final int pci = data.getPci().getValue();
            int primarySyncSequence = CalculationUtils.getPrimarySyncSequence(pci);
            int secondarySyncSequence = CalculationUtils.getSecondarySyncSequence(pci);
            viewModel.setPci(pci + " (" + primarySyncSequence + "/" + secondarySyncSequence + ")");
        } else
        {
            viewModel.setPci("");
        }
        viewModel.setBandwidth(LteMessageConstants.getLteBandwidth(data.getLteBandwidth()));
        viewModel.setTa(data.hasTa() ? String.valueOf(data.getTa().getValue()) : "");

        viewModel.setSignalOne(data.hasRsrp() ? (int) data.getRsrp().getValue() : null);
        viewModel.setSignalTwo(data.hasRsrq() ? (int) data.getRsrq().getValue() : null);
    }

    /**
     * Takes in the NR serving cell details and sets it in the view model so that it can be
     * displayed in the UI.
     *
     * @param data The details for the NR serving cell record.
     */
    private void processNrServingCell(NrRecordData data)
    {
        viewModel.setCarrier(data.getProvider());
        viewModel.setMcc(data.hasMcc() ? String.valueOf(data.getMcc().getValue()) : "");
        viewModel.setMnc(data.hasMnc() ? String.valueOf(data.getMnc().getValue()) : "");
        viewModel.setAreaCode(data.hasTac() ? String.valueOf(data.getTac().getValue()) : "");
        viewModel.setCellId(data.hasNci() ? data.getNci().getValue() : null);
        viewModel.setChannelNumber(data.hasNarfcn() ? String.valueOf(data.getNci().getValue()) : "");

        if (data.hasPci())
        {
            final int pci = data.getPci().getValue();
            int primarySyncSequence = CalculationUtils.getPrimarySyncSequence(pci);
            int secondarySyncSequence = CalculationUtils.getSecondarySyncSequence(pci);
            viewModel.setPci(pci + " (" + primarySyncSequence + "/" + secondarySyncSequence + ")");
        } else
        {
            viewModel.setPci("");
        }
        viewModel.setTa(data.hasTa() ? String.valueOf(data.getTa().getValue()) : "");

        viewModel.setSignalOne(data.hasSsRsrp() ? (int) data.getSsRsrp().getValue() : null);
        viewModel.setSignalTwo(data.hasSsRsrq() ? (int) data.getSsRsrq().getValue() : null);
    }

    /**
     * Takes in the current group of UMTS neighbors, converts them to a {@link UmtsNeighbor}, and then
     * updates the view model.
     *
     * @param neighbors The current group of Lte Neighbors.
     */
    private void processGsmNeighbors(List<GsmRecordData> neighbors)
    {
        final TreeSet<GsmNeighbor> gsmNeighbors = neighbors.stream().map(data -> {
            GsmNeighbor.GsmNeighborBuilder builder = GsmNeighbor.builder();
            if (data.hasArfcn()) builder.arfcn(data.getArfcn().getValue());
            if (data.hasBsic()) builder.bsic(data.getBsic().getValue());
            if (data.hasSignalStrength()) builder.rssi((int) data.getSignalStrength().getValue());
            return builder.build();
        }).sorted().collect(Collectors.toCollection(TreeSet::new));

        viewModel.setGsmNeighbors(gsmNeighbors);
    }

    /**
     * Takes in the current group of UMTS neighbors, converts them to a {@link UmtsNeighbor}, and then
     * updates the view model.
     *
     * @param neighbors The current group of Lte Neighbors.
     */
    private void processUmtsNeighbors(List<UmtsRecordData> neighbors)
    {
        final TreeSet<UmtsNeighbor> umtsNeighbors = neighbors.stream().map(data -> {
            UmtsNeighbor.UmtsNeighborBuilder builder = UmtsNeighbor.builder();
            if (data.hasUarfcn()) builder.uarfcn(data.getUarfcn().getValue());
            if (data.hasPsc()) builder.psc(data.getPsc().getValue());
            if (data.hasRscp()) builder.rscp((int) data.getRscp().getValue());
            return builder.build();
        }).sorted().collect(Collectors.toCollection(TreeSet::new));

        viewModel.setUmtsNeighbors(umtsNeighbors);
    }

    /**
     * Takes in the current group of LTE neighbors, converts them to an {@link LteNeighbor}, and then
     * updates the view model.
     *
     * @param neighbors The current group of Lte Neighbors.
     */
    private void processLteNeighbors(List<LteRecordData> neighbors)
    {
        final TreeSet<LteNeighbor> lteNeighbors = neighbors.stream().map(data -> {
            LteNeighbor.LteNeighborBuilder builder = LteNeighbor.builder();
            if (data.hasEarfcn()) builder.earfcn(data.getEarfcn().getValue());
            if (data.hasPci()) builder.pci(data.getPci().getValue());
            if (data.hasRsrp()) builder.rsrp((int) data.getRsrp().getValue());
            if (data.hasRsrq()) builder.rsrq((int) data.getRsrq().getValue());
            if (data.hasTa()) builder.ta(data.getTa().getValue());
            return builder.build();
        }).sorted().collect(Collectors.toCollection(TreeSet::new));

        viewModel.setLteNeighbors(lteNeighbors);
    }

    /**
     * Takes in the current group of LTE neighbors, converts them to an {@link NrNeighbor}, and then
     * updates the view model.
     *
     * @param neighbors The current group of Lte Neighbors.
     */
    private void processNrNeighbors(List<NrRecordData> neighbors)
    {
        final TreeSet<NrNeighbor> nrNeighbors = neighbors.stream().map(data -> {
            NrNeighbor.NrNeighborBuilder builder = NrNeighbor.builder();
            if (data.hasNarfcn()) builder.narfcn(data.getNarfcn().getValue());
            if (data.hasPci()) builder.pci(data.getPci().getValue());
            if (data.hasSsRsrp()) builder.ssRsrp((int) data.getSsRsrp().getValue());
            if (data.hasSsRsrq()) builder.ssRsrq((int) data.getSsRsrq().getValue());
            return builder.build();
        }).sorted().collect(Collectors.toCollection(TreeSet::new));

        viewModel.setNrNeighbors(nrNeighbors);
    }

    /**
     * Sets the Cell Identity.
     * <p>
     * For LTE, it also calculates and sets the  related fields.
     *
     * @param cellIdentity The cell identity to set and calculate the other values from.
     */
    private void updateCellIdentity(Long cellIdentity)
    {
        if (cellIdentity != null)
        {
            final int ci = cellIdentity.intValue();
            binding.cid.setText(String.valueOf(ci));

            if (viewModel.getServingCellProtocol().getValue() == CellularProtocol.LTE)
            {
                // The Cell Identity is 28 bits long. The first 20 bits represent the Macro eNodeB ID. The last 8 bits
                // represent the sector.  Strip off the last 8 bits to get the Macro eNodeB ID.
                int eNodebId = CalculationUtils.getEnodebIdFromCellId(ci);
                binding.enbId.setText(String.valueOf(eNodebId));

                int sectorId = CalculationUtils.getSectorIdFromCellId(ci);
                binding.sectorId.setText(String.valueOf(sectorId));
            }
        } else
        {
            binding.cid.setText("");
            binding.enbId.setText("");
            binding.sectorId.setText("");
        }
    }

    /**
     * Sets the provided value on the first Signal Strength display, and handles configuring the display with the
     * appropriate min and max value.
     *
     * @param signalValue The new signal value to set.
     */
    private void updateSignalStrengthOne(Integer signalValue)
    {
        final CellularProtocol protocol = viewModel.getServingCellProtocol().getValue();
        if (protocol == null) return;

        binding.signalOneGroup.setVisibility(signalValue == null ? View.GONE : View.VISIBLE);
        binding.signalOneValue.setText(signalValue != null ? String.valueOf(signalValue) : "");
        setSignalStrengthBar(binding.progressBarSignalOne, signalValue, protocol.getMinSignalOne(), protocol.getMaxNormalizedSignalOne());
    }

    /**
     * Sets the provided value on the second Signal Strength display, and handles configuring the display with the
     * appropriate min and max value.
     *
     * @param signalValue The new signal value to set.
     */
    private void updateSignalStrengthTwo(Integer signalValue)
    {
        final CellularProtocol protocol = viewModel.getServingCellProtocol().getValue();
        if (protocol == null) return;

        if (protocol == CellularProtocol.UMTS &&
                (signalValue == null || signalValue == RSCP_UNSET_VALUE_120 || signalValue == RSCP_UNSET_VALUE_24))
        {
            // Special handling for UMTS RSCP because devices seem to report the wrong value for "Unset"
            signalValue = null;
        }

        binding.signalTwoGroup.setVisibility(signalValue == null ? View.GONE : View.VISIBLE);
        binding.signalTwoValue.setText(signalValue != null ? String.valueOf(signalValue) : "");
        setSignalStrengthBar(binding.progressBarSignalTwo, signalValue, protocol.getMinSignalTwo(), protocol.getMaxNormalizedSignalTwo());
    }

    /**
     * Updates the first signal strength indicator UI element with the provided value. If the value is null, then
     * the current value is cleared and a blank UI element is show.
     *
     * @param signalValue The new signal value to set, or null if the current value should be cleared.
     */
    private void setSignalStrengthBar(DonutProgressView signalStrengthBar, Integer signalValue, int minValue, int maxNormalizedValue)
    {
        if (signalValue == null)
        {
            signalStrengthBar.clear();
            return;
        }

        int normalizedValue = signalValue <= minValue ? 0 : Math.abs(minValue - signalValue);

        final int color = ColorUtils.getSignalColorForValue(normalizedValue, maxNormalizedValue);

        final DonutSection fillSection = new DonutSection("fill", color, normalizedValue);
        signalStrengthBar.setCap(maxNormalizedValue);
        signalStrengthBar.submitData(Collections.singletonList(fillSection));
    }

    /**
     * Given the newest set of  r neighbors, update the neighbors table view.
     *
     * @param neighbors The latest batch of NR neighbors.
     */
    private void updateNrNeighborsView(SortedSet<NrNeighbor> neighbors)
    {
        final Context context = getContext();
        if (context == null) return;

        if (neighbors.isEmpty())
        {
            binding.nrNeighborsGroup.setVisibility(View.GONE);
            return;
        }

        binding.nrNeighborsGroup.setVisibility(View.VISIBLE);

        final TableLayout neighborsTable = binding.nrNeighborsTable;

        neighborsTable.removeAllViews();

        for (NrNeighbor neighbor : neighbors)
        {
            final TableRow row = new TableRow(context);

            addValueToRow(context, row, neighbor.narfcn);
            addValueToRow(context, row, neighbor.pci);
            addValueToRow(context, row, neighbor.ssRsrp);
            addValueToRow(context, row, neighbor.ssRsrq);

            neighborsTable.addView(row);
        }
    }

    /**
     * Given the newest set of LTE neighbors, update the neighbors table view.
     *
     * @param neighbors The latest batch of LTE neighbors.
     */
    private void updateLteNeighborsView(SortedSet<LteNeighbor> neighbors)
    {
        final Context context = getContext();
        if (context == null) return;

        if (neighbors.isEmpty())
        {
            binding.lteNeighborsGroup.setVisibility(View.GONE);
            return;
        }

        binding.lteNeighborsGroup.setVisibility(View.VISIBLE);

        final TableLayout lteNeighborsTable = binding.lteNeighborsTable;

        lteNeighborsTable.removeAllViews();

        for (LteNeighbor neighbor : neighbors)
        {
            final TableRow row = new TableRow(context);

            addValueToRow(context, row, neighbor.earfcn);
            addValueToRow(context, row, neighbor.pci);
            addValueToRow(context, row, neighbor.rsrp);
            addValueToRow(context, row, neighbor.rsrq);
            addValueToRow(context, row, neighbor.ta);

            lteNeighborsTable.addView(row);
        }
    }

    /**
     * Given the newest set of UMTS neighbors, update the neighbors table view.
     *
     * @param neighbors The latest batch of UMTS neighbors.
     */
    private void updateUmtsNeighborsView(SortedSet<UmtsNeighbor> neighbors)
    {
        final Context context = getContext();
        if (context == null) return;

        final TableLayout umtsNeighborsTable = binding.umtsNeighborsTable;

        if (neighbors.isEmpty())
        {
            binding.umtsNeighborsGroup.setVisibility(View.GONE);
            return;
        }

        binding.umtsNeighborsGroup.setVisibility(View.VISIBLE);

        umtsNeighborsTable.removeAllViews();

        for (UmtsNeighbor neighbor : neighbors)
        {
            final TableRow row = new TableRow(context);

            addValueToRow(context, row, neighbor.uarfcn);
            addValueToRow(context, row, neighbor.psc);
            addValueToRow(context, row, neighbor.rscp);

            umtsNeighborsTable.addView(row);
        }
    }

    /**
     * Given the newest set of GSM neighbors, update the neighbors table view.
     *
     * @param neighbors The latest batch of GSM neighbors.
     */
    private void updateGsmNeighborsView(SortedSet<GsmNeighbor> neighbors)
    {
        final Context context = getContext();
        if (context == null) return;

        final TableLayout gsmNeighborsTable = binding.gsmNeighborsTable;

        if (neighbors.isEmpty())
        {
            binding.gsmNeighborsGroup.setVisibility(View.GONE);
            return;
        }

        binding.gsmNeighborsGroup.setVisibility(View.VISIBLE);

        gsmNeighborsTable.removeAllViews();

        for (GsmNeighbor neighbor : neighbors)
        {
            final TableRow row = new TableRow(context);

            addValueToRow(context, row, neighbor.arfcn);
            addValueToRow(context, row, neighbor.bsic);
            addValueToRow(context, row, neighbor.rssi);

            gsmNeighborsTable.addView(row);
        }
    }

    /**
     * Set the provided value in a TextView and then add it to the row.
     *
     * @param context The context to use for creating the TextView.
     * @param row     The row to add the cell to.
     * @param value   The value to place in the cell. If the value is
     *                {@link com.craxiom.networksurvey.constants.NetworkSurveyConstants#UNSET_VALUE},
     *                then an empty strinig is placed in the cell.
     */
    private void addValueToRow(Context context, TableRow row, int value)
    {
        final String cellText;
        if (value == NetworkSurveyConstants.UNSET_VALUE)
        {
            // We need to add an empty text view to make sure the columns align correctly
            cellText = "";
        } else
        {
            cellText = String.valueOf(value);
        }

        final TextView view = new TextView(context, null, 0, R.style.TableText);
        view.setText(cellText);
        row.addView(view);
    }
}
