package com.aura.launcher.data

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.ContactsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** A contact found by drawer search. */
data class ContactHit(val name: String, val lookupUri: Uri, val number: String?)

/**
 * Finds contacts by name for drawer search, on the phone. Only works once the Contacts
 * permission has been granted (for Gemini calls); search never asks for it by itself.
 */
object ContactSearch {
    fun allowed(context: Context) = context.checkSelfPermission(Manifest.permission.READ_CONTACTS) == PackageManager.PERMISSION_GRANTED

    suspend fun find(context: Context, query: String, limit: Int = 3): List<ContactHit> = withContext(Dispatchers.IO) {
        if (query.isBlank() || query.length < 2 || !allowed(context)) return@withContext emptyList()
        val uri = Uri.withAppendedPath(ContactsContract.Contacts.CONTENT_FILTER_URI, Uri.encode(query.trim()))
        val cols = arrayOf(
            ContactsContract.Contacts._ID, ContactsContract.Contacts.LOOKUP_KEY,
            ContactsContract.Contacts.DISPLAY_NAME_PRIMARY, ContactsContract.Contacts.HAS_PHONE_NUMBER,
        )
        runCatching {
            context.contentResolver.query(uri, cols, null, null, null)?.use { c ->
                buildList {
                    while (c.moveToNext() && size < limit) {
                        val id = c.getLong(0)
                        val lookup = ContactsContract.Contacts.getLookupUri(id, c.getString(1))
                        val name = c.getString(2) ?: continue
                        val number = if (c.getInt(3) > 0) firstNumber(context, id) else null
                        add(ContactHit(name, lookup, number))
                    }
                }
            }.orEmpty()
        }.getOrDefault(emptyList())
    }

    private fun firstNumber(context: Context, contactId: Long): String? = runCatching {
        context.contentResolver.query(
            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
            arrayOf(ContactsContract.CommonDataKinds.Phone.NUMBER),
            "${ContactsContract.CommonDataKinds.Phone.CONTACT_ID} = ?", arrayOf(contactId.toString()),
            "${ContactsContract.CommonDataKinds.Phone.IS_SUPER_PRIMARY} DESC",
        )?.use { if (it.moveToFirst()) it.getString(0) else null }
    }.getOrNull()

    fun openIntent(hit: ContactHit) = Intent(Intent.ACTION_VIEW, hit.lookupUri)
    fun dialIntent(number: String) = Intent(Intent.ACTION_DIAL, Uri.fromParts("tel", number, null))
    fun messageIntent(number: String) = Intent(Intent.ACTION_SENDTO, Uri.fromParts("smsto", number, null))
}
