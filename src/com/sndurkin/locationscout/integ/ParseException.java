package com.sndurkin.locationscout.integ;


public class ParseException extends Exception {

    public ParseException(String detailMessage) {
        super(detailMessage);
    }

    public ParseException(String detailMessage, Throwable throwable) {
        super(detailMessage, throwable);
    }

}
