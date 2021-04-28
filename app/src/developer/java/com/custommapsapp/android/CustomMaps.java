/*
 * Copyright 2011 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.custommapsapp.android;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.ContentResolver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.res.Configuration;
import android.location.Location;
import android.net.Uri;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.Window;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.ImageButton;
import android.widget.PopupMenu;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.localbroadcastmanager.content.LocalBroadcastManager;

import com.custommapsapp.android.MapDisplay.MapImageTooLargeException;
import com.custommapsapp.android.fio.FileStreamer;
import com.custommapsapp.android.fio.OutputDirectoryManager;
import com.custommapsapp.android.kml.GroundOverlay;
import com.custommapsapp.android.kml.KmlFeature;
import com.custommapsapp.android.kml.KmlFinder;
import com.custommapsapp.android.kml.KmlFolder;
import com.custommapsapp.android.kml.KmlInfo;
import com.custommapsapp.android.kml.KmlParser;
import com.custommapsapp.android.kml.Placemark;
import com.custommapsapp.android.language.Linguist;
import com.custommapsapp.android.storage.EditPreferences;
import com.custommapsapp.android.storage.PreferenceStore;
import com.google.android.gms.location.FusedLocationProviderClient;
import com.google.android.gms.location.LocationRequest;
import com.google.android.gms.location.LocationResult;
import com.google.android.gms.location.LocationServices;

import org.xmlpull.v1.XmlPullParserException;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.security.KeyException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.Locale;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * CustomMaps is the main activity of the application. It displays a bitmap image tied to geo
 * coordinates and the user's location on it.
 *
 * @author Marko Teittinen
 */
public class CustomMaps extends AppCompatActivity {
    public static final String LOG_TAG = "Custom Maps";
    public static final String TIME_INFO = "TimeInfo";

    private static final String PREFIX = "com.custommapsapp.android";

    public static final String PDF_RENDERER_FRAGMENT_TAG = PREFIX + ".PdfRendererFragment";

    private static final String SAVED_LOCATION = PREFIX + ".Location";
    private static final String SAVED_MAP = PREFIX + ".Map";
    private static final String SAVED_MARKER = PREFIX + ".Placemark";
    private static final String SAVED_ZOOMLEVEL = PREFIX + ".ZoomLevel";
    private static final String SAVED_FOLLOWMODE = PREFIX + ".FollowMode";
    private static final String SAVED_CENTER = PREFIX + ".Center";
    private static final String SAVED_INSTANCESTATE = PREFIX + ".InstanceState";
    private static final String SAVED_SAFETY_REMINDER = PREFIX + ".SafetyReminder";

    private static final String DOWNLOAD_URL_PREFIX = "http://www.custommapsapp.com/qr?";
    private static final long NANO_TO_SEC = 1000000000;

    private OutputDirectoryManager outputDirectoryManager;

    private enum MapError {
        NO_ERROR,
        IMAGE_TOO_LARGE,
        IMAGE_NOT_FOUND,
        INVALID_IMAGE
    }

    // Activity IDs
    private static final int ACCEPT_LICENSE = 10;
    private static final int SELECT_MAP = 1;
    private static final int DOWNLOAD_MAP = 2;
    private static final int EDIT_PREFS = 3;

    private static final int MENU_SELECT_MAP = 1;
    private static final int MENU_MY_LOCATION = 2;
    private static final int MENU_LOCATION_DETAILS = 3;
    private static final int MENU_SHARE_MAP = 4;
    private static final int MENU_PREFERENCES = 5;

    private static final String WRITER_TAG = "location";

    private MapDisplay mapDisplay;
    private LocationLayer locationLayer;
    private DistanceLayer distanceLayer;
    private KmlFolder selectedMap = null;
    private GroundOverlay mapImage = null;
    private List<Placemark> placemarks = new ArrayList<>();
    private DetailsDisplay detailsDisplay;
    private InertiaScroller inertiaScroller;
    private ImageButton zoomIn;
    private ImageButton zoomOut;
    private DisplayState displayState;

    private Linguist linguist;
    private SafetyWarningDialog safetyReminder = null;
    private boolean updateMenuItems = false;

    private boolean isLaunchingSelectMap = false;

    private Button selectPoint;
    private Button startStopButton;
    private ImageButton deleteOptionsButton;
    private AtomicBoolean isRecording = new AtomicBoolean(false);
    private FileStreamer mFileStreamer;
    OutputDirectoryManager mStreamFolder;

    private TextView mLabelInterfaceTime;
    private AtomicLong start_time = new AtomicLong(-1);
    private Timer mInterfaceTimer;
    private TimerStatusReceiver timerReciever;

    @Override
    public void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        linguist = ((CustomMapsApp) getApplication()).getLinguist();
        Log.i(LOG_TAG, "App memory available (MB): " + MemoryUtil.getTotalAppMemoryMB(this));
        ImageHelper.initializePreferredBitmapConfig(this);
        if (PreferenceStore.instance(this).isUseGpu()) {
            getWindow().setFlags(WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                    WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED);
        }
        try {
            mStreamFolder = new OutputDirectoryManager("mapLocalize");
        } catch (IOException e) {
            Log.d(LOG_TAG, "startRecording: Cannot create output folder.");
            e.printStackTrace();
        }

        // Initialize disk cache
        ImageDiskCache.instance(this);
        GeoidHeightEstimator.initialize(getAssets());

        // Never display actionbar in landscape mode
        if (getResources().getConfiguration().orientation == Configuration.ORIENTATION_LANDSCAPE) {
            requestWindowFeature(Window.FEATURE_NO_TITLE);
        }

        timerReciever = new TimerStatusReceiver();
        reloadUI();

        // Do not process launch intent if software license is not accepted yet
        if (PreferenceStore.instance(this).isLicenseAccepted()) {
            processLaunchIntent(savedInstanceState);
        } else {
            selectedMap = null;
        }
    }

    private void reloadUI() {
        float[] screenCenter = null;
        float zoomLevel = Float.NaN;
        if (displayState != null) {
            screenCenter = displayState.getScreenCenterGeoLocation();
            zoomLevel = displayState.getZoomLevel();
        }
        setContentView(R.layout.main);
        setSupportActionBar(findViewById(R.id.toolbar));
        linguist.translateView(findViewById(R.id.root_view));
        mapDisplay = findViewById(R.id.mapDisplay);
        mapDisplay.setLinguist(linguist);
        if (inertiaScroller == null) {
            inertiaScroller = new InertiaScroller(mapDisplay);
        } else {
            inertiaScroller.setMap(mapDisplay);
        }
        locationLayer = findViewById(R.id.locationLayer);
        displayState = new DisplayState();
        mapDisplay.setDisplayState(displayState);
        locationLayer.setDisplayState(displayState);
        distanceLayer = findViewById(R.id.distanceLayer);
        distanceLayer.setDisplayState(displayState);
        detailsDisplay = findViewById(R.id.detailsDisplay);
        detailsDisplay.setLinguist(linguist);
        inertiaScroller.setOverlayViews(locationLayer, distanceLayer);
        mapDisplay.setOverlay(locationLayer);

        if (locator == null) {
            locator = LocationServices.getFusedLocationProviderClient(this);
        }

        if (zoomIn != null) {
            zoomIn.setOnClickListener(null);
        }
        if (zoomOut != null) {
            zoomOut.setOnClickListener(null);
        }
        zoomIn = findViewById(R.id.zoomIn);
        zoomOut = findViewById(R.id.zoomOut);
        zoomIn.setOnClickListener(v -> mapDisplay.zoomMap(2.0f));
        zoomOut.setOnClickListener(v -> mapDisplay.zoomMap(0.5f));
        if (selectedMap != null) {
            loadMapForDisplay(mapImage, null);
            if (screenCenter != null) {
                displayState.centerOnGeoLocation(screenCenter[0], screenCenter[1]);
                displayState.setZoomLevel(zoomLevel);
            }
        }

        selectPoint = findViewById(R.id.selectPoint);
        startStopButton = findViewById((R.id.startStopButton));
        selectPoint.setOnClickListener(v -> saveSelectedPoint());
        startStopButton.setOnClickListener(v -> startStopRecording());
        deleteOptionsButton = findViewById(R.id.deletePopUpBtn);
        deleteOptionsButton.setOnClickListener(v->showDeleteOptions(v));
        mLabelInterfaceTime = findViewById(R.id.timerTxt);
        if(isRecording.get()){
          startStopButton.setText(R.string.stop_record);
          selectPoint.setEnabled(true);
          mLabelInterfaceTime.setVisibility(View.VISIBLE);
        }else{
            startStopButton.setText(R.string.start_record);
            selectPoint.setEnabled(false);
            mLabelInterfaceTime.setVisibility(View.GONE);
        }
    }

    private void processLaunchIntent(Bundle savedInstanceState) {
        Intent intent = getIntent();
        if (intent != null && Intent.ACTION_VIEW.equals(intent.getAction())) {
            String action = intent.getAction();
            Uri data = intent.getData();
            // Clear intent action to prevent triggering download many times
            intent.setAction(null);
            if (launchIntentAction(action, data)) {
                return;
            }
            // Found invalid intent parameters, ignore them and launch normally
            Log.d(LOG_TAG, "Invalid params for VIEW action, ignoring action");
        }

        if (savedInstanceState != null && savedInstanceState.getSerializable(SAVED_MAP) != null) {
            // onRestoreInstanceState(savedInstanceState);
        } else if (selectedMap != null) {
            loadKmlFile();
        } else {
            isLaunchingSelectMap = true;
            launchSelectMap(true);
        }
    }

    @SuppressWarnings("MissingPermission")
    @Override
    protected void onResume() {
        super.onResume();

        // Do not display anything if license is not accepted yet
        if (!PreferenceStore.instance(this).isLicenseAccepted() && !isFinishing()) {
            Log.i(LOG_TAG, "Showing license activity");
            launchLicenseActivity();
            return;
        }
        if (isLaunchingSelectMap) {
            return;
        }
        // If some permissions have not been granted, launch select map activity to request them
        if (!PermissionFragment.hasPermission(this, Manifest.permission.ACCESS_FINE_LOCATION)
                || !PermissionFragment.hasPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE)) {
            isLaunchingSelectMap = true;
            launchSelectMap(true);
            return;
        }

        if (selectedMap != null) {
            loadMapForDisplay(mapImage, null);
            mapDisplay.centerOnGpsLocation();
        }

        if (mapDisplay.getMap() == null && getIntent().getBundleExtra(SAVED_INSTANCESTATE) != null) {
            selectedMap = null;
            onRestoreInstanceState(getIntent().getBundleExtra(SAVED_INSTANCESTATE));
        }
        locationTracker.setContext(getApplicationContext());
        locationTracker.setDisplay(getWindowManager().getDefaultDisplay());
        locationTracker.setQuitting(false);

        // Avoid crashing on some systems where location providers are disabled
        // This is possible on some open source Android variants.
        mLocationRequest = new LocationRequest();
        mLocationRequest.setInterval(2000);
        mLocationRequest.setFastestInterval(1000);
        mLocationRequest.setPriority(LocationRequest.PRIORITY_HIGH_ACCURACY);

        String message = null;
        try {
            locator.requestLocationUpdates(mLocationRequest, locationTracker, Looper.myLooper());
        } catch (SecurityException e) {
            message = "startSession: Error creating location service: " + e.getMessage();
        }
        locationLayer.setWarningMessage(message);

        PreferenceStore prefs = PreferenceStore.instance(getApplicationContext());
        detailsDisplay.setUseMetric(prefs.isMetric());
        int visibility = (prefs.isShowDetails() ? View.VISIBLE : View.GONE);
        detailsDisplay.setVisibility(visibility);
        visibility = (prefs.isShowDistance() ? View.VISIBLE : View.GONE);
        distanceLayer.setVisibility(visibility);
        distanceLayer.setShowHeading(prefs.isShowHeading());

        LocalBroadcastManager.getInstance(this).registerReceiver(timerReciever, new IntentFilter(TIME_INFO));
    }

    @Override
    protected void onPostResume() {
        super.onPostResume();
        mapDisplay.postInvalidate();
        locationLayer.postInvalidate();
    }

    @Override
    protected void onPause() {
        locationTracker.setQuitting(true);
        locator.removeLocationUpdates(locationTracker);
        super.onPause();
        isLaunchingSelectMap = false;
    }

    @Override
    protected void onStop() {
        LocalBroadcastManager.getInstance(this).unregisterReceiver(timerReciever);
        loadMapForDisplay(null, null);
        super.onStop();
    }

    // --------------------------------------------------------------------------
    // Instance state saving and restoring

    @Override
    public void onSaveInstanceState(@NonNull Bundle outState) {
        super.onSaveInstanceState(outState);
        outState.putParcelable(SAVED_LOCATION, locationTracker.getCurrentLocation(null));
        if (selectedMap != null) {
            outState.putSerializable(SAVED_MAP, selectedMap);
        }
        outState.putBoolean(SAVED_FOLLOWMODE, displayState.getFollowMode());
        float[] geoCenter = mapDisplay.getScreenCenterGeoLocation();
        if (geoCenter != null) {
            outState.putSerializable(SAVED_CENTER, geoCenter);
        }
        float zoomLevel = mapDisplay.getZoomLevel();
        outState.putFloat(SAVED_ZOOMLEVEL, zoomLevel);
        if (safetyReminder != null) {
            outState.putBundle(SAVED_SAFETY_REMINDER, safetyReminder.onSaveInstanceState());
        }
        // Save to original intent to have this available at will
        getIntent().putExtra(SAVED_INSTANCESTATE, outState);
    }

    @Override
    public void onRestoreInstanceState(Bundle inState) {
        if (PreferenceStore.instance(this).isLicenseAccepted()) {
            Bundle safetyReminderState = inState.getBundle(SAVED_SAFETY_REMINDER);
            if (safetyReminderState != null) {
                displaySafetyReminder(safetyReminderState);
            }
        }
        if (selectedMap != null) {
            // Do not restore instance state since a map is selected
            return;
        }
        KmlFolder savedMap = (KmlFolder) inState.getSerializable(SAVED_MAP);
        if (savedMap != null) {
            initializeMapVariables(savedMap);
            if (loadMapForDisplay(mapImage, null) == MapError.NO_ERROR) {
                selectedMap = savedMap;
                mapDisplay.addMapMarkers(placemarks);
                float[] geoCenter = (float[]) inState.getSerializable(SAVED_CENTER);
                if (geoCenter != null) {
                    mapDisplay.centerOnLocation(geoCenter[0], geoCenter[1]);
                }
                mapDisplay.zoomMap(inState.getFloat(SAVED_ZOOMLEVEL, 1f));
                displayState.setFollowMode(inState.getBoolean(SAVED_FOLLOWMODE));
                Location savedLocation = inState.getParcelable(SAVED_LOCATION);
                if (savedLocation != null) {
                    locationTracker.onLocationResult(savedLocation);
                }
            } else {
                // TODO: Do we need a failure message by error result from loadMap...()?
                if (savedMap.getKmlInfo().getFile().exists()) {
                    // map image exists but is too large, notify user
                    displayMapLoadWarning();
                } else {
                    // map has been deleted, remove its info
                    inState.remove(SAVED_MAP);
                    savedMap = null;
                    selectedMap = null;
                }
            }
        }
        // Remove from original intent (might have been saved there)
        getIntent().removeExtra(SAVED_INSTANCESTATE);
    }

    private void displayUserMessage(final String message) {
        Runnable displayTask = () -> Toast.makeText(CustomMaps.this, message, Toast.LENGTH_LONG).show();
        runOnUiThread(displayTask);
    }

    /**
     * Initializes mapImage (first GroundOverlay in KmlFolder) and list of placemarks stored with
     * map.
     *
     * @param map KmlFolder containing the data
     */
    private void initializeMapVariables(KmlFolder map) {
        selectedMap = map;
        placemarks.clear();
        mapImage = null;
        if (map != null) {
            mapImage = map.getFirstMap();
            Log.d(LOG_TAG, "Selected map: " + (mapImage == null ? "- none -" : mapImage.getName()));
            for (KmlFeature feature : map.getFeatures()) {
                if (feature instanceof Placemark) {
                    placemarks.add((Placemark) feature);
                }
            }
        }
        Log.d(LOG_TAG,
                String.format("CustomMaps initialized map. Found %d placemarks.", placemarks.size()));
    }

    // --------------------------------------------------------------------------
    // Menus

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        super.onCreateOptionsMenu(menu);
        createMenuItems(menu);
        return true;
    }

    private void createMenuItems(Menu menu) {
        menu.add(Menu.NONE, MENU_SELECT_MAP, Menu.NONE, linguist.getString(R.string.select_map))
                .setIcon(R.drawable.mapmode);
        MenuItem item =
                menu.add(Menu.NONE, MENU_MY_LOCATION, Menu.NONE, linguist.getString(R.string.my_location))
                        .setIcon(R.drawable.ic_my_location_black_24dp);
        item.setShowAsAction(MenuItem.SHOW_AS_ACTION_IF_ROOM);
        menu.add(
                Menu.NONE, MENU_LOCATION_DETAILS, Menu.NONE, linguist.getString(R.string.location_details))
                .setIcon(android.R.drawable.ic_menu_info_details);
        menu.add(Menu.NONE, MENU_SHARE_MAP, Menu.NONE, linguist.getString(R.string.share_map))
                .setIcon(android.R.drawable.ic_menu_share);
        menu.add(Menu.NONE, MENU_PREFERENCES, Menu.NONE, linguist.getString(R.string.settings))
                .setIcon(android.R.drawable.ic_menu_preferences);
    }

    @Override
    public boolean onPrepareOptionsMenu(Menu menu) {
        if (updateMenuItems) {
            menu.findItem(MENU_SELECT_MAP).setTitle(linguist.getString(R.string.select_map));
            menu.findItem(MENU_MY_LOCATION).setTitle(linguist.getString(R.string.my_location));
            menu.findItem(MENU_LOCATION_DETAILS).setTitle(linguist.getString(R.string.location_details));
            menu.findItem(MENU_SHARE_MAP).setTitle(linguist.getString(R.string.share_map));
            menu.findItem(MENU_PREFERENCES).setTitle(linguist.getString(R.string.settings));
            updateMenuItems = false;
        }
        // Can't share a null map or one that doesn't contain KmlInfo
        boolean enableShare = (selectedMap != null && selectedMap.getKmlInfo() != null);
        menu.findItem(MENU_SHARE_MAP).setEnabled(enableShare);
        return super.onPrepareOptionsMenu(menu);
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (!triggerMenuAction(item)) {
            return super.onOptionsItemSelected(item);
        }
        return true;
    }

    private boolean triggerMenuAction(MenuItem item) {
        switch (item.getItemId()) {
            case MENU_SELECT_MAP:
                launchSelectMap(false);
                break;
            case MENU_MY_LOCATION:
                centerUserLocation();
                break;
            case MENU_LOCATION_DETAILS:
                toggleDetailsDisplay();
                break;
            case MENU_SHARE_MAP:
                shareMap();
                break;
            case MENU_PREFERENCES:
                launchEditPreferences();
                break;
            default:
                return false;
        }
        return true;
    }

    private void showDeleteOptions(View v){
        PopupMenu popup = new PopupMenu(this, deleteOptionsButton);
        //Inflating the Popup using xml file
        popup.getMenuInflater().inflate(R.menu.delete_popup, popup.getMenu());

        //registering popup with OnMenuItemClickListener
        popup.setOnMenuItemClickListener(item -> {
            switch (item.getItemId()){
                case R.id.deleteRecord:
                    deleteLastRecord();
                    break;
                case R.id.deleteSession:
                    deleteThisSession();
                    break;
                default:
                    return false;
            }
            return true;
        });

        popup.show();//showing popup menu
    }

    private void deleteLastRecord(){
        if(isRecording.get()){
            try {
                mFileStreamer.addRecord(WRITER_TAG, "\tDELETE");
            } catch (IOException | KeyException e) {
                Log.d(LOG_TAG, "onSensorChanged: Something is wrong.");
                e.printStackTrace();
            }
        }else{
            displayUserMessage("No active session");
        }
    }

    private void deleteThisSession(){
        if(isRecording.get()){
            try {
                mFileStreamer.addRecord(WRITER_TAG, "\nCANCEL SESSION");
            } catch (IOException | KeyException e) {
                Log.d(LOG_TAG, "onSensorChanged: Something is wrong.");
                e.printStackTrace();
            }finally {
                stopRecording();
                displayUserMessage("Cancelled entire session");
            }
        }else{
            displayUserMessage("No active session");
        }
    }

    private void centerUserLocation() {
        if (!mapDisplay.centerOnGpsLocation()) {
            displayUserMessage(linguist.getString(R.string.gps_outside_map));
        } else {
            displayState.setFollowMode(true);
        }
    }

    private void toggleDetailsDisplay() {
        Location location = locationTracker.getCurrentLocation(null);
        boolean showDetails = !detailsDisplay.isShown();
        if (showDetails) {
            detailsDisplay.updateValues(location);
            detailsDisplay.setVisibility(View.VISIBLE);
            detailsDisplay.getParent().requestLayout();
        } else {
            detailsDisplay.setVisibility(View.GONE);
            detailsDisplay.getParent().requestLayout();
        }
        PreferenceStore.instance(getApplicationContext()).setShowDetails(showDetails);
    }

    private void shareMap() {
        if (selectedMap == null || !FileUtil.shareMap(this, selectedMap)) {
            displayUserMessage(linguist.getString(R.string.share_map_failed));
        }
    }

    // --------------------------------------------------------------------------
    // Activities

    private boolean launchIntentAction(String action, Uri data) {
        if (!Intent.ACTION_VIEW.equals(action) || data == null) {
            return false;
        }
        // Save content:// scheme Uri data into a file using ContentResolver
        if (ContentResolver.SCHEME_CONTENT.equals(data.getScheme())) {
            File savedFile = FileUtil.saveKmzContentUri(this, data);
            if (savedFile != null) {
                // Open the saved file instead of passed-in content Uri
                data = Uri.fromFile(savedFile);
            } else {
                // Failed to save the data, display error message and quit app
                displayUserMessage(linguist.getString(R.string.external_content_failed));
                finish();
                return false;
            }
        }
        // NOTE: All errors "fall through" to return 'false'
        if ("file".equals(data.getScheme())) {
            // Open local KMZ file
            String localPath = data.getPath();
            if (localPath != null) {
                File localFile = new File(localPath);
                if (localFile.exists() && localFile.isFile() && localFile.getName().endsWith(".kmz")) {
                    launchSelectMap(localFile);
                    return true;
                }
            }
            Log.w(LOG_TAG, "Invalid open local file request: " + localPath);
        } else if (data.toString().startsWith(DOWNLOAD_URL_PREFIX)) {
            // Download file from the web and open it
            String targetUrl = data.getQueryParameter("url");
            if (targetUrl != null) {
                try {
                    URL kmzUrl = new URL(targetUrl);
                    launchKmzDownloader(kmzUrl);
                    return true;
                } catch (MalformedURLException ex) {
                    Log.w(LOG_TAG, "Invalid open web file request: " + targetUrl, ex);
                }
            }
        }
        return false;
    }

    private void launchKmzDownloader(URL mapUrl) {
        Intent downloadMap = new Intent(this, KmzDownloader.class);
        downloadMap.putExtra(KmzDownloader.URL, mapUrl.toString());
        startActivityForResult(downloadMap, DOWNLOAD_MAP);
    }

    private void processKmzDownloaderResult(Intent data) {
        String localPath = (data != null ? data.getStringExtra(KmzDownloader.LOCAL_FILE) : null);
        if (localPath != null) {
            File localFile = new File(localPath);
            if (localFile.exists() && localFile.isFile() && localFile.getName().endsWith(".kmz")) {
                // Web file download succeeded, auto-select it
                launchSelectMap(localFile);
                return;
            }
        }
        Log.w(LOG_TAG, "Invalid local file result from KmzDownloader: " + localPath);
        // Failed to download the web file, or it was cancelled
        launchSelectMap(false);
    }

    private void launchEditPreferences() {
        Intent editPrefs = new Intent(this, EditPreferences.class);
        startActivityForResult(editPrefs, EDIT_PREFS);
    }

    private void launchSelectMap(File localFile) {
        Intent selectMap = new Intent(this, SelectMap.class);
        selectMap.putExtra(SelectMap.LOCAL_FILE, localFile.getAbsolutePath());
        selectMap.putExtra(SelectMap.AUTO_SELECT, true);
        startActivityForResult(selectMap, SELECT_MAP);
    }

    private void launchSelectMap(Location location) {
        Intent selectMap = new Intent(this, SelectMap.class);
        selectMap.putExtra(SelectMap.LAST_LOCATION, location);
        selectMap.putExtra(SelectMap.AUTO_SELECT, true);
        startActivityForResult(selectMap, SELECT_MAP);
    }

    private void launchSelectMap(boolean autoSelect) {
        Intent selectMap = new Intent(this, SelectMap.class);
        if (autoSelect) {
            selectMap.putExtra(SelectMap.AUTO_SELECT, true);
        }
        startActivityForResult(selectMap, SELECT_MAP);
    }

    private void processSelectMapResult(Intent data) {
        // Reload UI and update menu items just in case the language was changed
        reloadUI();
        updateMenuItems = true;

        if (data == null) {
            Log.w(LOG_TAG, "User did not select a map.");
            Bundle instanceState = getIntent().getBundleExtra(SAVED_INSTANCESTATE);
            if (instanceState != null && instanceState.containsKey(SAVED_MAP)) {
                selectedMap = null;
                onRestoreInstanceState(instanceState);
                if (selectedMap == null) {
                    // Previous map is no longer available, auto-select another one
                    mapDisplay.post(() -> launchSelectMap(getLastKnownLocation(0)));
                    return;
                }
            }
            if (selectedMap == null) {
                // User canceled initial selection, exit app
                finish();
            }
        } else {
            initializeMapVariables((KmlFolder) data.getSerializableExtra(SelectMap.SELECTED_MAP));
            MapError status = loadMapForDisplay(mapImage, mapDisplay.getMap());
            if (status == MapError.NO_ERROR) {
                mapDisplay.addMapMarkers(placemarks);
                if (PreferenceStore.instance(this).isReminderRequested()) {
                    displaySafetyReminder(null);
                }
            } else {
                // TODO: display error message based on errorCode
                displayMapLoadWarning();
            }
            // Start with last known location if it is fresher than 15 minutes
            long _15Minutes = 15 * 60 * 1000;
            Location location = getLastKnownLocation(_15Minutes);
            locationTracker.onLocationResult(location);
            if (location == null) {
                displayUserMessage(linguist.getString(R.string.waiting_for_gps));
                displayState.setFollowMode(true);
                mapDisplay.centerOnMapCenterLocation();
            } else {
                // Location known, center on user location if within map boundaries
                displayState.setFollowMode(mapDisplay.centerOnGpsLocation());
                if (!displayState.getFollowMode()) {
                    mapDisplay.centerOnMapCenterLocation();
                }
            }
        }
    }

    private void processEditPreferencesResult(Intent data) {
        // Reload UI and update menu items just in case the language was changed
        reloadUI();
        updateMenuItems = true;
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        // Update linguist since it may have changed during the activity that is returning
        linguist = ((CustomMapsApp) getApplication()).getLinguist();
        if (requestCode == DOWNLOAD_MAP) {
            if (resultCode == RESULT_OK && data != null) {
                // map downloaded successfully from web, process results
                processKmzDownloaderResult(data);
            } else {
                // download failed or was cancelled, launch default map selection
                launchSelectMap(false);
            }
            return;
        }
        if (requestCode == SELECT_MAP) {
            processSelectMapResult(resultCode == RESULT_OK ? data : null);
            return;
        }
        if (requestCode == EDIT_PREFS) {
            processEditPreferencesResult(data);
            return;
        }
        if (requestCode == ACCEPT_LICENSE) {
            processLicenseActivityResult(resultCode, data);
            return;
        }
        Log.w(LOG_TAG, String.format(
                "Unexpected activity result. Request: %d, Result %d", requestCode, resultCode));
        super.onActivityResult(requestCode, resultCode, data);
    }

    /**
     * Attempts to load a new map. If the attempt fails because the image is too large, loads map
     * given as old map. If even that attempt fails, clears map completely. If clearing the map fails,
     * will throw OutOfMemoryError.
     *
     * @param newMap to be displayed
     * @param oldMap to be restored if newMap fails to display
     * @return MapError.NO_ERROR if newMap was loaded successfully, otherwise some other MapError
     * value indicating nature of problem
     */
    private MapError loadMapForDisplay(GroundOverlay newMap, GroundOverlay oldMap) {
        try {
            mapDisplay.setMap(newMap);
            if (newMap != null) {
                // Force failure if map file has been deleted
                if (!newMap.getKmlInfo().getFile().exists()) {
                    return MapError.IMAGE_NOT_FOUND;
                }
                locationLayer.updateMapAngle();
            }
            return MapError.NO_ERROR;
        } catch (MapImageTooLargeException ex) {
            // map was too large, restore old map
            if (newMap != null) {
                loadMapForDisplay(oldMap, null);
                if (oldMap != null) {
                    locationLayer.updateMapAngle();
                }
                return MapError.IMAGE_TOO_LARGE;
            } else {
                throw new OutOfMemoryError("Can't load even 'null' map. Giving up.");
            }
        }
    }

    private void displayMapLoadWarning() {
        // Failed to load selected map, old was recovered, display error message
        displayUserMessage(linguist.getString(R.string.map_too_large));
    }

    private void displaySafetyReminder(Bundle savedState) {
        safetyReminder = new SafetyWarningDialog(this);
        // Make sure the text is displayed in the correct language
        linguist.translateView(safetyReminder.getRootView());

        safetyReminder.setOnDismissListener(dialog -> {
            SafetyWarningDialog reminder = (SafetyWarningDialog) dialog;
            if (reminder.wasButtonPressed() && !reminder.getShowAgainRequested()) {
                PreferenceStore.instance(CustomMaps.this).setReminderRequested(false);
            }
            safetyReminder = null;
        });
        safetyReminder.show();
        if (savedState != null) {
            safetyReminder.onRestoreInstanceState(savedState);
        }
    }

    public void startStopRecording(){
        if(isRecording.get()) {
            stopRecording();
        }else {
            startRecording();
        }
    }

    private void startRecording(){
        isRecording.set(true);
        startStopButton.setText(R.string.stop_record);
        selectPoint.setEnabled(true);
        try {
            mFileStreamer = new FileStreamer(this, mStreamFolder.getOutputDirectory());
            Calendar current_time = Calendar.getInstance();
            SimpleDateFormat formatter = new SimpleDateFormat("yyyyMMddhhmmss");
            String folder_name = formatter.format(current_time.getTime());
            mFileStreamer.addFile(WRITER_TAG, String.format("location_%s.txt", folder_name));
        } catch (IOException e) {
            Log.d(LOG_TAG, "startRecording: Cannot create output folder.");
            e.printStackTrace();
        }
        start_time.set(SystemClock.elapsedRealtimeNanos());
        mLabelInterfaceTime.setVisibility(View.VISIBLE);
        mInterfaceTimer = new Timer();
        mInterfaceTimer.schedule(new TimerTask() {
            @Override
            public void run() {
                if(start_time.get() < 0){return;}
                Intent timerInfoIntent = new Intent(TIME_INFO);
                long input = (SystemClock.elapsedRealtimeNanos() - start_time.get())/NANO_TO_SEC;
                // extract hour, minute, second information from second
                long hours = input / 3600;
                input = input % 3600;
                long mins = input / 60;
                long secs = input % 60;

                // return interface int time
                timerInfoIntent.putExtra("VALUE", String.format(Locale.US, "%02d:%02d:%02d", hours, mins, secs));
                LocalBroadcastManager.getInstance(CustomMaps.this).sendBroadcast(timerInfoIntent);
            }
        }, 0, 1000);

    }

    private void stopRecording(){
        isRecording.set(false);
        startStopButton.setText(R.string.start_record);
        selectPoint.setEnabled(false);
        try {
            mFileStreamer.endFiles();
        } catch (IOException e) {
            e.printStackTrace();
        }
        mInterfaceTimer.cancel();
        start_time.set(-1);
        mLabelInterfaceTime.setVisibility(View.GONE);
    }

    public void saveSelectedPoint() {
        float[] imagePoint = mapDisplay.getSelectedPointInImage();
        float[] geoPoint = mapDisplay.getScreenCenterGeoLocation();
        try {
            StringBuilder str = new StringBuilder();
            str.append('\n');
            str.append(mapDisplay.map_name.replace(' ', '_'));
            str.append('\t');
            str.append(SystemClock.elapsedRealtimeNanos());
            str.append('\t');
            str.append(imagePoint[0]);
            str.append('\t');
            str.append(imagePoint[1]);
            str.append('\t');
            str.append(geoPoint[0]);
            str.append('\t');
            str.append(geoPoint[1]);
//            str.append('\n');
            mFileStreamer.addRecord(WRITER_TAG, str.toString());
        } catch (IOException | KeyException e) {
            Log.d(LOG_TAG, "onSensorChanged: Something is wrong.");
            e.printStackTrace();
        }
        Toast.makeText(this, String.format("Selected point (%.2f %.2f)", imagePoint[0], imagePoint[1]), Toast.LENGTH_LONG).show();
    }


    // --------------------------------------------------------------------------
    // License handling

    private void launchLicenseActivity() {
        Intent acceptLicense = new Intent(this, AboutDisplay.class);
        acceptLicense.putExtra(AboutDisplay.CANCELLABLE, false);
        startActivityForResult(acceptLicense, ACCEPT_LICENSE);
    }

    private void processLicenseActivityResult(int resultCode, Intent data) {
        if (resultCode == RESULT_OK && data != null &&
                data.getBooleanExtra(AboutDisplay.LICENSE_ACCEPTED, false)) {
            licenseAccepted();
        } else {
            Log.i(LOG_TAG, "User rejected software license, exiting app");
            finish();
        }
    }

    private void licenseAccepted() {
        Log.i(LOG_TAG, "Software license was accepted");
        PreferenceStore.instance(this).setLicenseAccepted(true);
        // Continue app launch
        processLaunchIntent(null);
    }

    // --------------------------------------------------------------------------

    /**
     * Get last known location that is not older than given maximum age. Age value 0 is considered
     * unlimited.
     *
     * @param maxAgeMs Maximum age of acceptable known location in milliseconds.
     * @return Last known location that is not older than maxAgeMs, or null if there is no known
     * location that is fresh enough. GPS location is preferred over network location.
     */
    @SuppressWarnings("MissingPermission")
    private Location getLastKnownLocation(long maxAgeMs) {
//        long oldestAllowed = (maxAgeMs == 0 ? 0 : System.currentTimeMillis() - maxAgeMs);
        return locationTracker.getCurrentLocation(null);
    }

    private void loadKmlFile() {
        try {
            selectedMap = findAnyMap();
        } catch (Exception ex) {
            Log.w(LOG_TAG, "Failed to load kml file", ex);
        }
    }

    private KmlFolder findAnyMap() throws IOException, XmlPullParserException {
        if (selectedMap != null) {
            return selectedMap;
        }

        Iterable<KmlInfo> kmlFiles = KmlFinder.findKmlFiles(FileUtil.getDataDirectory());
        KmlInfo data = kmlFiles.iterator().next();
        KmlParser parser = new KmlParser();
        Iterable<KmlFeature> features = parser.readFile(data.getKmlReader());
        // Find first map in the KML file
        KmlFolder mapFolder = null;
        List<KmlFeature> placemarks = new ArrayList<>();
        GroundOverlay map = null;
        for (KmlFeature feature : features) {
            if (feature instanceof Placemark) {
                feature.setKmlInfo(data);
                placemarks.add(feature);
                continue;
            }
            if (map != null) {
                continue;
            }
            if (feature instanceof GroundOverlay) {
                map = (GroundOverlay) feature;
            } else if (feature instanceof KmlFolder) {
                // Scan folder for a possible map
                KmlFolder folder = (KmlFolder) feature;
                for (KmlFeature folderFeature : folder.getFeatures()) {
                    if (folderFeature instanceof GroundOverlay) {
                        map = (GroundOverlay) folderFeature;
                        mapFolder = folder;
                        break;
                    }
                }
            }
        }
        KmlFolder result = null;
        if (map != null) {
            map.setKmlInfo(data);
            result = new KmlFolder();
            result.setKmlInfo(data);
            result.addFeature(map);
            result.addFeatures(placemarks);
            if (mapFolder != null) {
                result.setName(mapFolder.getName());
                result.setDescription(mapFolder.getDescription());
                for (KmlFeature feature : mapFolder.getFeatures()) {
                    if (feature instanceof Placemark) {
                        feature.setKmlInfo(data);
                        result.addFeature(feature);
                    }
                }
            }
        }
        return result;
    }

    private class TimerStatusReceiver extends BroadcastReceiver {
        @Override
        public void onReceive(Context context, Intent intent) {
            if (intent != null && intent.getAction().equals(TIME_INFO)) {
                if (intent.hasExtra("VALUE")) {
                    mLabelInterfaceTime.setText(intent.getStringExtra("VALUE"));
                }
            }
        }
    }

    private FusedLocationProviderClient locator;
    private LocationRequest mLocationRequest;
    private LocationTrackerFLP locationTracker = new LocationTrackerFLP(null) {
        @Override
        public void onLocationResult(LocationResult locationResult) {
            // check valid location result
            if (locationResult == null) {
                return;
            }
            super.onLocationResult(locationResult);
            List<Location> locationList = locationResult.getLocations();
            Location location = locationList.get(locationList.size() - 1);
            // Get updated location w/ averaged speed and altitude to be passed to
            // mapDisplay
            getCurrentLocation(location);
            mapDisplay.setGpsLocation((float) location.getLongitude(), (float) location.getLatitude(),
                    location.hasAccuracy() ? location.getAccuracy() : 10000f, location.getBearing());
            locationLayer.setGpsLocation(location);
            if (distanceLayer.getVisibility() == View.VISIBLE) {
                distanceLayer.setUserLocation(location);
            }
            if (detailsDisplay.isShown()) {
                detailsDisplay.updateValues(location);
            }
        }
    };
}
