package com.rescueconnect;

import android.os.Bundle;
import android.widget.ListView;

import androidx.appcompat.app.AppCompatActivity;

import com.rescueconnect.Contact.ContactAdapter;
import com.rescueconnect.Contact.ContactModel;
import com.rescueconnect.Contact.ContactsDB;

import java.util.List;

public class ViewContactsActivity extends AppCompatActivity {

    private ListView contactListView;
    private ContactsDB contactsDB;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_view_contacts);

        // BUG FIX: use singleton
        contactsDB = ContactsDB.getInstance(this);

        contactListView = findViewById(R.id.contactList);

        // BUG FIX: removed redundant `new ArrayList<>()` that was immediately overwritten.
        // BUG FIX: getAllCotacts() renamed to getAllContacts()
        List<ContactModel> contactModelList = contactsDB.getAllContacts();

        ContactAdapter contactAdapter = new ContactAdapter(this, contactModelList);
        contactListView.setAdapter(contactAdapter);
    }
}