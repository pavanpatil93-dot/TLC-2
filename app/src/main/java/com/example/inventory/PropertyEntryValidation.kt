package com.example.inventory

data class PropertyEntryDraft(val project:String,val location:String,val ownerPhone:String,val carpetArea:String,val priceOrRent:String)
object PropertyEntryValidation {
 fun error(d:PropertyEntryDraft):String? {
  if(d.project.trim().isEmpty()) return "Project / building is required"
  if(d.location.trim().isEmpty()) return "Location is required"
  val p=d.ownerPhone.filter{it.isDigit()}; if(p.isNotEmpty() && p.takeLast(10).length!=10) return "Enter a valid owner phone number"
  if(d.carpetArea.isNotBlank() && (d.carpetArea.toDoubleOrNull()?:0.0)<=0.0) return "Carpet area must be a positive number"
  if(d.priceOrRent.isNotBlank() && (d.priceOrRent.toDoubleOrNull()?:0.0)<=0.0) return "Rent / sale price must be a positive number"
  return null
 }
}

