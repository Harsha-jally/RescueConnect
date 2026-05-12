package com.rescueconnect.Util;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.widget.Toast;

import androidx.core.app.ActivityCompat;

/**
 * Created by HOME on 5/9/2017.
 */
public class CheckInternet {

    public static boolean isNetworkAvailable(Context ctx) {

        NetworkInfo activeNetworkInfo = null;
        if (ActivityCompat.checkSelfPermission(ctx, Manifest.permission.ACCESS_NETWORK_STATE) != PackageManager.PERMISSION_GRANTED) {

        } else {
            ConnectivityManager connectivityManager = (ConnectivityManager) ctx.getSystemService(Context.CONNECTIVITY_SERVICE);
            activeNetworkInfo = connectivityManager
                    .getActiveNetworkInfo();
        }
        return activeNetworkInfo != null && activeNetworkInfo.isConnected();
    }

    public static void display(Context ctx) {
        Toast.makeText(ctx, "Please check your internet connection.", Toast.LENGTH_LONG).show();

    }

}
