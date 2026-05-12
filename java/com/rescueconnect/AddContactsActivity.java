package com.rescueconnect;

import android.app.AlertDialog;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import android.provider.ContactsContract;
import android.util.Log;
import android.view.LayoutInflater;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;

import com.rescueconnect.Contact.ContactModel;
import com.rescueconnect.Contact.ContactsDB;

import java.util.List;

public class AddContactsActivity extends AppCompatActivity {

    private static final String TAG = "AddContactsActivity";

    private ContactsDB contactsDB;

    private String contactNameString   = null;
    private String contactNumberString = null;

    private EditText contactEdit;
    private EditText mobileNumberEdit;
    private ListView emergencyContactList;

    // ─── Contact picker launcher ──────────────────────────────────────────────

    private final ActivityResultLauncher<Intent> contactPickerLauncher =
            registerForActivityResult(
                    new ActivityResultContracts.StartActivityForResult(),
                    result -> {
                        if (result.getResultCode() == RESULT_OK && result.getData() != null) {
                            Uri contactData = result.getData().getData();
                            if (contactData == null) return;

                            try (Cursor cursor = getContentResolver().query(
                                    contactData,
                                    new String[]{
                                            ContactsContract.Contacts.DISPLAY_NAME,
                                            ContactsContract.CommonDataKinds.Phone.NUMBER
                                    },
                                    null, null, null)) {

                                if (cursor != null && cursor.moveToFirst()) {
                                    contactNameString = cursor.getString(
                                            cursor.getColumnIndexOrThrow(
                                                    ContactsContract.Contacts.DISPLAY_NAME));
                                    contactNumberString = cursor.getString(
                                            cursor.getColumnIndexOrThrow(
                                                    ContactsContract.CommonDataKinds.Phone.NUMBER));
                                    contactEdit.setText(contactNameString);
                                }
                            }
                        }
                    });

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_add_contacts);

        contactsDB           = ContactsDB.getInstance(this);
        contactEdit          = findViewById(R.id.contactEdit);
        mobileNumberEdit     = findViewById(R.id.mobileEdit);
        emergencyContactList = findViewById(R.id.emergencyContactList);

        refreshContactList();
    }

    // ─── Pick from phonebook ──────────────────────────────────────────────────

    public void contactGettingImplementation(View view) {
        try {
            Intent intent = new Intent(Intent.ACTION_PICK);
            intent.setDataAndType(
                    Uri.parse("content://contacts"),
                    ContactsContract.CommonDataKinds.Phone.CONTENT_TYPE);
            contactPickerLauncher.launch(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to open contact picker", e);
            Toast.makeText(this, "Could not open contacts", Toast.LENGTH_SHORT).show();
        }
    }

    // ─── Submit ───────────────────────────────────────────────────────────────

    public void submitImplementation(View view) {
        boolean added = false;

        if (contactNameString != null && contactNumberString != null) {
            contactsDB.insertContact(contactNameString, contactNumberString);
            contactEdit.setText("");
            contactNameString   = null;
            contactNumberString = null;
            added = true;
        }

        String manual = mobileNumberEdit.getText().toString().trim();
        if (!manual.isEmpty()) {
            contactsDB.insertContact("Unknown", manual);
            mobileNumberEdit.setText("");
            added = true;
        }

        if (added) {
            Toast.makeText(this, "Contact added successfully", Toast.LENGTH_SHORT).show();
            refreshContactList();
        } else {
            Toast.makeText(this, "Please pick a contact or enter a number", Toast.LENGTH_SHORT).show();
        }
    }

    // ─── Contacts list ────────────────────────────────────────────────────────

    private void refreshContactList() {
        List<ContactModel> contacts = contactsDB.getAllContacts();
        emergencyContactList.setAdapter(new ContactListAdapter(contacts));
    }

    // ─── Edit dialog ──────────────────────────────────────────────────────────

    private void showEditDialog(ContactModel contact) {
        final EditText editName  = new EditText(this);
        final EditText editPhone = new EditText(this);
        editName.setHint("Name");
        editPhone.setHint("Phone number");
        editPhone.setInputType(android.text.InputType.TYPE_CLASS_PHONE);
        editName.setText(contact.getName());
        editPhone.setText(contact.getPhone());

        int pad = (int) (16 * getResources().getDisplayMetrics().density);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(pad, pad, pad, pad);

        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = pad;
        layout.addView(editName, params);
        layout.addView(editPhone, new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));

        new AlertDialog.Builder(this)
                .setTitle("Edit Contact")
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newName  = editName.getText().toString().trim();
                    String newPhone = editPhone.getText().toString().trim();
                    if (newName.isEmpty() || newPhone.isEmpty()) {
                        Toast.makeText(this, "Name and phone cannot be empty",
                                Toast.LENGTH_SHORT).show();
                        return;
                    }
                    contactsDB.updateContact(Integer.parseInt(contact.getId()), newName, newPhone);
                    Toast.makeText(this, "Contact updated", Toast.LENGTH_SHORT).show();
                    refreshContactList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─── Delete confirmation ──────────────────────────────────────────────────

    private void showDeleteConfirm(ContactModel contact) {
        new AlertDialog.Builder(this)
                .setTitle("Delete Contact")
                .setMessage("Remove " + contact.getName() + " from emergency contacts?")
                .setPositiveButton("Delete", (dialog, which) -> {
                    contactsDB.deleteContact(Integer.parseInt(contact.getId()));
                    Toast.makeText(this, "Contact deleted", Toast.LENGTH_SHORT).show();
                    refreshContactList();
                })
                .setNegativeButton("Cancel", null)
                .show();
    }

    // ─── Inline adapter ───────────────────────────────────────────────────────

    private class ContactListAdapter extends ArrayAdapter<ContactModel> {

        ContactListAdapter(List<ContactModel> items) {
            super(AddContactsActivity.this, 0, items);
        }

        @NonNull
        @Override
        public View getView(int position, @Nullable View convertView, @NonNull ViewGroup parent) {
            if (convertView == null) {
                convertView = LayoutInflater.from(getContext())
                        .inflate(R.layout.contact_list_item, parent, false);
            }

            ContactModel contact = getItem(position);
            if (contact == null) return convertView;

            ((TextView) convertView.findViewById(R.id.contactName)).setText(contact.getName());
            ((TextView) convertView.findViewById(R.id.contactPhone)).setText(contact.getPhone());
            convertView.findViewById(R.id.btnEdit).setOnClickListener(
                    v -> showEditDialog(contact));
            convertView.findViewById(R.id.btnDelete).setOnClickListener(
                    v -> showDeleteConfirm(contact));

            return convertView;
        }
    }
}