package com.rescueconnect.Loader;

import android.app.ProgressDialog;
import android.content.Context;
import android.widget.Toast;

public class ShowLoader {
    Context context;
    ProgressDialog progressDialog;

    public ShowLoader(Context context) {
        this.context = context;
        progressDialog = new ProgressDialog(context);
    }

    public void showProgressDialog() {
        progressDialog.setMessage("Please wait..");
        progressDialog.setCanceledOnTouchOutside(false);
        progressDialog.show();
    }

    public void dismissDialog() {
        progressDialog.dismiss();
    }

    public void PresentToast(String message) {
        Toast.makeText(context, message, Toast.LENGTH_LONG).show();
    }
}
