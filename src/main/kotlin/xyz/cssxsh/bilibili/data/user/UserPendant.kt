package xyz.cssxsh.bilibili.data.user

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

@Serializable
data class UserPendant(
    @SerialName("expire")
    val expire: Int,
    @SerialName("image")
    val image: String,
    @SerialName("image_enhance")
    val imageEnhance: String = "",
    @SerialName("image_enhance_frame")
    val imageEnhanceFrame: String = "",
    @SerialName("name")
    val name: String,
    @SerialName("pid")
    val pid: Long
)