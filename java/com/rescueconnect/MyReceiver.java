package com.rescueconnect;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.telephony.SmsManager;
import android.util.Log;
import android.widget.Toast;

import com.rescueconnect.Contact.ContactModel;
import com.rescueconnect.Contact.ContactsDB;
import com.rescueconnect.LocalStorage.SaveLocalData;
import com.rescueconnect.Util.Constants;
import com.rescueconnect.Util.SPHelper;

import java.util.List;

public class MyReceiver extends BroadcastReceiver {

    private static final String TAG = "MyReceiver";

    // BUG FIX: instance variable would reset every time the OS recreates the receiver.
    // static ensures the count survives across onReceive calls within the same process.
    private static int clickCount = 0;

    @Override
    public void onReceive(Context context, Intent intent) {
        SaveLocalData saveLocalData = new SaveLocalData(context);

        // BUG FIX: use singleton instead of creating a new ContactsDB each broadcast
        ContactsDB contactsDB = ContactsDB.getInstance(context);

        String locationUri = "http://maps.google.com/maps?saddr=&daddr="
                + saveLocalData.getValue("LATITUDE")
                + ","
                + saveLocalData.getValue("LOGITUDE");

        clickCount++;

        if (clickCount == 3) {
            String message = SPHelper.GetData(context, Constants.SP_EMERGENCY_MESSAGE);

            // BUG FIX: getAllCotacts() renamed to getAllContacts()
            List<ContactModel> contactModelsList = contactsDB.getAllContacts();

            if (!contactModelsList.isEmpty()) {
                SmsManager manager = SmsManager.getDefault();
                for (ContactModel contact : contactModelsList) {
                    manager.sendTextMessage(
                            contact.getPhone(), null,
                            message + " " + locationUri,
                            null, null
                    );
                    // BUG FIX: replaced System.out.println with proper logging
                    Log.d(TAG, "SMS sent to: " + contact.getPhone() + " | URI: " + locationUri);
                }
            } else {
                Toast.makeText(context, "No contacts added", Toast.LENGTH_LONG).show();
            }

            clickCount = 0;
        }
    }
}