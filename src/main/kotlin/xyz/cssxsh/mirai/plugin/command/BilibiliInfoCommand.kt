package xyz.cssxsh.mirai.plugin.command

import net.mamoe.mirai.console.command.CommandSenderOnMessage
import net.mamoe.mirai.console.command.CompositeCommand
import net.mamoe.mirai.console.command.descriptor.ExperimentalCommandDescriptors
import net.mamoe.mirai.console.util.ConsoleExperimentalApi
import net.mamoe.mirai.contact.Contact
import net.mamoe.mirai.event.GlobalEventChannel
import net.mamoe.mirai.event.events.MessageEvent
import net.mamoe.mirai.event.subscribeMessages
import net.mamoe.mirai.message.data.MessageSource.Key.quote
import net.mamoe.mirai.message.data.QuoteReply
import net.mamoe.mirai.message.data.buildMessageChain
import net.mamoe.mirai.utils.*
import net.mamoe.mirai.utils.ExternalResource.Companion.uploadAsImage
import xyz.cssxsh.bilibili.api.*
import xyz.cssxsh.bilibili.data.*
import xyz.cssxsh.mirai.plugin.*
import xyz.cssxsh.mirai.plugin.BilibiliHelperPlugin.bilibiliClient
import xyz.cssxsh.mirai.plugin.BilibiliHelperPlugin.logger

@Suppress("unused")
object BilibiliInfoCommand : CompositeCommand(
    owner = BilibiliHelperPlugin,
    "bili-info", "B信息",
    description = "B站信息指令"
) {

    private val VIDEO_REGEX = """(?<=https://(m|www)\.bilibili\.com/video/)?((av|AV)\d+|(bv|BV)[0-9A-z]{10})""".toRegex()

    private val DYNAMIC_REGEX = """(?<=https://t\.bilibili\.com/(h5/dynamic/detail/)?)([0-9]{18})""".toRegex()

    fun subscribeBilibiliInfo() = GlobalEventChannel.parentScope(BilibiliHelperPlugin).subscribeMessages {
        DYNAMIC_REGEX findingReply { result ->
            logger.info { "[${sender}] 匹配DYNAMIC(${result.value})" }
            runCatching {
                bilibiliClient.getDynamicDetail(
                    dynamicId = result.value.toLong()
                ).card.buildDynamicMessage(contact = subject, quote = message.quote())
            }.onFailure {
                logger.warning({ "构建DYNAMIC(${result.value})信息失败" }, it)
            }.getOrElse {
                it.message
            }
        }
        VIDEO_REGEX findingReply { result ->
            logger.info { "[${sender}] 匹配VIDEO(${result.value})" }
            runCatching {
                when (result.value.first()) {
                    'B', 'b' -> {
                        result.value.let {
                            bilibiliClient.getVideoInfo(bvId = it)
                        }
                    }
                    'A', 'a' -> {
                        result.value.substring(2).toLong().let {
                            bilibiliClient.getVideoInfo(aid = it)
                        }
                    }
                    else -> throw IllegalArgumentException("未知视频ID(${result.value})")
                }.buildVideoMessage(contact = subject, quote = message.quote())
            }.onFailure {
                logger.warning({ "构建VIDEO(${result.value})信息失败" }, it)
            }.getOrElse {
                it.message
            }
        }
    }

    @ExperimentalCommandDescriptors
    @ConsoleExperimentalApi
    override val prefixOptional: Boolean = true

    private suspend fun BiliVideoInfo.buildVideoMessage(contact: Contact, quote: QuoteReply? = null) = buildMessageChain {
        quote?.let { add(it) }
        appendLine("标题: $title")
        appendLine("作者: ${owner.name}")
        appendLine("时间: ${timestampToOffsetDateTime(pubdate)}")
        appendLine("时长: ${durationText()}")
        appendLine("链接: ${getVideoUrl()}")

        runCatching {
            add(getCover().uploadAsImage(contact))
        }.onFailure {
            logger.warning({ "获取[${title}](${bvId})}视频封面失败" }, it)
        }
    }

    private suspend fun BiliCardInfo.buildDynamicMessage(contact: Contact, quote: QuoteReply? = null) = buildMessageChain {
        quote?.let { add(it) }
        appendLine("${describe.userProfile.info.uname} 动态")
        appendLine("时间: ${timestampToOffsetDateTime(describe.timestamp)}")
        appendLine("链接: ${getDynamicUrl()}")

        runCatching {
            add(getScreenShot(refresh = true).uploadAsImage(contact))
        }.onFailure {
            logger.warning({ "获取动态${describe.dynamicId}快照失败" }, it)
            add(toMessageText())
        }

        runCatching {
            getImages().forEach {
                add(it.uploadAsImage(contact))
            }
        }.onFailure {
            logger.warning({ "获取动态${describe.dynamicId}图片失败" }, it)
        }
    }

    @SubCommand
    suspend fun CommandSenderOnMessage<MessageEvent>.aid(id: Long) = runCatching {
        bilibiliClient.getVideoInfo(aid = id).buildVideoMessage(
            contact = fromEvent.subject,
            quote = fromEvent.message.quote()
        ).let {
            sendMessage(it)
        }
    }.onFailure { sendMessage(it.toString()) }.isSuccess

    @SubCommand
    suspend fun CommandSenderOnMessage<MessageEvent>.bvid(id: String) = runCatching {
        bilibiliClient.getVideoInfo(bvId = id).buildVideoMessage(
            contact = fromEvent.subject,
            quote = fromEvent.message.quote()
        ).let {
            sendMessage(it)
        }
    }.onFailure { sendMessage(it.toString()) }.isSuccess

    @SubCommand
    suspend fun CommandSenderOnMessage<MessageEvent>.dynamic(id: Long) = runCatching {
        bilibiliClient.getDynamicDetail(id).card.buildDynamicMessage(
            contact = fromEvent.subject,
            quote = fromEvent.message.quote()
        ).let {
            sendMessage(it)
        }
    }.onFailure { sendMessage(it.toString()) }.isSuccess
}