package xyz.cssxsh.bilibili.api

import io.ktor.client.request.*
import io.ktor.http.*
import xyz.cssxsh.bilibili.BilibiliClient
import xyz.cssxsh.bilibili.data.*

suspend fun BilibiliClient.getUserInfo(
    mid: Long,
    url: String = BilibiliApi.ACC_INFO
): BiliUserInfo = useHttpClient { client ->
    client.get<BiliTempInfo>(url) {
        header(HttpHeaders.Origin, BilibiliApi.SPACE)
        header(HttpHeaders.Referrer, BilibiliApi.SPACE)

        parameter("mid", mid)
        parameter("jsonp", "jsonp")
        parameter("tid", 0)
    }
}.transferTo(BiliUserInfo.serializer())