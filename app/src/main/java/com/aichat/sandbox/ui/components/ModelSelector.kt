package com.aichat.sandbox.ui.components

import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun ModelSelector(
    label: String,
    selectedModel: String,
    models: List<String>,
    onModelSelected: (String) -> Unit,
    customModels: List<String> = emptyList(),
    onAddCustomModel: ((String) -> Unit)? = null,
    onRemoveCustomModel: ((String) -> Unit)? = null
) {
    var expanded by remember { mutableStateOf(false) }
    var showCustomInput by remember { mutableStateOf(false) }
    var customModelName by remember { mutableStateOf("") }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium)
        Box {
            OutlinedButton(onClick = { expanded = true }) {
                Text(selectedModel)
                Icon(Icons.Default.ArrowDropDown, contentDescription = "Select model")
            }
            DropdownMenu(
                expanded = expanded,
                onDismissRequest = {
                    expanded = false
                    showCustomInput = false
                    customModelName = ""
                }
            ) {
                // Built-in models
                models.forEach { model ->
                    DropdownMenuItem(
                        text = { Text(model) },
                        onClick = {
                            onModelSelected(model)
                            expanded = false
                        }
                    )
                }

                // Custom models
                if (customModels.isNotEmpty()) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    customModels.forEach { model ->
                        DropdownMenuItem(
                            text = {
                                Row(
                                    modifier = Modifier.fillMaxWidth(),
                                    horizontalArrangement = Arrangement.SpaceBetween,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(model, modifier = Modifier.weight(1f))
                                    if (onRemoveCustomModel != null) {
                                        IconButton(
                                            onClick = { onRemoveCustomModel(model) },
                                            modifier = Modifier.size(24.dp)
                                        ) {
                                            Icon(
                                                Icons.Default.Close,
                                                contentDescription = "Remove custom model",
                                                modifier = Modifier.size(16.dp)
                                            )
                                        }
                                    }
                                }
                            },
                            onClick = {
                                onModelSelected(model)
                                expanded = false
                            }
                        )
                    }
                }

                // Add custom model option
                if (onAddCustomModel != null) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                    if (!showCustomInput) {
                        DropdownMenuItem(
                            text = {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        Icons.Default.Add,
                                        contentDescription = null,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text("Add custom model...")
                                }
                            },
                            onClick = { showCustomInput = true }
                        )
                    } else {
                        Row(
                            modifier = Modifier
                                .padding(horizontal = 12.dp, vertical = 4.dp)
                                .fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = customModelName,
                                onValueChange = { customModelName = it },
                                placeholder = { Text("model-name") },
                                singleLine = true,
                                modifier = Modifier.weight(1f),
                                textStyle = MaterialTheme.typography.bodySmall
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            IconButton(
                                onClick = {
                                    val name = customModelName.trim()
                                    if (name.isNotEmpty()) {
                                        onAddCustomModel(name)
                                        onModelSelected(name)
                                        customModelName = ""
                                        showCustomInput = false
                                        expanded = false
                                    }
                                },
                                enabled = customModelName.trim().isNotEmpty()
                            ) {
                                Icon(
                                    Icons.Default.Add,
                                    contentDescription = "Add model"
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
