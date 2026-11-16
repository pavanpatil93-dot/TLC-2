package com.example.crm

import android.content.Context
import java.text.SimpleDateFormat
import java.util.*

enum class FollowUpState { NONE, UPCOMING, DUE, OVERDUE, INVALID }

object CallFollowUpStore {
 private const val PREFS="crm_call_followups_v1"
 private val format=SimpleDateFormat("yyyy-MM-dd",Locale.US).apply{isLenient=false}
 fun set(context:Context,callId:String,date:String):Boolean {
  if(callId.isBlank()||state(date)!=FollowUpState.NONE&&state(date)==FollowUpState.INVALID)return false
  return com.example.data.auditedCrmPreferences(context, PREFS,0).edit().putString(callId,date).commit()
 }
 fun get(context:Context,callId:String):String?=com.example.data.auditedCrmPreferences(context, PREFS,0).getString(callId,null)
 fun state(date:String?,today:Date=Date()):FollowUpState {
  if(date.isNullOrBlank()) return FollowUpState.NONE
  val parsed=runCatching{format.parse(date)}.getOrNull()?:return FollowUpState.INVALID
  val d=SimpleDateFormat("yyyy-MM-dd",Locale.US).parse(format.format(today))!!
  return when { parsed.before(d)->FollowUpState.OVERDUE; parsed.time==d.time->FollowUpState.DUE; else->FollowUpState.UPCOMING }
 }
 fun dueCalls(context:Context,calls:List<CrmCallRecord>,today:Date=Date()):List<CrmCallRecord> = calls.filter{state(get(context,it.id),today)==FollowUpState.DUE||state(get(context,it.id),today)==FollowUpState.OVERDUE}
}

