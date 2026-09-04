package com.wyattfleming.frameos.control
import android.content.Context
class FrameCompanionCommandStore(context: Context) { private val p=context.getSharedPreferences("frameos_companion_commands",0)
 @Synchronized fun claim(id:String):Boolean { if(p.contains(id)) return false; p.edit().putBoolean(id,true).apply(); return true }
 @Synchronized fun ack(id:String,status:String,message:String?=null){ p.edit().putString("ack:$id","$status|${message?:""}").apply() }
 @Synchronized fun consumeAcks():List<FrameRemoteAck>{ return p.all.filterKeys{it.startsWith("ack:")}.map{FrameRemoteAck(it.key.removePrefix("ack:"),it.value.toString().substringBefore('|'),it.value.toString().substringAfter('|').ifBlank{null})} }
 @Synchronized fun clearAcks(acks:List<FrameRemoteAck>){ p.edit().apply{acks.forEach{remove("ack:${it.id}")}}.apply() }
}
