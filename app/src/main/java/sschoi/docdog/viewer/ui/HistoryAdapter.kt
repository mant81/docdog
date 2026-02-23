package sschoi.docdog.viewer.ui

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageButton
import android.widget.TextView
import androidx.recyclerview.widget.RecyclerView
import sschoi.docdog.viewer.R
import sschoi.docdog.viewer.data.HistoryItem
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class HistoryAdapter(
    private var items: List<HistoryItem>,
    val onItemClick: (HistoryItem) -> Unit,
    val onDeleteClick: (HistoryItem) -> Unit
) : RecyclerView.Adapter<HistoryAdapter.ViewHolder>() {

    fun updateData(newItems: List<HistoryItem>) {
        this.items = newItems
        notifyDataSetChanged()
    }

    class ViewHolder(view: View) : RecyclerView.ViewHolder(view) {
        val tvFileName: TextView = view.findViewById(R.id.tvFileName)
        val tvExpireTime: TextView = view.findViewById(R.id.tvExpireTime)
        val tvStatus: TextView = view.findViewById(R.id.tvStatus)
        val btnView: Button = view.findViewById(R.id.btnView)
        val btnDelete: ImageButton = view.findViewById(R.id.btnDelete)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ViewHolder {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_history, parent, false)
        return ViewHolder(view)
    }

    override fun onBindViewHolder(holder: ViewHolder, position: Int) {
        val item = items[position]
        holder.tvFileName.text = item.fileName
        
        val expiryMillis = item.createdAt + item.expireOption.duration.inWholeMilliseconds
        val sdf = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
        holder.tvExpireTime.text = "만료: ${sdf.format(Date(expiryMillis))}"
        
        val isExpired = System.currentTimeMillis() > expiryMillis
        if (isExpired) {
            holder.tvStatus.visibility = View.VISIBLE
            holder.btnView.isEnabled = false
            holder.btnView.alpha = 0.5f
        } else {
            holder.tvStatus.visibility = View.GONE
            holder.btnView.isEnabled = true
            holder.btnView.alpha = 1.0f
        }

        holder.btnView.setOnClickListener { onItemClick(item) }
        holder.btnDelete.setOnClickListener { onDeleteClick(item) }
    }

    override fun getItemCount() = items.size
}
