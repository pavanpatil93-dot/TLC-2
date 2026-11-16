package com.example.crm

import com.example.model.PropertyItem
import kotlin.math.abs

data class ClientRequirement(
    val segment:String="",
    val locations:List<String> = emptyList(),
    val minArea:Double?=null,
    val maxArea:Double?=null,
    val maxBudget:Double?=null,
    val propertyType:String=""
)
data class PropertyMatch(val property:PropertyItem,val score:Int,val reasons:List<String>)
object ClientPropertyMatcher {
 fun match(properties:List<PropertyItem>,req:ClientRequirement):List<PropertyMatch> = properties.mapNotNull { p ->
  var score=0; val reasons=mutableListOf<String>()
  val loc=req.locations.filter{it.isNotBlank()}.any{p.location.contains(it.trim(),true)||p.projectName.contains(it.trim(),true)}
  if(req.locations.isNotEmpty() && !loc) return@mapNotNull null
  if(loc){score+=40;reasons+="Location match"}
  if(req.minArea!=null && p.carpetAreaSqFt<req.minArea) return@mapNotNull null
  if(req.maxArea!=null && p.carpetAreaSqFt>req.maxArea) return@mapNotNull null
  if(req.minArea!=null||req.maxArea!=null){score+=25;reasons+="Area match"}
  if(req.maxBudget!=null && p.priceOrRent>req.maxBudget) return@mapNotNull null
  if(req.maxBudget!=null){score+=25;reasons+="Budget match"}
  if(req.propertyType.isNotBlank() && p.propertyType.contains(req.propertyType,true)){score+=10;reasons+="Type match"}
  PropertyMatch(p,score,reasons)
 }.sortedByDescending{it.score}
}

