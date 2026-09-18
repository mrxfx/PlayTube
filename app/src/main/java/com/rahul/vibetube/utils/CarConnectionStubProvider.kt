/*
 * VibeTube
 * Copyright (C) 2026 VibeTube contributors
 *
 * Licensed under GPL-3.0-or-later
 */
package com.rahul.vibetube.utils

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri

/**
 * Stub ContentProvider for authority "androidx.car.app.connection".
 * Media3 / CarApp libraries query this authority at runtime to check Android Auto status.
 * Registering this provider prevents OS ActivityThread from logging
 * "Failed to find provider info for androidx.car.app.connection" when running
 * on devices or emulators without an Android Auto system host app.
 */
class CarConnectionStubProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor {
        val cursor = MatrixCursor(arrayOf("car_connection_state"))
        // 0 corresponds to CarConnection.CONNECTION_TYPE_NOT_CONNECTED
        cursor.addRow(arrayOf(0))
        return cursor
    }

    override fun getType(uri: Uri): String = "vnd.android.cursor.item/car_connection_state"

    override fun insert(uri: Uri, values: ContentValues?): Uri? = null

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = 0
}
