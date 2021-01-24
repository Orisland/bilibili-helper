package xyz.cssxsh.bilibili.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement

@Serializable
data class BiliVideoCard(
    @SerialName("aid")
    val aid: Int,
    @SerialName("attribute")
    val attribute: Int? = null,
    @SerialName("cid")
    val cid: Int,
    @SerialName("copyright")
    val copyright: Int,
    @SerialName("ctime")
    val createTime: Int,
    @SerialName("desc")
    val description: String,
    @SerialName("dimension")
    val dimension: BiliDimension,
    @SerialName("duration")
    val duration: Int,
    @SerialName("dynamic")
    val dynamic: String = "",
    @SerialName("jump_url")
    val jumpUrl: String,
    @SerialName("mission_id")
    val missionId: Long? = null,
    @SerialName("owner")
    val owner: BiliOwner,
    @SerialName("pic")
    val picture: String,
    @SerialName("player_info")
    val playerInfo: JsonElement,
    @SerialName("pubdate")
    val pubdate: Int,
    @SerialName("rights")
    val rights: JsonElement,
    @SerialName("stat")
    val stat: BiliVideoState,
    @SerialName("state")
    val state: Int,
    @SerialName("tid")
    val tid: Long,
    @SerialName("title")
    val title: String,
    @SerialName("tname")
    val typeName: String,
    @SerialName("videos")
    val videos: Int
) {

    companion object {
        const val TYPE = 8
    }
}
