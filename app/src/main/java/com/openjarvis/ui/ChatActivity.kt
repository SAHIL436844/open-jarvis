package com.openjarvis.ui

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import com.openjarvis.agent.AgentCore
import com.openjarvis.llm.UniversalAdapter
import com.openjarvis.ui.theme.OpenJarvisTheme
import com.openjarvis.ui.theme.VoidColor
import com.openjarvis.voice.VoiceManager
import com.openjarvis.voice.VoiceManager.VoiceState
import kotlinx.coroutines.launch

class ChatActivity : ComponentActivity() {
    private val requestMicPermission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        if (!granted) android.widget.Toast.makeText(this, "Microphone permission is needed for voice input", android.widget.Toast.LENGTH_SHORT).show()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val agent = AgentCore(applicationContext)
        val voice = VoiceManager(applicationContext)
        setContent {
            OpenJarvisTheme {
                ChatScreen(
                    agentCore = agent,
                    voiceManager = voice,
                    onBack = { finish() },
                    onRequestMicPermission = {
                        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
                            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
                            false
                        } else true
                    }
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
    }
}

private data class ChatMessage(val text: String, val fromUser: Boolean)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ChatScreen(
    agentCore: AgentCore,
    voiceManager: VoiceManager,
    onBack: () -> Unit,
    onRequestMicPermission: () -> Boolean
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var draft by remember { mutableStateOf("") }
    var persona by remember { mutableStateOf("JARVIS") }
    var showMythosSettings by remember { mutableStateOf(false) }
    val adapter = remember { UniversalAdapter(context) }
    var mythosProvider by remember { mutableStateOf("Groq") }
    var mythosModel by remember { mutableStateOf("llama-3.1-70b-versatile") }
    var mythosApiKey by remember { mutableStateOf("") }
    var mythosBaseUrl by remember { mutableStateOf("") }
    var showProviderChoices by remember { mutableStateOf(false) }
    var messages by remember { mutableStateOf(listOf(ChatMessage("I\u0027m JARVIS. Send me a device task, or tap the microphone to speak.", false))) }
    var listening by remember { mutableStateOf(false) }
    var working by remember { mutableStateOf(false) }
    val agentState by agentCore.state.collectAsState()

    fun submit(text: String) {
        val command = text.trim()
        if (command.isEmpty() || working) return
        messages = messages + ChatMessage(command, true)
        draft = ""
        working = true
        messages = messages + ChatMessage("Working on it…", false)
        agentCore.executeTask(command, persona)
    }

    LaunchedEffect(showMythosSettings) {
        if (showMythosSettings) {
            val settings = adapter.getPersonaSettings("mythos")
            mythosProvider = settings.provider
            mythosModel = settings.model
            mythosApiKey = settings.apiKey
            mythosBaseUrl = settings.baseUrl
        }
    }

    LaunchedEffect(agentState) {
        when (val state = agentState) {
            is com.openjarvis.agent.AgentState.Done -> {
                if (working) {
                    messages = messages.dropLast(1) + ChatMessage("Completed: ${state.result}", false)
                    working = false
                }
            }
            is com.openjarvis.agent.AgentState.Error -> {
                if (working) {
                    messages = messages.dropLast(1) + ChatMessage("Couldn't complete that: ${state.message}", false)
                    working = false
                }
            }
            else -> if (working && state is com.openjarvis.agent.AgentState.Running) {
                messages = messages.dropLast(1) + ChatMessage("Working: ${state.step}", false)
            }
        }
    }

    LaunchedEffect(voiceManager) {
        voiceManager.state.collect { state ->
            when (state) {
                is VoiceState.Result -> {
                    listening = false
                    submit(state.text)
                    voiceManager.resetState()
                }
                is VoiceState.Error -> {
                    listening = false
                    messages = messages + ChatMessage("Voice input: ${state.message}", false)
                    voiceManager.resetState()
                }
                VoiceState.Idle -> listening = false
                else -> Unit
            }
        }
    }

    Scaffold(
        containerColor = VoidColor.Void950,
        topBar = {
            TopAppBar(
                title = { Text("$persona Chat", color = when (persona) { "ULTRON" -> Color(0xFFFF4655); "MYTHOS" -> Color(0xFFFFB74D); else -> VoidColor.TextPrimary }) },
                actions = {
                    if (persona == "MYTHOS") TextButton(onClick = { showMythosSettings = true }) { Text("MYTHOS ⚙", color = Color(0xFFFFB74D)) }
                    TextButton(onClick = {
                        persona = when (persona) { "JARVIS" -> "ULTRON"; "ULTRON" -> "MYTHOS"; else -> "JARVIS" }
                        messages = messages + ChatMessage("Active persona: $persona. Mythos has separate provider settings.", false)
                    }) {
                        Text(when (persona) { "ULTRON" -> "ULTRON ●"; "MYTHOS" -> "MYTHOS ◈"; else -> "JARVIS ◉" },
                            color = when (persona) { "ULTRON" -> Color(0xFFFF4655); "MYTHOS" -> Color(0xFFFFB74D); else -> VoidColor.Violet })
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back", tint = VoidColor.TextPrimary)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = VoidColor.Void900)
            )
        },
        bottomBar = {
            Row(
                modifier = Modifier.fillMaxWidth().navigationBarsPadding().imePadding()
                    .background(VoidColor.Void900).padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                OutlinedTextField(
                    value = draft,
                    onValueChange = { draft = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Tell $persona what to do…") },
                    enabled = !working,
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Send),
                    keyboardActions = KeyboardActions(onSend = { submit(draft) })
                )
                IconButton(
                    onClick = {
                        if (!listening) {
                            if (!onRequestMicPermission()) return@IconButton
                            if (!voiceManager.isVoiceEnabled()) {
                                messages = messages + ChatMessage("Turn on Voice Assistant in Settings first.", false)
                            } else {
                                listening = true
                                voiceManager.startListening()
                            }
                        } else {
                            voiceManager.stopListening()
                            listening = false
                        }
                    },
                    enabled = !working
                ) {
                    Icon(
                        imageVector = if (listening) Icons.Filled.Stop else Icons.Filled.Mic,
                        contentDescription = if (listening) "Stop voice input" else "Voice input / call",
                        tint = if (listening) VoidColor.Green else VoidColor.Violet
                    )
                }
                IconButton(onClick = { submit(draft) }, enabled = draft.isNotBlank() && !working) {
                    Icon(Icons.AutoMirrored.Filled.Send, contentDescription = "Send message", tint = VoidColor.Violet)
                }
            }
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 12.dp),
            contentPadding = PaddingValues(vertical = 12.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            items(messages) { message ->
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (message.fromUser) Arrangement.End else Arrangement.Start
                ) {
                    Surface(
                        color = if (message.fromUser) VoidColor.Violet.copy(alpha = 0.22f) else VoidColor.Void900,
                        shape = RoundedCornerShape(18.dp),
                        modifier = Modifier.widthIn(max = 320.dp)
                    ) {
                        Text(
                            text = message.text,
                            color = VoidColor.TextPrimary,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 10.dp)
                        )
                    }
                }
            }
        }
    }

    if (showMythosSettings) {
        AlertDialog(
            onDismissRequest = { showMythosSettings = false },
            title = { Text("Mythos model settings") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Independent encrypted settings. Choose a supported provider and model ID.")
                    TextButton(onClick = { showProviderChoices = true }) { Text("Provider: $mythosProvider") }
                    DropdownMenu(expanded = showProviderChoices, onDismissRequest = { showProviderChoices = false }) {
                        UniversalAdapter.AVAILABLE_PROVIDERS.forEach { option ->
                            DropdownMenuItem(text = { Text(option) }, onClick = {
                                mythosProvider = option
                                mythosModel = UniversalAdapter.getDefaultModel(option)
                                mythosBaseUrl = if (option == "Ollama (Local)") "http://localhost:11434" else ""
                                showProviderChoices = false
                            })
                        }
                    }
                    OutlinedTextField(value = mythosModel, onValueChange = { mythosModel = it }, label = { Text("Model ID") }, singleLine = true)
                    OutlinedTextField(value = mythosApiKey, onValueChange = { mythosApiKey = it }, label = { Text("API key") }, singleLine = true)
                    if (mythosProvider == "Custom" || mythosProvider == "Ollama (Local)") {
                        OutlinedTextField(value = mythosBaseUrl, onValueChange = { mythosBaseUrl = it }, label = { Text("Base URL") }, singleLine = true)
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    adapter.savePersonaSettings("mythos", mythosProvider, mythosBaseUrl, mythosApiKey, mythosModel)
                    showMythosSettings = false
                    messages = messages + ChatMessage("Mythos settings saved separately from JARVIS.", false)
                }) { Text("Save") }
            },
            dismissButton = { TextButton(onClick = { showMythosSettings = false }) { Text("Cancel") } }
        )
    }
}
