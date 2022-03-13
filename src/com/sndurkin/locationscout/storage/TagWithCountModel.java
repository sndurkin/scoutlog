package com.sndurkin.locationscout.storage;


public class TagWithCountModel extends TagModel {

    public int count;

    public TagWithCountModel(TagModel tag, int count) {
        mergeFrom(tag, true);
        this.count = count;
    }

}
