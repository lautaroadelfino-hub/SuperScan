package com.example.ui.screens

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import com.example.data.ReceiptEntity
import java.io.File
import java.io.FileWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun exportToCsvAndShare(context: Context, receipts: List<ReceiptEntity>) {
    val file = File(context.cacheDir, "gastos_historicos.csv")
    val writer = FileWriter(file)
    writer.append("Fecha,Supermercado,Categoria,Producto,Cantidad,Precio,Total Ticket\n")
    
    for (receipt in receipts) {
        val dateStr = receipt.date
        if (receipt.items.isEmpty()) {
            writer.append("${dateStr},\"${receipt.storeName}\",,,,${receipt.totalAmount}\n")
        } else {
            for (item in receipt.items) {
                writer.append("${dateStr},\"${receipt.storeName}\",\"${item.category}\",\"${item.productName}\",${item.quantity},${item.totalPrice},${receipt.totalAmount}\n")
            }
        }
    }
    writer.flush()
    writer.close()

    val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
    val intent = Intent(Intent.ACTION_SEND).apply {
        type = "text/csv"
        putExtra(Intent.EXTRA_SUBJECT, "Gastos Históricos")
        putExtra(Intent.EXTRA_STREAM, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    context.startActivity(Intent.createChooser(intent, "Compartir CSV"))
}
