package com.rescueconnect.Contact;

import android.content.ContentValues;
import android.content.Context;
import android.database.Cursor;
import android.database.DatabaseUtils;
import android.database.sqlite.SQLiteDatabase;
import android.database.sqlite.SQLiteOpenHelper;
import android.util.Log;

import java.util.ArrayList;
import java.util.List;

public class ContactsDB extends SQLiteOpenHelper {

    private static final String TAG = "ContactsDB";

    // Database metadata
    private static final String DATABASE_NAME = "Contacts.db";
    private static final int DATABASE_VERSION = 1;

    // Table & column names
    public static final String TABLE_CONTACTS       = "contacts";
    public static final String COLUMN_ID            = "id";
    public static final String COLUMN_NAME          = "name";
    public static final String COLUMN_PHONE         = "phone";

    // DDL
    private static final String CREATE_TABLE_CONTACTS =
            "CREATE TABLE " + TABLE_CONTACTS + " ("
                    + COLUMN_ID    + " INTEGER PRIMARY KEY AUTOINCREMENT, "
                    + COLUMN_NAME  + " TEXT NOT NULL, "
                    + COLUMN_PHONE + " TEXT NOT NULL"
                    + ")";

    // Singleton
    private static volatile ContactsDB instance;

    public static synchronized ContactsDB getInstance(Context context) {
        if (instance == null) {
            instance = new ContactsDB(context.getApplicationContext());
        }
        return instance;
    }

    public ContactsDB(Context context) {
        super(context, DATABASE_NAME, null, DATABASE_VERSION);
    }

    // ─── Lifecycle ────────────────────────────────────────────────────────────

    @Override
    public void onCreate(SQLiteDatabase db) {
        db.execSQL(CREATE_TABLE_CONTACTS);
    }

    @Override
    public void onUpgrade(SQLiteDatabase db, int oldVersion, int newVersion) {
        db.execSQL("DROP TABLE IF EXISTS " + TABLE_CONTACTS);
        onCreate(db);
    }

    // ─── CRUD ─────────────────────────────────────────────────────────────────

    /**
     * Inserts a new contact.
     *
     * @return the row ID of the newly inserted row, or -1 on failure.
     */
    public long insertContact(String name, String phone) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COLUMN_NAME, name);
        cv.put(COLUMN_PHONE, phone);
        long rowId = db.insert(TABLE_CONTACTS, null, cv);
        if (rowId == -1) {
            Log.e(TAG, "insertContact: failed for name=" + name);
        }
        return rowId;
    }

    /**
     * Returns a single contact by ID, or {@code null} if not found.
     */
    public ContactModel getContact(int id) {
        SQLiteDatabase db = this.getReadableDatabase();
        // Parameterised query — no SQL injection risk
        Cursor cursor = db.query(
                TABLE_CONTACTS,
                null,
                COLUMN_ID + " = ?",
                new String[]{String.valueOf(id)},
                null, null, null
        );
        if (cursor == null) return null;
        try {
            if (!cursor.moveToFirst()) return null;
            return cursorToContact(cursor);
        } finally {
            cursor.close();
        }
    }

    /**
     * Returns every contact in the table.
     */
    public List<ContactModel> getAllContacts() {
        List<ContactModel> contacts = new ArrayList<>();
        SQLiteDatabase db = this.getReadableDatabase();
        Cursor cursor = db.query(TABLE_CONTACTS, null, null, null, null, null, COLUMN_NAME + " ASC");
        if (cursor == null) return contacts;
        try {
            while (cursor.moveToNext()) {
                contacts.add(cursorToContact(cursor));
            }
        } finally {
            cursor.close();
        }
        return contacts;
    }

    /**
     * Updates name and phone for the given contact ID.
     *
     * @return number of rows affected (0 means nothing was updated).
     */
    public int updateContact(int id, String name, String phone) {
        SQLiteDatabase db = this.getWritableDatabase();
        ContentValues cv = new ContentValues();
        cv.put(COLUMN_NAME, name);
        cv.put(COLUMN_PHONE, phone);
        return db.update(TABLE_CONTACTS, cv, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
    }

    /**
     * Deletes a contact by ID.
     *
     * @return number of rows deleted.
     */
    public int deleteContact(int id) {
        SQLiteDatabase db = this.getWritableDatabase();
        return db.delete(TABLE_CONTACTS, COLUMN_ID + " = ?", new String[]{String.valueOf(id)});
    }

    /**
     * Returns the total number of contacts stored.
     */
    public long getContactCount() {
        SQLiteDatabase db = this.getReadableDatabase();
        return DatabaseUtils.queryNumEntries(db, TABLE_CONTACTS);
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Maps the current cursor row to a {@link ContactModel}. */
    private ContactModel cursorToContact(Cursor cursor) {
        String id    = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_ID));
        String name  = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_NAME));
        String phone = cursor.getString(cursor.getColumnIndexOrThrow(COLUMN_PHONE));
        return new ContactModel(id, name, phone);
    }
}