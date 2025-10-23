package com.desperatio.curlresolver

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import androidx.core.graphics.toColorInt

class DomainStatusAdapter :
    ListAdapter<DomainStatus, DomainStatusAdapter.Holder>(Diff) {

    object Diff : DiffUtil.ItemCallback<DomainStatus>() {
        override fun areItemsTheSame(oldItem: DomainStatus, newItem: DomainStatus): Boolean =
            oldItem.domain.equals(newItem.domain, ignoreCase = true)

        override fun areContentsTheSame(oldItem: DomainStatus, newItem: DomainStatus): Boolean =
            oldItem == newItem
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): Holder {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_domain_status, parent, false)
        return Holder(view)
    }

    override fun onBindViewHolder(holder: Holder, position: Int) {
        val item = getItem(position)
        holder.domain.text = item.domain

        var text: String
        var color: Int
        when (item.state) {
            Status.PENDING -> {
                text = "Ожидание..."
                color = "#444444".toColorInt()
            }
            Status.RUNNING -> {
                text = "Выполняется..."
                color = "#1565C0".toColorInt()
            }
            Status.SUCCESS -> {
                val b = StringBuilder("OK")
                if (item.code != null) b.append(" (HTTP ").append(item.code).append(")")
                if (item.durationMs != null) b.append(", ").append(item.durationMs).append(" мс")
                text = b.toString()
                color = "#2E7D32".toColorInt()
            }
            Status.FAIL -> {
                val b = StringBuilder("Ошибка")
                if (item.error != null) b.append(": ").append(item.error)
                if (item.durationMs != null) b.append(" (").append(item.durationMs).append(" мс)")
                text = b.toString()
                color = "#C62828".toColorInt()
            }
        }
        holder.status.text = text
        holder.status.setTextColor(color)
    }

    fun replaceAll(list: List<DomainStatus>) {
        submitList(ArrayList(list))
    }


    class Holder(view: View) : RecyclerView.ViewHolder(view) {
        val domain: TextView = view.findViewById(R.id.domainText)
        val status: TextView = view.findViewById(R.id.statusText)
    }
}
