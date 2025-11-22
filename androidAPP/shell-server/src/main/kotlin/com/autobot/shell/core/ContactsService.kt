package com.autobot.shell.core

import android.content.Context
import android.provider.ContactsContract

/**
 * 联系人服务
 */
class ContactsService(private val context: Context) {
    
    /**
     * 获取所有联系人
     * @return 联系人列表
     */
    fun getAllContacts(): List<Map<String, String>> {
        return try {
            val contacts = mutableListOf<Map<String, String>>()
            val cursor = context.contentResolver.query(
                ContactsContract.Contacts.CONTENT_URI,
                null,
                null,
                null,
                ContactsContract.Contacts.DISPLAY_NAME + " ASC"
            )
            
            cursor?.use {
                val idColumn = it.getColumnIndex(ContactsContract.Contacts._ID)
                val nameColumn = it.getColumnIndex(ContactsContract.Contacts.DISPLAY_NAME)
                
                while (it.moveToNext()) {
                    val id = if (idColumn >= 0) it.getString(idColumn) else null
                    val name = if (nameColumn >= 0) it.getString(nameColumn) else "Unknown"
                    
                    // 获取电话号码
                    val phones = mutableListOf<String>()
                    if (id != null) {
                        val phoneCursor = context.contentResolver.query(
                            ContactsContract.CommonDataKinds.Phone.CONTENT_URI,
                            null,
                            ContactsContract.CommonDataKinds.Phone.CONTACT_ID + " = ?",
                            arrayOf(id),
                            null
                        )
                        phoneCursor?.use { pc ->
                            val phoneColumn = pc.getColumnIndex(ContactsContract.CommonDataKinds.Phone.NUMBER)
                            while (pc.moveToNext()) {
                                if (phoneColumn >= 0) {
                                    phones.add(pc.getString(phoneColumn))
                                }
                            }
                        }
                    }
                    
                    contacts.add(mapOf(
                        "id" to (id ?: ""),
                        "name" to name,
                        "phones" to phones.joinToString(",")
                    ))
                }
            }
            
            contacts
        } catch (e: Exception) {
            e.printStackTrace()
            emptyList()
        }
    }
    
    /**
     * 插入联系人
     * @param name 联系人姓名
     * @param phone 电话号码
     * @return 是否成功
     */
    fun insertContact(name: String, phone: String): Boolean {
        return try {
            val values = android.content.ContentValues().apply {
                put(ContactsContract.RawContacts.ACCOUNT_TYPE, null as String?)
                put(ContactsContract.RawContacts.ACCOUNT_NAME, null as String?)
            }
            
            val uri = context.contentResolver.insert(
                ContactsContract.RawContacts.CONTENT_URI,
                values
            ) ?: return false
            
            val rawContactId = android.net.Uri.parse(uri.toString()).lastPathSegment?.toLong()
                ?: return false
            
            // 插入姓名
            val nameValues = android.content.ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.StructuredName.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.StructuredName.DISPLAY_NAME, name)
            }
            context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, nameValues)
            
            // 插入电话号码
            val phoneValues = android.content.ContentValues().apply {
                put(ContactsContract.Data.RAW_CONTACT_ID, rawContactId)
                put(ContactsContract.Data.MIMETYPE, ContactsContract.CommonDataKinds.Phone.CONTENT_ITEM_TYPE)
                put(ContactsContract.CommonDataKinds.Phone.NUMBER, phone)
                put(ContactsContract.CommonDataKinds.Phone.TYPE, ContactsContract.CommonDataKinds.Phone.TYPE_MOBILE)
            }
            context.contentResolver.insert(ContactsContract.Data.CONTENT_URI, phoneValues)
            
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    /**
     * 删除联系人
     * @param contactId 联系人ID
     * @return 是否成功
     */
    fun deleteContact(contactId: String): Boolean {
        return try {
            val deleted = context.contentResolver.delete(
                ContactsContract.RawContacts.CONTENT_URI,
                ContactsContract.RawContacts._ID + " = ?",
                arrayOf(contactId)
            )
            deleted > 0
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
}

