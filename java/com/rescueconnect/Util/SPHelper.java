package com.rescueconnect.Util;

import android.content.Context;
import android.content.SharedPreferences;
import android.preference.PreferenceManager;

/**
 * Created by HOME on 5/9/2017.
 */
public class SPHelper {

   static SharedPreferences sharedPreferences;

    public static void SaveData(Context mContext,String key,String value)
    {
        sharedPreferences= PreferenceManager.getDefaultSharedPreferences(mContext);
        SharedPreferences.Editor editor=sharedPreferences.edit();
        editor.putString(key,value);
        editor.commit();
    }

    public static String GetData(Context mContext,String key)
    {
        sharedPreferences= PreferenceManager.getDefaultSharedPreferences(mContext);
        String data=sharedPreferences.getString(key,null);

        return data;
    }


}
