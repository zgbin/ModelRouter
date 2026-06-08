package com.example.modelrouter.ui.fragments

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.modelrouter.R
import com.example.modelrouter.databinding.FragmentConfigBinding
import com.example.modelrouter.models.ProviderInfo
import com.example.modelrouter.models.ProviderModel
import com.example.modelrouter.service.ProviderManager
import com.example.modelrouter.ui.adapters.ConfigGroupAdapter
import com.example.modelrouter.viewmodels.ModelViewModel

class ConfigFragment : Fragment() {
    private lateinit var binding: FragmentConfigBinding
    private val viewModel: ModelViewModel by activityViewModels()
    private lateinit var adapter: ConfigGroupAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentConfigBinding.inflate(inflater, container, false)
        setupRecyclerView()
        setupObservers()
        setupListeners()
        return binding.root
    }

    private fun setupRecyclerView() {
        adapter = ConfigGroupAdapter(
            onReplaceClick = { modelId, modelName, groupName -> showReplaceDialog(modelId, modelName, groupName) },
            onDeleteClick = { modelId, groupName -> showDeleteDialog(modelId, groupName) },
            onAddClick = { groupName -> showAddDialog(groupName) },
            onToggleEnabled = { modelId, groupName, enabled -> toggleModelEnabled(modelId, groupName, enabled) },
            onRenameClick = { oldName, _ -> showRenameGroupDialog(oldName) },
            onDeleteGroupClick = { groupName -> showDeleteGroupDialog(groupName) },
            onToggleGroupEnabled = { groupName, enabled -> toggleGroupEnabled(groupName, enabled) }
        )
        binding.rvGroups.layoutManager = LinearLayoutManager(context)
        binding.rvGroups.adapter = adapter
    }

    private fun setupObservers() {
        viewModel.groups.observe(viewLifecycleOwner) { groups ->
            groups?.let {
                adapter.submitList(it)
                binding.tvEmpty.visibility = if (it.isEmpty()) View.VISIBLE else View.GONE
                binding.rvGroups.visibility = if (it.isEmpty()) View.GONE else View.VISIBLE
            }
        }
    }

    private fun setupListeners() {
        binding.btnAddGroup.setOnClickListener {
            showAddGroupDialog()
        }
        binding.fabAddModel.setOnClickListener {
            showAddDialogToGroup()
        }
    }

    private fun toggleModelEnabled(modelId: String, groupName: String, enabled: Boolean) {
        viewModel.toggleModelEnabled(modelId, groupName, enabled) { _, message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun toggleGroupEnabled(groupName: String, enabled: Boolean) {
        viewModel.toggleGroupEnabled(groupName, enabled) { _, message ->
            Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
        }
    }

    private fun showAddDialog(groupName: String) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
        builder.setTitle("添加模型到 $groupName")

        val view = layoutInflater.inflate(R.layout.dialog_add_model, null)
        builder.setView(view)

        val spinnerProvider = view.findViewById<Spinner>(R.id.spinner_provider)
        val spinnerModel = view.findViewById<Spinner>(R.id.spinner_model)
        val etModelId = view.findViewById<EditText>(R.id.et_model_id)
        val etModelName = view.findViewById<EditText>(R.id.et_model_name)
        val tvOrManual = view.findViewById<TextView>(R.id.tv_or_manual)

        val providers = ProviderManager.getAllProviders()
        val providerNames = providers.map { it.name }.toTypedArray()
        val providerIds = providers.map { it.id }

        spinnerProvider.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            providerNames
        )

        var currentModels = listOf<ProviderModel>()

        fun updateModelSpinner(provider: ProviderInfo) {
            currentModels = provider.models
            if (currentModels.isNotEmpty()) {
                val modelDisplayNames = listOf("-- 请选择 --") + currentModels.map { "${it.name} (${it.id})" }
                spinnerModel.adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    modelDisplayNames
                )
                spinnerModel.visibility = View.VISIBLE
                tvOrManual.visibility = View.VISIBLE
                view.findViewById<View>(R.id.tv_or_manual).visibility = View.VISIBLE
            } else {
                spinnerModel.adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf("-- 无可用模型，请手动输入 --")
                )
                spinnerModel.visibility = View.VISIBLE
                tvOrManual.visibility = View.VISIBLE
            }
        }

        if (providers.isNotEmpty()) {
            updateModelSpinner(providers[0])
        }

        spinnerProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                val provider = providers.getOrNull(position)
                if (provider != null) {
                    updateModelSpinner(provider)
                    etModelId.text.clear()
                    etModelName.text.clear()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        spinnerModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (currentModels.isNotEmpty() && position in 1..currentModels.size) {
                    val model = currentModels[position - 1]
                    etModelId.setText(model.id)
                    etModelName.setText(model.name)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        builder.setPositiveButton("确定") { _, _ ->
            val providerPosition = spinnerProvider.selectedItemPosition
            val providerId = providerIds.getOrNull(providerPosition) ?: "nvidia"
            val modelId = etModelId.text.toString().trim()
            val modelName = etModelName.text.toString().trim()

            if (modelId.isEmpty() || modelName.isEmpty()) {
                Toast.makeText(context, "请输入完整信息", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }

            viewModel.addModel(modelId, modelName, groupName, providerId) { _, message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        builder.setNegativeButton("取消", null)
        builder.show()
    }

    private fun showAddDialogToGroup() {
        val groups = viewModel.groups.value ?: emptyList()
        if (groups.isEmpty()) return

        val groupNames = groups.map { it.name }.toTypedArray()

        val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
        builder.setTitle("选择要添加模型的分组")

        builder.setItems(groupNames) { _, which ->
            showAddDialog(groupNames[which])
        }

        builder.setNegativeButton("取消", null)
        builder.show()
    }

    private fun showReplaceDialog(oldModelId: String, oldModelName: String, groupName: String) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
        builder.setTitle("替换模型")

        val view = layoutInflater.inflate(com.example.modelrouter.R.layout.dialog_add_model, null)
        builder.setView(view)

        val spinnerProvider = view.findViewById<Spinner>(R.id.spinner_provider)
        val spinnerModel = view.findViewById<Spinner>(R.id.spinner_model)
        val etModelId = view.findViewById<EditText>(R.id.et_model_id)
        val etModelName = view.findViewById<EditText>(R.id.et_model_name)
        val tvOrManual = view.findViewById<TextView>(R.id.tv_or_manual)

        val providers = ProviderManager.getAllProviders()
        val providerNames = providers.map { it.name }.toTypedArray()
        val providerIds = providers.map { it.id }

        spinnerProvider.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            providerNames
        )

        val currentProviderId = run {
            val groups = viewModel.groups.value ?: emptyList()
            for (group in groups) {
                val model = group.models.find { it.id == oldModelId }
                if (model != null) return@run model.providerId
            }
            "nvidia"
        }
        val currentProviderIndex = providerIds.indexOf(currentProviderId).takeIf { it >= 0 } ?: 0
        spinnerProvider.setSelection(currentProviderIndex)

        var currentModels = listOf<ProviderModel>()

        fun updateModelSpinner(provider: ProviderInfo) {
            currentModels = provider.models
            if (currentModels.isNotEmpty()) {
                val modelDisplayNames = listOf("-- 请选择 --") + currentModels.map { "${it.name} (${it.id})" }
                spinnerModel.adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    modelDisplayNames
                )
                spinnerModel.visibility = View.VISIBLE
                tvOrManual.visibility = View.VISIBLE
            } else {
                spinnerModel.adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    listOf("-- 无可用模型，请手动输入 --")
                )
                spinnerModel.visibility = View.VISIBLE
                tvOrManual.visibility = View.VISIBLE
            }
        }

        val initialProvider = providers.getOrNull(currentProviderIndex)
        if (initialProvider != null) {
            updateModelSpinner(initialProvider)
        }

        spinnerProvider.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                val provider = providers.getOrNull(position)
                if (provider != null) {
                    updateModelSpinner(provider)
                    etModelId.text.clear()
                    etModelName.text.clear()
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        spinnerModel.onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, position: Int, id: Long) {
                if (currentModels.isNotEmpty() && position in 1..currentModels.size) {
                    val model = currentModels[position - 1]
                    etModelId.setText(model.id)
                    etModelName.setText(model.name)
                }
            }
            override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
        }

        etModelId.setText(oldModelId)
        etModelName.setText(oldModelName)

        builder.setPositiveButton("确定") { _, _ ->
            val providerPosition = spinnerProvider.selectedItemPosition
            val providerId = providerIds.getOrNull(providerPosition) ?: "nvidia"
            val newModelId = etModelId.text.toString().trim()
            val newModelName = etModelName.text.toString().trim()

            if (newModelId.isEmpty() || newModelName.isEmpty()) {
                Toast.makeText(context, "请输入完整信息", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }

            viewModel.replaceModel(oldModelId, newModelId, newModelName, groupName, providerId) { _, message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }

        builder.setNegativeButton("取消", null)
        builder.show()
    }

    private fun showDeleteDialog(modelId: String, groupName: String) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
        builder.setTitle("删除模型")
        builder.setMessage("确定删除模型 $modelId 吗？")
        builder.setPositiveButton("确定") { _, _ ->
            viewModel.deleteModel(modelId, groupName) { _, message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("取消", null)
        builder.show()
    }

    private fun showRenameGroupDialog(oldName: String) {
        val input = android.widget.EditText(requireContext()).apply {
            setText(oldName)
            selectAll()
            setPadding(48, 32, 48, 32)
        }
        val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
        builder.setTitle("重命名分组")
        builder.setView(input)
        builder.setPositiveButton("确定") { _, _ ->
            val newName = input.text.toString().trim()
            if (newName.isEmpty()) {
                Toast.makeText(context, "名称不能为空", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            if (newName == oldName) return@setPositiveButton
            viewModel.renameGroup(oldName, newName) { _, message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("取消", null)
        builder.show()
    }

    private fun showAddGroupDialog() {
        val view = layoutInflater.inflate(com.example.modelrouter.R.layout.dialog_add_group, null)
        val etGroupName = view.findViewById<android.widget.EditText>(com.example.modelrouter.R.id.et_group_name)
        val etGroupDesc = view.findViewById<android.widget.EditText>(com.example.modelrouter.R.id.et_group_desc)
        val etGroupPort = view.findViewById<android.widget.EditText>(com.example.modelrouter.R.id.et_group_port)

        val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
        builder.setTitle("添加新分组")
        builder.setView(view)
        builder.setPositiveButton("确定") { _, _ ->
            val name = etGroupName.text.toString().trim()
            val desc = etGroupDesc.text.toString().trim()
            val portStr = etGroupPort.text.toString().trim()
            if (name.isEmpty()) {
                Toast.makeText(context, "请输入分组名称", Toast.LENGTH_SHORT).show()
                return@setPositiveButton
            }
            val port = portStr.toIntOrNull() ?: 8190
            viewModel.addGroup(name, desc.ifEmpty { name }, port) { _, message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("取消", null)
        builder.show()
    }

    private fun showDeleteGroupDialog(groupName: String) {
        val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
        builder.setTitle("删除分组")
        builder.setMessage("确定删除分组 $groupName 吗？此操作不可撤销。")
        builder.setPositiveButton("删除") { _, _ ->
            viewModel.deleteGroup(groupName) { _, message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("取消", null)
        builder.show()
    }
}
