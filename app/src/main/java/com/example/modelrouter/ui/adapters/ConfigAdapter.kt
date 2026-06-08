package com.example.modelrouter.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.example.modelrouter.databinding.ItemConfigGroupBinding
import com.example.modelrouter.databinding.ItemConfigModelBinding
import com.example.modelrouter.models.ConfigModelItem
import com.example.modelrouter.models.GroupItem
import com.example.modelrouter.service.ProviderManager

class ConfigGroupAdapter(
    private val onReplaceClick: (String, String, String) -> Unit,
    private val onDeleteClick: (String, String) -> Unit,
    private val onAddClick: (String) -> Unit,
    private val onToggleEnabled: (String, String, Boolean) -> Unit,
    private val onRenameClick: (String, String) -> Unit,
    private val onDeleteGroupClick: (String) -> Unit,
    private val onToggleGroupEnabled: (String, Boolean) -> Unit
) : ListAdapter<GroupItem, ConfigGroupAdapter.GroupViewHolder>(GroupDiffCallback()) {

    class GroupViewHolder(private val binding: ItemConfigGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(group: GroupItem, onReplaceClick: (String, String, String) -> Unit, onDeleteClick: (String, String) -> Unit, onAddClick: (String) -> Unit, onToggleEnabled: (String, String, Boolean) -> Unit, onRenameClick: (String, String) -> Unit, onDeleteGroupClick: (String) -> Unit, onToggleGroupEnabled: (String, Boolean) -> Unit) {
            binding.tvGroupName.text = group.name
            binding.tvGroupDesc.text = group.description
            binding.tvPort.text = "端口: ${group.port}"

            binding.switchGroupEnabled.setOnCheckedChangeListener(null)
            binding.switchGroupEnabled.isChecked = group.enabled
            binding.switchGroupEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggleGroupEnabled(group.name, isChecked)
            }

            if (!group.enabled) {
                binding.root.alpha = 0.6f
            } else {
                binding.root.alpha = 1.0f
            }

            binding.llModels.removeAllViews()
            val inflater = LayoutInflater.from(binding.root.context)
            for (model in group.models) {
                val modelBinding = ItemConfigModelBinding.inflate(inflater, binding.llModels, false)
                bindModel(modelBinding, model, group.name, onReplaceClick, onDeleteClick, onToggleEnabled)
                binding.llModels.addView(modelBinding.root)
            }

            binding.btnAddModel.setOnClickListener {
                onAddClick(group.name)
            }

            binding.btnRenameGroup.setOnClickListener {
                onRenameClick(group.name, group.name)
            }

            binding.btnDeleteGroup.setOnClickListener {
                onDeleteGroupClick(group.name)
            }
        }

        private fun bindModel(binding: ItemConfigModelBinding, model: ConfigModelItem, groupName: String,
                              onReplaceClick: (String, String, String) -> Unit, onDeleteClick: (String, String) -> Unit,
                              onToggleEnabled: (String, String, Boolean) -> Unit) {
            binding.tvModelName.text = model.name
            binding.tvModelId.visibility = View.GONE
            binding.tvPriority.text = "优先级: ${model.priority}"

            val provider = ProviderManager.getProvider(model.providerId)
            binding.tvProviderBadge.text = provider?.name ?: model.providerId

            binding.switchEnabled.setOnCheckedChangeListener(null)
            binding.switchEnabled.isChecked = model.enabled
            binding.switchEnabled.setOnCheckedChangeListener { _, isChecked ->
                onToggleEnabled(model.id, groupName, isChecked)
            }

            if (!model.enabled) {
                binding.root.alpha = 0.5f
            } else {
                binding.root.alpha = 1.0f
            }

            binding.btnReplace.setOnClickListener {
                onReplaceClick(model.id, model.name, groupName)
            }

            binding.btnDelete.setOnClickListener {
                onDeleteClick(model.id, groupName)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val binding = ItemConfigGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return GroupViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(getItem(position), onReplaceClick, onDeleteClick, onAddClick, onToggleEnabled, onRenameClick, onDeleteGroupClick, onToggleGroupEnabled)
    }

    class GroupDiffCallback : DiffUtil.ItemCallback<GroupItem>() {
        override fun areItemsTheSame(oldItem: GroupItem, newItem: GroupItem): Boolean {
            return oldItem.name == newItem.name
        }

        override fun areContentsTheSame(oldItem: GroupItem, newItem: GroupItem): Boolean {
            return oldItem == newItem
        }
    }
}
