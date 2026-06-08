package com.example.modelrouter.ui.adapters

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.modelrouter.databinding.ItemModelCardBinding
import com.example.modelrouter.models.ModelItem

class ModelAdapter(
    private val onAddClick: (ModelItem) -> Unit,
    private val onViewDetails: (String) -> Unit
) : ListAdapter<ModelItem, ModelAdapter.ModelViewHolder>(ModelDiffCallback()) {

    class ModelViewHolder(private val binding: ItemModelCardBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(model: ModelItem, onAddClick: (ModelItem) -> Unit, onViewDetails: (String) -> Unit) {
            binding.tvModelName.text = model.name
            binding.tvModelId.text = model.id
            binding.tvOwner.text = model.owner
            binding.tvSource.text = model.source

            binding.tvBadgeHot.visibility = if (model.hot) android.view.View.VISIBLE else android.view.View.GONE

            binding.btnAdd.setOnClickListener { onAddClick(model) }
            binding.btnViewDetails.setOnClickListener { onViewDetails(model.link) }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ModelViewHolder {
        val binding = ItemModelCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ModelViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ModelViewHolder, position: Int) {
        holder.bind(getItem(position), onAddClick, onViewDetails)
    }

    class ModelDiffCallback : DiffUtil.ItemCallback<ModelItem>() {
        override fun areItemsTheSame(oldItem: ModelItem, newItem: ModelItem): Boolean {
            return oldItem.id == newItem.id
        }

        override fun areContentsTheSame(oldItem: ModelItem, newItem: ModelItem): Boolean {
            return oldItem == newItem
        }
    }
}