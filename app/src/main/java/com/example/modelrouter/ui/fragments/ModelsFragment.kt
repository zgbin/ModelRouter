package com.example.modelrouter.ui.fragments

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Toast
import androidx.fragment.app.Fragment
import androidx.fragment.app.activityViewModels
import androidx.recyclerview.widget.LinearLayoutManager
import com.example.modelrouter.databinding.FragmentModelsBinding
import com.example.modelrouter.models.ModelItem
import com.example.modelrouter.ui.adapters.ModelAdapter
import com.example.modelrouter.viewmodels.ModelViewModel

class ModelsFragment : Fragment() {
    private lateinit var binding: FragmentModelsBinding
    private val viewModel: ModelViewModel by activityViewModels()
    private lateinit var adapter: ModelAdapter

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        binding = FragmentModelsBinding.inflate(inflater, container, false)
        setupRecyclerView()
        setupObservers()
        setupListeners()
        viewModel.loadModels()
        return binding.root
    }

    private fun setupRecyclerView() {
        adapter = ModelAdapter(
            onAddClick = { model -> showAddModelDialog(model) },
            onViewDetails = { link -> openLink(link) }
        )
        binding.rvModels.layoutManager = LinearLayoutManager(context)
        binding.rvModels.adapter = adapter
    }

    private fun setupObservers() {
        viewModel.models.observe(viewLifecycleOwner) { models ->
            adapter.submitList(models)
            binding.layoutEmpty.visibility = if (models.isEmpty()) View.VISIBLE else View.GONE
            binding.rvModels.visibility = if (models.isEmpty()) View.GONE else View.VISIBLE
        }

        viewModel.stats.observe(viewLifecycleOwner) { stats ->
            binding.tvTotalCount.text = stats.totalCount.toString()
            binding.tvOwnersCount.text = stats.ownersCount.toString()
            binding.tvDocVersion.text = stats.docVersion
            binding.tvUpdateTime.text = "更新时间: ${stats.fetchedAt}"
        }

        viewModel.loading.observe(viewLifecycleOwner) { loading ->
            binding.progressBar.visibility = if (loading) View.VISIBLE else View.GONE
        }

        viewModel.error.observe(viewLifecycleOwner) { error ->
            error?.let {
                Toast.makeText(context, it, Toast.LENGTH_SHORT).show()
                viewModel.clearError()
            }
        }
    }

    private fun setupListeners() {
        binding.etSearch.setOnEditorActionListener { _, _, _ ->
            val query = binding.etSearch.text.toString().trim()
            if (query.isNotEmpty()) {
                viewModel.searchModels(query)
            } else {
                viewModel.loadModels()
            }
            true
        }

        binding.btnRefresh.setOnClickListener {
            viewModel.refreshData()
        }
    }

    private fun showAddModelDialog(model: ModelItem) {
        val allGroups = viewModel.groups.value ?: emptyList()
        val activeGroups = allGroups.filter { it.enabled && !it.isBackup }
        val groupNames = activeGroups.map { "${it.name} (${it.description})" }.toTypedArray()

        if (groupNames.isEmpty()) {
            Toast.makeText(context, "没有可用的分组", Toast.LENGTH_SHORT).show()
            return
        }

        var selectedIndex = 0
        val builder = androidx.appcompat.app.AlertDialog.Builder(requireContext())
        builder.setTitle("添加到: ${model.name}")
        builder.setSingleChoiceItems(groupNames, 0) { _, which ->
            selectedIndex = which
        }
        builder.setPositiveButton("添加") { _, _ ->
            val groupName = activeGroups[selectedIndex].name
            viewModel.addModel(model.id, model.name, groupName) { _, message ->
                Toast.makeText(context, message, Toast.LENGTH_SHORT).show()
            }
        }
        builder.setNegativeButton("取消", null)
        builder.show()
    }

    private fun openLink(link: String) {
        if (link.isNotEmpty()) {
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(link))
            startActivity(intent)
        }
    }
}