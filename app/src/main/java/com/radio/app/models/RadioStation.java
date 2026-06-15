package com.radio.app.models;

import org.json.JSONObject;

public class RadioStation {
    private String id;
    private String name;
    private String streamUrl;
    private String favicon;
    private String country;
    private int bitrate;
    private boolean lastCheckOk;
    private String currentProgram;

    public static RadioStation fromJson(JSONObject json) {
        RadioStation station = new RadioStation();
        try {
            station.id = json.optString("stationuuid", "");
            station.name = json.optString("name", "");
            station.streamUrl = json.optString("url_resolved", json.optString("url", ""));
            station.favicon = json.optString("favicon", "");
            station.country = json.optString("country", "");
            station.bitrate = json.optInt("bitrate", 0);
            station.lastCheckOk = json.optInt("lastcheckok", 0) == 1;
        } catch (Exception e) {
            e.printStackTrace();
        }
        return station;
    }

    // Getters and setters
    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getName() { return name; }
    public void setName(String name) { this.name = name; }
    public String getStreamUrl() { return streamUrl; }
    public void setStreamUrl(String streamUrl) { this.streamUrl = streamUrl; }
    public String getFavicon() { return favicon; }
    public void setFavicon(String favicon) { this.favicon = favicon; }
    public String getCountry() { return country; }
    public void setCountry(String country) { this.country = country; }
    public int getBitrate() { return bitrate; }
    public void setBitrate(int bitrate) { this.bitrate = bitrate; }
    public boolean isLastCheckOk() { return lastCheckOk; }
    public void setLastCheckOk(boolean lastCheckOk) { this.lastCheckOk = lastCheckOk; }
    public String getCurrentProgram() { return currentProgram; }
    public void setCurrentProgram(String currentProgram) { this.currentProgram = currentProgram; }
}
