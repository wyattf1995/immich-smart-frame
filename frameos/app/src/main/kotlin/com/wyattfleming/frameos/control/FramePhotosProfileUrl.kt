package com.wyattfleming.frameos.control
import java.net.URI
object FramePhotosProfileUrl {
 fun withProfile(url: String, profile: String): String? = withQueryParameter(url,"curation_profile",profile,FrameRemoteControlPolicy::acceptsProfile)
 fun withFrameId(url:String, deviceId:String):String?=withQueryParameter(url,"frame_id",deviceId,FrameRemoteControlPolicy::acceptsDeviceId)
 fun profile(url:String):String?=runCatching { URI(url).rawQuery?.split('&')?.firstOrNull { it.substringBefore('=').equals("curation_profile",true) }?.substringAfter('=')?.takeIf(FrameRemoteControlPolicy::acceptsProfile) }.getOrNull()
 private fun withQueryParameter(url:String,key:String,value:String,valid:(String)->Boolean):String?=runCatching {
  require(valid(value)); val uri=URI(url); val query=(uri.rawQuery?.split('&')?:emptyList()).filterNot{it.substringBefore('=').equals(key,true)}+"$key=$value"
  val prefix=url.substringBefore('?').substringBefore('#'); val fragment=uri.rawFragment?.let{"#$it"}.orEmpty(); "$prefix?${query.joinToString("&")}$fragment"
 }.getOrNull()
}
