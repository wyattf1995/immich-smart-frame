package com.wyattfleming.frameos.control
import java.net.URI
object FramePhotosProfileUrl {
 fun withProfile(url: String, profile: String): String? = runCatching {
  require(FrameRemoteControlPolicy.acceptsProfile(profile)); val u=URI(url)
  val parts=(u.rawQuery?.split('&')?: emptyList()).filterNot { it.substringBefore('=').equals("curation_profile",true) } + "curation_profile=$profile"
  URI(u.scheme,u.userInfo,u.host,u.port,u.rawPath,parts.joinToString("&"),u.rawFragment).toASCIIString()
 }.getOrNull()
 fun profile(url:String):String?=runCatching { URI(url).rawQuery?.split('&')?.firstOrNull { it.substringBefore('=').equals("curation_profile",true) }?.substringAfter('=')?.takeIf(FrameRemoteControlPolicy::acceptsProfile) }.getOrNull()
}
