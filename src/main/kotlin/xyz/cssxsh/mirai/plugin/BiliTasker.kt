package xyz.cssxsh.mirai.plugin

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.*
import net.mamoe.mirai.console.permission.PermissionService.Companion.testPermission
import net.mamoe.mirai.console.permission.PermitteeId.Companion.permitteeId
import net.mamoe.mirai.console.util.CoroutineScopeUtils.childScope
import net.mamoe.mirai.contact.*
import net.mamoe.mirai.message.data.*
import net.mamoe.mirai.utils.*
import xyz.cssxsh.bilibili.api.*
import xyz.cssxsh.bilibili.*
import xyz.cssxsh.bilibili.data.*
import xyz.cssxsh.mirai.plugin.data.*
import java.time.*
import kotlin.math.*

interface BiliTasker {

    suspend fun task(id: Long, subject: Contact): BiliTask

    suspend fun remove(id: Long, subject: Contact): BiliTask

    suspend fun list(subject: Contact): String

    suspend fun start()

    suspend fun stop()

    val tasks: Map<Long, BiliTask>

    companion object : Collection<BiliTasker> {
        private val taskers: List<BiliTasker> by lazy {
            AbstractTasker::class.sealedSubclasses.flatMap { it.sealedSubclasses }.mapNotNull { it.objectInstance }
        }

        override val size: Int get() = taskers.size

        override fun contains(element: BiliTasker): Boolean = taskers.contains(element)

        override fun containsAll(elements: Collection<BiliTasker>): Boolean = taskers.containsAll(elements)

        override fun isEmpty(): Boolean = taskers.isEmpty()

        override fun iterator(): Iterator<BiliTasker> = taskers.iterator()
    }
}

sealed class AbstractTasker<T : Entry> : BiliTasker, CoroutineScope {

    protected val name get() = coroutineContext[CoroutineName]?.name

    protected val mutex = Mutex()

    protected abstract val fast: Long

    protected abstract val slow: Long

    abstract override val tasks: MutableMap<Long, BiliTask>

    protected abstract suspend fun T.build(contact: Contact): Message

    protected open fun empty(id: Long) = tasks[id]?.contacts.isNullOrEmpty()

    protected open fun BiliTask.send(item: T) = contacts.map { delegate ->
        async {
            try {
                val contact = requireNotNull(findContact(delegate)) { "找不到联系人 $delegate" }
                contact.sendMessage(item.build(contact))
            } catch (e: Throwable) {
                logger.warning({ "对[${delegate}]构建消息失败" }, e)
                null
            }
        }
    }

    private val jobs = mutableMapOf<Long, Job>()

    protected abstract suspend fun listen(id: Long): Long

    protected open fun addListener(id: Long) = launch(SupervisorJob()) {
        while (isActive && !empty(id)) {
            val interval = try {
                listen(id)
            } catch (e: Throwable) {
                logger.warning({ "$name with $id fail $e" }, e)
                slow
            }
            delay(interval)
        }
    }

    protected open fun removeListener(id: Long) = jobs.remove(id)?.cancel()

    abstract suspend fun initTask(id: Long): BiliTask

    override suspend fun task(id: Long, subject: Contact) = mutex.withLock {
        val old = tasks[id] ?: initTask(id)
        val new = old.copy(contacts = old.contacts + subject.delegate)
        tasks[id] = new
        jobs.compute(id) { _, job ->
            job?.takeIf { it.isActive } ?: addListener(id)
        }
        new
    }

    override suspend fun remove(id: Long, subject: Contact) = mutex.withLock {
        val old = tasks[id] ?: initTask(id)
        val new = old.copy(contacts = old.contacts - subject.delegate)
        if (new.contacts.isEmpty()) {
            jobs[id]?.cancel()
            tasks.remove(id)
        } else {
            tasks[id] = new
        }
        new
    }

    override suspend fun list(subject: Contact): String = mutex.withLock {
        buildString {
            appendLine("监听状态:")
            for ((id, info) in tasks) {
                if (subject.delegate in info.contacts) {
                    appendLine("@${info.name}#$id -> ${info.last} | ${jobs[id]}")
                }
            }
        }
    }

    override suspend fun start(): Unit = mutex.withLock {
        for ((id, info) in tasks) {
            try {
                jobs[id] = addListener(id)
            } finally {
                logger.info { "$name with $id start $info" }
            }
        }
    }

    override suspend fun stop(): Unit = mutex.withLock {
        coroutineContext.cancelChildren()
        jobs.clear()
    }
}

sealed class Loader<T : Entry> : AbstractTasker<T>() {

    protected abstract suspend fun load(id: Long): List<T>

    protected abstract fun List<T>.last(): OffsetDateTime

    protected abstract fun List<T>.after(last: OffsetDateTime): List<T>

    protected abstract suspend fun List<T>.near(): Boolean

    override suspend fun listen(id: Long): Long {
        val list = load(id)

        mutex.withLock {
            val task = tasks.getValue(id)
            for (item in list.after(task.last)) {
                task.send(item)
            }
            tasks[id] = task.copy(last = list.last())
        }

        return if (list.near()) fast else slow
    }
}

sealed class Waiter<T : Entry> : AbstractTasker<T>() {

    private val states = mutableMapOf<Long, Boolean>()

    protected abstract suspend fun load(id: Long): T

    protected abstract suspend fun T.success(): Boolean

    protected abstract suspend fun T.near(): Boolean

    protected abstract suspend fun T.last(): OffsetDateTime

    override suspend fun listen(id: Long): Long {
        val item = load(id)
        val state = states.put(id, item.success())

        if (state != true && item.success()) {
            mutex.withLock {
                val task = tasks.getValue(id)
                tasks[id] = task.copy(last = item.last())
                task.send(item)
            }
            delay(slow)
        }

        return if (item.near()) fast else slow
    }
}

private fun List<LocalTime>.near(slow: Long, now: LocalTime = LocalTime.now()): Boolean {
    return any { abs(it.toSecondOfDay() - now.toSecondOfDay()) * 1000 < slow }
}

private const val Minute = 60 * 1000L

object BiliVideoLoader : Loader<Video>(), CoroutineScope by BiliHelperPlugin.childScope("VideoTasker") {
    override val tasks: MutableMap<Long, BiliTask> by BiliTaskData::video

    override val fast get() = Minute

    override val slow get() = BiliHelperSettings.video * Minute

    override suspend fun load(id: Long) = client.getVideos(id).list.videos

    override fun List<Video>.last(): OffsetDateTime = maxOfOrNull { it.datetime } ?: OffsetDateTime.now()

    override fun List<Video>.after(last: OffsetDateTime) = filter { it.datetime > last }

    override suspend fun List<Video>.near() = map { it.datetime.toLocalTime() }.near(slow)

    override suspend fun Video.build(contact: Contact) = toMessage(contact)

    override suspend fun initTask(id: Long): BiliTask = BiliTask(name = client.getUserInfo(id).name)
}

object BiliDynamicLoader : Loader<DynamicInfo>(), CoroutineScope by BiliHelperPlugin.childScope("DynamicTasker") {
    override val tasks: MutableMap<Long, BiliTask> by BiliTaskData::dynamic

    override val fast get() = Minute

    override val slow = BiliHelperSettings.dynamic * Minute

    override suspend fun load(id: Long) = client.getSpaceHistory(id).dynamics

    override fun List<DynamicInfo>.last(): OffsetDateTime = maxOfOrNull { it.datetime } ?: OffsetDateTime.now()

    override fun List<DynamicInfo>.after(last: OffsetDateTime) = filter { it.datetime > last }

    override suspend fun List<DynamicInfo>.near() = map { it.datetime.toLocalTime() }.near(slow)

    override suspend fun DynamicInfo.build(contact: Contact) = toMessage(contact)

    override suspend fun initTask(id: Long): BiliTask = BiliTask(name = client.getUserInfo(id).name)
}

object BiliLiveWaiter : Waiter<BiliUserInfo>(), CoroutineScope by BiliHelperPlugin.childScope("LiveWaiter") {
    override val tasks: MutableMap<Long, BiliTask> by BiliTaskData::live

    override val fast get() = Minute

    override val slow get() = BiliHelperSettings.live * Minute

    override suspend fun load(id: Long) = client.getUserInfo(id)

    override suspend fun BiliUserInfo.success(): Boolean = liveRoom.liveStatus

    private val LiveAtAll = BiliHelperPlugin.registerPermission("live.atall", "直播 @全体成员")

    private fun withAtAll(contact: Contact): Message {
        return if (contact is Group && LiveAtAll.testPermission(contact.permitteeId)) {
            @Suppress("INVISIBLE_REFERENCE", "INVISIBLE_MEMBER")
            AtAll + net.mamoe.mirai.internal.message.ForceAsLongMessage
        } else {
            EmptyMessageChain
        }
    }

    override suspend fun BiliUserInfo.build(contact: Contact): Message {
        val start = tasks.getValue(mid).last
        return "主播: $name#$mid \n".toPlainText() + liveRoom.toMessage(contact, start) + withAtAll(contact)
    }

    // TODO by live history
    override suspend fun BiliUserInfo.near(): Boolean = LocalTime.now().minute < 5

    override suspend fun BiliUserInfo.last(): OffsetDateTime = client.getRoomInfo(roomId = liveRoom.roomId).datetime

    override suspend fun initTask(id: Long): BiliTask = BiliTask(name = client.getUserInfo(id).name)
}

object BiliSeasonWaiter : Waiter<SeasonSection>(), CoroutineScope by BiliHelperPlugin.childScope("SeasonWaiter") {
    override val tasks: MutableMap<Long, BiliTask> by BiliTaskData::season

    override val fast get() = Minute

    override val slow = BiliHelperSettings.season * Minute

    private val record = mutableMapOf<Long, Video>()

    private suspend fun video(aid: Long) = record.getOrPut(aid) { client.getVideoInfo(aid) }

    override suspend fun load(id: Long): SeasonSection = client.getSeasonSection(id).mainSection

    override suspend fun SeasonSection.success(): Boolean {
        val video = video(aid = episodes.maxOfOrNull { it.aid } ?: return false)
        return video.datetime > tasks.getValue(id).last
    }

    override suspend fun SeasonSection.build(contact: Contact): Message {
        return "$title 有更新".toPlainText() + record.getValue(id).toMessage(contact)
    }

    override suspend fun SeasonSection.near(): Boolean {
        return episodes.map { video(it.aid).datetime.toLocalTime() }.near(slow)
    }

    override suspend fun SeasonSection.last(): OffsetDateTime {
        val video = client.getVideoInfo(aid = episodes.maxOf { it.aid })
        record[id] = video
        return video.datetime
    }

    override suspend fun initTask(id: Long): BiliTask = BiliTask(name = client.getSeasonSection(id).mainSection.title)
}