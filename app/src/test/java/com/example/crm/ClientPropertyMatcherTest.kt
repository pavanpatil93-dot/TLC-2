package com.example.crm
import com.example.model.*
import org.junit.Assert.assertEquals
import org.junit.Test
class ClientPropertyMatcherTest {
 @Test fun ranksMatchingLocationAreaAndBudget(){
  val a=PropertyItem(id="a",projectName="Gera Imperium",location="Kharadi",carpetAreaSqFt=500.0,priceOrRent=45000.0,propertyType="Office")
  val b=PropertyItem(id="b",projectName="Riverdale",location="Mundhwa",carpetAreaSqFt=900.0,priceOrRent=90000.0)
  val r=ClientPropertyMatcher.match(listOf(a,b),ClientRequirement(locations=listOf("Kharadi"),minArea=400.0,maxArea=600.0,maxBudget=50000.0,propertyType="Office"))
  assertEquals("a",r.single().property.id); assertEquals(100,r.single().score)
 }
 @Test fun excludesOverBudgetAndWrongLocation(){
  val p=PropertyItem(id="p",location="Baner",priceOrRent=100000.0)
  assertEquals(0,ClientPropertyMatcher.match(listOf(p),ClientRequirement(locations=listOf("Kharadi"),maxBudget=50000.0)).size)
 }
}

