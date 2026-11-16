package com.example.crm

import android.content.Context
import com.example.model.*
import java.text.SimpleDateFormat
import java.util.*
import org.json.JSONArray
import org.json.JSONObject

internal data class CallPropertyDraft(val contactName:String,val contactPhone:String,val project:String,val location:String,val unit:String,val tab:InventoryTab,val carpet:Double,val price:Double,val remarks:String,val brokerName:String="",val brokerPhone:String="",val brokerFirm:String="",val propertyType:String="")
internal data class CallConversionResult(val success:Boolean,val message:String,val id:String?=null,val title:String="")
internal object CallConversionService {
 private fun digits(v:String)=v.filter{it.isDigit()}.takeLast(10)
 private fun stable(callId:String,kind:String)=UUID.nameUUIDFromBytes(("tlc-call-"+kind+"-"+callId).toByteArray()).toString()
 private fun today()=SimpleDateFormat("dd/MM/yyyy",Locale.US).format(Date())
 fun convertLead(c:Context,call:CrmCallRecord,name:String,phone:String,segment:String,requirement:String,location:String,budget:String,status:String,followUp:String):CallConversionResult {
  if(call.id.startsWith("demo_call_")) return CallConversionResult(false,"Demo calls cannot create live CRM records")
  val p=digits(phone); if(p.length!=10) return CallConversionResult(false,"Enter a valid 10-digit mobile number")
  if(name.trim().isEmpty()||requirement.trim().isEmpty()) return CallConversionResult(false,"Name and requirement are required")
  val pref=com.example.data.auditedCrmPreferences(c, "crm_development_v1",0); val raw=pref.getString("leads","[]") ?: return CallConversionResult(false,"CRM data is unreadable; recover it before converting")
  val arr=try{JSONArray(raw)}catch(e:Exception){return CallConversionResult(false,"CRM data is unreadable; recover it before converting")}
  var target:JSONObject?=null
  for(i in 0 until arr.length()){val o=arr.optJSONObject(i)?:return CallConversionResult(false,"CRM data contains an invalid lead record"); if(o.optString("id")==stable(call.id,"lead")) target=o; if(o.optString("phone").filter{it.isDigit()}.takeLast(10)==p && o.optString("id")!=stable(call.id,"lead")) return CallConversionResult(false,"A lead with this phone number already exists")}
  val o=target ?: JSONObject().apply{put("id",stable(call.id,"lead"))}
  o.put("name",if(target==null)name.trim() else o.optString("name",name.trim())); o.put("phone",p); o.put("segment",segment); o.put("requirement",requirement.trim()); o.put("location",location.trim()); o.put("budget",budget.trim()); o.put("status",status); o.put("nextFollowUp",followUp.trim()); o.put("sourceCallId",call.id)
  if(target==null) arr.put(o)
  if(!pref.edit().putString("leads",arr.toString()).commit()) return CallConversionResult(false,"Lead save failed; try again")
  val linked=CrmCallManager.linkCallToOpportunity(c,call.id,"Lead",o.optString("name"),o.optString("id"))
  return if(linked) CallConversionResult(true,"Lead created and call linked",o.optString("id"),o.optString("name")) else CallConversionResult(false,"Lead saved, but call link failed. Retry the same conversion",o.optString("id"),o.optString("name"))
 }
 fun convertProperty(c:Context,call:CrmCallRecord,d:CallPropertyDraft,save:(PropertyItem)->Boolean,code:(InventoryTab)->String, existing:(String)->PropertyItem? = { null }):CallConversionResult {
  if(call.id.startsWith("demo_call_")) return CallConversionResult(false,"Demo calls cannot create live inventory records")
  if(d.contactName.trim().isEmpty()||digits(d.contactPhone).length!=10) return CallConversionResult(false,"Owner name and valid mobile are required")
  if(d.project.trim().isEmpty()||d.location.trim().isEmpty()) return CallConversionResult(false,"Project and location are required")
  val id=stable(call.id,"property")
  existing(id)?.let { saved ->
   val linked=CrmCallManager.linkCallToOpportunity(c,call.id,"Property",saved.projectName,id)
   return CallConversionResult(linked,if(linked) "Existing property linked" else "Property saved; linking failed, retry",id,saved.projectName)
  }
  val item=PropertyItem(id=id,propertyCode=code(d.tab),date=today(),location=d.location.trim(),projectName=d.project.trim(),unitNumber=d.unit.trim(),propertyType=d.propertyType.ifBlank{"Commercial"},tab=d.tab,carpetAreaSqFt=d.carpet,priceOrRent=d.price,priceSale=if(d.tab.isRental)null else d.price,rent=if(d.tab.isRental)d.price else null,status=AvailabilityStatus.NOT_CONFIRMED,ownerName=d.contactName.trim(),ownerContact=digits(d.contactPhone),brokerName=d.brokerName.takeIf{it.isNotBlank()},brokerContact=digits(d.brokerPhone).takeIf{it.length==10},sourceInfo="Call intake",remarks=d.remarks.takeIf{it.isNotBlank()},internalRemarks=call.notes,portfolio=if(d.tab.isCommercial)"Commercial" else "Residential",transactionType=if(d.tab.isRental)"Lease" else "Sale",recordId="CALL-"+id,isSyncedToSheet=false)
  if(!save(item)) return CallConversionResult(false,"Inventory save failed; no call link was created")
  val linked=CrmCallManager.linkCallToOpportunity(c,call.id,"Property",d.project.trim(),id)
  return if(linked) CallConversionResult(true,"Property saved and call linked",id,d.project.trim()) else CallConversionResult(false,"Property saved, but call link failed. Retry the same conversion",id,d.project.trim())
 }
}

