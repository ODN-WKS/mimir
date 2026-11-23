package io.github.odnwks.mimir.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import io.github.odnwks.mimir.standalone.Attach
import io.github.odnwks.mimir.standalone.LocalModel
import io.github.odnwks.mimir.standalone.Pictures
import io.github.odnwks.mimir.standalone.TurnFile
import io.github.odnwks.mimir.standalone.TurnPicture
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * What goes with the next message: pictures and files chosen from the gallery, the camera, the file
 * picker or pasted, and the line that says why one wasn't taken. The chat's box and a task's reply box
 * share this one piece, so a picture is read, shrunk and wiped of hidden details the same way in both.
 */
class AttachState(private val scope: CoroutineScope, private val ctx: Context, var bigPictures: Boolean, var pcReadsFiles: Boolean) {
    val files = mutableStateListOf<TurnFile>()
    val pictures = mutableStateListOf<TurnPicture>()
    var note by mutableStateOf<String?>(null)
    /** The one button under a note (Try again, Open settings), when there is a fix to offer. */
    var fix by mutableStateOf<Pair<String, () -> Unit>?>(null)

    // Set by [rememberAttach] to open the pickers.
    internal var openFiles: () -> Unit = {}
    internal var openPhotos: () -> Unit = {}
    internal var openCamera: () -> Unit = {}
    internal var askCamera: () -> Unit = {}

    val any: Boolean get() = files.isNotEmpty() || pictures.isNotEmpty()

    fun clear() { files.clear(); pictures.clear() }
    fun dismissNote() { note = null; fix = null }

    /** Gallery, camera and pasted pictures all come in here. */
    fun addPictures(got: List<TurnPicture>) {
        if (got.isEmpty()) note = "That picture couldn't be read. Try another one."
        val room = Pictures.MAX - pictures.size
        got.filter { p -> pictures.none { it == p } }.take(room).forEach { pictures += it }
        if (got.size > room) note = "Only ${Pictures.MAX} pictures go with one message (a video counts as ${Pictures.VIDEO_FRAMES})."
    }

    /** A file chosen with the picker. */
    suspend fun addFile(uri: Uri) {
        when (val r = withContext(Dispatchers.IO) { if (pcReadsFiles) Attach.readForPc(ctx, uri) else Attach.read(ctx, uri) }) {
            is Attach.Result.Read -> if (files.size < Attach.MAX_FILES) files += r.file
            is Attach.Result.No -> note = attachProblem(r.problem)
        }
    }

    /** Pictures (or videos as a few stills) from [uris]; also used for a picture pasted into the box. */
    suspend fun addMedia(uris: List<Uri>) {
        val got = withContext(Dispatchers.IO) {
            uris.flatMap { uri ->
                val name = Attach.nameOf(ctx, uri) ?: "Picture"
                if (ctx.contentResolver.getType(uri).orEmpty().startsWith("video/")) Pictures.video(ctx, uri, name, if (bigPictures) Pictures.PC_FRAME_SIDE else Pictures.FRAME_SIDE)
                else listOfNotNull(Pictures.photo(ctx, uri, name, if (bigPictures) Pictures.PC_SIDE else Pictures.MAX_SIDE))
            }
        }
        addPictures(got)
    }

    fun pasted(uris: List<Uri>) { if (uris.isNotEmpty()) scope.launch { addMedia(uris) } }

    fun photos() = openPhotos()
    fun file() = openFiles()
    fun camera() {
        if (ContextCompat.checkSelfPermission(ctx, android.Manifest.permission.CAMERA) == android.content.pm.PackageManager.PERMISSION_GRANTED) openCamera() else askCamera()
    }

    /** The chips for what is chosen, each with its ×. */
    @Composable
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    fun Chips() {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            pictures.toList().forEach { p -> PictureChip(p) { pictures.remove(p) } }
            files.toList().forEach { f -> FileChip(f) { files.remove(f) } }
        }
    }

    /** The + button: Photos, Camera and Files (and Models, when [onModels] is given). */
    @Composable
    fun Button(sight: Sight, onModels: () -> Unit = {}, model: LocalModel? = null) {
        AttachButton(
            sight,
            filesFull = files.size >= Attach.MAX_FILES,
            picturesFull = pictures.size >= Pictures.MAX,
            onFile = ::file, onPhotos = ::photos, onCamera = ::camera, onModels = onModels,
            model = model, pcFiles = pcReadsFiles,
        )
    }

    @Composable
    fun Note(modifier: Modifier = Modifier) {
        val shown = remember { arrayOf("") }
        note?.let { shown[0] = it }
        androidx.compose.animation.AnimatedVisibility(
            note != null,
            enter = androidx.compose.animation.expandVertically(io.github.odnwks.mimir.ui.theme.motionSpec(io.github.odnwks.mimir.ui.theme.Motion.NEEDS_YOU_BAR_MS)) + androidx.compose.animation.fadeIn(io.github.odnwks.mimir.ui.theme.motionSpec(io.github.odnwks.mimir.ui.theme.Motion.NEEDS_YOU_BAR_MS)),
            exit = androidx.compose.animation.shrinkVertically(io.github.odnwks.mimir.ui.theme.motionSpec(io.github.odnwks.mimir.ui.theme.Motion.NEEDS_YOU_BAR_MS)) + androidx.compose.animation.fadeOut(io.github.odnwks.mimir.ui.theme.motionSpec(io.github.odnwks.mimir.ui.theme.Motion.NEEDS_YOU_BAR_MS)),
            modifier = modifier,
        ) { AttachNote(shown[0], fix) { dismissNote() } }
    }
}

/** Said when the camera won't open (with "Try again") or isn't allowed (with "Open settings"). */
internal const val CAMERA_FAILED = "The camera couldn't be opened. Close other apps that use it, then try again."
internal const val CAMERA_REFUSED = "The camera isn't allowed for this app. Allow it in Android settings, or pick a photo instead."

/**
 * The pickers and the camera for one message box. [bigPictures]: the PC reads them (larger pictures);
 * [pcReadsFiles]: any file goes to the PC to be read, not only text files.
 */
@Composable
fun rememberAttach(bigPictures: Boolean, pcReadsFiles: Boolean): AttachState {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val a = remember { AttachState(scope, ctx, bigPictures, pcReadsFiles) }.also { it.bigPictures = bigPictures; it.pcReadsFiles = pcReadsFiles }
    val pick = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) scope.launch { a.addFile(uri) } }
    val pickMedia = rememberLauncherForActivityResult(ActivityResultContracts.PickMultipleVisualMedia(Pictures.MAX)) { uris -> if (uris.isNotEmpty()) scope.launch { a.addMedia(uris) } }
    // A camera shot waits in the app's cache only until it's read in, then it's deleted.
    val shot = remember { java.io.File(ctx.cacheDir, "camera/shot.jpg") }
    val takePhoto = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { ok ->
        scope.launch {
            val got = withContext(Dispatchers.IO) {
                val p = if (ok && shot.isFile) Pictures.photo(ctx, Uri.fromFile(shot), "Photo", if (bigPictures) Pictures.PC_SIDE else Pictures.MAX_SIDE) else null
                shot.delete()
                p
            }
            if (ok) a.addPictures(listOfNotNull(got))
        }
    }
    val openCamera: () -> Unit = {
        runCatching {
            shot.parentFile?.mkdirs()
            takePhoto.launch(FileProvider.getUriForFile(ctx, ctx.packageName + ".camera", shot))
        }.onFailure { a.note = CAMERA_FAILED; a.fix = "Try again" to { a.dismissNote(); a.openCamera() } }
        Unit
    }
    val askCamera = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { ok ->
        if (ok) openCamera() else { a.note = CAMERA_REFUSED; a.fix = "Open settings" to { openAppSettings(ctx) } }
    }
    a.openFiles = { pick.launch(arrayOf("*/*")) }
    a.openPhotos = { pickMedia.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageAndVideo)) }
    a.openCamera = openCamera
    a.askCamera = { askCamera.launch(android.Manifest.permission.CAMERA) }
    // A note about a file that wasn't added shows for a few seconds only.
    LaunchedEffect(a.note) { if (a.note != null) { delay(if (a.fix != null) 12000 else 6000); a.dismissNote() } }
    return a
}
