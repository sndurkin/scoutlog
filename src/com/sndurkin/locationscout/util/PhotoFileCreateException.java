package com.sndurkin.locationscout.util;


public class PhotoFileCreateException extends Exception {

    protected String folder;

    public PhotoFileCreateException(String folder) {
        this.folder = folder;
    }

    public String getFolder() {
        return folder;
    }

}
