package io.ensomi.android.model

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import io.ensomi.android.R
import java.io.File
import java.io.IOException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** The licence notices: the model's own (BeatThis's), then ONNX Runtime's, each opening its text. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticesScreen(notices: List<Notice>, onOpen: (Notice) -> Unit, onBack: () -> Unit) {
    NoticeScaffold(stringResource(R.string.notices_title), onBack) { padding ->
        LazyColumn(
            modifier = Modifier.widthIn(max = 640.dp).fillMaxSize(),
            contentPadding = padding,
        ) {
            item {
                Text(
                    stringResource(R.string.notices_intro),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                )
            }
            items(notices, key = { it.path }) { notice ->
                ListItem(
                    modifier = Modifier.clickable { onOpen(notice) },
                    headlineContent = { Text(noticeTitle(notice)) },
                    supportingContent = { Text(stringResource(if (notice.asset) R.string.notices_from_runtime else R.string.notices_from_model)) },
                    leadingContent = { Icon(painterResource(R.drawable.ic_description), contentDescription = null) },
                    trailingContent = { Icon(painterResource(R.drawable.ic_chevron_right), contentDescription = null) },
                )
            }
        }
    }
}

/** One notice's text, read off the main thread, in paragraphs. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NoticeTextScreen(notice: Notice, modelDirectory: File?, onBack: () -> Unit) {
    val context = LocalContext.current
    val paragraphs by produceState<List<String>?>(initialValue = null, notice, modelDirectory) {
        value = withContext(Dispatchers.IO) { readNotice(context, notice, modelDirectory) }
            ?.replace("\r\n", "\n")
            ?.split(Regex("\n\\s*\n"))
            ?.map { it.trim('\n') }
            ?.filter { it.isNotBlank() }
            ?: emptyList()
    }
    NoticeScaffold(noticeTitle(notice), onBack) { padding ->
        val shown = paragraphs
        if (shown != null && shown.isEmpty()) {
            Text(
                stringResource(R.string.notices_unavailable),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(padding).padding(16.dp),
            )
        } else {
            LazyColumn(
                modifier = Modifier.widthIn(max = 720.dp).fillMaxSize(),
                contentPadding = PaddingValues(
                    start = 16.dp,
                    end = 16.dp,
                    top = padding.calculateTopPadding() + 8.dp,
                    bottom = padding.calculateBottomPadding() + 24.dp,
                ),
            ) {
                items(shown.orEmpty()) { paragraph ->
                    Text(
                        paragraph,
                        style = MaterialTheme.typography.bodySmall,
                        fontFamily = FontFamily.Monospace,
                        modifier = Modifier.padding(bottom = 12.dp),
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun NoticeScaffold(title: String, onBack: () -> Unit, content: @Composable (PaddingValues) -> Unit) {
    val scrollBehavior = TopAppBarDefaults.pinnedScrollBehavior()
    Scaffold(
        modifier = Modifier.nestedScroll(scrollBehavior.nestedScrollConnection),
        topBar = {
            TopAppBar(
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(painterResource(R.drawable.ic_arrow_back), contentDescription = stringResource(R.string.action_back))
                    }
                },
                title = { Text(title) },
                scrollBehavior = scrollBehavior,
            )
        },
    ) { padding ->
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) { content(padding) }
    }
}

/** "BeatThis licence" for the model's `BeatThis-LICENSE.txt`; ONNX Runtime's by their asset names. */
@Composable
fun noticeTitle(notice: Notice): String {
    val name = notice.path.substringAfterLast('/')
    return when {
        notice.asset && name == "LICENSE" -> stringResource(R.string.notices_onnxruntime_license)
        notice.asset && name.startsWith("ThirdPartyNotices") -> stringResource(R.string.notices_onnxruntime_third_party)
        else -> {
            val stem = name.substringBeforeLast('.')
            val owner = stem.removeSuffix("-LICENSE").removeSuffix("_LICENSE").removeSuffix("-LICENCE")
            if (owner != stem) stringResource(R.string.notices_licence_of, owner) else stem
        }
    }
}

private fun readNotice(context: Context, notice: Notice, modelDirectory: File?): String? = try {
    if (notice.asset) {
        context.assets.open(notice.path).use { it.readBytes().decodeToString() }
    } else {
        modelDirectory?.let { File(it, notice.path) }?.takeIf { it.isFile }?.readText()
    }
} catch (e: IOException) {
    null
}
