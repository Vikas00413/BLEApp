package com.example.bleapp.ui.chat

import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.AttachFile
import androidx.compose.material.icons.filled.Image
import androidx.compose.material.icons.filled.Link
import androidx.compose.material.icons.filled.LocationOn
import androidx.compose.material.icons.filled.Videocam
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.example.bleapp.R
import com.example.bleapp.data.ChatContentType
import com.example.bleapp.data.ChatMessage

private val OnlineGreen = Color(0xFF4CAF50)
private val OfflineGrey = Color(0xFF9E9E9E)
private val IncomingEndPadding = 56.dp
private val OutgoingStartPadding = 56.dp

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BleChatScreen(
    peerName: String?,
    chatStatus: String?,
    canSend: Boolean,
    isPeerTyping: Boolean,
    messages: List<ChatMessage>,
    onInputChange: (String) -> Unit,
    onSend: (String) -> Unit,
    onShareLocation: () -> Unit,
    onShareLink: (String) -> Unit,
    onSharePhoto: (String) -> Unit,
    onShareVideo: (String) -> Unit,
    onBack: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val listState = rememberLazyListState()
    val listItems = remember(messages) { groupChatMessagesByDay(messages) }
    var draft by rememberSaveable { mutableStateOf("") }
    var showAttachSheet by remember { mutableStateOf(false) }
    var showLinkDialog by remember { mutableStateOf(false) }

    val lastScrollKey = messages.lastOrNull()?.let { msg ->
        if (msg.id != 0L) msg.id else msg.timestampMillis
    }

    val pickPhoto = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onSharePhoto(it.toString()) }
        showAttachSheet = false
    }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        uri?.let { onShareVideo(it.toString()) }
        showAttachSheet = false
    }

    LaunchedEffect(lastScrollKey) {
        if (listItems.isNotEmpty()) {
            listState.scrollToItem(listItems.lastIndex)
        }
    }

    if (showLinkDialog) {
        LinkInputDialog(
            onDismiss = { showLinkDialog = false },
            onConfirm = { url ->
                showLinkDialog = false
                onShareLink(url)
            },
        )
    }

    if (showAttachSheet) {
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
        ModalBottomSheet(
            onDismissRequest = { showAttachSheet = false },
            sheetState = sheetState,
        ) {
            AttachOptionsSheet(
                onPhoto = {
                    pickPhoto.launch("image/*")
                },
                onVideo = {
                    pickVideo.launch("video/*")
                },
                onLocation = {
                    showAttachSheet = false
                    onShareLocation()
                },
                onLink = {
                    showAttachSheet = false
                    showLinkDialog = true
                },
            )
        }
    }

    Scaffold(
        modifier = modifier,
        contentWindowInsets = WindowInsets.safeDrawing.only(
            WindowInsetsSides.Top + WindowInsetsSides.Horizontal,
        ),
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(peerName ?: stringResource(R.string.chat_waiting_peer))
                        when {
                            isPeerTyping -> {
                                Text(
                                    text = stringResource(R.string.peer_typing),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.primary,
                                )
                            }
                            !chatStatus.isNullOrBlank() -> {
                                Text(
                                    text = chatStatus,
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                    }
                },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.chat_back),
                        )
                    }
                },
                actions = {
                    ConnectionStatusDot(isConnected = canSend)
                },
            )
        },
        bottomBar = {
            ChatInputBar(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .imePadding(),
                draft = draft,
                canSend = canSend,
                onAttachClick = { showAttachSheet = true },
                onInputChange = { draft = it; onInputChange(it) },
                onSend = {
                    val text = draft.trim()
                    if (text.isNotEmpty()) {
                        onSend(text)
                        draft = ""
                    }
                },
            )
        },
    ) { padding ->
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            if (listItems.isEmpty()) {
                item {
                    Text(
                        stringResource(R.string.chat_empty),
                        modifier = Modifier.padding(vertical = 24.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            items(listItems, key = { it.key }) { item ->
                when (item) {
                    is ChatListItem.DaySeparator -> DaySeparatorChip(label = item.label)
                    is ChatListItem.MessageItem -> ChatBubble(message = item.message)
                }
            }
        }
    }
}

@Composable
private fun DaySeparatorChip(label: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        Surface(
            color = MaterialTheme.colorScheme.surfaceVariant,
            shape = RoundedCornerShape(12.dp),
        ) {
            Text(
                text = label,
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun ConnectionStatusDot(isConnected: Boolean) {
    val label = if (isConnected) {
        stringResource(R.string.chat_online)
    } else {
        stringResource(R.string.chat_offline)
    }
    Box(
        modifier = Modifier
            .padding(end = 16.dp)
            .size(10.dp)
            .semantics { contentDescription = label }
            .background(
                color = if (isConnected) OnlineGreen else OfflineGrey,
                shape = CircleShape,
            ),
    )
}

@Composable
private fun AttachOptionsSheet(
    onPhoto: () -> Unit,
    onVideo: () -> Unit,
    onLocation: () -> Unit,
    onLink: () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = 24.dp)) {
        Text(
            text = stringResource(R.string.chat_attach_title),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
            style = MaterialTheme.typography.titleMedium,
        )
        AttachRow(icon = { Icon(Icons.Default.Image, null) }, label = stringResource(R.string.chat_attach_photo), onClick = onPhoto)
        AttachRow(icon = { Icon(Icons.Default.Videocam, null) }, label = stringResource(R.string.chat_attach_video), onClick = onVideo)
        AttachRow(icon = { Icon(Icons.Default.LocationOn, null) }, label = stringResource(R.string.chat_attach_location), onClick = onLocation)
        AttachRow(icon = { Icon(Icons.Default.Link, null) }, label = stringResource(R.string.chat_attach_link), onClick = onLink)
    }
}

@Composable
private fun AttachRow(
    icon: @Composable () -> Unit,
    label: String,
    onClick: () -> Unit,
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        icon()
        Text(text = label, style = MaterialTheme.typography.bodyLarge)
    }
}

@Composable
private fun LinkInputDialog(
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var url by remember { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(stringResource(R.string.chat_attach_link)) },
        text = {
            OutlinedTextField(
                value = url,
                onValueChange = { url = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text(stringResource(R.string.chat_link_hint)) },
                singleLine = true,
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(url) },
                enabled = url.isNotBlank(),
            ) {
                Text(stringResource(R.string.chat_send))
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(stringResource(R.string.chat_cancel))
            }
        },
    )
}

@Composable
private fun ChatInputBar(
    draft: String,
    canSend: Boolean,
    onAttachClick: () -> Unit,
    onInputChange: (String) -> Unit,
    onSend: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Surface(modifier = modifier, shadowElevation = 8.dp) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(8.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            IconButton(
                onClick = onAttachClick,
                enabled = canSend,
            ) {
                Icon(
                    Icons.Default.AttachFile,
                    contentDescription = stringResource(R.string.chat_attach_title),
                )
            }
            OutlinedTextField(
                value = draft,
                onValueChange = onInputChange,
                modifier = Modifier.weight(1f),
                placeholder = {
                    Text(
                        if (canSend) {
                            stringResource(R.string.chat_input_hint)
                        } else {
                            stringResource(R.string.chat_input_reconnecting)
                        },
                    )
                },
                enabled = true,
                minLines = 1,
                maxLines = 6,
                textStyle = MaterialTheme.typography.bodyMedium,
            )
            IconButton(
                onClick = onSend,
                enabled = canSend && draft.isNotBlank(),
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = stringResource(R.string.chat_send),
                )
            }
        }
    }
}

@Composable
private fun ChatBubble(message: ChatMessage) {
    val context = LocalContext.current
    val backgroundColor = if (message.isMine) {
        MaterialTheme.colorScheme.primaryContainer
    } else {
        MaterialTheme.colorScheme.surfaceVariant
    }
    val shape = remember(message.id, message.isMine) { chatBubbleShape(isMine = message.isMine) }
    val timeText = remember(message.timestampMillis) { formatMessageTime(message.timestampMillis) }
    val bubbleModifier = if (message.isMine) {
        Modifier.padding(start = OutgoingStartPadding)
    } else {
        Modifier.padding(end = IncomingEndPadding)
    }
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = if (message.isMine) Arrangement.End else Arrangement.Start,
    ) {
        Column(
            modifier = bubbleModifier.widthIn(max = 280.dp),
            horizontalAlignment = if (message.isMine) Alignment.End else Alignment.Start,
        ) {
            Surface(
                shape = shape,
                color = backgroundColor,
                shadowElevation = 1.dp,
            ) {
                Column(
                    modifier = Modifier.padding(
                        start = if (message.isMine) 12.dp else 14.dp,
                        end = if (message.isMine) 14.dp else 12.dp,
                        top = 8.dp,
                        bottom = 6.dp,
                    ),
                ) {
                    when (message.contentType) {
                        ChatContentType.PHOTO -> {
                            message.mediaUri?.let { uriString ->
                                AsyncImage(
                                    model = Uri.parse(uriString),
                                    contentDescription = stringResource(R.string.chat_attach_photo),
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .height(120.dp)
                                        .clip(RoundedCornerShape(8.dp)),
                                    contentScale = ContentScale.Crop,
                                )
                            } ?: Text("📷 ${message.text}", style = MaterialTheme.typography.bodySmall)
                        }
                        ChatContentType.VIDEO -> {
                            Text("🎬 ${message.text}", style = MaterialTheme.typography.bodySmall)
                            message.mediaUri?.let {
                                Text(
                                    stringResource(R.string.chat_video_local_only),
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                            }
                        }
                        ChatContentType.LOCATION -> {
                            Text("📍 ${message.text}", style = MaterialTheme.typography.bodySmall)
                        }
                        ChatContentType.LINK -> {
                            val url = message.linkUrl ?: message.text
                            Text(
                                text = url,
                                style = MaterialTheme.typography.bodySmall.copy(
                                    textDecoration = TextDecoration.Underline,
                                    color = MaterialTheme.colorScheme.primary,
                                ),
                                modifier = Modifier.clickable {
                                    runCatching {
                                        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)))
                                    }
                                },
                            )
                        }
                        ChatContentType.TEXT -> {
                            Text(text = message.text, style = MaterialTheme.typography.bodySmall)
                        }
                    }
                }
            }
            Text(
                text = timeText,
                modifier = Modifier.padding(top = 2.dp, start = 4.dp, end = 4.dp),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}
