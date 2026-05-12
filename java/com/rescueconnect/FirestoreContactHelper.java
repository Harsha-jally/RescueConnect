package com.rescueconnect.Contact;

import android.util.Log;

import com.google.firebase.firestore.FirebaseFirestore;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * FirestoreContactHelper
 * ─────────────────────────────────────────────────────────────────────────────
 * Replaces SQLite ContactsDB for storing emergency contacts.
 * Contacts are stored in Firestore under:
 *
 *   emergency_contacts/{userMobile}/contacts/{auto-id}
 *     → { name: "...", phone: "..." }
 *
 * Usage:
 *   FirestoreContactHelper helper = new FirestoreContactHelper("9876543210");
 *   helper.addContact("Ravi", "9876543211", success -> { ... });
 *   helper.getAllContacts(contacts -> { ... });
 */
public class FirestoreContactHelper {

    private static final String TAG              = "FirestoreContacts";
    private static final String COLLECTION       = "emergency_contacts";
    private static final String SUB_COLLECTION   = "contacts";

    private final FirebaseFirestore db;
    private final String            userMobile;   // document key = user's mobile

    public interface Callback<T> {
        void onResult(T result);
    }

    public FirestoreContactHelper(String userMobile) {
        this.db         = FirebaseFirestore.getInstance();
        this.userMobile = userMobile;
    }

    // ─── Add contact ──────────────────────────────────────────────────────────

    public void addContact(String name, String phone, Callback<Boolean> callback) {
        Map<String, Object> data = new HashMap<>();
        data.put("name",  name);
        data.put("phone", phone);

        db.collection(COLLECTION)
                .document(userMobile)
                .collection(SUB_COLLECTION)
                .add(data)
                .addOnSuccessListener(ref -> {
                    Log.d(TAG, "Contact added: " + name);
                    if (callback != null) callback.onResult(true);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to add contact: " + e.getMessage());
                    if (callback != null) callback.onResult(false);
                });
    }

    // ─── Get all contacts ─────────────────────────────────────────────────────

    public void getAllContacts(Callback<List<ContactModel>> callback) {
        db.collection(COLLECTION)
                .document(userMobile)
                .collection(SUB_COLLECTION)
                .get()
                .addOnSuccessListener(snapshot -> {
                    List<ContactModel> list = new ArrayList<>();
                    snapshot.getDocuments().forEach(doc -> {
                        String name  = doc.getString("name");
                        String phone = doc.getString("phone");
                        if (name != null && phone != null) {
                            list.add(new ContactModel(doc.getId(), name, phone));
                        }
                    });
                    if (callback != null) callback.onResult(list);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to get contacts: " + e.getMessage());
                    if (callback != null) callback.onResult(new ArrayList<>());
                });
    }

    // ─── Delete contact ───────────────────────────────────────────────────────

    public void deleteContact(String docId, Callback<Boolean> callback) {
        db.collection(COLLECTION)
                .document(userMobile)
                .collection(SUB_COLLECTION)
                .document(docId)
                .delete()
                .addOnSuccessListener(v -> {
                    if (callback != null) callback.onResult(true);
                })
                .addOnFailureListener(e -> {
                    Log.e(TAG, "Failed to delete contact: " + e.getMessage());
                    if (callback != null) callback.onResult(false);
                });
    }

    // ─── Migrate from SQLite ──────────────────────────────────────────────────

    /**
     * Call this once after app update to copy existing SQLite contacts to Firestore.
     * After migration completes successfully, you can stop using ContactsDB entirely.
     */
    public void migrateFromSQLite(List<ContactModel> sqliteContacts,
                                  Callback<Boolean> callback) {
        if (sqliteContacts == null || sqliteContacts.isEmpty()) {
            if (callback != null) callback.onResult(true);
            return;
        }

        final int[] remaining = {sqliteContacts.size()};
        final boolean[] hasError = {false};

        for (ContactModel contact : sqliteContacts) {
            Map<String, Object> data = new HashMap<>();
            data.put("name",  contact.getName());
            data.put("phone", contact.getPhone());

            db.collection(COLLECTION)
                    .document(userMobile)
                    .collection(SUB_COLLECTION)
                    .add(data)
                    .addOnCompleteListener(task -> {
                        if (!task.isSuccessful()) hasError[0] = true;
                        remaining[0]--;
                        if (remaining[0] == 0 && callback != null) {
                            callback.onResult(!hasError[0]);
                        }
                    });
        }
    }
}