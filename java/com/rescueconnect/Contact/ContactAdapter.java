package com.rescueconnect.Contact;

import android.app.AlertDialog;
import android.content.Context;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.BaseAdapter;
import android.widget.TextView;
import android.widget.Toast;

import com.rescueconnect.R;

import java.util.List;

public class ContactAdapter extends BaseAdapter {

    Context            context;
    List<ContactModel> contactModelList;

    public ContactAdapter(Context context, List<ContactModel> contactModelList) {
        this.context          = context;
        this.contactModelList = contactModelList;
    }

    @Override
    public int getCount() {
        return contactModelList.size();
    }

    // FIX 3: return the actual item and ID
    @Override
    public Object getItem(int i) {
        return contactModelList.get(i);
    }

    @Override
    public long getItemId(int i) {
        return Long.parseLong(contactModelList.get(i).getId());
    }

    @Override
    public View getView(int i, View view, ViewGroup viewGroup) {

        LayoutInflater layoutInflater =
                (LayoutInflater) context.getSystemService(Context.LAYOUT_INFLATER_SERVICE);

        view = layoutInflater.inflate(R.layout.contact, null);

        TextView contactnameText = view.findViewById(R.id.contactName);
        contactnameText.setText(contactModelList.get(i).getName());

        view.setTag(i); // store list index as tag (not the DB id)

        view.setOnClickListener(clickedView -> {

            int listIndex = (int) clickedView.getTag();
            ContactModel contact = contactModelList.get(listIndex);

            AlertDialog.Builder alertBuilder = new AlertDialog.Builder(context);
            alertBuilder.setMessage("Do you want to delete \"" + contact.getName() + "\"?");

            // FIX 2: Positive = confirm destructive action, Negative = cancel
            alertBuilder.setPositiveButton("Delete", (dialogInterface, w) -> {
                int dbId = Integer.parseInt(contact.getId());

                // FIX 4: use singleton instead of new instance
                ContactsDB.getInstance(context).deleteContact(dbId);

                // FIX 1: remove from list and refresh the view
                contactModelList.remove(listIndex);
                notifyDataSetChanged();

                Toast.makeText(context, "Contact deleted successfully", Toast.LENGTH_SHORT).show();
            });

            alertBuilder.setNegativeButton("Cancel", null);

            alertBuilder.show();
        });

        return view;
    }
}