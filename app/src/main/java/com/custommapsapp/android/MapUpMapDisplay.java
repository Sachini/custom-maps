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

import android.content.Context;
import android.graphics.Canvas;
import android.util.AttributeSet;
import android.util.Log;
import android.widget.Toast;

import com.custommapsapp.android.kml.GroundOverlay;

import java.io.IOException;

/**
 * MapUpMapDisplay displays a bitmap as a map in its native orientation and
 * maps users' GPS coordinates to the image coordinates to show their location.
 *
 * @author Marko Teittinen
 */
public class MapUpMapDisplay extends MapDisplay {

  public MapUpMapDisplay(Context context) {
    super(context);
  }

  public MapUpMapDisplay(Context context, AttributeSet attrs) {
    super(context, attrs);
  }

  /**
   * Translates the map image being displayed by (tx, ty). Returns a boolean
   * indicating if the full translation was allowed. Value {@code false}
   * indicates the translation was truncated to avoid scrolling the map off
   * screen.
   *
   * @param tx amount of translation in east-west direction
   * @param ty amount of translation in north-south direction
   * @return {@code true} if the full translation was allowed, {@code false}
   *         if the full translation was not allowed to keep map on screen
   */
  @Override
  public boolean translateMap(float tx, float ty) {
    boolean result = displayState.translate(tx, ty);
    triggerRepaint();
    return result;
  }

  /**
   * Returns float[] containing longitude and latitude of the screen center point.
   * If the system fails to convert screen to image coordinates or image to geo
   * coordinates, returns 'null' (that should never happen).
   *
   * @return geo coordinates (longitude and latitude, in that order) of the screen
   * center point
   */
  @Override
  public float[] getScreenCenterGeoLocation() {
    return displayState.getScreenCenterGeoLocation();
  }

  @Override
  public GroundOverlay getMap() {
    return mapData;
  }

  @Override
  public void setMap(GroundOverlay newMap) throws MapImageTooLargeException {
    if (mapData == newMap || (mapData != null && mapData.equals(newMap))) {
      return;
    }
    removeAllMapMarkers();
    if (mapImage != null) {
      // Release memory used by the old map image
      mapImage.recycle();
      mapImage = null;
      mapData = null;
    }
    try {
      mapImage = loadMapImage(newMap);
    } catch (IOException ex) {
      // TODO: throw IOException here, and display error message in caller
      Log.w(CustomMaps.LOG_TAG, "Failed to load map image for " + newMap.getName(), ex);
      mapImage = null;
      // Failed to read image, display error message
      String mapName = newMap.getName();
      if (mapName == null || mapName.trim().length() == 0) {
        mapName = linguist.getString(R.string.unnamed_map);
      }
      final String errorMsg = linguist.getString(R.string.map_image_load_failed, mapName);
      post(() -> Toast.makeText(getContext(), errorMsg, Toast.LENGTH_LONG).show());
    }
    if (mapImage == null) {
      if (newMap != null) {
        Log.w(CustomMaps.LOG_TAG, "Map image failed to load, map not set.");
      }
      spotSet = false;
      mapData = null;
      return;
    }
    mapData = newMap;

    displayState.setMapData(mapData);
    displayState.setScreenView(this);
    triggerRepaint();
  }

  @Override
  public boolean centerOnGpsLocation() {
    // Check if geo location has been set
    if (!spotSet) {
      // No GPS location available, center map until we know GPS location
      centerOnMapCenterLocation();
      return true;
    }
    return centerOnLocation(geoLocation[0], geoLocation[1]);
  }

  @Override
  public void centerOnMapCenterLocation() {
    float[] location = displayState.getMapCenterGeoLocation(null);
    if (location != null) {
      centerOnLocation(location[0], location[1]);
    }
  }

  @Override
  public boolean centerOnLocation(float longitude, float latitude) {
    boolean result = displayState.centerOnGeoLocation(longitude, latitude);
    if (result) {
      triggerRepaint();
    }
    return result;
  }

  @Override
  public void onDraw(Canvas canvas) {
    if (mapImage == null || mapImage.isRecycled()) {
      return;
    }
    canvas.drawBitmap(mapImage, displayState.getImageToScreenMatrix(), null);
    drawMapMarkers(canvas, displayState);
  }

  @Override
  public void onSizeChanged(int w, int h, int oldW, int oldH) {
    super.onSizeChanged(w, h, oldW, oldH);
    if (displayState != null) {
      // Keep the same point centered in the view
      displayState.translate((w - oldW) / 2f, (h - oldH) / 2);
    }
  }

  //--------------------------------------------------------------------------------------------
  // Latitude/longitude

  private boolean spotSet = false;
  private float[] geoLocation = new float[2];

  @Override
  public void setGpsLocation(float longitude, float latitude, float accuracy, float heading) {
    if (mapImage == null) {
      return;
    }
    geoLocation[0] = longitude;
    geoLocation[1] = latitude;
    spotSet = true;
    if (displayState.getFollowMode()) {
      displayState.setFollowMode(centerOnGpsLocation());
      invalidate();
      overlay.invalidate();
    }
  }
}
