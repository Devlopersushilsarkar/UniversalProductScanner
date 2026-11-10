package com.universalproductscanner

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper

data class Product(val barcode: String, val name: String, val price: String)

class ProductDb(context: Context) : SQLiteOpenHelper(context, "products.db", null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE products (barcode TEXT PRIMARY KEY, name TEXT NOT NULL, price TEXT NOT NULL)")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun find(barcode: String): Product? {
        readableDatabase.rawQuery(
            "SELECT barcode, name, price FROM products WHERE barcode = ?", arrayOf(barcode)
        ).use { c ->
            if (c.moveToFirst()) return Product(c.getString(0), c.getString(1), c.getString(2))
        }
        return null
    }
    fun save(product: Product) {
        val values = ContentValues().apply {
            put("barcode", product.barcode); put("name", product.name); put("price", product.price)
        }
        writableDatabase.insertWithOnConflict("products", null, values, SQLiteDatabase.CONFLICT_REPLACE)
    }
}
