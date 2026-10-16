package com.lagradost.quicknovel

import android.content.Intent
import android.content.res.Resources
import android.graphics.Rect
import android.media.MediaPlayer
import android.speech.tts.Voice
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.util.Log
import android.widget.Toast
import androidx.annotation.WorkerThread
import androidx.core.graphics.drawable.toDrawable
import androidx.core.text.toSpanned
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.fasterxml.jackson.annotation.JsonProperty
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.nl.translate.TranslateLanguage
import com.lagradost.quicknovel.mvvm.Resource
import com.lagradost.quicknovel.mvvm.logError
import com.lagradost.quicknovel.mvvm.safeApiCall
import com.lagradost.quicknovel.ui.OrientationType
import com.lagradost.quicknovel.ui.ReadingType
import com.lagradost.quicknovel.ui.download.BookDownloader2
import com.lagradost.quicknovel.util.*
import io.noties.markwon.Markwon
import io.noties.markwon.MarkwonConfiguration
import io.noties.markwon.ext.latex.JLatexMathPlugin
import io.noties.markwon.image.AsyncDrawable
import io.noties.markwon.image.AsyncDrawableSpan
import io.noties.markwon.image.ImageSizeResolver
import io.noties.markwon.image.coil.CoilImagesPlugin
import io.noties.markwon.inlineparser.MarkwonInlineParserPlugin
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.commonmark.node.Node
import java.io.File
import java.net.URLDecoder
import java.security.MessageDigest
import java.util.Locale
import java.util.concurrent.TimeoutException

class ReadActivityViewModel : APIRepository() {
    companion object {
        const val TAG = "ReadActivityViewModel"
    }

    private val markwonMutex = Mutex()

    @WorkerThread
    private suspend fun loadIndividualChapter(
        index: Int,
        reload: Boolean = false,
        notify: Boolean = true,
        reTranslate: Boolean = false,
        postLoading: Boolean = false,
    ) {
        if (index < 0) return

        chapterMutex.withLock {
            if (loading.contains(index)) return
            if (!reload && !reTranslate && chapterData.contains(index)) {
                return
            }

            loading += index
            chapterData[index] = Resource.Loading(null)
            if (notify) notifyChapterUpdate(index)
        }

        chapterExpandMutex.withLock {
            val preSize = book.size()
            while (index >= book.size()) {
                if (hasExpanded.contains(book.size())) break
                hasExpanded += book.size()

                try {
                    book.expand(book.getChapterData(book.size() - 1, reload = false))
                } catch (t: Throwable) {
                    logError(t)
                }
            }
            if (preSize != book.size()) updateChapters()
        }

        if (index >= book.size()) {
            chapterMutex.withLock {
                if (index == book.size()) {
                    chapterData[index] =
                        Resource.Failure(
                            null,
                            context?.getString(R.string.no_more_chapters) ?: "ERROR"
                        )
                } else {
                    chapterData[index] = null
                }
                loading -= index
                if (notify) notifyChapterUpdate(index)
            }
            return
        }

        chapterMutex.withLock {
            chapterData[index] = Resource.Loading(book.getLoadingStatus(index))
            if (notify) notifyChapterUpdate(index)
        }

        try {
            val data = safeApiCall {
                book.getChapterData(index, reload)
            }.map { text ->
                val rawText = applyAliases(preParseHtml(text, authorNotes))
                val parsed: Node
                var rendered: Spanned
                val originalRendered: Spanned
                val originalSpans: ArrayList<TextSpan>
                var spans: ArrayList<TextSpan>

                markwonMutex.withLock {
                    parsed = markwon.parse(rawText)
                    rendered = markwon.render(parsed)

                    spans = parseTextToSpans(rendered, index)
                    originalSpans = spans
                    originalRendered = rendered

                    val asyncDrawables = rendered.getSpans<AsyncDrawableSpan>()
                    for (async in asyncDrawables) {
                        async.drawable.result =
                            book.loadImageBitmap(async.drawable.destination)?.toDrawable(
                                Resources.getSystem()
                            )
                    }

                    if (isTranslationActive) {
                        translate(
                            rendered,
                            spans
                        ) { (progressChapter, progressInnerIndex, progressInnerTotal) ->
                            val progressText =
                                "${context?.getString(R.string.translating)} ${
                                    book.getChapterTitle(
                                        progressChapter
                                    )
                                } ($progressInnerIndex/$progressInnerTotal)"
                            if (postLoading) {
                                _loadingStatus.postValue(Resource.Loading(progressText))
                            } else {
                                chapterMutex.withLock {
                                    chapterData[index] =
                                        Resource.Loading(progressText)
                                    if (notify) notifyChapterUpdate(index)
                                }
                            }
                        }.let { (mlRender, mlSpans) ->
                            rendered = mlRender
                            spans = mlSpans
                        }
                    }
                }

                LiveChapterData(
                    index = index,
                    rendered = rendered,
                    spans = spans,
                    originalRendered = originalRendered,
                    originalSpans = originalSpans,
                    rawText = rawText,
                    title = book.getChapterTitle(index),
                )
            }

            chapterMutex.withLock {
                chapterData[index] = data
            }
        } catch (t: Throwable) {
            chapterMutex.withLock {
                chapterData[index] = throwableToResource(t)
            }
        } finally {
            chapterMutex.withLock {
                loading -= index
                if (notify) notifyChapterUpdate(index)
            }
        }
    }

    private fun hashString(text: ByteArray): String {
        val digest = MessageDigest.getInstance("MD5").digest(text)
        val sb = StringBuilder()
        for (b in digest) {
            sb.append(String.format("%02x", b))
        }
        return sb.toString()
    }

    private fun getFinalTranslatedText(spans: ArrayList<TextSpan>, translatedLines: List<String>): Pair<SpannableStringBuilder, ArrayList<TextSpan>> {
        val builder = SpannableStringBuilder()
        val out = ArrayList<TextSpan>()
        spans.forEachIndexed { i, originalSpan ->
            val hasImage = originalSpan.text.getSpans<AsyncDrawableSpan>().isNotEmpty()
            val finalText =
                if (hasImage)
                    originalSpan.text
                else
                    (translatedLines.getOrNull(i) ?: return@forEachIndexed).toSpanned()
            val start = builder.length
            builder.append(finalText)
            val end = builder.length
            builder.append('\n')
            out.add(TextSpan(finalText, start, end, originalSpan.index, originalSpan.innerIndex))
        }
        return builder to out
    }

    @Throws(MLException::class)
    private suspend fun translate(
        text: Spanned,
        spans: ArrayList<TextSpan>,
        loading: suspend (Triple<Int, Int, Int>) -> Unit
    ): Pair<Spanned, ArrayList<TextSpan>> {
        try {
            val currentSettings = mlSettings
            if (spans.isEmpty() || currentSettings.isInvalid()) return text to spans
            val textHash = hashString(
                text.trim().toString().toByteArray()
            )
            val agentSuffix = currentSettings.agent.title.ifEmpty { TranslatorAgents.OFFLINE.title }

            val filePrefix = "ml_${textHash}.${currentSettings.from}_to_${currentSettings.to}.$agentSuffix"

            val cachedData = safe {
                context?.cacheDir?.let { dir ->
                    val cache = File(dir, "$filePrefix.txt")
                    if (cache.exists()) {
                        Log.i(TAG, "Cache exists for $filePrefix")
                        val lines = cache.readLines()
                        val (builder, out) = getFinalTranslatedText(spans, lines)
                        return@safe builder to out
                    }
                }
                return@safe null
            }
            if (cachedData != null) return cachedData

            val translatedList = translationManager.translate(
                textList = spans.map { it.text.toString() },
                from = currentSettings.from,
                to = currentSettings.to,
                agent = currentSettings.agent,
                progress = { progress, total ->
                    loading.invoke(Triple(spans[0].index, progress, total))
                }
            )

            val (builder, out) = getFinalTranslatedText(spans, translatedList)

            safe {
                context?.cacheDir?.let {
                    val cache = File(it, "$filePrefix.tmp")
                    cache.writeText(builder.toString())
                    safe { File(it, "$filePrefix.txt").delete() }
                    cache.renameTo(File(it, "$filePrefix.txt"))
                }
            }

            return builder to out
        } catch (t: Throwable) {
            throw MLException(t)
        }
    }

    @Throws
    suspend fun requireMLDownload(): Boolean {
        val settings = MLSettings(from = mlFromLanguage, to = mlToLanguage, agent = currentAgent)
        if (settings.isInvalid() || settings.agent != TranslatorAgents.OFFLINE) {
            return false
        }
        return !translationManager.isModelDownloaded(settings.from, settings.to)
    }

    fun applyMLSettings(allowDownload: Boolean = true) = ioSafe {
        val settings = MLSettings(from = mlFromLanguage, to = mlToLanguage, agent = currentAgent)
        if (settings.isValid() && allowDownload && safeAsync { requireMLDownload() } == true) {
            _loadingStatus.postValue(Resource.Loading("Downloading language"))
        }
        initMLFromSettings(settings, allowDownload)
        reloadMLForAllChapters()
    }

    private suspend fun reloadMLForAllChapters() {
        _loadingStatus.postValue(Resource.Loading(context?.getString(R.string.translating)))
        chapterMutex.withLock {
            val cIndex = currentIndex
            val lower = cIndex - chapterPaddingBottom
            val upper = cIndex + chapterPaddingTop

            val keys = chapterData.keys.toTypedArray()

            for (key in keys) {
                if (key < lower || key > upper) {
                    chapterData.remove(key)
                }
            }

            for (entry in chapterData.entries) {
                val value = entry.value
                if (value !is Resource.Success) continue
                val success = value.value

                try {
                    translate(
                        success.originalRendered,
                        success.originalSpans
                    ) { (progressChapter, progressInnerIndex, progressInnerTotal) ->
                        _loadingStatus.postValue(
                            Resource.Loading(
                                "${context?.getString(R.string.translating)} ${
                                    book.getChapterTitle(
                                        progressChapter
                                    )
                                } ($progressInnerIndex/$progressInnerTotal)"
                            )
                        )
                    }.let { (mlRender, mlSpans) ->
                        entry.setValue(
                            Resource.Success(
                                success.copy(
                                    rendered = mlRender,
                                    spans = mlSpans,
                                )
                            )
                        )
                    }
                } catch (t: Throwable) {
                    entry.setValue(
                        throwableToResource(t)
                    )
                }
            }
        }
        updateReadArea()
    }

    private suspend fun initMLFromSettings(settings: MLSettings, allowDownload: Boolean) {
        try {
            translationManager.release()
            if (settings.isValid() && settings.agent == TranslatorAgents.OFFLINE) {
                translationManager.prepareModel(settings.from, settings.to, downloadIfNeeded = allowDownload)
            }
            mlSettings = settings
        } catch (_: TimeoutException) {
            showToast(R.string.unable_to_download_language)
            translationManager.release()
        } catch (t: Throwable) {
            logError(t)
        }
    }

    fun init(intent: Intent?, context: ReadActivity2) = ioSafe {
        _loadingStatus.postValue(Resource.Loading())
        initTTSSession(context)

        val loadedBook = safeApiCall {
            if (intent == null) throw ErrorLoadingException("No intent")
            val data = intent.data ?: throw ErrorLoadingException("Empty intent")
            val isFromEpub = intent.type != "quickstream"

            val epub = if (isFromEpub) {
                val fd = context.contentResolver.openFileDescriptor(data, "r")
                    ?: throw ErrorLoadingException("Unable to open file descriptor")
                val zipFile = AndroidZipFile(fd, "")
                val book = EpubReader().readEpubLazy(zipFile, "utf-8")
                RegularBook(book)
            } else {
                val input = context.contentResolver.openInputStream(data)
                    ?: throw ErrorLoadingException("Empty data")
                QuickBook(DataStore.mapper.readValue(input.reader().readText()))
            }

            if (epub.size() <= 0) {
                throw ErrorLoadingException("Empty book, failed to parse ${intent.type}")
            }
            epub
        }

        when (loadedBook) {
            is Resource.Success -> {
                init(loadedBook.value, context)
                initMLFromSettings(mlSettings, false)

                val desiredChapterName = getKey<String>(EPUB_CURRENT_POSITION_CHAPTER, book.title())
                val desiredChapterIndex =
                    (0 until book.size()).firstOrNull {
                        loadedBook.value.getChapterTitle(it)
                            .asStringNull(context) == desiredChapterName
                    } ?: getKey<Int>(EPUB_CURRENT_POSITION, book.title()) ?: 0
                val loadedChapterIndex = maxOf(desiredChapterIndex, 0)

                if (loadedChapterIndex < book.size()) {
                    _loadingStatus.postValue(
                        Resource.Loading(
                            book.getLoadingStatus(
                                loadedChapterIndex
                            )
                        )
                    )
                }

                currentIndex = loadedChapterIndex
                updateIndexAsync(loadedChapterIndex, notify = false, postLoading = true)

                if (book.size() <= 0) {
                    _loadingStatus.postValue(
                        Resource.Failure(
                            null,
                            "Invalid chapter data when trying to load chapter $loadedChapterIndex when the book only has ${book.size()} chapters"
                        )
                    )
                    return@ioSafe
                }

                if (loadedChapterIndex >= book.size()) {
                    currentIndex = book.size() - 1
                    updateIndexAsync(currentIndex, notify = false)
                    showToast("Resize $loadedChapterIndex -> $currentIndex", Toast.LENGTH_LONG)
                }

                val char = getKey(
                    EPUB_CURRENT_POSITION_SCROLL_CHAR, book.title()
                ) ?: 0

                val innerIndex = innerCharToIndex(currentIndex, char) ?: 0
                changeIndex(ScrollIndex(currentIndex, innerIndex, char))
                updateReadArea(seekToDesired = true)
            }

            is Resource.Failure -> {
                _loadingStatus.postValue(
                    Resource.Failure(
                        loadedBook.cause,
                        loadedBook.errorString
                    )
                )
            }
            else -> throw NotImplementedError()
        }
    }

    fun init(book: AbstractBook, context: Context) {
        this.book = book
        _title.postValue(book.title())
        updateChapters()
        val imageLoader: ImageLoader = SingletonImageLoader.get(context)

        val coilStore = object : CoilStore {
            override fun load(drawable: AsyncDrawable): ImageRequest {
                val newUrl = drawable.destination.substringAfter("&url=")
                val url = book.resolveUrl(
                    if (newUrl.length > 8) {
                        URLDecoder.decode(newUrl)
                    } else {
                        drawable.destination
                    }
                )
                return ImageRequest.Builder(context).data(url).build()
            }
            override fun cancel(disposable: Disposable) {
                disposable.dispose()
            }
        }

        markwon = Markwon.builder(context)
            .usePlugin(HtmlPlugin.create { plugin -> plugin.excludeDefaults(false) })
            .usePlugin(CoilImagesPlugin.create(context, coilStore, imageLoader))
            .usePlugin(object : AbstractMarkwonPlugin() {
                override fun configureConfiguration(builder: MarkwonConfiguration.Builder) {
                    builder.imageSizeResolver(object : ImageSizeResolver() {
                        override fun resolveImageSize(drawable: AsyncDrawable): Rect {
                            return drawable.result.bounds
                        }
                    })
                }
            })
            .usePlugin(SoftBreakAddsNewLinePlugin.create())
            .build()
    }

    var ttsSession: TTSSession? = null
    private fun initTTSSession(context: Context) {
        runOnMainThread {
            ttsSession = TTSSession(context, ::parseAction)
        }
    }

    private var pendingTTSSkip: Int = 0
    private var _currentTTSStatus: TTSHelper.TTSStatus = TTSHelper.TTSStatus.IsStopped
    var currentTTSStatus: TTSHelper.TTSStatus
        get() = _currentTTSStatus
        set(value) = synchronized(this@ReadActivityViewModel) {
            playDummySound()
            if (_currentTTSStatus == TTSHelper.TTSStatus.IsStopped && value == TTSHelper.TTSStatus.IsRunning) {
                startTTSWorker()
            }
            _ttsStatus.postValue(value)
            _currentTTSStatus = value
        }

    fun stopTTS() { currentTTSStatus = TTSHelper.TTSStatus.IsStopped }
    fun setTTSLanguage(locale: Locale?) { ttsSession?.setLanguage(locale) }
    fun setTTSVoice(voice: Voice?) { ttsSession?.setVoice(voice) }
    fun pauseTTS() {
        val ttsSession = ttsSession ?: return
        if (!ttsSession.ttsInitialized()) return
        if (currentTTSStatus == TTSHelper.TTSStatus.IsRunning) {
            currentTTSStatus = TTSHelper.TTSStatus.IsPaused
        }
    }
    fun startTTS() { currentTTSStatus = TTSHelper.TTSStatus.IsRunning }
    fun forwardsTTS() {
        val ttsSession = ttsSession ?: return
        if (!ttsSession.ttsInitialized()) return
        pendingTTSSkip += 1
    }
    fun backwardsTTS() {
        val ttsSession = ttsSession ?: return
        if (!ttsSession.ttsInitialized()) return
        pendingTTSSkip -= 1
    }
    fun playTTS() { currentTTSStatus = TTSHelper.TTSStatus.IsRunning }
    fun pausePlayTTS() {
        if (currentTTSStatus == TTSHelper.TTSStatus.IsRunning) {
            currentTTSStatus = TTSHelper.TTSStatus.IsPaused
        } else if (currentTTSStatus == TTSHelper.TTSStatus.IsPaused) {
            currentTTSStatus = TTSHelper.TTSStatus.IsRunning
        }
    }
    fun isTTSRunning(): Boolean { return currentTTSStatus == TTSHelper.TTSStatus.IsRunning }

    private val ttsThreadMutex = Mutex()
    fun startTTSWorker() = ioSafe {
        TTSNotificationService.start(this@ReadActivityViewModel, context ?: return@ioSafe)
    }

    suspend fun startTTSThread() = coroutineScope {
        val ttsSession = ttsSession ?: return@coroutineScope
        try {
            val ttsStartTime = System.currentTimeMillis()
            var ttsEndTime = ttsStartTime + ttsTimer
            val ttsHasTimer = ttsEndTime > ttsStartTime
            val dIndex = desiredTTSIndex ?: desiredIndex ?: return@coroutineScope

            if (ttsThreadMutex.isLocked) return@coroutineScope
            ttsThreadMutex.withLock {
                ttsSession.register()
                ttsSession.setSpeed(ttsSpeed)
                ttsSession.setPitch(ttsPitch)

                var ttsInnerIndex = 0
                var index = dIndex.index

                let {
                    val startChar = dIndex.char
                    val lines = chapterMutex.withLock {
                        chapterData[index].letInner { it.ttsLines }
                    } ?: run {
                        index++
                        return@let
                    }

                    val idx = lines.indexOfFirst { it.startChar >= startChar }
                    if (idx != -1) {
                        ttsInnerIndex = idx
                    } else {
                        index++
                    }
                }

                loadIndividualChapter(index)
                while (isActive && currentTTSStatus != TTSHelper.TTSStatus.IsStopped) {
                    val lines = when (val currentData = chapterMutex.withLock { chapterData[index] }) {
                        null -> { showToast(R.string.got_null_data); break }
                        is Resource.Failure -> { showToast(currentData.errorString); break }
                        is Resource.Loading -> {
                            if (currentTTSStatus == TTSHelper.TTSStatus.IsStopped) break
                            delay(100)
                            continue
                        }
                        is Resource.Success -> { currentData.value.ttsLines }
                    }

                    fun notify() {
                        TTSNotifications.notify(
                            book.title(),
                            chaptersTitlesInternal[index],
                            book.poster(),
                            currentTTSStatus,
                            context
                        )
                    }
                    notify()

                    if (ttsInnerIndex < 0) {
                        ttsInnerIndex += lines.size
                    }

                    updateIndex(index)

                    viewModelScope.launch(Dispatchers.IO) {
                        val exists = chapterMutex.withLock { chapterData[index + 1] is Resource.Success }
                        if (!exists) loadIndividualChapter(index + 1)
                    }

                    while (ttsInnerIndex < lines.size && ttsInnerIndex >= 0) {
                        ensureActive()
                        val currentTimeRemaining = ttsEndTime - System.currentTimeMillis()
                        if (ttsHasTimer) {
                            if (currentTimeRemaining < 0) {
                                currentTTSStatus = TTSHelper.TTSStatus.IsStopped
                            } else {
                                ttsTimeRemaining.postValue(currentTimeRemaining)
                            }
                        }

                        if (currentTTSStatus == TTSHelper.TTSStatus.IsStopped) break
                        val line = lines[ttsInnerIndex]
                        val nextLine = lines.getOrNull(ttsInnerIndex + 1)

                        if (!isInApp) {
                            innerCharToIndex(index, line.startChar)?.let {
                                changeIndex(ScrollIndex(index, it, line.startChar), alsoTitle = false)
                            }
                        }

                        _ttsLine.postValue(line)
                        val waitFor = ttsSession.speak(line, nextLine) {
                            currentTTSStatus != TTSHelper.TTSStatus.IsRunning || pendingTTSSkip != 0
                        }

                        if (!ttsSession.isValidTTS()) {
                            currentTTSStatus = TTSHelper.TTSStatus.IsStopped
                        }

                        ttsSession.waitForOr(waitFor, {
                            currentTTSStatus != TTSHelper.TTSStatus.IsRunning || pendingTTSSkip != 0
                        }) {
                            notify()
                        }

                        var isPauseDuration = 0L
                        while (currentTTSStatus == TTSHelper.TTSStatus.IsPaused) {
                            isPauseDuration++
                            delay(100)
                        }

                        ttsEndTime += 100L * isPauseDuration
                        if (isPauseDuration > 0) {
                            notify()
                            pendingTTSSkip = 0
                            continue
                        }

                        if (pendingTTSSkip != 0) {
                            ttsInnerIndex += pendingTTSSkip
                            pendingTTSSkip = 0
                        } else {
                            ttsInnerIndex += 1
                        }
                    }
                    if (currentTTSStatus == TTSHelper.TTSStatus.IsStopped) break

                    if (ttsInnerIndex > 0 || lines.isEmpty()) {
                        index++
                        ttsInnerIndex = 0
                    } else if (index > 0) {
                        index--
                    } else {
                        ttsInnerIndex = 0
                    }
                }
            }
        } catch (_: TimeoutCancellationException) {
        } catch (t: Throwable) {
            logError(t)
        } finally {
            currentTTSStatus = TTSHelper.TTSStatus.IsStopped
            TTSNotifications.notify(
                book.title(),
                "".toUiText(),
                book.poster(),
                TTSHelper.TTSStatus.IsStopped,
                context
            )
            ttsSession.interruptTTS()
            ttsSession.unregister()
            _ttsLine.postValue(null)
            ttsTimeRemaining.postValue(null)
        }
    }

    fun parseAction(input: TTSHelper.TTSActionType): Boolean {
        val ttsSession = ttsSession ?: return false
        if (
            (currentTTSStatus == TTSHelper.TTSStatus.IsPaused && input == TTSHelper.TTSActionType.Pause) ||
            (currentTTSStatus != TTSHelper.TTSStatus.IsPaused && input == TTSHelper.TTSActionType.Resume) ||
            (currentTTSStatus == TTSHelper.TTSStatus.IsStopped && input == TTSHelper.TTSActionType.Stop) ||
            (currentTTSStatus != TTSHelper.TTSStatus.IsRunning && input == TTSHelper.TTSActionType.Next)
        ) {
            return false
        }
        if (!ttsSession.ttsInitialized()) return false

        when (input) {
            TTSHelper.TTSActionType.Pause -> pauseTTS()
            TTSHelper.TTSActionType.Resume -> startTTS()
            TTSHelper.TTSActionType.Stop -> stopTTS()
            TTSHelper.TTSActionType.Next -> forwardsTTS()
        }
        return true
    }

    fun innerCharToIndex(index: Int, char: Int): Int? {
        return runBlocking {
            chapterMutex.withLock { chapterData[index] }?.letInner { live ->
                live.spans.firstOrNull { it.start >= char }?.innerIndex
            }
        }
    }

    private var lastChangeIndex: ScrollIndex? = null
    private var lastScrollMs: Long = 0
    private fun changeIndex(scrollIndex: ScrollIndex, alsoTitle: Boolean = true) {
        if (alsoTitle) {
            _chapterTile.postValue(chaptersTitlesInternal[scrollIndex.index])
        }
        desiredIndex = scrollIndex
        currentIndex = scrollIndex.index
        lastChangeIndex = scrollIndex
        if (System.currentTimeMillis() > lastScrollMs + 200L) {
            lastScrollMs = System.currentTimeMillis()
            setScrollKeys(scrollIndex)
        }
    }

    private fun setScrollKeys(scrollIndex: ScrollIndex) {
        setKey(
            EPUB_CURRENT_POSITION_READ_AT,
            "${book.title()}/${scrollIndex.index}",
            System.currentTimeMillis()
        )
        setKey(EPUB_CURRENT_POSITION_SCROLL_CHAR, book.title(), scrollIndex.char)
        setKey(EPUB_CURRENT_POSITION, book.title(), scrollIndex.index)
        context?.let {
            setKey(
                EPUB_CURRENT_POSITION_CHAPTER,
                book.title(),
                book.getChapterTitle(scrollIndex.index).asString(it)
            )
        }
    }

    fun scrollToDesired(scrollIndex: ScrollIndex) {
        changeIndex(scrollIndex)
        updateReadArea(seekToDesired = true)
    }

    fun seekToChapter(index: Int) = ioSafe {
        if (index < 0 || index >= book.size()) return@ioSafe
        if (currentTTSStatus != TTSHelper.TTSStatus.IsStopped) {
            currentTTSStatus = TTSHelper.TTSStatus.IsStopped
        }
        _loadingStatus.postValue(Resource.Loading())
        updateIndexAsync(index, notify = false, postLoading = true)
        setKey(EPUB_CURRENT_POSITION, book.title(), index)
        setKey(EPUB_CURRENT_POSITION_SCROLL_CHAR, book.title(), 0)
        desiredIndex = ScrollIndex(index, 0, 0)
        currentIndex = index
        desiredTTSIndex = ScrollIndex(index, 0, 0)
        updateReadArea(seekToDesired = true)
        _chapterTile.postValue(chaptersTitlesInternal[index])
    }

    private fun playDummySound() {
        val act = activity ?: return
        val mMediaPlayer: MediaPlayer = MediaPlayer.create(act, R.raw.dummy_sound_500ms)
        mMediaPlayer.setOnCompletionListener { mMediaPlayer.release() }
        mMediaPlayer.start()
    }

    override fun onCleared() {
        lastChangeIndex?.let { setScrollKeys(it) }
        ttsSession?.release()
        ttsSession = null
        translationManager.release()
        if (::book.isInitialized) {
            BookDownloader2.chapterReadChanged(book.title())
        }
    }

    private var readerTypeInternal by PreferenceDelegate(
        EPUB_READER_TYPE,
        ReadingType.DEFAULT.prefValue,
        Int::class
    )

    var readerType
        get() = ReadingType.fromSpinner(readerTypeInternal)
        set(value) {
            readerTypeInternal = value.prefValue
            updateReadArea(seekToDesired = true)
        }

    var scrollWithVolume by PreferenceDelegate(EPUB_SCROLL_VOL, true, Boolean::class)
    var authorNotes by PreferenceDelegate(EPUB_AUTHOR_NOTES, true, Boolean::class)
    var ttsLock by PreferenceDelegate(EPUB_TTS_LOCK, true, Boolean::class)

    private var ttsSpeedKey by PreferenceDelegate(EPUB_TTS_SET_SPEED, 1.0f, Float::class)
    private var ttsPitchKey by PreferenceDelegate(EPUB_TTS_SET_PITCH, 1.0f, Float::class)

    var ttsSpeed: Float
        get() = ttsSpeedKey
        set(value) {
            ttsSession?.setSpeed(value)
            ttsSpeedKey = value
        }

    var ttsPitch: Float
        get() = ttsPitchKey
        set(value) {
            ttsSession?.setPitch(value)
            ttsPitchKey = value
        }

    val textFontLive: MutableLiveData<String> = MutableLiveData(null)
    var textFont by PreferenceDelegateLiveView(EPUB_FONT, "", String::class, textFontLive)
    val textSizeLive: MutableLiveData<Int> = MutableLiveData(null)
    var textSize by PreferenceDelegateLiveView(EPUB_TEXT_SIZE, DEF_FONT_SIZE, Int::class, textSizeLive)
    val bionicReadingLive: MutableLiveData<Boolean> = MutableLiveData(null)
    var bionicReading by PreferenceDelegateLiveView(EPUB_TEXT_BIONIC, false, Boolean::class, bionicReadingLive)
    val isTextSelectableLive: MutableLiveData<Boolean> = MutableLiveData(null)
    var isTextSelectable by PreferenceDelegateLiveView(EPUB_TEXT_SELECTABLE, false, Boolean::class, isTextSelectableLive)
    val orientationLive: MutableLiveData<Int> = MutableLiveData(null)
    var orientation by PreferenceDelegateLiveView(EPUB_LOCK_ROTATION, OrientationType.DEFAULT.prefValue, Int::class, orientationLive)
    val textColorLive: MutableLiveData<Int> = MutableLiveData(null)
    var textColor by PreferenceDelegateLiveView(EPUB_TEXT_COLOR, "#cccccc".toColorInt(), Int::class, textColorLive)
    val textVerticalPaddingLive: MutableLiveData<Float> = MutableLiveData(null)
    var textVerticalPadding by PreferenceDelegateLiveView(EPUB_TEXT_VERTICAL_PADDING, 7.5f, Float::class, textVerticalPaddingLive)
    val backgroundColorLive: MutableLiveData<Int> = MutableLiveData(null)
    var backgroundColor by PreferenceDelegateLiveView(EPUB_BG_COLOR, "#292832".toColorInt(), Int::class, backgroundColorLive)
    val showBatteryLive: MutableLiveData<Boolean> = MutableLiveData(null)
    var showBattery by PreferenceDelegateLiveView(EPUB_HAS_BATTERY, true, Boolean::class, showBatteryLive)
    val showTimeLive: MutableLiveData<Boolean> = MutableLiveData(null)
    var showTime by PreferenceDelegateLiveView(EPUB_HAS_TIME, true, Boolean::class, showTimeLive)
    val paddingHorizontalLive: MutableLiveData<Int> = MutableLiveData(null)
    var paddingHorizontal by PreferenceDelegateLiveView(EPUB_TEXT_PADDING, DEF_HORIZONTAL_PAD, Int::class, paddingHorizontalLive)
    val paddingVerticalLive: MutableLiveData<Int> = MutableLiveData(null)
    var paddingVertical by PreferenceDelegateLiveView(EPUB_TEXT_PADDING_TOP, DEF_VERTICAL_PAD, Int::class, paddingVerticalLive)
    val screenAwakeLive: MutableLiveData<Boolean> = MutableLiveData(null)
    var screenAwake by PreferenceDelegateLiveView(EPUB_KEEP_SCREEN_ACTIVE, true, Boolean::class, screenAwakeLive)
    val ttsTimerLive: MutableLiveData<Long> = MutableLiveData(null)
    var ttsTimer by PreferenceDelegateLiveView(EPUB_SLEEP_TIMER, 0, Long::class, ttsTimerLive)
    val ttsTimeRemaining: MutableLiveData<Long?> = MutableLiveData(null)

    val mlFromLanguageLive: MutableLiveData<String> = MutableLiveData(null)
    var mlFromLanguage by PreferenceDelegateLiveView(
        EPUB_ML_FROM_LANGUAGE, TranslateLanguage.ENGLISH, String::class, mlFromLanguageLive
    )

    val mlToLanguageLive: MutableLiveData<String> = MutableLiveData(null)
    var mlToLanguage by PreferenceDelegateLiveView(
        EPUB_ML_TO_LANGUAGE, TranslateLanguage.ENGLISH, String::class, mlToLanguageLive
    )

    val mlTranslationAgentLive: MutableLiveData<String> = MutableLiveData(TranslatorAgents.OFFLINE.name)
    var mlTranslationAgent by PreferenceDelegateLiveView(
        "ml_translation_agent", TranslatorAgents.OFFLINE.name, String::class, mlTranslationAgentLive
    )
    val currentAgent get() = try { TranslatorAgents.valueOf(mlTranslationAgent) } catch (e: Exception) { TranslatorAgents.OFFLINE }

    data class MLSettings(
        @JsonProperty("from") val from: String,
        @JsonProperty("to") val to: String,
        @JsonProperty("agent") val agent: TranslatorAgents = TranslatorAgents.OFFLINE
    ) {
        companion object {
            const val AUTO_LANG = "auto"
            val map = listOf(
                "af" to "Afrikaans", "ar" to "Arabic", "be" to "Belarusian", "bg" to "Bulgarian",
                "bn" to "Bengali", "ca" to "Catalan", "cs" to "Czech", "cy" to "Welsh",
                "da" to "Danish", "de" to "German", "el" to "Greek", "en" to "English",
                "eo" to "Esperanto", "es" to "Spanish", "et" to "Estonian", "fa" to "Persian",
                "fi" to "Finnish", "fr" to "French", "ga" to "Irish", "gl" to "Galician",
                "gu" to "Gujarati", "he" to "Hebrew", "hi" to "Hindi", "hr" to "Croatian",
                "ht" to "Haitian", "hu" to "Hungarian", "id" to "Indonesian", "is" to "Icelandic",
                "it" to "Italian", "ja" to "Japanese", "ka" to "Georgian", "kn" to "Kannada",
                "ko" to "Korean", "lt" to "Lithuanian", "lv" to "Latvian", "mk" to "Macedonian",
                "mr" to "Marathi", "ms" to "Malay", "mt" to "Maltese", "nl" to "Dutch",
                "no" to "Norwegian", "pl" to "Polish", "pt" to "Portuguese", "ro" to "Romanian",
                "ru" to "Russian", "sk" to "Slovak", "sl" to "Slovenian", "sq" to "Albanian",
                "sv" to "Swedish", "sw" to "Swahili", "ta" to "Tamil", "te" to "Telugu",
                "th" to "Thai", "tl" to "Tagalog", "tr" to "Turkish", "uk" to "Ukrainian",
                "ur" to "Urdu", "vi" to "Vietnamese", "zh" to "Chinese"
            ).sortedBy { (_, value) -> value }.toMap()

            val mapOnline = mapOf(AUTO_LANG to "Auto") + map
            fun fromShortToDisplay(from: String): String = mapOnline[from] ?: "Unknown"
        }

        val fromDisplay get() = fromShortToDisplay(from)
        val toDisplay get() = fromShortToDisplay(to)

        fun isInvalid(): Boolean = !isValid()
        fun isValid(): Boolean {
            if (from.isBlank() || to.isBlank()) return false
            val all = TranslateLanguage.getAllLanguages()
            if (!all.contains(to)) return false
            if (!all.contains(from) && !(agent != TranslatorAgents.OFFLINE && from == AUTO_LANG)) return false
            if (from == to) return false
            return true
        }
    }
}
