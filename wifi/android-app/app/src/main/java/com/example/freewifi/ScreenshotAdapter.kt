package com.example.freewifi

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import java.io.File

class ScreenshotAdapter(
    private val context: Context,
    private var files: MutableList<File>,
    private val onShare: (File) -> Unit,
    private val onDelete: (File) -> Unit
) : RecyclerView.Adapter<ScreenshotAdapter.VH>() {

    class VH(view: View) : RecyclerView.ViewHolder(view) {
        val ivThumb: ImageView = view.findViewById(R.id.ivThumb)
        val tvName: TextView = view.findViewById(R.id.tvName)
        val btnShare: Button = view.findViewById(R.id.btnShare)
        val btnDelete: Button = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_screenshot, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val file = files[position]
        holder.tvName.text = file.name
        val bmp = BitmapFactory.decodeFile(file.absolutePath)
        holder.ivThumb.setImageBitmap(bmp)
        holder.btnShare.setOnClickListener { onShare(file) }
        holder.btnDelete.setOnClickListener {
            onDelete(file)
            // optimistic remove
            files.removeAt(position)
            notifyItemRemoved(position)
        }
    }

    override fun getItemCount(): Int = files.size

    fun updateFiles(newFiles: List<File>) {
        files.clear()
        files.addAll(newFiles)
        notifyDataSetChanged()
    }
}
