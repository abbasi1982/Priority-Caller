package com.elham.priorityringer.presentation.addcontact

import android.content.Context
import android.net.Uri
import android.provider.ContactsContract
import timber.log.Timber

/** What the device contact picker handed back, once resolved to a number. */
sealed interface PickedContact {
    data class Resolved(val displayName: String, val number: String) : PickedContact

    /**
     * The picker returned a contact but its number could not be read — almost
     * always because `READ_CONTACTS` is not granted. Not an error to swallow:
     * the UI falls back to manual entry and shows what granting would fix.
     */
    data object NumberUnreadable : PickedContact
}

/**
 * Reads a phone number out of the URI returned by
 * [androidx.activity.result.contract.ActivityResultContracts.PickContact].
 *
 * Two URI shapes have to be handled. `PickContact` yields a *contact* URI, and
 * pivoting from it to the contact's phone rows is a second query that needs
 * `READ_CONTACTS`. Some pickers (and `ACTION_PICK` on `Phone.CONTENT_URI`)
 * instead return a *data-row* URI carrying a one-shot read grant, which needs
 * no permission at all. Trying the permission-free shape first means the common
 * case costs nothing, and `Capability.READ_CONTACTS` stays honestly optional —
 * it is `isRequired = false` in the domain model precisely because of this.
 *
 * Must be called off the main thread: both branches hit `ContentResolver`.
 */
object ContactUriResolver {

    private val DATA_ROW_PROJECTION = arrayOf(
        ContactsContract.CommonDataKinds.Phone.NUMBER,
        ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
    )

    fun resolve(context: Context, uri: Uri): PickedContact =
        readDataRow(context, uri) ?: readViaContactId(context, uri) ?: PickedContact.NumberUnreadable

    /** The permission-free path: the URI already points at a phone data row. */
    private fun readDataRow(context: Context, uri: Uri): PickedContact.Resolved? = runCatching {
        context.contentResolver.query(uri, DATA_ROW_PROJECTION, null, null, null)?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val number = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
            PickedContact.Resolved(
                displayName = cursor.getString(1).orEmpty(),
                number = number,
            )
        }
    }.getOrElse { error ->
        Timber.d(error, "Picked URI is not a readable phone data row")
        null
    }

    /** The `READ_CONTACTS` path: resolve the contact id, then its first number. */
    private fun readViaContactId(context: Context, uri: Uri): PickedContact.Resolved? = runCatching {
        val projection = arrayOf(ContactsContract.Contacts._ID, ContactsContract.Contacts.DISPLAY_NAME)

        var contactId: String? = null
        var displayName = ""
        context.contentResolver.query(uri, projection, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                contactId = cursor.getString(0)
                displayName = cursor.getString(1).orEmpty()
            }
        }
        val id = contactId ?: return@runCatching null

        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?",
            arrayOf(id),
            null,
        )?.use { cursor ->
            if (!cursor.moveToFirst()) return@use null
            val number = cursor.getString(0)?.takeIf { it.isNotBlank() } ?: return@use null
            PickedContact.Resolved(displayName = displayName, number = number)
        }
    }.getOrElse { error ->
        // SecurityException lands here when READ_CONTACTS is denied. Per
        // Architecture.md § A.4 a permission denial is data, not a crash.
        Timber.d(error, "Could not read contact number via contact id")
        null
    }
}
