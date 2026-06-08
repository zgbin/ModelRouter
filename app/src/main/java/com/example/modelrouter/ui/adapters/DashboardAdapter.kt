package com.example.modelrouter.ui.adapters

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.recyclerview.widget.RecyclerView
import com.example.modelrouter.R
import com.example.modelrouter.databinding.ItemDashboardGroupBinding
import com.example.modelrouter.databinding.ItemDashboardModelBinding
import com.example.modelrouter.models.DashboardGroup
import com.example.modelrouter.models.DashboardModel

class DashboardGroupAdapter(
    private val onLockClick: (String, String) -> Unit,
    private val onUnlockClick: (String) -> Unit,
    private val onSpeedTestClick: (String) -> Unit
) : RecyclerView.Adapter<DashboardGroupAdapter.GroupViewHolder>() {

    private var groups: List<DashboardGroup> = emptyList()

    companion object {
        private val providerColorCache = mutableMapOf<String, Int>()
        private var colorIndex = 0

        // 高区分度色板，色相间隔大
        private val palette = intArrayOf(
            0xFFE53935.toInt(), // 红
            0xFF1E88E5.toInt(), // 蓝
            0xFF43A047.toInt(), // 绿
            0xFFFB8C00.toInt(), // 橙
            0xFF8E24AA.toInt(), // 紫
            0xFF00ACC1.toInt(), // 青
            0xFFF4511E.toInt(), // 深橙
            0xFF3949AB.toInt(), // 靛蓝
            0xFF7CB342.toInt(), // 黄绿
            0xFFD81B60.toInt(), // 粉红
            0xFF00897B.toInt(), // 墨绿
            0xFFFFB300.toInt(), // 琥珀
            0xFF5E35B1.toInt(), // 深紫
            0xFF039BE5.toInt(), // 亮蓝
            0xFFC0CA33.toInt(), // 柠檬
        )

        fun getProviderColor(providerName: String): Int {
            providerColorCache[providerName]?.let { return it }
            val color = palette[colorIndex % palette.size]
            colorIndex++
            providerColorCache[providerName] = color
            return color
        }
    }

    fun submitList(list: List<DashboardGroup>) {
        groups = list
        notifyDataSetChanged()
    }

    class GroupViewHolder(private val binding: ItemDashboardGroupBinding) : RecyclerView.ViewHolder(binding.root) {
        fun bind(group: DashboardGroup,
                 onLockClick: (String, String) -> Unit, onUnlockClick: (String) -> Unit, onSpeedTestClick: (String) -> Unit) {
            binding.tvGroupName.text = group.name
            binding.tvPort.text = "端口: ${group.port ?: "N/A"}"

            val healthyCount = group.models.count { it.status.isHealthy }
            val totalCount = group.models.size
            binding.tvHealthySummary.text = "$healthyCount/$totalCount 健康"
            binding.tvHealthySummary.setTextColor(
                ContextCompat.getColor(
                    binding.root.context,
                    if (healthyCount == totalCount) R.color.success else if (healthyCount > 0) R.color.warning else R.color.error
                )
            )

            val currentModelId = group.models.find { it.status.isCurrent || it.status.isLocked }?.id ?: ""
            if (currentModelId.isNotEmpty()) {
                binding.tvCurrentModel.visibility = View.VISIBLE
                binding.tvCurrentModel.text = "当前: $currentModelId"
            } else {
                binding.tvCurrentModel.visibility = View.GONE
            }

            binding.llModels.removeAllViews()
            val inflater = LayoutInflater.from(binding.root.context)
            for (model in group.models) {
                val modelBinding = ItemDashboardModelBinding.inflate(inflater, binding.llModels, false)
                bindModel(modelBinding, model, group.name, onLockClick, onUnlockClick, onSpeedTestClick)
                binding.llModels.addView(modelBinding.root)
            }
        }

        private fun bindModel(binding: ItemDashboardModelBinding, model: DashboardModel, groupName: String,
                              onLockClick: (String, String) -> Unit, onUnlockClick: (String) -> Unit,
                              onSpeedTestClick: (String) -> Unit) {
            binding.tvModelName.text = model.name
            binding.tvProviderBadge.text = model.providerName
            binding.tvProviderBadge.setTextColor(getProviderColor(model.providerName))
            binding.tvProviderBadge.visibility = if (model.providerName.isNotEmpty()) View.VISIBLE else View.GONE
            binding.tvRequests.text = model.status.totalRequests.toString()

            val responseTime = model.status.avgResponseTime
            binding.tvResponseTime.text = when {
                responseTime == null -> "未测速"
                responseTime >= 100 -> "${responseTime.toInt()}s"
                responseTime > 0.001 -> "%.2fs".format(responseTime)
                responseTime >= 0 -> "<1ms"
                else -> "失败"
            }

            val isHealthy = model.status.isHealthy
            if (isHealthy) {
                binding.ivStatus.setImageResource(android.R.drawable.presence_online)
                binding.tvStatus.text = "健康"
                binding.tvStatus.setTextColor(ContextCompat.getColor(binding.root.context, R.color.success))
            } else {
                binding.ivStatus.setImageResource(android.R.drawable.presence_busy)
                val errorLabel = model.status.errorMessage ?: if (responseTime == null) "未测" else "失败"
                binding.tvStatus.text = errorLabel
                binding.tvStatus.setTextColor(ContextCompat.getColor(binding.root.context, R.color.error))
            }

            val isCurrent = model.status.isCurrent
            val isLocked = model.status.isLocked

            if (isLocked) {
                binding.tvTagLocked.visibility = View.VISIBLE
                binding.tvTagCurrent.visibility = View.GONE
            } else if (isCurrent) {
                binding.tvTagLocked.visibility = View.GONE
                binding.tvTagCurrent.visibility = View.VISIBLE
            } else {
                binding.tvTagLocked.visibility = View.GONE
                binding.tvTagCurrent.visibility = View.GONE
            }

            if (isCurrent || isLocked) {
                binding.root.setBackgroundColor(ContextCompat.getColor(binding.root.context, R.color.accentTransparent))
            } else {
                binding.root.setBackgroundColor(ContextCompat.getColor(binding.root.context, R.color.surface))
            }

            if (isLocked) {
                binding.btnAction.text = "解锁"
                binding.btnAction.setBackgroundColor(ContextCompat.getColor(binding.root.context, R.color.error))
                binding.btnAction.setOnClickListener { onUnlockClick(groupName) }
            } else {
                binding.btnAction.text = "锁定"
                binding.btnAction.setBackgroundColor(ContextCompat.getColor(binding.root.context, R.color.primary))
                binding.btnAction.setOnClickListener { onLockClick(model.id, groupName) }
            }

            binding.btnSpeedTest.setOnClickListener {
                onSpeedTestClick(model.id)
            }
        }
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): GroupViewHolder {
        val binding = ItemDashboardGroupBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return GroupViewHolder(binding)
    }

    override fun onBindViewHolder(holder: GroupViewHolder, position: Int) {
        holder.bind(groups[position], onLockClick, onUnlockClick, onSpeedTestClick)
    }

    override fun getItemCount(): Int = groups.size
}
