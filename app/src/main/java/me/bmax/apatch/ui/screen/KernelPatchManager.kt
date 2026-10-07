package me.bmax.apatch.ui.screen

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.dropUnlessResumed
import com.ramcosta.composedestinations.annotation.Destination
import com.ramcosta.composedestinations.annotation.RootGraph
import com.ramcosta.composedestinations.generated.destinations.PatchesDestination
import com.ramcosta.composedestinations.navigation.DestinationsNavigator
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import me.bmax.apatch.R
import me.bmax.apatch.ui.viewmodel.PatchesViewModel
import me.bmax.apatch.util.KernelPatchStore

@Destination<RootGraph>
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun KernelPatchManagerScreen(navigator: DestinationsNavigator) {
    var channel by remember { mutableStateOf(KernelPatchStore.Channel.STABLE) }
    var releases by remember { mutableStateOf<List<KernelPatchStore.Release>>(emptyList()) }
    var loading by remember { mutableStateOf(true) }
    var installingTag by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf("") }
    var active by remember { mutableStateOf(KernelPatchStore.active()) }
    val scope = rememberCoroutineScope()

    suspend fun refresh() {
        loading = true
        error = ""
        runCatching {
            KernelPatchStore.fetch(channel)
        }.onSuccess {
            releases = it
        }.onFailure {
            releases = emptyList()
            error = it.message ?: it.toString()
        }
        loading = false
    }

    LaunchedEffect(channel) {
        refresh()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(stringResource(R.string.kp_manager_title)) },
                navigationIcon = {
                    IconButton(onClick = dropUnlessResumed { navigator.popBackStack() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = null)
                    }
                },
                actions = {
                    IconButton(onClick = { scope.launch { refresh() } }) {
                        Icon(Icons.Filled.Refresh, contentDescription = null)
                    }
                }
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .padding(innerPadding)
                .padding(horizontal = 16.dp)
        ) {
            val tabs = listOf(
                KernelPatchStore.Channel.STABLE to R.string.kp_manager_stable,
                KernelPatchStore.Channel.TEST to R.string.kp_manager_test,
            )
            val selectedIndex = tabs.indexOfFirst { it.first == channel }
            TabRow(selectedTabIndex = selectedIndex) {
                tabs.forEachIndexed { index, pair ->
                    Tab(
                        selected = index == selectedIndex,
                        onClick = { channel = pair.first },
                        text = { Text(stringResource(pair.second)) }
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            active?.let { current ->
                ElevatedCard(
                    colors = CardDefaults.elevatedCardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        verticalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.kp_manager_current),
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(current.name, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            text = current.tag + " · " + current.compileTime,
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Spacer(Modifier.height(4.dp))
                        Button(
                            onClick = {
                                KernelPatchStore.clearActive()
                                active = null
                                navigator.navigate(
                                    PatchesDestination(
                                        PatchesViewModel.PatchMode.UPDATE_KERNELPATCH
                                    )
                                )
                            },
                            enabled = installingTag == null
                        ) {
                            Text(stringResource(R.string.kp_manager_use_bundled))
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            if (loading) {
                CircularProgressIndicator(modifier = Modifier.padding(24.dp))
            }

            if (error.isNotEmpty()) {
                Text(
                    text = stringResource(R.string.kp_manager_error, error),
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            if (!loading && releases.isEmpty() && error.isEmpty()) {
                Text(
                    text = stringResource(R.string.kp_manager_empty),
                    modifier = Modifier.padding(vertical = 12.dp)
                )
            }

            LazyColumn(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                items(releases, key = { it.tag }) { release ->
                    ElevatedCard {
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(12.dp),
                            verticalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            Text(
                                text = release.name,
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = release.tag,
                                style = MaterialTheme.typography.bodyMedium
                            )
                            Text(
                                text = stringResource(
                                    R.string.kp_manager_release_meta,
                                    release.publishedAt
                                ),
                                style = MaterialTheme.typography.bodySmall
                            )

                            val isActive = active?.tag == release.tag
                            Button(
                                enabled = installingTag == null,
                                onClick = {
                                    scope.launch {
                                        installingTag = release.tag
                                        error = ""
                                        runCatching {
                                            withContext(Dispatchers.IO) {
                                                KernelPatchStore.activate(release, channel)
                                            }
                                        }.onSuccess {
                                            active = it
                                            navigator.navigate(
                                                PatchesDestination(
                                                    PatchesViewModel.PatchMode.UPDATE_KERNELPATCH
                                                )
                                            )
                                        }.onFailure {
                                            error = it.message ?: it.toString()
                                        }
                                        installingTag = null
                                    }
                                }
                            ) {
                                Text(
                                    if (isActive) {
                                        stringResource(R.string.kp_manager_apply)
                                    } else {
                                        stringResource(R.string.kp_manager_download_install)
                                    }
                                )
                            }

                            if (installingTag == release.tag) {
                                Text(
                                    stringResource(R.string.kp_manager_downloading),
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
