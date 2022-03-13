package com.sndurkin.locationscout.integ;


import com.sndurkin.locationscout.R;

public class PlaceResult {

    public Integer errorMsg;

    public Double latitude;
    public Double longitude;
    public String title;

    public PlaceResult() {
        this.errorMsg = R.string.import_failed;
    }

    public PlaceResult(Integer errorMsg) {
        this.errorMsg = errorMsg;
    }

    public PlaceResult(Double latitude, Double longitude) {
        this(latitude, longitude, null);
    }

    public PlaceResult(Double latitude, Double longitude, String title) {
        this.latitude = latitude;
        this.longitude = longitude;
        this.title = title;
    }

}
