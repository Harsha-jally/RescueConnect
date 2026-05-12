package com.rescueconnect;

import com.google.firebase.firestore.Exclude;

import java.util.ArrayList;
import java.util.List;

public class MessageModel {

    String lat;
    String lon;
    String location;
    String message;
    String date;
    String senderName;
    String senderMobile;

    // Volunteers who tapped "I'm Going"
    List<String> responders = new ArrayList<>();

    // Set to true when a volunteer taps "I Helped"
    boolean resolved   = false;
    String  resolvedBy = "";   // name of volunteer who resolved it

    @Exclude
    String firestoreId;

    public MessageModel() {}

    public MessageModel(String lat, String lon, String location,
                        String message, String date,
                        String senderName, String senderMobile) {
        this.lat          = lat;
        this.lon          = lon;
        this.location     = location;
        this.message      = message;
        this.date         = date;
        this.senderName   = senderName;
        this.senderMobile = senderMobile;
    }

    public MessageModel(String lat, String lon, String location,
                        String message, String date) {
        this(lat, lon, location, message, date, "", "");
    }

    // Getters
    public String getLat()              { return lat; }
    public String getLon()              { return lon; }
    public String getLocation()         { return location; }
    public String getMessage()          { return message; }
    public String getDate()             { return date; }
    public String getSenderName()       { return senderName; }
    public String getSenderMobile()     { return senderMobile; }
    public List<String> getResponders() { return responders; }
    public boolean isResolved()         { return resolved; }
    public String getResolvedBy()       { return resolvedBy; }

    @Exclude
    public String getFirestoreId()      { return firestoreId; }

    // Setters
    public void setLat(String lat)                   { this.lat = lat; }
    public void setLon(String lon)                   { this.lon = lon; }
    public void setLocation(String location)         { this.location = location; }
    public void setMessage(String message)           { this.message = message; }
    public void setDate(String date)                 { this.date = date; }
    public void setSenderName(String senderName)     { this.senderName = senderName; }
    public void setSenderMobile(String senderMobile) { this.senderMobile = senderMobile; }
    public void setResponders(List<String> r)        { this.responders = r; }
    public void setResolved(boolean resolved)        { this.resolved = resolved; }
    public void setResolvedBy(String resolvedBy)     { this.resolvedBy = resolvedBy; }

    @Exclude
    public void setFirestoreId(String id)            { this.firestoreId = id; }
}