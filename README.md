# custom-maps

Adopted from Custom Maps by Marko Teittinen [https://github.com/markoteittinen/custom-maps]

-------------------
Custom Maps can use almost any map image as a GPS map. The map image can be provided in JPG, PNG, GIF, or PDF format.
If a PDF file is used the user must select the map page from the PDF document, and the page is converted into JPG image
before it is used as a GPS map.

The project can be directly imported to Android Studio, but before it can be successfully built, it has to be provided
a Google Maps API key, as a string resource named "google_maps_key". This is typically done by creating file 
"google_maps_api.xml" in app/src/main/res/values directory, and the file should contain only that one string resource.
More details for getting a Google Maps API key can be found at https://developers.google.com/maps/documentation/android-sdk/get-api-key.

--------------------

Modified to use Google FLP and record manually marked locations under `Downloads/mapLocalize` folder.
The repository has two build variants:
    - minimal - use existing map files to manually mark locations
    - developer - all functionality.
                - can create maps from images that can be used by minimal version. The created .kmz files are saved in `CustomMaps`


