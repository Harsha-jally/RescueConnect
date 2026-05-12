package com.rescueconnect;

public class volunteerModel {

    String name;
    String mobile;
    Double latitude;   // Boxed — deserializes as null if missing, never silently 0.0
    Double longitude;  // Boxed — deserializes as null if missing, never silently 0.0
    String email;
    String fcmToken;   // Pushy device token — updated on every login
    String status;     // "pending" or "approved" — set by admin in Firestore

    public volunteerModel() {}

    public volunteerModel(String name, String mobile,
                          double latitude, double longitude, String email) {
        this.name      = name;
        this.mobile    = mobile;
        this.latitude  = latitude;
        this.longitude = longitude;
        this.email     = email;
        this.fcmToken  = "";
        this.status    = "pending";
    }

    // ── Getters ───────────────────────────────────────────────────────────────
    public String getName()      { return name; }
    public String getMobile()    { return mobile; }
    public Double getLatitude()  { return latitude; }
    public Double getLongitude() { return longitude; }
    public String getEmail()     { return email; }
    public String getFcmToken()  { return fcmToken; }
    public String getStatus()    { return status; }

    // ── Setters ───────────────────────────────────────────────────────────────
    public void setName(String name)           { this.name = name; }
    public void setMobile(String mobile)       { this.mobile = mobile; }
    public void setLatitude(Double latitude)   { this.latitude = latitude; }
    public void setLongitude(Double longitude) { this.longitude = longitude; }
    public void setEmail(String email)         { this.email = email; }
    public void setFcmToken(String fcmToken)   { this.fcmToken = fcmToken; }
    public void setStatus(String status)       { this.status = status; }
}