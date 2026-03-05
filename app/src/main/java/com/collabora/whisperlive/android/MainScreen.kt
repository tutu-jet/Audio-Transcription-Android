package com.collabora.whisperlive.android

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Divider
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun MainScreen() {
    val vm = remember { RecordingViewModel() }
    val ui by vm.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp)
            .padding(top = 20.dp)
    ) {
        Text("WhisperLive Android Client")
        Spacer(Modifier.height(12.dp))

        OutlinedTextField(
            modifier = Modifier.fillMaxWidth(),
            value = ui.serverUrl,
            onValueChange = vm::setServerUrl,
            label = { Text("Server WebSocket URL") },
            singleLine = true
        )

        Spacer(Modifier.height(8.dp))

        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Button(onClick = vm::start, enabled = !ui.isRecording) { Text("Start") }
            Button(onClick = vm::pause, enabled = ui.isRecording && !ui.isPaused) { Text("Pause") }
            Button(onClick = vm::resume, enabled = ui.isRecording && ui.isPaused) { Text("Resume") }
            Button(onClick = vm::stop, enabled = ui.isRecording) { Text("Stop") }
        }

        Spacer(Modifier.height(12.dp))
        Divider()
        Spacer(Modifier.height(12.dp))

        Text("Live transcript")
        Spacer(Modifier.height(8.dp))
        Column(
            modifier = Modifier
                .weight(1f)
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
        ) {
            Text(ui.transcript)
            Spacer(Modifier.height(12.dp))
            Divider()
            Spacer(Modifier.height(12.dp))
            Text("Logs")
            Spacer(Modifier.height(8.dp))
            Text(ui.logs)
        }
    }
}

