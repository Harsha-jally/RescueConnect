package com.rescueconnect.LocalStorage;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

public class SaveLocalData {
    Context context;
    SharedPreferences sharedPreferences;

    public SaveLocalData(Context context) {
        this.context = context;
        sharedPreferences = PreferenceManager.getDefaultSharedPreferences(context);
    }

    public void saveValue(String key, String value) {
        SharedPreferences.Editor editor = sharedPreferences.edit();
        editor.putString(key, value);
        editor.commit();
    }

    public String getValue(String key) {
        String value = sharedPreferences.getString(key, null);
        return value;
    }
}
