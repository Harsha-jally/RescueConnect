package com.rescueconnect;

import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.TextView;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.ArrayList;
import java.util.List;

public class AlertAdapter extends ArrayAdapter<MessageModel> {

    public interface OnAlertActionListener {
        void onNavigate(MessageModel alert);
        void onImGoing(MessageModel alert, int position);
        void onIHelped(MessageModel alert, int position);
        void onCantGo(MessageModel alert, int position);
        void onCallUser(MessageModel alert);
    }

    private final OnAlertActionListener listener;
    private final String currentVolunteerName;

    public AlertAdapter(Context context,
                        ArrayList<MessageModel> alerts,
                        String currentVolunteerName,
                        OnAlertActionListener listener) {
        super(context, 0, alerts);
        this.currentVolunteerName = currentVolunteerName;
        this.listener             = listener;
    }

    @NonNull
    @Override
    public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
        if (convertView == null) {
            convertView = LayoutInflater.from(getContext())
                    .inflate(R.layout.item_alert, parent, false);
        }

        MessageModel alert = getItem(position);
        if (alert == null) return convertView;

        TextView alertMessage   = convertView.findViewById(R.id.alertMessage);
        TextView respondersText = convertView.findViewById(R.id.respondersText);
        TextView statusText     = convertView.findViewById(R.id.statusText);
        Button   navigateBtn    = convertView.findViewById(R.id.navigateBtn);
        Button   imGoingBtn     = convertView.findViewById(R.id.imGoingBtn);
        Button   iHelpedBtn     = convertView.findViewById(R.id.iHelpedBtn);
        Button   cantGoBtn      = convertView.findViewById(R.id.cantGoBtn);
        Button   callUserBtn    = convertView.findViewById(R.id.callUserBtn);

        alertMessage.setText(alert.getMessage());

        // Show responders
        List<String> responders = alert.getResponders();
        if (responders != null && !responders.isEmpty()) {
            respondersText.setVisibility(View.VISIBLE);
            respondersText.setText("🙋 Going: " + String.join(", ", responders));
        } else {
            respondersText.setVisibility(View.GONE);
        }

        // Show resolved status
        if (alert.isResolved()) {
            statusText.setVisibility(View.VISIBLE);
            statusText.setText("✅ Resolved by: " + (alert.getResolvedBy() != null
                    ? alert.getResolvedBy() : "Unknown"));
        } else {
            statusText.setVisibility(View.GONE);
        }

        // I'm Going button state
        boolean alreadyResponded = responders != null
                && responders.contains(currentVolunteerName);
        boolean resolved = alert.isResolved();

        if (resolved) {
            imGoingBtn.setText("✅ Resolved");
            imGoingBtn.setEnabled(false);
            imGoingBtn.setAlpha(0.5f);
        } else if (alreadyResponded) {
            imGoingBtn.setText("✅ You're Going");
            imGoingBtn.setEnabled(false);
            imGoingBtn.setAlpha(0.6f);
        } else {
            imGoingBtn.setText("🙋 I'm Going");
            imGoingBtn.setEnabled(true);
            imGoingBtn.setAlpha(1.0f);
        }

        // Show "I Helped" only if this volunteer is going AND alert is not yet resolved
        if (alreadyResponded && !resolved) {
            iHelpedBtn.setVisibility(View.VISIBLE);
            iHelpedBtn.setOnClickListener(v -> listener.onIHelped(alert, position));
        } else {
            iHelpedBtn.setVisibility(View.GONE);
        }

        // Show "I Can't Go" only if this volunteer already committed AND alert is not resolved
        if (alreadyResponded && !resolved) {
            cantGoBtn.setVisibility(View.VISIBLE);
            cantGoBtn.setOnClickListener(v -> listener.onCantGo(alert, position));
        } else {
            cantGoBtn.setVisibility(View.GONE);
        }

        // Show "Call User" only when the volunteer has committed to going and alert is still active
        // Lets the volunteer call the person in distress directly before arriving
        if (alreadyResponded && !resolved) {
            callUserBtn.setVisibility(View.VISIBLE);
            callUserBtn.setOnClickListener(v -> listener.onCallUser(alert));
        } else {
            callUserBtn.setVisibility(View.GONE);
        }

        navigateBtn.setOnClickListener(v -> listener.onNavigate(alert));
        imGoingBtn.setOnClickListener(v -> {
            if (!alreadyResponded && !resolved)
                listener.onImGoing(alert, position);
        });

        return convertView;
    }
}